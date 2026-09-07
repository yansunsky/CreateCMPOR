package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
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

    static boolean isChunkIdle(ServerLevel level, ChunkPos pos) {
        long key = pos.toLong();
        return level.getChunkSource().chunkMap.getVisibleChunkIfPresent(key) == null
                && level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(key) == null
                && !level.getChunkSource().chunkMap.pendingUnloads.containsKey(key)
                && level.getChunkSource().getChunkNow(pos.x, pos.z) == null
                && !level.areEntitiesLoaded(key);
    }

    static boolean areChunksIdle(ServerLevel level, List<ChunkPos> chunks) {
        return chunks.stream().allMatch(pos -> isChunkIdle(level, pos));
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
            boolean idle = visible == null && updating == null && !pendingUnload
                    && loaded == null && !entitiesLoaded;
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
            }
            sb.append(" tickets=[").append(chunkTicketNames(level, key)).append(']').append('\n');
            if (++shown >= 24) {
                sb.append("  ... 仅显示前 24 个 / 共 ").append(chunks.size()).append(" 个区块\n");
                break;
            }
        }
        return sb.toString();
    }

    /** 玩家对话框用的简洁诊断（中文）：统计非 idle 区块、出现的区块票据类型与维度玩家数。 */
    static String chatSourceIdleSummary(ServerLevel level, List<ChunkPos> chunks, String phase) {
        java.util.LinkedHashSet<String> ticketTypes = new java.util.LinkedHashSet<>();
        java.util.List<ChunkPos> nonIdle = new java.util.ArrayList<>();
        for (ChunkPos pos : chunks) {
            long key = pos.toLong();
            var map = level.getChunkSource().chunkMap;
            boolean idle = map.getVisibleChunkIfPresent(key) == null
                    && map.getUpdatingChunkIfPresent(key) == null
                    && !map.pendingUnloads.containsKey(key)
                    && level.getChunkSource().getChunkNow(pos.x, pos.z) == null
                    && !level.areEntitiesLoaded(key);
            if (!idle) {
                nonIdle.add(pos);
                for (var ticket : ticketsAt(level, key)) {
                    ticketTypes.add(ticket.getType().toString());
                }
            }
        }
        StringBuilder sb = new StringBuilder("【诊断·").append(phase).append("】");
        if (nonIdle.isEmpty()) {
            sb.append("此刻源区块已全部恢复卸载，复载为瞬时现象，未留下票据/持有者线索。");
            return sb.toString();
        }
        sb.append("源区块 ").append(nonIdle.size()).append('/').append(chunks.size()).append(" 未保持卸载");
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

    static SourceChunk inspectSource(ServerLevel source, SourceRecords records) {
        CompoundTag chunk = records.chunk().orElseThrow(() ->
                new IllegalStateException("源区块记录缺失：" + records.pos()));
        validateChunkTag(source, records.pos(), chunk);
        inspectLegacyEntities(records.pos(), chunk);
        ListTag entities = inspectEntityRecord(records.pos(), records.entities());
        Optional<CompoundTag> poi = inspectPoiRecord(records.pos(), records.poi());
        inspectBlockPalette(source, records.pos(), chunk);
        return new SourceChunk(records.pos(), chunk.copy(), CanonicalNbtHasher.sha256(chunk),
                chunk.getInt("DataVersion"), entities.copy(), poi);
    }

    /**
     * 副本方块实体初始化（防评估欺诈骗局）：把 {@code create:blaze_burner} 的
     * {@code fuelLevel}(activeFuel) 与 {@code burnTimeRemaining}(剩余燃烧 tick) 清零，
     * 让评估期间燃烧室只能靠真实的燃料物品流维持（三扫描/IO 流量记录燃料消耗）。
     *
     * <p><b>只清普通/超热燃烧室</b>（用户决策）：{@code isCreative=true} 的创造燃烧室保留
     * （创造模式下外部输入的物品本来也是"免费"的，且其 NBT 不写 fuelLevel/burnTimeRemaining）。
     * blockstate 的 {@code blaze}(HeatLevel) 无需处理——BE tick 燃烧耗尽后自带
     * {@code updateBlockState()} 自愈（Create 源码 BlazeBurnerBlockEntity.tick）。</p>
     */
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
                if (Config.SUSPICIOUS_BLOCKS.get().contains(blockId.toString())) {
                    throw new UnsupportedContentException("message.createcmpor.evaluation.block_blacklisted");
                }
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