package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.CreateCMPOR;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 复用维度现有 ChunkMap/entity/POI worker，并管理会话专属 staging。 */
final class EvaluationStorageBridge {
    private EvaluationStorageBridge() {
    }

    static ChunkStorage chunkStorage(ServerLevel level) {
        return level.getChunkSource().chunkMap;
    }

    static SimpleRegionStorage entityStorage(ServerLevel level) {
        EntityPersistentStorage<Entity> storage = level.entityManager.permanentStorage;
        if (!(storage instanceof EntityStorage entityStorage)) {
            throw new IllegalStateException("当前实体持久化存储不是 EntityStorage");
        }
        return entityStorage.simpleRegionStorage;
    }

    static SimpleRegionStorage poiStorage(ServerLevel level) {
        return level.getPoiManager().simpleRegionStorage;
    }

    /**
     * 严格「已完全卸载」：五个子条件必须全空（含 {@code pendingUnloads} 必须为空）。
     *
     * <p>⚠️ 这是<b>最严格</b>的读法，只在<b>诊断</b>里用（判断房间是否已彻底离开卸载流程）。
     * <b>不要</b>用它做冻结门控——见 {@link #areChunksUnloadedForFreeze} 的说明。</p>
     */
    static boolean isChunkIdle(ServerLevel level, ChunkPos pos) {
        return isChunkContentUnloaded(level, pos)
                && !level.getChunkSource().chunkMap.pendingUnloads.containsKey(pos.toLong());
    }

    static boolean areChunksIdle(ServerLevel level, List<ChunkPos> chunks) {
        return chunks.stream().allMatch(pos -> isChunkIdle(level, pos));
    }

    /**
     * 「内容已卸载」：区块本体不在内存（可见表/更新表/chunkNow/实体全部为空）。
     *
     * <p>与 {@link #isChunkIdle} 的区别：<b>不检查</b> {@code pendingUnloads}。
     * 用于识别 vanilla 卸载收尾卡死——{@code ChunkMap.scheduleUnload} 是异步自我重试
     * （{@code if (!holder.isReadyForSaving()) scheduleUnload(...)}），一旦该 holder 的
     * {@code saveSync} / generation 引用永不归零，holder 会永远留在 {@code pendingUnloads}，
     * 但区块内容其实早已卸载、且没有任何票据（实测日志：{@code pendingUnload=true, tickets=[],
     * visible=false, chunkNow=false, entities=false}）。此时评估读的是磁盘数据，继续冻结是安全的。
     */
    static boolean isChunkContentUnloaded(ServerLevel level, ChunkPos pos) {
        long key = pos.toLong();
        return level.getChunkSource().chunkMap.getVisibleChunkIfPresent(key) == null
                && level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(key) == null
                && level.getChunkSource().getChunkNow(pos.x, pos.z) == null
                && !level.areEntitiesLoaded(key);
    }

    /**
     * <b>冻结判定（唯一权威）</b>：该区块是否允许被冻结/被冷拷贝。
     *
     * <p><b>为什么 <u>不看</u> {@code pendingUnloads}</b>（0.4.28 修复·重大口径错误）：
     * {@code ChunkMap.scheduleUnload}（{@code ChunkMap:532-563}）把 holder 从 {@code pendingUnloads}
     * 移除的<b>唯一</b>路径是「{@code isReadyForSaving()} 为真」，而它的自我重试链挂在
     * {@code holder.getSaveSyncFuture()} 上——{@code saveSync} 的<b>唯一</b>写入者是
     * {@code ChunkHolder.addSaveDependency}（{@code ChunkHolder:125-131}），
     * 只在 {@code ChunkHolder.updateFutures} 的 278/291/308 行被调用，喂的是
     * {@code fullChunkFuture} / {@code tickingChunkFuture} / {@code entityTickingChunkFuture}。
     * <b>它与磁盘 IO 无关</b>（{@code ChunkMap.save} 从不碰它），等的是"升级到
     * FULL / BLOCK_TICKING / ENTITY_TICKING 的 future"。这些 future 的完成回调排在
     * {@code mainThreadExecutor}（= 服务端主线程邮箱）上，加上 {@code processUnloads} 自身受
     * tick 时间预算（{@code hasMoreTime}）限制，服务器一卡 holder 就会在 {@code pendingUnloads}
     * 里滞留<b>数秒到数分钟</b>，队列排空后自己消失。该服常驻
     * {@code Can't keep up! Running 2000~5800ms behind} ⇒ 属<b>正常滞后</b>，不是死锁。
     * 把 {@code !pendingUnloads} 当门控 ⇒ 在繁忙服上房间几乎永远无法评估
     * （实测 35/35 次失败全部因此）。
     *
     * <p><b>真正要求的不变式是「源房间内容不在 level 里」</b>：区块本体不在
     * {@code visibleChunkMap} / {@code updatingChunkMap} / {@code getChunkNow}，且实体未加载
     * ⇒ 源房间不可能再被方块 tick 或实体 tick 修改。此时冷拷贝走的是 {@code ChunkStorage.read}
     * → {@code IOWorker.loadAsync}（{@code IOWorker:137-145} <b>优先返回 {@code pendingWrites}</b>，
     * 即最后一份写入数据），不需要等 {@code pendingUnloads} 排空。
     * 若扫描期间源区块真被复载，顶层 + {@code tickStagingSource} 两层复检与
     * {@code staging 摘要不一致} 探针会照常兜底（{@code ChunkMap:414} 会把复用的 holder 从
     * {@code pendingUnloads} 直接搬回 {@code updatingChunkMap}，所以复载一定看得见）。
     *
     * <p><b>诊断</b>：{@code readyForSaving=false} 时的 {@code generationRefCount} 与
     * {@code saveSyncDone} 是区分「正常滞后」与「真卡死」的唯一实锤（见 {@link #describePendingHolder}）。
     *
     * @see #isChunkUnloadBacklogged 取证判据（只用于日志，不参与门控）
     */
    static boolean isChunkUnloadedForFreeze(ServerLevel level, ChunkPos pos) {
        return isChunkContentUnloaded(level, pos);
    }

    /**
     * <b>冻结判定（唯一权威）</b>——房间级：每个区块都满足 {@link #isChunkUnloadedForFreeze}。
     *
     * <p><b>逐区块</b>判定，绝不能写成「全部内容已卸 + 任一满足」：后者会误放行
     * 「大部分区块已卸、个别区块被外部强加载」的房间，那才是真正危险的场景。</p>
     *
     * <p><b>三处门控必须共用本方法</b>（{@code EvaluationManager.tickWaitingForUnload}、
     * {@code EvaluationCloneManager.tickFrozen}/{@code tickStagingSource}）。0.4.27 及以前的 bug 正是
     * 「豁免放行冻结 → 下一步严格复检立刻判未卸载 → 回滚」，16~52ms 内必现、玩家侧只看到
     * 「原房间区块未保持卸载，评估已安全取消」。</p>
     */
    static boolean areChunksUnloadedForFreeze(ServerLevel level, List<ChunkPos> chunks) {
        return chunks.stream().allMatch(pos -> isChunkUnloadedForFreeze(level, pos));
    }

    /**
     * 取证判据：该区块是否「滞后在 {@code pendingUnloads} 且 vanilla 尚未判定可保存」。
     *
     * <p>内容已卸载、无票据、实体已卸，却仍被 {@code scheduleUnload} 的重试链挂着——
     * 用 {@link #describePendingHolder} 打印 {@code isReadyForSaving()}/{@code saveSync} 依据。
     * 仅用于诊断，不参与门控。
     */
    static boolean isChunkUnloadBacklogged(ServerLevel level, ChunkPos pos) {
        long key = pos.toLong();
        return isChunkUnloadedForFreeze(level, pos)
                && level.getChunkSource().chunkMap.pendingUnloads.containsKey(key);
    }

    /** 该房间是否有区块滞后在 {@code pendingUnloads}（诊断/降噪计时用）。 */
    static boolean hasChunkUnloadBacklog(ServerLevel level, List<ChunkPos> chunks) {
        return chunks.stream().anyMatch(pos -> isChunkUnloadBacklogged(level, pos));
    }

    /**
     * 诊断：滞留在 {@code pendingUnloads} 的 holder 为什么还没通过 {@code isReadyForSaving()}。
     * 这是「滞后」与「真卡死」唯一的证据来源（{@code generationRefCount} 与 {@code saveSync}）。
     */
    static String describePendingHolder(ServerLevel level, ChunkPos pos) {
        ChunkHolder holder = level.getChunkSource().chunkMap.pendingUnloads.get(pos.toLong());
        if (holder == null) {
            return "holder=<已离开队列>";
        }
        boolean saveSyncDone;
        try {
            saveSyncDone = holder.getSaveSyncFuture().isDone();
        } catch (RuntimeException exception) {
            return "holder=<读取失败:" + exception.getClass().getSimpleName() + ">";
        }
        return "holder{readyForSaving=" + holder.isReadyForSaving()
                + ", generationRefCount=" + holder.getGenerationRefCount()
                + ", saveSyncDone=" + saveSyncDone
                + ", ticketLevel=" + holder.getTicketLevel() + '}';
    }

    /**
     * A：准确描述"卸载被什么挡住"——旧文案一律说"被外部票据加载"，
     * 与诊断里 {@code tickets=[]} 的事实矛盾，会把排查引向错误方向（外部模组）。
     * 这里按实际子条件归类。
     */
    static String describeUnloadBlocker(ServerLevel level, List<ChunkPos> chunks) {
        int backlogged = 0;    // 仍在 pendingUnloads（vanilla 收尾滞后/卡死）
        int ticketed = 0;      // 有票据持有
        int inMemory = 0;      // 仍在可见/更新表或内容在内存
        int entities = 0;      // 实体仍加载
        for (ChunkPos pos : chunks) {
            long key = pos.toLong();
            boolean contentUnloaded = isChunkContentUnloaded(level, pos);
            boolean pending = level.getChunkSource().chunkMap.pendingUnloads.containsKey(key);
            boolean hasTicket = !chunkTicketNames(level, key).isEmpty();
            if (hasTicket) {
                ticketed++;
            }
            if (!contentUnloaded) {
                inMemory++;
            }
            if (level.areEntitiesLoaded(key)) {
                entities++;
            }
            if (contentUnloaded && pending) {
                backlogged++;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (ticketed > 0) {
            sb.append(ticketed).append(" 个区块仍持有票据（外部加载）");
        }
        if (inMemory > 0) {
            sb.append(sb.isEmpty() ? "" : "；").append(inMemory).append(" 个区块内容仍在内存");
        }
        if (entities > 0) {
            sb.append(sb.isEmpty() ? "" : "；").append(entities).append(" 个区块实体仍加载");
        }
        if (backlogged > 0) {
            sb.append(sb.isEmpty() ? "" : "；").append(backlogged)
                    .append(" 个区块仍在 vanilla 卸载队列（内容已卸载，收尾未跑完；卡顿下属正常滞后）");
        }
        if (sb.isEmpty()) {
            sb.append("原因未归类（详见诊断行）");
        }
        return sb.toString();
    }

    /**
     * 失败探针：源房间区块 idle 状态详查（谁在持有/哪个票据在拉加载）。
     * 逐区块输出五个 idle 子条件 + ChunkHolder 的 ticketLevel/fullStatus/ticking + DistanceManager 上的票据清单。
     * 只在失败路径调用（一次性），用于定位"冻结后 ~1 tick 内复载源区块"的机制。
     */
    static String sourceIdleDiagnostics(ServerLevel level, List<ChunkPos> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("dim=").append(level.dimension().location())
                .append(", players=").append(level.players().size())
                .append(", roomChunks=").append(chunks.size()).append('\n');
        int shown = 0;
        for (ChunkPos pos : chunks) {
            long key = pos.toLong();
            var map = level.getChunkSource().chunkMap;
            var visible = map.getVisibleChunkIfPresent(key);
            var updating = map.getUpdatingChunkIfPresent(key);
            boolean pendingUnload = map.pendingUnloads.containsKey(key);
            var loaded = level.getChunkSource().getChunkNow(pos.x, pos.z);
            boolean entitiesLoaded = level.areEntitiesLoaded(key);
            boolean idle = isChunkIdle(level, pos);
            sb.append("  ").append(pos).append(" idle=").append(idle)
                    .append(" {visible=").append(visible != null)
                    .append(", updating=").append(updating != null)
                    .append(", pendingUnload=").append(pendingUnload)
                    .append(", chunkNow=").append(loaded != null)
                    .append(", entities=").append(entitiesLoaded).append('}');
            if (visible != null) {
                sb.append(" holder{ticketLevel=").append(visible.getTicketLevel())
                        .append(", status=").append(visible.getFullStatus())
                        .append(", ticking=").append(visible.getTickingChunk() != null).append('}');
            } else if (pendingUnload) {
                // 明细统一放到诊断块末尾的 holder 表，避免逐区块重复长篇（大房间会刷爆日志）
                sb.append(" holder=见末尾");
            }
            sb.append(" tickets=[").append(chunkTicketNames(level, key)).append(']').append('\n');
            if (++shown >= 24) {
                sb.append("  ... 仅显示前 24 个 / 共 ").append(chunks.size()).append(" 个区块\n");
                break;
            }
        }
        // holder 明细表：pendingUnloads 里的 holder 为何还没通过 isReadyForSaving()。
        // 「正常滞后」（saveSyncDone=false，等升级 future）与「真卡死」（readyForSaving=false 且
        // generationRefCount>0 ⇒ scheduleUnload 自我重试空转）在这里一眼可分。
        boolean headerWritten = false;
        for (ChunkPos pos : chunks) {
            if (!level.getChunkSource().chunkMap.pendingUnloads.containsKey(pos.toLong())) {
                continue;
            }
            if (!headerWritten) {
                sb.append("  —— 仍在 vanilla 卸载队列的 holder（未通过 isReadyForSaving 的依据）：\n");
                headerWritten = true;
            }
            sb.append("    ").append(pos).append(' ').append(describePendingHolder(level, pos)).append('\n');
        }
        return sb.toString();
    }

    /** 玩家对话框用的简洁诊断（中文）：统计非 idle 区块、出现的区块票据类型与维度玩家数。 */
    static String chatSourceIdleSummary(ServerLevel level, List<ChunkPos> chunks, String phase) {
        java.util.LinkedHashSet<String> ticketTypes = new java.util.LinkedHashSet<>();
        java.util.List<ChunkPos> nonIdle = new java.util.ArrayList<>();
        int backlogged = 0;
        for (ChunkPos pos : chunks) {
            long key = pos.toLong();
            if (!isChunkIdle(level, pos)) {
                nonIdle.add(pos);
                for (var ticket : ticketsAt(level, key)) {
                    ticketTypes.add(ticket.getType().toString());
                }
                if (isChunkUnloadBacklogged(level, pos)) {
                    backlogged++;
                }
            }
        }
        StringBuilder sb = new StringBuilder("【诊断·").append(phase).append("】");
        if (nonIdle.isEmpty()) {
            sb.append("此刻源区块已全部恢复卸载，复载为瞬时现象，未留下票据/持有者线索。");
            return sb.toString();
        }
        sb.append("源区块 ").append(nonIdle.size()).append('/').append(chunks.size()).append(" 未保持卸载");
        if (backlogged > 0) {
            sb.append("（其中 ").append(backlogged).append(" 个只是仍在 vanilla 卸载队列收尾，卡顿时属正常滞后）");
        }
        for (int i = 0; i < Math.min(nonIdle.size(), 3); i++) {
            sb.append(i == 0 ? "，例如 " : ", ").append(nonIdle.get(i));
        }
        sb.append("；区块票据类型: ").append(ticketTypes.isEmpty() ? "无" : String.join(", ", ticketTypes))
                .append("；该维度玩家数: ").append(level.players().size())
                .append("。详见服务端日志。");
        return sb.toString();
    }

    /**
     * DistanceManager.tickets 为包私有字段（新增 AT 在 IDEA 编译模型里不总是即时生效，
     * 会报 "tickets 不为 public，无法从外部软件包访问"）。这里用反射读取，Gradle/IDEA 均无需 AT 即可编译。
     * final Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>>；Long2ObjectMap 实现 java.util.Map，故可安全转型。
     */
    private static final java.lang.reflect.Field TICKETS_FIELD = findTicketsField();

    private static java.lang.reflect.Field findTicketsField() {
        try {
            java.lang.reflect.Field field = net.minecraft.server.level.DistanceManager.class.getDeclaredField("tickets");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | RuntimeException exception) {
            CreateCMPOR.LOGGER.warn("无法反射 DistanceManager.tickets，探针将不显示区块票据类型", exception);
            return null;
        }
    }

    /** 反射读取该区块在 DistanceManager 上的全部 Ticket（可能为空；失败也返回空，不抛异常）。 */
    private static java.util.List<net.minecraft.server.level.Ticket<?>> ticketsAt(ServerLevel level, long key) {
        java.util.List<net.minecraft.server.level.Ticket<?>> result = new java.util.ArrayList<>();
        try {
            if (TICKETS_FIELD == null) {
                return result;
            }
            Object mapObj = TICKETS_FIELD.get(level.getChunkSource().chunkMap.getDistanceManager());
            if (!(mapObj instanceof java.util.Map<?, ?> ticketMap)) {
                return result;
            }
            Object setObj = ticketMap.get(key);
            if (setObj instanceof Iterable<?> iterable) {
                for (Object obj : iterable) {
                    if (obj instanceof net.minecraft.server.level.Ticket<?> ticket) {
                        result.add(ticket);
                    }
                }
            }
        } catch (IllegalAccessException | RuntimeException ignored) {
            // 探针尽力而为：反射失败仅导致票据列缺失，不影响主流程
        }
        return result;
    }

    private static String chunkTicketNames(ServerLevel level, long key) {
        StringBuilder sb = new StringBuilder();
        for (var ticket : ticketsAt(level, key)) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(ticket.getType()).append('@').append(ticket.getTicketLevel());
            if (ticket.isForceTicks()) {
                sb.append("(forceTick)");
            }
        }
        return sb.toString();
    }

    /**
     * 只序列化并异步写入一个已经加载的区块。
     * ChunkSerializer 必须在主线程调用；ChunkStorage.write 的实际磁盘 IO 由该维度 IOWorker 完成。
     */
    static CompletableFuture<Void> saveLoadedChunk(ServerLevel level, ChunkPos pos) {
        ChunkAccess chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
        if (chunk == null) {
            return CompletableFuture.completedFuture(null);
        }
        level.getPoiManager().flush(pos);
        CompoundTag tag = ChunkSerializer.write(level, chunk);
        chunk.setUnsaved(false);
        return chunkStorage(level).write(pos, tag).whenComplete((ignored, error) -> {
            if (error != null) {
                chunk.setUnsaved(true);
            }
        });
    }

    static ChunkStorage openStaging(MinecraftServer server, EvaluationManifest manifest) {
        Path root = stagingRoot(server, manifest);
        try {
            Files.createDirectories(root.resolve("region"));
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建评估 staging 目录", exception);
        }
        return new ChunkStorage(
                new RegionStorageInfo(manifest.sourceLevelId(), manifest.targetDimension(),
                        CreateCMPOR.MOD_ID + "_evaluation_staging"),
                root.resolve("region"), server.getFixerUpper(), server.forceSynchronousWrites());
    }

    static void deleteStaging(MinecraftServer server, EvaluationManifest manifest) {
        Path root = stagingRoot(server, manifest);
        if (Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法删除评估 staging 目录", exception);
        }
    }

    static CompletableFuture<SourceRecords> readSourceRecords(ServerLevel level, ChunkPos pos) {
        // 源房间在 WAITING_UNLOAD 阶段由 vanilla ChunkMap 逐区块提交到同一 IOWorker；
        // 这里的 read 会优先返回 pendingWrites，因此不需要再次 per-chunk synchronize/全量 flush。
        CompletableFuture<Optional<CompoundTag>> chunk = chunkStorage(level).read(pos);
        CompletableFuture<Optional<CompoundTag>> entities = entityStorage(level).read(pos);
        CompletableFuture<Optional<CompoundTag>> poi = poiStorage(level).read(pos);
        return CompletableFuture.allOf(chunk, entities, poi)
                .thenApply(ignored -> new SourceRecords(pos, chunk.join(), entities.join(), poi.join()));
    }

    static CompletableFuture<StoredRecords> readStoredRecords(ServerLevel level, ChunkPos pos) {
        CompletableFuture<Optional<CompoundTag>> chunk = chunkStorage(level).read(pos);
        CompletableFuture<Optional<CompoundTag>> entities = entityStorage(level).read(pos);
        CompletableFuture<Optional<CompoundTag>> poi = poiStorage(level).read(pos);
        return CompletableFuture.allOf(chunk, entities, poi)
                .thenApply(ignored -> new StoredRecords(pos, chunk.join(), entities.join(), poi.join()));
    }

    static SourceChunk inspectSource(ServerLevel source, SourceRecords records,
                                     java.util.Map<UUID, UUID> backpackUuids) {
        CompoundTag chunk = records.chunk().orElseThrow(() ->
                new IllegalStateException("源区块记录缺失：" + records.pos()));
        validateChunkTag(source, records.pos(), chunk);
        inspectLegacyEntities(records.pos(), chunk);
        ListTag entities = inspectEntityRecord(records.pos(), records.entities());
        Optional<CompoundTag> poi = inspectPoiRecord(records.pos(), records.poi());
        inspectBlockPalette(source, records.pos(), chunk);
        CompoundTag copy = chunk.copy();
        // 副本初始化：精妙背包 storage_uuid 换成新 UUID——防副本与源房间共用全局
        // SavedData（否则副本侧写入直接落到源房间背包，表现为"凭空多出产物"）。
        // 纯 NBT 改写，可在本异步线程执行；内容深拷贝/清理由 CloneManager 在服务端线程完成。
        // 无背包时为空操作。源 hash 用原始 chunk（源侧），staging 校验读回的是改写后内容。
        com.yansunsky.createcmpor.compat.inventory.SophisticatedBackpackIsolation
                .rewriteUuids(copy, backpackUuids);
        return new SourceChunk(records.pos(), copy, CanonicalNbtHasher.sha256(chunk),
                chunk.getInt("DataVersion"), entities.copy(), poi);
    }

    static CompletableFuture<Void> deleteRecords(ServerLevel level, List<ChunkPos> chunks) {
        CompletableFuture<?>[] futures = chunks.stream().flatMap(pos -> java.util.stream.Stream.of(
                        chunkStorage(level).write(pos, null),
                        entityStorage(level).write(pos, null),
                        poiStorage(level).write(pos, null)))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    private static Path stagingRoot(MinecraftServer server, EvaluationManifest manifest) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path stagingBase = worldRoot.resolve("data/createcmpor/evaluation-staging").normalize();
        Path root = worldRoot.resolve(manifest.stagingDirectory()).normalize();
        if (!root.startsWith(stagingBase)
                || !root.getFileName().toString().equals(manifest.sessionId().toString())) {
            throw new IllegalStateException("评估 staging 路径不受当前会话控制");
        }
        return root;
    }

    private static void validateChunkTag(ServerLevel source, ChunkPos expected, CompoundTag tag) {
        if (!tag.contains("xPos", Tag.TAG_ANY_NUMERIC) || !tag.contains("zPos", Tag.TAG_ANY_NUMERIC)
                || tag.getInt("xPos") != expected.x || tag.getInt("zPos") != expected.z) {
            throw new IllegalStateException("源区块 NBT 坐标错误：" + expected);
        }
        if (!tag.contains("DataVersion", Tag.TAG_ANY_NUMERIC) || tag.getInt("DataVersion") < 0) {
            throw new IllegalStateException("源区块缺少有效 DataVersion：" + expected);
        }
        if (!tag.contains("Status", Tag.TAG_STRING)) {
            throw new IllegalStateException("源区块缺少 Status：" + expected);
        }
        ChunkStatus status = ChunkStatus.byName(tag.getString("Status"));
        if (status == null || status.getChunkType() != ChunkType.LEVELCHUNK) {
            throw new IllegalStateException("源区块不是完整 LevelChunk：" + expected);
        }
        if (!tag.contains("sections", Tag.TAG_LIST) || tag.getList("sections", Tag.TAG_COMPOUND).isEmpty()) {
            throw new IllegalStateException("源区块 sections 无效：" + expected);
        }
    }

    private static void inspectLegacyEntities(ChunkPos pos, CompoundTag chunk) {
        if (!chunk.contains("entities")) {
            return;
        }
        if (!chunk.contains("entities", Tag.TAG_LIST)) {
            throw new IllegalStateException("源区块 legacy entities 格式无效：" + pos);
        }
        ListTag entities = chunk.getList("entities", Tag.TAG_COMPOUND);
        if (!entities.isEmpty()) {
            throw new UnsupportedContentException("message.createcmpor.evaluation.entities_unsupported");
        }
    }

    private static ListTag inspectEntityRecord(ChunkPos pos, Optional<CompoundTag> raw) {
        if (raw.isEmpty()) {
            return new ListTag();
        }
        CompoundTag tag = raw.get();
        if (!tag.contains("Position", Tag.TAG_INT_ARRAY)
                || tag.getIntArray("Position").length != 2
                || tag.getIntArray("Position")[0] != pos.x
                || tag.getIntArray("Position")[1] != pos.z
                || !tag.contains("Entities", Tag.TAG_LIST)) {
            throw new IllegalStateException("源实体记录格式无效：" + pos);
        }
        return tag.getList("Entities", Tag.TAG_COMPOUND).copy();
    }

    /**
     * 解析并校验源 POI 记录（Phase 4 起允许 POI 复制参与评估）。
     * 返回规范化副本（含 Position 与 Sections），供发布窗口写入目标 POI 存储。
     */
    private static Optional<CompoundTag> inspectPoiRecord(ChunkPos pos, Optional<CompoundTag> raw) {
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag tag = raw.get();
        if (!tag.contains("Sections", Tag.TAG_COMPOUND)) {
            throw new IllegalStateException("源 POI 记录格式无效：" + pos);
        }
        CompoundTag sections = tag.getCompound("Sections");
        for (String key : sections.getAllKeys()) {
            if (!sections.contains(key, Tag.TAG_COMPOUND)) {
                throw new IllegalStateException("源 POI section 格式无效：" + pos);
            }
            CompoundTag section = sections.getCompound(key);
            if (!section.contains("Records", Tag.TAG_LIST)) {
                throw new IllegalStateException("源 POI records 格式无效：" + pos);
            }
            ListTag records = section.getList("Records", Tag.TAG_COMPOUND);
            for (int i = 0; i < records.size(); i++) {
                CompoundTag poiRecord = records.getCompound(i);
                // BlockPos.CODEC = Codec.INT_STREAM → NBT 编码为 IntArrayTag（[I;x,y,z]），非 LongTag
                if (!poiRecord.contains("pos", Tag.TAG_INT_ARRAY) || !poiRecord.contains("type", Tag.TAG_STRING)) {
                    throw new IllegalStateException("源 POI 记录条目格式无效：" + pos);
                }
            }
        }
        return Optional.of(tag.copy());
    }

    private static void inspectBlockPalette(ServerLevel source, ChunkPos pos, CompoundTag chunk) {
        var blockLookup = source.registryAccess().lookupOrThrow(Registries.BLOCK);
        ListTag sections = chunk.getList("sections", Tag.TAG_COMPOUND);
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            CompoundTag section = sections.getCompound(sectionIndex);
            if (!section.contains("block_states")) {
                continue;
            }
            if (!section.contains("block_states", Tag.TAG_COMPOUND)) {
                throw new IllegalStateException("源区块 block_states 格式无效：" + pos);
            }
            CompoundTag blockStates = section.getCompound("block_states");
            if (!blockStates.contains("palette", Tag.TAG_LIST)) {
                throw new IllegalStateException("源区块 palette 缺失：" + pos);
            }
            ListTag palette = blockStates.getList("palette", Tag.TAG_COMPOUND);
            for (int paletteIndex = 0; paletteIndex < palette.size(); paletteIndex++) {
                CompoundTag stateTag = palette.getCompound(paletteIndex);
                if (!stateTag.contains("Name", Tag.TAG_STRING)) {
                    throw new IllegalStateException("源区块 palette 方块状态无效：" + pos);
                }
                ResourceLocation blockId = ResourceLocation.tryParse(stateTag.getString("Name"));
                if (blockId == null || blockLookup.get(ResourceKey.create(Registries.BLOCK, blockId)).isEmpty()) {
                    throw new IllegalStateException("源区块 palette 包含未知方块：" + pos);
                }
                // 方块级 + 模组级黑名单（统一走 Blacklist，避免多处判定漂移）
                Blacklist.checkBlock(blockId);
                if ("create:track_signal".equals(blockId.toString())) {
                    throw new UnsupportedContentException("message.createcmpor.evaluation.railway_signal_unsupported");
                }
            }
        }
    }

    /** 构造目标实体记录并写入目标实体存储（必须在目标 chunk 实体未加载的发布窗口内调用）。 */
    static CompletableFuture<Void> writeEntities(ServerLevel target, ChunkPos pos, ListTag entities) {
        CompoundTag record = new CompoundTag();
        record.put("Position", new IntArrayTag(new int[]{pos.x, pos.z}));
        record.put("Entities", entities);
        net.minecraft.nbt.NbtUtils.addCurrentDataVersion(record);
        return entityStorage(target).write(pos, record);
    }

    /** 把源 POI 记录原样写入目标 POI 存储（必须在目标 chunk 未加载的发布窗口内调用）。 */
    static CompletableFuture<Void> writePoi(ServerLevel target, ChunkPos pos, CompoundTag poi) {
        CompoundTag record = poi.copy();
        if (!record.contains("Position", Tag.TAG_INT_ARRAY)) {
            record.put("Position", new IntArrayTag(new int[]{pos.x, pos.z}));
        }
        net.minecraft.nbt.NbtUtils.addCurrentDataVersion(record);
        return poiStorage(target).write(pos, record);
    }

    record SourceRecords(ChunkPos pos, Optional<CompoundTag> chunk,
                         Optional<CompoundTag> entities, Optional<CompoundTag> poi) {
    }

    record StoredRecords(ChunkPos pos, Optional<CompoundTag> chunk,
                         Optional<CompoundTag> entities, Optional<CompoundTag> poi) {
        boolean allAbsent() {
            return chunk.isEmpty() && entities.isEmpty() && poi.isEmpty();
        }
    }

    record SourceChunk(ChunkPos pos, CompoundTag tag, String hash, int dataVersion, ListTag entities,
                       Optional<CompoundTag> poi) {
    }

    static final class UnsupportedContentException extends RuntimeException {
        private final String messageKey;

        UnsupportedContentException(String messageKey) {
            super(messageKey);
            this.messageKey = messageKey;
        }

        String messageKey() {
            return messageKey;
        }
    }
}