package com.yansunsky.createcmpor.preview;

import com.yansunsky.createcmpor.CreateCMPOR;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
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
        Map<BlockState, int[]> histogram = step > 1 ? new HashMap<>() : null;
        BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos();

        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    BlockState state = step == 1
                            ? level.getBlockState(sample.set(roomMin.getX() + minX + x,
                                    roomMin.getY() + minY + y, roomMin.getZ() + minZ + z))
                            : dominantState(level, roomMin, minX, minY, minZ, x, y, z, step,
                                    focusWidth, focusHeight, focusDepth, histogram, sample);
                    if (state == null || state.isAir()) {
                        continue;
                    }
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
                    cells[x + width * (y + height * z)] = index.byteValue();
                }
            }
        }

        PreviewSnapshot result = new PreviewSnapshot(width, height, depth, palette, cells);
        if (result.nonAirCount() == 0) {
            CreateCMPOR.LOGGER.info("[预览] 房间内没有可用方块，跳过预览");
            return null;
        }
        CreateCMPOR.LOGGER.info(
                "[预览] 采集完成：房间 {}x{}x{} → 取景 {}x{}x{}（步长 {}）→ 快照 {}x{}x{}，非空气 {} 格，调色板 {} 种，约 {} 字节",
                roomWidth, roomHeight, roomDepth, focusWidth, focusHeight, focusDepth, step,
                result.width(), result.height(), result.depth(), result.nonAirCount(),
                result.paletteSize(), result.encodedSize());
        return result;
    }

    /** 盒式降采样：取 {@code step³} 组内出现次数最多的非空气方块（全空气则返回 null）。 */
    private static BlockState dominantState(ServerLevel level, BlockPos roomMin,
                                            int focusMinX, int focusMinY, int focusMinZ,
                                            int x, int y, int z, int step,
                                            int focusWidth, int focusHeight, int focusDepth,
                                            Map<BlockState, int[]> histogram, BlockPos.MutableBlockPos cursor) {
        histogram.clear();
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
                    histogram.computeIfAbsent(state, ignored -> new int[1])[0]++;
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
