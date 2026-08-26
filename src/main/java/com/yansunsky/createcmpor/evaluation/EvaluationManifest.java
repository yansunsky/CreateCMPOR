package com.yansunsky.createcmpor.evaluation;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Phase 4 的精确区块复制清单和逐区块进度。 */
public final class EvaluationManifest {
    public static final int SCHEMA_VERSION = 1;

    public enum SourceStatus {
        MISSING,
        READ,
        HASHED
    }

    public enum StagingStatus {
        NONE,
        WRITTEN,
        VERIFIED
    }

    public enum PublishStatus {
        NONE,
        WRITTEN,
        VERIFIED,
        CLEANED
    }

    private final UUID sessionId;
    private final String roomCode;
    private final ResourceKey<Level> sourceDimension;
    private final ResourceKey<Level> targetDimension;
    private final String sourceLevelId;
    private final String targetLevelId;
    private final long createdGameTime;
    private final long sourceSnapshotTick;
    private final String stagingDirectory;
    private final List<ChunkRecord> chunks;
    private boolean targetWriteIntent;
    private boolean ticketsAdded;
    private boolean targetReady;
    private int entityCount;
    private final List<RailwayRecord> railwayRecords = new ArrayList<>();

    private EvaluationManifest(UUID sessionId, String roomCode,
                               ResourceKey<Level> sourceDimension, ResourceKey<Level> targetDimension,
                               String sourceLevelId, String targetLevelId, long createdGameTime,
                               long sourceSnapshotTick, String stagingDirectory, List<ChunkRecord> chunks,
                               boolean targetWriteIntent, boolean ticketsAdded, boolean targetReady) {
        this.sessionId = sessionId;
        this.roomCode = roomCode;
        this.sourceDimension = sourceDimension;
        this.targetDimension = targetDimension;
        this.sourceLevelId = sourceLevelId;
        this.targetLevelId = targetLevelId;
        this.createdGameTime = createdGameTime;
        this.sourceSnapshotTick = sourceSnapshotTick;
        this.stagingDirectory = stagingDirectory;
        this.chunks = chunks;
        this.targetWriteIntent = targetWriteIntent;
        this.ticketsAdded = ticketsAdded;
        this.targetReady = targetReady;
    }

    public static EvaluationManifest create(UUID sessionId, String roomCode,
                                             ResourceKey<Level> sourceDimension,
                                             ResourceKey<Level> targetDimension,
                                             long gameTime, List<ChunkPos> positions) {
        List<ChunkPos> sorted = positions.stream()
                .distinct()
                .sorted(Comparator.comparingInt((ChunkPos pos) -> pos.x).thenComparingInt(pos -> pos.z))
                .toList();
        List<ChunkRecord> records = new ArrayList<>();
        for (ChunkPos pos : sorted) {
            records.add(new ChunkRecord(pos));
        }
        return new EvaluationManifest(sessionId, roomCode, sourceDimension, targetDimension,
                sourceDimension.location().toString(), targetDimension.location().toString(), gameTime,
                gameTime, "data/createcmpor/evaluation-staging/" + sessionId, records,
                false, false, false);
    }

    public UUID sessionId() {
        return sessionId;
    }

    public String roomCode() {
        return roomCode;
    }

    public ResourceKey<Level> sourceDimension() {
        return sourceDimension;
    }

    public ResourceKey<Level> targetDimension() {
        return targetDimension;
    }

    public String sourceLevelId() {
        return sourceLevelId;
    }

    public String targetLevelId() {
        return targetLevelId;
    }

    public long createdGameTime() {
        return createdGameTime;
    }

    public long sourceSnapshotTick() {
        return sourceSnapshotTick;
    }

    public String stagingDirectory() {
        return stagingDirectory;
    }

    public List<ChunkRecord> chunks() {
        return chunks;
    }

    public boolean targetWriteIntent() {
        return targetWriteIntent;
    }

    public void setTargetWriteIntent(boolean targetWriteIntent) {
        this.targetWriteIntent = targetWriteIntent;
    }

    public boolean ticketsAdded() {
        return ticketsAdded;
    }

    public void setTicketsAdded(boolean ticketsAdded) {
        this.ticketsAdded = ticketsAdded;
    }

    public boolean targetReady() {
        return targetReady;
    }

    public void setTargetReady(boolean targetReady) {
        this.targetReady = targetReady;
    }

    /**
     * 多分支评估：清理副本完成后重置本清单的克隆/发布状态，供下一分支重新克隆。
     * （房间保持冻结，源 chunk 数据不变，直接从源重新走 STAGING_SOURCE → … → PUBLISHED。）
     */
    public void resetForBranch() {
        this.targetWriteIntent = false;
        this.ticketsAdded = false;
        this.targetReady = false;
        for (ChunkRecord chunk : chunks) {
            chunk.resetForBranch();
        }
    }

    public int entityCount() {
        return entityCount;
    }

    public void setEntityCount(int entityCount) {
        this.entityCount = entityCount;
    }

    public List<RailwayRecord> railwayRecords() {
        return railwayRecords;
    }

    public ChunkRecord chunk(ChunkPos position) {
        return chunks.stream().filter(chunk -> chunk.chunkPos.equals(position)).findFirst().orElse(null);
    }

    public void validate() {
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("复制清单没有区块");
        }
        Set<Long> positions = new HashSet<>();
        for (ChunkRecord chunk : chunks) {
            if (!positions.add(chunk.chunkPos.toLong())) {
                throw new IllegalArgumentException("复制清单包含重复区块");
            }
        }
        if (!stagingDirectory.equals("data/createcmpor/evaluation-staging/" + sessionId)) {
            throw new IllegalArgumentException("复制暂存目录不在受控路径");
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("schema_version", SCHEMA_VERSION);
        tag.putUUID("session_id", sessionId);
        tag.putString("room_code", roomCode);
        tag.putString("source_dimension", sourceDimension.location().toString());
        tag.putString("target_dimension", targetDimension.location().toString());
        tag.putString("source_level_id", sourceLevelId);
        tag.putString("target_level_id", targetLevelId);
        tag.putLong("created_game_time", createdGameTime);
        tag.putLong("source_snapshot_tick", sourceSnapshotTick);
        tag.putString("staging_directory", stagingDirectory);
        tag.putBoolean("target_write_intent", targetWriteIntent);
        tag.putBoolean("tickets_added", ticketsAdded);
        tag.putBoolean("target_ready", targetReady);
        tag.putInt("entity_count", entityCount);

        ListTag railwayTags = new ListTag();
        for (RailwayRecord record : railwayRecords) {
            railwayTags.add(record.save());
        }
        tag.put("railway_records", railwayTags);

        ListTag chunkTags = new ListTag();
        for (ChunkRecord chunk : chunks) {
            chunkTags.add(chunk.save());
        }
        tag.put("chunks", chunkTags);
        return tag;
    }

    public static EvaluationManifest load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("schema_version") != SCHEMA_VERSION || !tag.hasUUID("session_id")) {
            throw new IllegalArgumentException("不支持的复制清单版本");
        }
        ResourceKey<Level> sourceDimension = dimension(tag.getString("source_dimension"));
        ResourceKey<Level> targetDimension = dimension(tag.getString("target_dimension"));
        ListTag chunkTags = tag.getList("chunks", Tag.TAG_COMPOUND);
        List<ChunkRecord> chunks = new ArrayList<>();
        for (int index = 0; index < chunkTags.size(); index++) {
            chunks.add(ChunkRecord.load(chunkTags.getCompound(index)));
        }
        EvaluationManifest manifest = new EvaluationManifest(
                tag.getUUID("session_id"), tag.getString("room_code"), sourceDimension, targetDimension,
                tag.getString("source_level_id"), tag.getString("target_level_id"),
                tag.getLong("created_game_time"), tag.getLong("source_snapshot_tick"),
                tag.getString("staging_directory"), chunks,
                tag.getBoolean("target_write_intent"), tag.getBoolean("tickets_added"),
                tag.getBoolean("target_ready"));
        manifest.entityCount = tag.getInt("entity_count");
        ListTag railwayTags = tag.getList("railway_records", Tag.TAG_COMPOUND);
        for (int index = 0; index < railwayTags.size(); index++) {
            manifest.railwayRecords.add(RailwayRecord.load(railwayTags.getCompound(index)));
        }
        manifest.validate();
        return manifest;
    }

    private static ResourceKey<Level> dimension(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("复制清单缺少维度");
        }
        return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, ResourceLocation.parse(id));
    }

    /** Railway 复制事务的记录：发布前分配新 train UUID，建图后补写 graph UUID。 */
    public static final class RailwayRecord {
        private final UUID newTrainId;
        private final UUID sourceTrainId;
        private final List<UUID> carriageUuids;
        private UUID newGraphId;
        private boolean graphBuilt;

        public RailwayRecord(UUID newTrainId, UUID sourceTrainId, List<UUID> carriageUuids) {
            this.newTrainId = newTrainId;
            this.sourceTrainId = sourceTrainId;
            this.carriageUuids = List.copyOf(carriageUuids);
        }

        public UUID newTrainId() {
            return newTrainId;
        }

        public UUID sourceTrainId() {
            return sourceTrainId;
        }

        public List<UUID> carriageUuids() {
            return carriageUuids;
        }

        public UUID newGraphId() {
            return newGraphId;
        }

        public boolean graphBuilt() {
            return graphBuilt;
        }

        public void markGraphBuilt(UUID newGraphId) {
            this.newGraphId = newGraphId;
            this.graphBuilt = true;
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("new_train", newTrainId);
            tag.putUUID("source_train", sourceTrainId);
            ListTag carriages = new ListTag();
            for (UUID uuid : carriageUuids) {
                carriages.add(NbtUtils.createUUID(uuid));
            }
            tag.put("carriages", carriages);
            if (newGraphId != null) {
                tag.putUUID("new_graph", newGraphId);
            }
            tag.putBoolean("graph_built", graphBuilt);
            return tag;
        }

        private static RailwayRecord load(CompoundTag tag) {
            List<UUID> carriages = new ArrayList<>();
            ListTag carriageTags = tag.getList("carriages", Tag.TAG_INT_ARRAY);
            for (int index = 0; index < carriageTags.size(); index++) {
                carriages.add(NbtUtils.loadUUID(carriageTags.get(index)));
            }
            RailwayRecord record = new RailwayRecord(
                    tag.getUUID("new_train"), tag.getUUID("source_train"), carriages);
            if (tag.hasUUID("new_graph")) {
                record.markGraphBuilt(tag.getUUID("new_graph"));
            }
            return record;
        }
    }

    public static final class ChunkRecord {
        private final ChunkPos chunkPos;
        private SourceStatus sourceStatus = SourceStatus.MISSING;
        private StagingStatus stagingStatus = StagingStatus.NONE;
        private PublishStatus publishStatus = PublishStatus.NONE;
        private String sourceHash = "";
        private String stagingHash = "";
        private String targetHash = "";
        private int dataVersion = -1;
        private boolean hadSourceRecord;
        private boolean hadTargetRecord;
        private String error = "";

        private ChunkRecord(ChunkPos chunkPos) {
            this.chunkPos = chunkPos;
        }

        /** 多分支评估：下一分支重新克隆前重置本区块的读取/暂存/发布状态。 */
        private void resetForBranch() {
            this.sourceStatus = SourceStatus.MISSING;
            this.stagingStatus = StagingStatus.NONE;
            this.publishStatus = PublishStatus.NONE;
            this.sourceHash = "";
            this.stagingHash = "";
            this.targetHash = "";
            this.dataVersion = -1;
            this.hadSourceRecord = false;
            this.hadTargetRecord = false;
            this.error = "";
        }

        public ChunkPos chunkPos() {
            return chunkPos;
        }

        public SourceStatus sourceStatus() {
            return sourceStatus;
        }

        public void setSourceStatus(SourceStatus sourceStatus) {
            this.sourceStatus = sourceStatus;
        }

        public StagingStatus stagingStatus() {
            return stagingStatus;
        }

        public void setStagingStatus(StagingStatus stagingStatus) {
            this.stagingStatus = stagingStatus;
        }

        public PublishStatus publishStatus() {
            return publishStatus;
        }

        public void setPublishStatus(PublishStatus publishStatus) {
            this.publishStatus = publishStatus;
        }

        public String sourceHash() {
            return sourceHash;
        }

        public void setSourceHash(String sourceHash) {
            this.sourceHash = sourceHash;
        }

        public String stagingHash() {
            return stagingHash;
        }

        public void setStagingHash(String stagingHash) {
            this.stagingHash = stagingHash;
        }

        public String targetHash() {
            return targetHash;
        }

        public void setTargetHash(String targetHash) {
            this.targetHash = targetHash;
        }

        public int dataVersion() {
            return dataVersion;
        }

        public void setDataVersion(int dataVersion) {
            this.dataVersion = dataVersion;
        }

        public boolean hadSourceRecord() {
            return hadSourceRecord;
        }

        public void setHadSourceRecord(boolean hadSourceRecord) {
            this.hadSourceRecord = hadSourceRecord;
        }

        public boolean hadTargetRecord() {
            return hadTargetRecord;
        }

        public void setHadTargetRecord(boolean hadTargetRecord) {
            this.hadTargetRecord = hadTargetRecord;
        }

        public String error() {
            return error;
        }

        public void setError(String error) {
            this.error = error == null ? "" : error;
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("chunk_x", chunkPos.x);
            tag.putInt("chunk_z", chunkPos.z);
            tag.putString("source_status", sourceStatus.name());
            tag.putString("staging_status", stagingStatus.name());
            tag.putString("publish_status", publishStatus.name());
            tag.putString("source_hash", sourceHash);
            tag.putString("staging_hash", stagingHash);
            tag.putString("target_hash", targetHash);
            tag.putInt("data_version", dataVersion);
            tag.putBoolean("had_source_record", hadSourceRecord);
            tag.putBoolean("had_target_record", hadTargetRecord);
            if (!error.isBlank()) {
                tag.putString("error", error);
            }
            return tag;
        }

        private static ChunkRecord load(CompoundTag tag) {
            ChunkRecord record = new ChunkRecord(new ChunkPos(tag.getInt("chunk_x"), tag.getInt("chunk_z")));
            record.sourceStatus = enumValue(SourceStatus.class, tag.getString("source_status"), SourceStatus.MISSING);
            record.stagingStatus = enumValue(StagingStatus.class, tag.getString("staging_status"), StagingStatus.NONE);
            record.publishStatus = enumValue(PublishStatus.class, tag.getString("publish_status"), PublishStatus.NONE);
            record.sourceHash = tag.getString("source_hash");
            record.stagingHash = tag.getString("staging_hash");
            record.targetHash = tag.getString("target_hash");
            record.dataVersion = tag.getInt("data_version");
            record.hadSourceRecord = tag.getBoolean("had_source_record");
            record.hadTargetRecord = tag.getBoolean("had_target_record");
            record.error = tag.getString("error");
            return record;
        }

        private static <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) {
            try {
                return Enum.valueOf(type, value);
            } catch (IllegalArgumentException exception) {
                return fallback;
            }
        }
    }
}
