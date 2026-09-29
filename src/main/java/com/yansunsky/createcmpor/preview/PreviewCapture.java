package com.yansunsky.createcmpor.preview;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端侧「房间产线 → 微缩预览快照」采集器。
 *
 * <p>只在评估固化那一刻调用一次（此后源房间已卸载，无法回查）。采集分两遍：
 * <ol>
 *     <li><b>第一遍</b>扫全房间，求出<b>非空气方块的包围盒</b>——这是取景与降采样的依据；</li>
 *     <li><b>第二遍</b>只在这个包围盒里逐格取方块状态：包围盒体积不超上限时<b>全分辨率</b>采集，
 *         超过时才按整数步长做盒式降采样（每组取组内出现次数最多的<b>非空气</b>方块）。</li>
 * </ol>
 *
 * <p><b>为什么必须"先取景再降采样"（用户 2026-09-29 实机反馈修正）</b>：
 * 45³ 房间里只在 10³ 范围内有建筑时，若先按 45³ 体积算步长（step=3）再取包围盒，
 * 10³ 的建筑会被压成 4³ 的糊块——细节全丢。先取包围盒再判步长，10³ = 1000 格 ≤ 上限，
 * 于是全分辨率保留细节，同时自动聚焦到有内容的区域。
 *
 * <p>降采样刻意不把空气计入票数：大房间里孤零零一台机器（周围全空）必须能被保留，
 * 否则"稀疏摆几个方块"的场景会整片消失。
 *
 * <p><b>v3 附带采集：每格转速</b>（"让微缩预览转起来"）。在第二遍的<b>同一次格遍历</b>里顺带读
 * {@link com.simibubi.create.content.kinetics.base.KineticBlockEntity#getTheoreticalSpeed()}：
 * <ul>
 *     <li>必须用 {@code getTheoreticalSpeed()} 而<b>不是</b> {@code getSpeed()}——后者在 overStressed
 *         或 {@code /tick freeze} 时静默返回 0，会把"在转但过载"的部件误采成静止；</li>
 *     <li>只记 {@code |speed| > }{@value PreviewSnapshot#SPEED_EPSILON} 的格，稀疏存；</li>
 *     <li>{@code Config#PREVIEW_ANIMATION_SECONDS} 为 0 时<b>完全不读转速</b>（快照里不写速度表，省带宽）；</li>
 *     <li><b>防御式兜底</b>：采集点位于停 IO 之后约 2 个相位，此时副本 kinetic 网络是否还保速
 *         <b>尚未实机确认</b>。因此"一格都没采到"只当静止处理（不报错、不崩），并打 INFO 便于实机定位。</li>
 * </ul>
 *
 * <p>任何失败（无房间、副本未加载、调色板超限、空房间、尺寸非法）都返回 {@code null}——
 * 预览是纯装饰，采集失败绝不允许影响固化的正确性。
 */
public final class PreviewCapture {

    /** 降采样步长上限，防止病态配置导致死循环。 */
    private static final int MAX_STEP = 32;

    /** 源网格体积硬上限（45³≈9.1 万，留足余量），超过直接放弃采集。 */
    private static final long MAX_SOURCE_VOLUME = 1_000_000L;

    private PreviewCapture() {
    }

    /**
     * 从 {@code level} 的 {@code innerBounds} 区域采集预览快照。
     *
     * @param maxVolume 快照网格体积上限（降采样后不得超过）
     * @return 快照；无内容或超限时返回 {@code null}
     */
    public static PreviewSnapshot capture(ServerLevel level, AABB innerBounds, int maxVolume) {
        BlockPos roomMin = BlockPos.containing(innerBounds.minX, innerBounds.minY, innerBounds.minZ);
        BlockPos maxExclusive = BlockPos.containing(innerBounds.maxX, innerBounds.maxY, innerBounds.maxZ);
        int roomWidth = maxExclusive.getX() - roomMin.getX();
        int roomHeight = maxExclusive.getY() - roomMin.getY();
        int roomDepth = maxExclusive.getZ() - roomMin.getZ();
        if (roomWidth <= 0 || roomHeight <= 0 || roomDepth <= 0) {
            CreateCMPOR.LOGGER.warn("[预览] 房间内界非法：{}x{}x{}，跳过预览", roomWidth, roomHeight, roomDepth);
            return null;
        }
        long roomVolume = (long) roomWidth * roomHeight * roomDepth;
        if (roomVolume > MAX_SOURCE_VOLUME) {
            CreateCMPOR.LOGGER.warn("[预览] 房间体积 {} 超过采集上限 {}，跳过预览", roomVolume, MAX_SOURCE_VOLUME);
            return null;
        }

        // 第一遍：求非空气包围盒（局部坐标，相对 roomMin）
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int minX = roomWidth;
        int minY = roomHeight;
        int minZ = roomDepth;
        int maxX = -1;
        int maxY = -1;
        int maxZ = -1;
        for (int z = 0; z < roomDepth; z++) {
            for (int y = 0; y < roomHeight; y++) {
                for (int x = 0; x < roomWidth; x++) {
                    if (level.getBlockState(cursor.set(roomMin.getX() + x, roomMin.getY() + y, roomMin.getZ() + z)).isAir()) {
                        continue;
                    }
                    if (x < minX) {
                        minX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (z < minZ) {
                        minZ = z;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                    if (z > maxZ) {
                        maxZ = z;
                    }
                }
            }
        }
        if (maxX < 0) {
            CreateCMPOR.LOGGER.info("[预览] 房间内没有可用方块，跳过预览");
            return null;
        }
        int focusWidth = maxX - minX + 1;
        int focusHeight = maxY - minY + 1;
        int focusDepth = maxZ - minZ + 1;

        // 步长只按"取景后的体积"决定：能全分辨率就全分辨率
        int step = 1;
        while (step < MAX_STEP) {
            long sampled = (long) ceilDiv(focusWidth, step) * ceilDiv(focusHeight, step)
                    * ceilDiv(focusDepth, step);
            if (sampled <= maxVolume) {
                break;
            }
            step++;
        }

        int width = ceilDiv(focusWidth, step);
        int height = ceilDiv(focusHeight, step);
        int depth = ceilDiv(focusDepth, step);

        Map<BlockState, Integer> indexByState = new HashMap<>();
        List<BlockState> palette = new ArrayList<>();
        palette.add(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        byte[] cells = new byte[width * height * depth];
        // 连通组表：只在全分辨率采集时有效（降采样后一格代表多方块，连通关系不再有意义）
        byte[] groups = step == 1 ? new byte[cells.length] : null;
        Map<BlockPos, Integer> groupIds = groups == null ? null : new HashMap<>();
        Map<BlockState, int[]> histogram = step > 1 ? new HashMap<>() : null;
        Map<BlockState, Long> dominantPositionOf = step > 1 ? new HashMap<>() : null;
        BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos dominantPos = new BlockPos.MutableBlockPos();

        // v3：稀疏转速表。animationSeconds == 0 时**完全不读** BE（省服务端开销，快照也就不含速度表）。
        float animationSeconds = Config.PREVIEW_ANIMATION_SECONDS.get();
        boolean collectSpeed = animationSeconds > 0.0F;
        int[] speedIndices = new int[32];
        float[] speedValues = new float[32];
        int speedCount = 0;

        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    BlockState state = step == 1
                            ? level.getBlockState(sample.set(roomMin.getX() + minX + x,
                                    roomMin.getY() + minY + y, roomMin.getZ() + minZ + z))
                            : dominantState(level, roomMin, minX, minY, minZ, x, y, z, step,
                                    focusWidth, focusHeight, focusDepth, histogram, sample, dominantPos,
                                    dominantPositionOf);
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    int linear = x + width * (y + height * z);
                    Integer index = indexByState.get(state);
                    if (index == null) {
                        if (palette.size() > PreviewSnapshot.MAX_PALETTE) {
                            CreateCMPOR.LOGGER.warn("[预览] 调色板超过 {} 种方块，跳过预览（房间方块种类过多）",
                                    PreviewSnapshot.MAX_PALETTE);
                            return null;
                        }
                        palette.add(state);
                        index = palette.size() - 1;
                        indexByState.put(state, index);
                    }
                    cells[linear] = index.byteValue();
                    if (groups != null) {
                        recordGroup(level, sample, groups, groupIds, linear);
                    }
                    if (collectSpeed && state.hasBlockEntity()) {
                        // 只有带方块实体的方块才可能有 kinetic BE —— 这一步把"每格一次 chunk 查表"
                        // 压缩到"每格一次 boolean 判断"，采集开销可忽略。
                        float speed = readKineticSpeed(level, step == 1 ? sample : dominantPos);
                        if (Math.abs(speed) > PreviewSnapshot.SPEED_EPSILON) {
                            if (speedCount == speedIndices.length) {
                                int grown = speedCount * 2;
                                speedIndices = Arrays.copyOf(speedIndices, grown);
                                speedValues = Arrays.copyOf(speedValues, grown);
                            }
                            speedIndices[speedCount] = linear;
                            speedValues[speedCount] = speed;
                            speedCount++;
                        }
                    }
                }
            }
        }

        int[] finalIndices = speedCount == 0 ? null : Arrays.copyOf(speedIndices, speedCount);
        float[] finalValues = speedCount == 0 ? null : Arrays.copyOf(speedValues, speedCount);
        // v4：实体表。取景原点 = 房间最小角 + 包围盒最小角（与 cells 同一坐标系），
        // 采到实体的坐标再按降采样步长折算——于是客户端在同一缩放变换下摆位即可。
        BlockPos focusOrigin = roomMin.offset(minX, minY, minZ);
        List<PreviewSnapshot.EntityRecord> entities =
                collectEntities(level, innerBounds, focusOrigin, step, width, height, depth);
        PreviewSnapshot result = new PreviewSnapshot(width, height, depth, palette, cells, groups,
                finalIndices, finalValues, speedCount == 0 ? 0.0F : animationSeconds, entities);
        if (result.nonAirCount() == 0) {
            CreateCMPOR.LOGGER.info("[预览] 房间内没有可用方块，跳过预览");
            return null;
        }
        CreateCMPOR.LOGGER.info(
                "[预览] 采集完成：房间 {}x{}x{} → 取景 {}x{}x{}（步长 {}）→ 快照 {}x{}x{}，非空气 {} 格，"
                        + "调色板 {} 种，实体 {} 只，约 {} 字节",
                roomWidth, roomHeight, roomDepth, focusWidth, focusHeight, focusDepth, step,
                result.width(), result.height(), result.depth(), result.nonAirCount(),
                result.paletteSize(), result.entityCount(), result.encodedSize());
        if (!collectSpeed) {
            CreateCMPOR.LOGGER.info("[预览] 动画已关闭（preview.factoryPreviewAnimationSeconds = 0），预览为静态，快照不含速度表");
        } else if (result.movingCount() == 0) {
            // 防御式兜底：不报错、不崩，只提示——采集点位于停 IO 之后约 2 个相位，
            // 届时评估副本的 kinetic 网络是否还保速**尚未实机确认**，这条日志就是实机定位入口。
            CreateCMPOR.LOGGER.info(
                    "[预览] 未采到转速（非空气 {} 格全部 getTheoreticalSpeed()≈0），预览保持静态。"
                            + "可能原因：房间内没有正在运转的传动部件，或停 IO 后副本 kinetic 网络已停转",
                    result.nonAirCount());
        } else {
            CreateCMPOR.LOGGER.info("[预览] 动态格 {}/{}（转速非零），动画周期 {} 秒（速度表每格约 5 字节）",
                    result.movingCount(), result.nonAirCount(), animationSeconds);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // v4：实体表
    // ------------------------------------------------------------------

    /**
     * 单条实体裁剪后 NBT 的体积上限（字节）。超限<b>整只丢弃</b>——宁可少画一只，
     * 也不让一条自带大背包/大数据的实体把工厂 BE 的 NBT 撑爆（快照是随 BE 同步的）。
     */
    private static final int MAX_ENTITY_NBT_BYTES = 3072;

    /**
     * 单条<b>装置</b>（contraption）裁剪后 NBT 的体积上限（字节）。
     *
     * <p>装置体积量级完全不同：它的方块结构就存在自己的 {@code Contraption} 复合里
     * （Create {@code Contraption.writeNBT} → {@code Blocks{Palette,BlockList}}，
     * 每方块约 27 字节起 + 每个调色板状态 40~60 字节）⇒ 小型装置 0.5~2 KB、大装置可到 8 KB 以上。
     * 这里给 16 KB 上限，超过就整只丢弃（宁愿这次不画，也不让工厂 BE 的 NBT 被一条装置撑爆）。
     */
    private static final int MAX_CONTRAPTION_NBT_BYTES = 16384;

    /**
     * 裁剪时直接删掉的键：都是<b>渲染无关</b>的大块数据（AI 记忆、属性修饰符、背包、运动状态）。
     * 外观必需标签（展示框的 {@code Item}/{@code Facing}、羊的 {@code Color}、
     * 村民的 {@code VillagerData}、盔甲架的 {@code Pose}、猫/狼/狐狸变体…）一律保留——
     * 逐类型的"外观必需标签"无法静态穷举（第三方实体），所以采用"黑名单删除 + 体积上限"，
     * 后续若发现某类型画错，再按实测往这里加键。
     */
    private static final List<String> ENTITY_STRIP_KEYS = List.of(
            "Motion", "UUID", "Attributes", "Brain", "Inventory", "EnderItems",
            "BrainMemories", "LastDeathLocation", "warden_spawn", "fall_distance");

    /**
     * 采集实体表（v4）：枚举 {@code innerBounds} 内的实体，分两桶——普通实体与装置（contraption）。
     *
     * <p><b>玩家永远不采（源码确认）</b>：{@code AbstractClientPlayer} 的渲染依赖
     * {@code PlayerInfo}/皮肤，客户端没有可用的构造路径（{@code EntityType.create} 造不出来），
     * 房间里正常也不会站着玩家。
     *
     * <p><b>两桶的差异只有"体积上限 + 条数上限"</b>：装置的方块结构整个存在实体 NBT 里
     * （{@code Contraption} 复合），所以它的上限必须远大于普通实体（16 KB vs 3 KB），
     * 条数也要单独限制（一个房间挂 20 个轴承装置的话，快照会变成几十 KB）。
     *
     * <p><b>超限截断是确定性的</b>：按"到取景盒中心的距离"从近到远保留——
     * 与实体遍历顺序无关，同一个房间每次采到的都是同一批（否则快照指纹会随机抖动、反复重烘）。
     */
    private static List<PreviewSnapshot.EntityRecord> collectEntities(ServerLevel level, AABB innerBounds,
                                                                     BlockPos focusOrigin, int step,
                                                                     int width, int height, int depth) {
        int entityLimit = Config.PREVIEW_MAX_ENTITIES.get();
        int contraptionLimit = Config.PREVIEW_MAX_CONTRAPTIONS.get();
        if (entityLimit <= 0 && contraptionLimit <= 0) {
            return List.of();
        }
        List<Entity> plain = new ArrayList<>();
        List<Entity> contraptions = new ArrayList<>();
        int skippedPlayers = 0;
        try {
            for (Entity entity : level.getEntitiesOfClass(Entity.class, innerBounds)) {
                if (entity instanceof Player) {
                    skippedPlayers++;
                    continue;
                }
                if (entity instanceof AbstractContraptionEntity) {
                    contraptions.add(entity);
                    continue;
                }
                plain.add(entity);
            }
        } catch (Throwable error) {
            // 实体采集失败只丢实体段：方块网格照常出预览（纯装饰，绝不影响固化）
            CreateCMPOR.LOGGER.warn("[预览] 实体枚举失败，本次快照不含实体", error);
            return List.of();
        }

        List<PreviewSnapshot.EntityRecord> records = new ArrayList<>();
        if (contraptionLimit > 0 && !contraptions.isEmpty()) {
            records.addAll(pickNearest(contraptions, contraptionLimit, MAX_CONTRAPTION_NBT_BYTES,
                    focusOrigin, step, width, height, depth, "装置"));
        }
        if (entityLimit > 0 && !plain.isEmpty()) {
            records.addAll(pickNearest(plain, entityLimit, MAX_ENTITY_NBT_BYTES,
                    focusOrigin, step, width, height, depth, "实体"));
        }
        if (!records.isEmpty() || skippedPlayers > 0) {
            CreateCMPOR.LOGGER.info(
                    "[预览] 实体：采到 {} 条（房间内 普通 {} 只 / 装置 {} 个；跳过玩家 {} 只；上限 实体 {} / 装置 {}）",
                    records.size(), plain.size(), contraptions.size(), skippedPlayers, entityLimit, contraptionLimit);
        }
        return records;
    }

    /**
     * 一桶实体 → 记录列表：先转记录（取景盒外/读取失败的丢掉），再按"到取景盒中心的距离"
     * 从近到远排序、截断到 {@code limit}。排序键只依赖数据本身，因此结果可复现。
     */
    private static List<PreviewSnapshot.EntityRecord> pickNearest(List<Entity> bucket, int limit, int maxBytes,
                                                                 BlockPos focusOrigin, int step,
                                                                 int width, int height, int depth, String label) {
        List<PreviewSnapshot.EntityRecord> records = new ArrayList<>(bucket.size());
        List<Double> distanceSqr = new ArrayList<>(bucket.size());
        int outOfFocus = 0;
        int tooLarge = 0;
        for (Entity entity : bucket) {
            PreviewSnapshot.EntityRecord record = toEntityRecord(entity, focusOrigin, step, width, height, depth);
            if (record == null) {
                outOfFocus++;
                continue;
            }
            if (record.data().sizeInBytes() > maxBytes) {
                tooLarge++;
                continue;
            }
            double dx = record.x() - (width - 1) / 2.0;
            double dy = record.y() - (height - 1) / 2.0;
            double dz = record.z() - (depth - 1) / 2.0;
            records.add(record);
            distanceSqr.add(dx * dx + dy * dy + dz * dz);
        }
        int dropped = 0;
        if (records.size() > limit) {
            List<Integer> index = new ArrayList<>(records.size());
            for (int i = 0; i < records.size(); i++) {
                index.add(i);
            }
            index.sort(Comparator.comparingDouble(distanceSqr::get));
            List<PreviewSnapshot.EntityRecord> kept = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                kept.add(records.get(index.get(i)));
            }
            dropped = records.size() - limit;
            records = kept;
        }
        if (dropped > 0 || tooLarge > 0 || outOfFocus > 0) {
            CreateCMPOR.LOGGER.info("[预览] {}：保留 {} 条（超条数丢弃 {}、超体积丢弃 {}、取景盒外/读取失败 {}）",
                    label, records.size(), dropped, tooLarge, outOfFocus);
        }
        return records;
    }

    /**
     * 单只实体 → 快照记录；坐标落在取景盒外或读取失败都返回 {@code null}。
     *
     * <p>坐标用<b>快照局部格坐标</b>（相对取景包围盒最小角、再按降采样步长折算），
     * 于是客户端在同一套 1/N 缩放变换下直接摆位即可。
     */
    private static PreviewSnapshot.EntityRecord toEntityRecord(Entity entity, BlockPos focusOrigin, int step,
                                                              int width, int height, int depth) {
        try {
            float x = (float) ((entity.getX() - focusOrigin.getX()) / step);
            float y = (float) ((entity.getY() - focusOrigin.getY()) / step);
            float z = (float) ((entity.getZ() - focusOrigin.getZ()) / step);
            if (x < 0.0F || y < 0.0F || z < 0.0F || x > width || y > height || z > depth) {
                return null;
            }
            CompoundTag data = entity.saveWithoutId(new CompoundTag());
            for (String key : ENTITY_STRIP_KEYS) {
                data.remove(key);
            }
            return new PreviewSnapshot.EntityRecord(EntityType.getKey(entity.getType()).toString(),
                    x, y, z, entity.getYRot(), entity.getXRot(), data);
        } catch (Throwable error) {
            // 单只实体失败不影响其余实体
            CreateCMPOR.LOGGER.debug("[预览] 实体采集失败：{}", entity.getType(), error);
            return null;
        }
    }

    /**
     * 读一格的转速（RPM，带符号）；不是 kinetic 方块或读取失败都返回 0。
     *
     * <p><b>必须用 {@code getTheoreticalSpeed()} 而不是 {@code getSpeed()}</b>：
     * {@code getSpeed()} 在 {@code overStressed} 或 {@code level.tickRateManager().isFrozen()} 时
     * 静默返回 0（Create {@code KineticBlockEntity:289-297}），而过载/冻结在评估副本里完全可能发生，
     * 会把"在转"的部件误采成静止。{@code getTheoreticalSpeed()} 直接返回原始 speed 字段。
     */
    private static float readKineticSpeed(ServerLevel level, BlockPos pos) {
        try {
            if (!(level.getBlockEntity(pos)
                    instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kinetic)) {
                return 0.0F;
            }
            float speed = kinetic.getTheoreticalSpeed();
            return Float.isFinite(speed) ? speed : 0.0F;
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览] 读取转速失败：{}", pos, error);
            return 0.0F;
        }
    }

    /**
     * 记录该格所属的"多方块连通组"——同一控制器（{@code IMultiBlockEntityContainer.getController()}）的方块同组。
     *
     * <p>客户端烘焙时会按组号给代理 BE 注入同一个伪控制器坐标，使 Create 的
     * {@code ConnectivityHandler.isConnected}(实现是 `one.getController().equals(two.getController())`)
     * 判定为连通，从而让连通纹理（CTM）正确显示。快照本身不存 BE NBT，所以这里从源世界读真实控制器。
     */
    private static void recordGroup(ServerLevel level, BlockPos pos, byte[] groups,
                                    Map<BlockPos, Integer> groupIds, int cellIndex) {
        if (!(level.getBlockEntity(pos)
                instanceof com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer container)) {
            return;
        }
        BlockPos controller = container.getController();
        if (controller == null) {
            return;
        }
        Integer group = groupIds.get(controller);
        if (group == null) {
            if (groupIds.size() >= PreviewSnapshot.MAX_GROUPS) {
                return;
            }
            group = groupIds.size() + 1;
            groupIds.put(controller, group);
        }
        groups[cellIndex] = group.byteValue();
    }

    /**
     * 盒式降采样：取 {@code step³} 组内出现次数最多的非空气方块（全空气则返回 null）。
     *
     * <p>选取逻辑与 0.4.0 逐字一致（同一份 histogram + 同一套"严格大于"比较），
     * 只是顺带把"每个候选方块第一次出现的位置"记进 {@code positionOf}，用于给 {@code winner} 出参定位。
     *
     * <p>{@code winner} 为出参：降采样格的代表位置（胜出方块首次出现的坐标）。v3 采集转速时按它读 BE
     * ——渲染画的就是这个"代表方块"，转速自然也该取它的，否则会把一个没被画出来的方块的速度
     * 安到代表方块身上。<b>已知局限</b>：降采样格只能表达一个转速，同一格里同时有外壳与传动轴时
     * 只有代表方块的速度可用。
     */
    private static BlockState dominantState(ServerLevel level, BlockPos roomMin,
                                            int focusMinX, int focusMinY, int focusMinZ,
                                            int x, int y, int z, int step,
                                            int focusWidth, int focusHeight, int focusDepth,
                                            Map<BlockState, int[]> histogram, BlockPos.MutableBlockPos cursor,
                                            BlockPos.MutableBlockPos winner, Map<BlockState, Long> positionOf) {
        histogram.clear();
        positionOf.clear();
        for (int dz = 0; dz < step; dz++) {
            int sz = z * step + dz;
            if (sz >= focusDepth) {
                break;
            }
            for (int dy = 0; dy < step; dy++) {
                int sy = y * step + dy;
                if (sy >= focusHeight) {
                    break;
                }
                for (int dx = 0; dx < step; dx++) {
                    int sx = x * step + dx;
                    if (sx >= focusWidth) {
                        break;
                    }
                    BlockState state = level.getBlockState(cursor.set(
                            roomMin.getX() + focusMinX + sx,
                            roomMin.getY() + focusMinY + sy,
                            roomMin.getZ() + focusMinZ + sz));
                    if (state.isAir()) {
                        continue;
                    }
                    if (histogram.computeIfAbsent(state, ignored -> new int[1])[0] == 0) {
                        positionOf.put(state, cursor.asLong());
                    }
                    histogram.get(state)[0]++;
                }
            }
        }
        BlockState best = null;
        int bestCount = 0;
        for (Map.Entry<BlockState, int[]> entry : histogram.entrySet()) {
            if (entry.getValue()[0] > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue()[0];
            }
        }
        Long packed = best == null ? null : positionOf.get(best);
        winner.set(0, 0, 0);
        if (packed != null) {
            winner.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
        }
        return best;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    /** 供调试命令使用：把调色板前若干项格式化成"方块 ×数量"的可读文本。 */
    public static List<String> describePalette(PreviewSnapshot snapshot, int limit) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < snapshot.volume(); i++) {
            BlockState state = snapshot.stateAt(i);
            if (state == null) {
                continue;
            }
            counts.merge(state.toString(), 1, Integer::sum);
        }
        List<String> lines = new ArrayList<>();
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .forEach(entry -> lines.add(entry.getKey() + " ×" + entry.getValue()));
        return lines;
    }
}
