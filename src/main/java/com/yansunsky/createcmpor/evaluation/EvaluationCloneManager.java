package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.room.RoomInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** 编排 Phase 4 的冷拷贝、发布、ticket 和 cleanup-first 恢复。 */
public final class EvaluationCloneManager {
    public static final EvaluationCloneManager INSTANCE = new EvaluationCloneManager();

    private final Map<UUID, RuntimeState> runtimes = new HashMap<>();
    @Nullable
    private UUID publishingSession;
    private int maxConcurrentEvaluations = Config.MAX_CONCURRENT_EVALUATIONS.get();

    private EvaluationCloneManager() {
    }

    void onServerStarted(MinecraftServer server, EvaluationSavedData data) {
        closeAllRuntimes();
        publishingSession = null;
        maxConcurrentEvaluations = Config.MAX_CONCURRENT_EVALUATIONS.get();
        EvaluationCriticalJournal.Snapshot snapshot = EvaluationCriticalJournal.read(server);
        data.reconcileCriticalSessions(snapshot.sessions(), snapshot.exists());
        for (EvaluationSession session : data.criticalSessions()) {
            session.setRollbackMessageKey("message.createcmpor.evaluation.recovered_after_restart");
            session.setState(EvaluationSession.State.CLEANING);
            data.changed();
        }
        syncCriticalState(server, data);
    }

    void tick(MinecraftServer server, EvaluationSavedData data, EvaluationSession session) {
        try {
            switch (session.state()) {
                case FROZEN -> tickFrozen(server, data, session);
                case QUEUED -> tickQueued(server, data, session);
                case STAGING_SOURCE -> tickStagingSource(server, data, session);
                case STAGING_WRITTEN -> tickStagingWritten(server, data, session);
                case STAGING_VERIFIED -> tickStagingVerified(server, data, session);
                case RAILWAY_TRANSFER -> tickRailwayTransfer(server, data, session);
                case PUBLISHING -> tickPublishing(server, data, session);
                case PUBLISHED -> tickPublished(server, data, session);
                case EVALUATING -> EvaluationScheduler.tick(server, data, session);
                case EVALUATED -> {
                    // Phase 7 固化接管前保持等待（正常不会停留此状态）
                }
                case SOLIDIFYING -> tickSolidifying(server, data, session);
                case CLEANING -> tickCleaning(server, data, session);
                default -> throw new IllegalStateException("非 Phase 4 状态进入克隆管理器：" + session.state());
            }
        } catch (EvaluationStorageBridge.UnsupportedContentException exception) {
            requestCleanup(server, data, session, exception.messageKey());
        } catch (TargetConflictException exception) {
            CreateCMPOR.LOGGER.warn("评估会话 {} 目标冲突：{}", session.id(), exception.getMessage());
            // 目标冲突取消：标记强制清理目标区域（eval_world 无法进入，残留必是评估残留）
            session.markCleanTargetOnCancel();
            requestCleanup(server, data, session, "message.createcmpor.evaluation.target_conflict");
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof EvaluationStorageBridge.UnsupportedContentException unsupported) {
                requestCleanup(server, data, session, unsupported.messageKey());
                return;
            }
            if (cause instanceof TargetConflictException conflict) {
                CreateCMPOR.LOGGER.warn("评估会话 {} 目标冲突：{}", session.id(), conflict.getMessage());
                // 目标冲突取消：标记强制清理目标区域（eval_world 无法进入，残留必是评估残留）
                session.markCleanTargetOnCancel();
                requestCleanup(server, data, session, "message.createcmpor.evaluation.target_conflict");
                return;
            }
            CreateCMPOR.LOGGER.error("评估会话 {} 的区块 IO 失败", session.id(), cause);
            requestCleanup(server, data, session, "message.createcmpor.evaluation.clone_failed");
        } catch (RuntimeException exception) {
            CreateCMPOR.LOGGER.error("评估会话 {} 的 Phase 4 状态机失败", session.id(), exception);
            requestCleanup(server, data, session, "message.createcmpor.evaluation.clone_failed");
        }
    }

    void requestCleanup(MinecraftServer server, EvaluationSavedData data, EvaluationSession session,
                        String messageKey) {
        session.setRollbackMessageKey(messageKey);
        if (session.id().equals(publishingSession)) {
            publishingSession = null;
        }
        if (!session.hasPhase4Manifest()) {
            session.setState(EvaluationSession.State.ROLLING_BACK);
            data.changed();
            return;
        }
        closeRuntime(session.id());
        session.setState(EvaluationSession.State.CLEANING);
        data.changed();
        syncCriticalState(server, data);
    }

    void afterSessionRemoved(MinecraftServer server, EvaluationSavedData data) {
        syncCriticalState(server, data);
    }

    private void tickFrozen(MinecraftServer server, EvaluationSavedData data, EvaluationSession session) {
        RoomInstance room = CompactMachines.room(server, session.roomCode()).orElse(null);
        ServerLevel target = server.getLevel(CreateCMPOR.EVAL_WORLD);
        if (room == null || target == null) {
            requestCleanup(server, data, session, "message.createcmpor.evaluation.dimension_missing");
            return;
        }
        List<ChunkPos> chunks = CompactMachines.roomChunks(session.roomCode()).stream().toList();
        if (chunks.isEmpty() || !EvaluationStorageBridge.areChunksIdle(room.level(), chunks)) {
            requestCleanup(server, data, session, "message.createcmpor.evaluation.source_not_idle");
            return;
        }
        EvaluationManifest manifest = EvaluationManifest.create(session.id(), session.roomCode(),
                room.levelKey(), target.dimension(), room.level().getGameTime(), chunks);
        manifest.validate();
        session.setManifest(manifest);
        session.setState(EvaluationSession.State.QUEUED);
        data.changed();
        syncCriticalState(server, data);
        EvaluationManager.INSTANCE.markEvaluatorPhase4(server, session);
        notifyOwner(server, session, Component.translatable("message.createcmpor.evaluation.queued"));
    }

    public int maxConcurrentEvaluations() {
        return maxConcurrentEvaluations;
    }

    public boolean setMaxConcurrentEvaluations(int value) {
        if (value < 1 || value > 16) {
            return false;
        }
        maxConcurrentEvaluations = value;
        CreateCMPOR.LOGGER.info("并发评估上限已改为 {}", value);
        return true;
    }

    private void tickQueued(MinecraftServer server, EvaluationSavedData data, EvaluationSession session) {
        if (!isQueueHead(data, session) || activeCount(data) >= maxConcurrentEvaluations) {
            return;
        }
        session.setState(EvaluationSession.State.STAGING_SOURCE);
        data.changed();
        syncCriticalState(server, data);
        notifyOwner(server, session, Component.translatable("message.createcmpor.evaluation.cloning"));
        CreateCMPOR.LOGGER.info("评估会话 {} 开始克隆房间 {}，共 {} 个区块（活动克隆 {} 个）",
                session.id(), session.roomCode(), session.manifest().chunks().size(), activeCount(data));
    }

    private void tickStagingSource(MinecraftServer server, EvaluationSavedData data,
                                   EvaluationSession session) {
        EvaluationManifest manifest = requireManifest(session);
        RoomInstance room = requireRoom(server, session);
        if (!sameRoomChunks(session, manifest)
                || !EvaluationStorageBridge.areChunksIdle(room.level(), manifest.chunks().stream()
                .map(EvaluationManifest.ChunkRecord::chunkPos).toList())) {
            requestCleanup(server, data, session, "message.createcmpor.evaluation.source_not_idle");
            return;
        }

        RuntimeState runtime = runtime(server, session);
        EvaluationManifest.ChunkRecord pending = manifest.chunks().stream()
                .filter(chunk -> chunk.stagingStatus() == EvaluationManifest.StagingStatus.NONE)
                .findFirst().orElse(null);
        if (pending == null) {
            // 全部 chunk 源读取完成：全房间实体检查（跨 chunk 引用需要全局 UUID 集合）
            EvaluationEntityInspector.AllChunksResult entities =
                    EvaluationEntityInspector.inspectAll(runtime.sourceEntities);
            runtime.rewrittenEntities = entities.rewrittenByChunk();
            manifest.setEntityCount(entities.totalEntities());
            data.changed();
            runtime.staging.flushWorker();
            session.setState(EvaluationSession.State.STAGING_WRITTEN);
            data.changed();
            syncCriticalState(server, data);
            runtime.clearOperation();
            if (entities.hasEntities()) {
                CreateCMPOR.LOGGER.info("评估会话 {} 共检查 {} 个实体（车厢 {} 个）",
                        session.id(), entities.totalEntities(), entities.carriageUuids().size());
            }
            return;
        }
        if (runtime.operation == Operation.NONE) {
            runtime.chunk = pending.chunkPos();
            runtime.sourceFuture = EvaluationStorageBridge.readSourceRecords(room.level(), pending.chunkPos())
                    .thenApply(records -> EvaluationStorageBridge.inspectSource(room.level(), records))
                    .thenCompose(sourceChunk -> runtime.staging.write(sourceChunk.pos(), sourceChunk.tag().copy())
                            .thenApply(ignored -> sourceChunk));
            runtime.operation = Operation.STAGING_WRITE;
            return;
        }
        if (!runtime.sourceFuture.isDone()) {
            return;
        }
        EvaluationStorageBridge.SourceChunk sourceChunk = runtime.sourceFuture.join();
        EvaluationManifest.ChunkRecord record = requireChunk(manifest, sourceChunk.pos());
        record.setHadSourceRecord(true);
        record.setDataVersion(sourceChunk.dataVersion());
        record.setSourceHash(sourceChunk.hash());
        record.setSourceStatus(EvaluationManifest.SourceStatus.HASHED);
        record.setStagingStatus(EvaluationManifest.StagingStatus.WRITTEN);
        runtime.sourceEntities.put(sourceChunk.pos(), sourceChunk.entities().copy());
        sourceChunk.poi().ifPresent(poi -> runtime.sourcePoi.put(sourceChunk.pos(), poi.copy()));
        runtime.sourceTags.put(sourceChunk.pos(), sourceChunk.tag().copy());
        data.changed();
        notifyCloneProgress(server, session, manifest);
        runtime.clearOperation();
    }

    private void tickStagingWritten(MinecraftServer server, EvaluationSavedData data,
                                    EvaluationSession session) {
        EvaluationManifest manifest = requireManifest(session);
        RuntimeState runtime = runtime(server, session);
        EvaluationManifest.ChunkRecord pending = manifest.chunks().stream()
                .filter(chunk -> chunk.stagingStatus() == EvaluationManifest.StagingStatus.WRITTEN)
                .findFirst().orElse(null);
        if (pending == null) {
            session.setState(EvaluationSession.State.STAGING_VERIFIED);
            data.changed();
            syncCriticalState(server, data);
            runtime.clearOperation();
            return;
        }
        if (runtime.operation == Operation.NONE) {
            runtime.chunk = pending.chunkPos();
            runtime.tagFuture = runtime.staging.read(pending.chunkPos());
            runtime.operation = Operation.STAGING_VERIFY;
            return;
        }
        if (!runtime.tagFuture.isDone()) {
            return;
        }
        Optional<CompoundTag> stored = runtime.tagFuture.join();
        CompoundTag tag = stored.orElseThrow(() ->
                new IllegalStateException("staging 区块缺失：" + runtime.chunk));
        validateStoredChunk(runtime.chunk, tag);
        String hash = CanonicalNbtHasher.sha256(tag);
        EvaluationManifest.ChunkRecord record = requireChunk(manifest, runtime.chunk);
        if (!hash.equals(record.sourceHash())) {
            // 探针：diff staging 读出 NBT 与源的顶层 key 差异（定位具体字段）
            CompoundTag sourceTag = runtime.sourceTags.get(runtime.chunk);
            if (sourceTag != null) {
                java.util.Set<String> sourceKeys = sourceTag.getAllKeys();
                java.util.Set<String> stagingKeys = tag.getAllKeys();
                java.util.Set<String> onlySource = new java.util.TreeSet<>(sourceKeys);
                onlySource.removeAll(stagingKeys);
                java.util.Set<String> onlyStaging = new java.util.TreeSet<>(stagingKeys);
                onlyStaging.removeAll(sourceKeys);
                java.util.Set<String> common = new java.util.TreeSet<>(sourceKeys);
                common.retainAll(stagingKeys);
                java.util.List<String> changedCommon = new java.util.ArrayList<>();
                for (String key : common) {
                    if (!tag.get(key).equals(sourceTag.get(key))) {
                        changedCommon.add(key);
                    }
                }
                CreateCMPOR.LOGGER.error("[探针] staging 摘要不一致 @{}: 仅源有={} 仅staging有={} 共同但不同={}",
                        runtime.chunk, onlySource, onlyStaging, changedCommon);
            }
            // 探针：打印 staging 读出 NBT 与源 hash 的差异细节（排查摘要不一致）
            CreateCMPOR.LOGGER.error("[探针] staging 摘要不一致 @{}: staging={} source={} | stagingDV={} sourceDV={} | stagingStatus={}",
                    runtime.chunk,
                    hash.length() > 16 ? hash.substring(0, 16) : hash,
                    record.sourceHash().length() > 16 ? record.sourceHash().substring(0, 16) : record.sourceHash(),
                    tag.getInt("DataVersion"), record.dataVersion(),
                    tag.getString("Status"));
            throw new IllegalStateException("staging 摘要不一致：" + runtime.chunk);
        }
        record.setStagingHash(hash);
        record.setStagingStatus(EvaluationManifest.StagingStatus.VERIFIED);
        data.changed();
        notifyCloneProgress(server, session, manifest);
        runtime.clearOperation();
    }

    private void tickStagingVerified(MinecraftServer server, EvaluationSavedData data,
                                     EvaluationSession session) {
        EvaluationManifest manifest = requireManifest(session);
        ServerLevel target = requireTarget(server, manifest);
        RuntimeState runtime = runtime(server, session);
        if (publishingSession == null) {
            publishingSession = session.id();
        } else if (!publishingSession.equals(session.id())) {
            return;
        }
        List<ChunkPos> targets = chunkPositions(manifest);
        if (runtime.footprintIndex >= targets.size()) {
            manifest.setTargetWriteIntent(true);
            boolean hasRailway = runtime.rewrittenEntities.values().stream()
                    .flatMap(List::stream)
                    .anyMatch(tag -> EvaluationEntityInspector.CARRIAGE_CONTRAPTION_ID
                            .equals(tag.getString("id")));
            session.setState(hasRailway
                    ? EvaluationSession.State.RAILWAY_TRANSFER
                    : EvaluationSession.State.PUBLISHING);
            data.changed();
            syncCriticalState(server, data);
            runtime.clearOperation();
            CreateCMPOR.LOGGER.info("评估会话 {} 冲突检查完成，进入{}", session.id(), hasRailway ? "铁路事务" : "发布");
            return;
        }
        ChunkPos pos = targets.get(runtime.footprintIndex);
        if (!EvaluationStorageBridge.isChunkIdle(target, pos)) {
            throw new TargetConflictException("目标 chunk 已加载或仍在卸载：" + pos);
        }
        if (runtime.operation == Operation.NONE) {
            runtime.chunk = pos;
            runtime.storedFuture = EvaluationStorageBridge.readStoredRecords(target, pos);
            runtime.operation = Operation.TARGET_CONFLICT_CHECK;
            return;
        }
        if (!runtime.storedFuture.isDone()) {
            return;
        }
        EvaluationStorageBridge.StoredRecords records = runtime.storedFuture.join();
        EvaluationManifest.ChunkRecord roomChunk = manifest.chunk(pos);
        if (roomChunk != null) {
            roomChunk.setHadTargetRecord(!records.allAbsent());
        }
        if (!records.allAbsent()) {
            throw new TargetConflictException("目标存储已存在未知记录：" + pos);
        }
        runtime.footprintIndex++;
        runtime.clearOperation();
    }

    private void tickRailwayTransfer(MinecraftServer server, EvaluationSavedData data,
                                     EvaluationSession session) {
        EvaluationManifest manifest = requireManifest(session);
        RuntimeState runtime = runtime(server, session);
        List<CompoundTag> allEntities = new ArrayList<>();
        runtime.rewrittenEntities.values().forEach(allEntities::addAll);
        EvaluationRailwayTransfer.prepare(server, session, manifest, allEntities);
        session.setState(EvaluationSession.State.PUBLISHING);
        data.changed();
        syncCriticalState(server, data);
        CreateCMPOR.LOGGER.info("评估会话 {} 铁路事务完成，共 {} 列火车，开始发布 {} 个区块",
                session.id(), manifest.railwayRecords().size(), manifest.chunks().size());
    }

    private void tickSolidifying(MinecraftServer server, EvaluationSavedData data,
                                 EvaluationSession session) {
        EvaluationVerdict.Result result = session.evaluationResult();
        if (result == null || result.rejected()) {
            requestCleanup(server, data, session, "message.createcmpor.evaluation.clone_failed");
            return;
        }
        ServerLevel machineLevel = server.getLevel(session.machinePos().dimension());
        if (machineLevel == null) {
            throw new IllegalStateException("机器维度未加载：" + session.machinePos().dimension().location());
        }
        BlockPos pos = session.machinePos().pos();
        machineLevel.removeBlockEntity(pos);
        machineLevel.setBlockAndUpdate(pos, com.yansunsky.createcmpor.init.ModBlocks.FACTORY.get().defaultBlockState());
        if (!(machineLevel.getBlockEntity(pos) instanceof com.yansunsky.createcmpor.block.FactoryBlockEntity factory)) {
            throw new IllegalStateException("工厂方块实体未创建");
        }
        factory.setRoomCode(session.roomCode());
        if (EvaluationVerdict.VERDICT_REPLAY.equals(result.verdict())) {
            factory.installPatterns(result.replayIn(), result.replayOut(),
                    result.energyReplayIn(), result.energyReplayOut());
        } else {
            factory.installRates(result.inputRates(), result.outputRates(),
                    result.inputEnergyRate(), result.outputEnergyRate());
        }
        factory.installRestoreData(session.originalState(), session.originalBlockEntityNbt(),
                result.stressProfile());
        // 登记 roomCode → 工厂位置（玩家进入压缩空间时自动还原防复制用）
        FactoryIndexSavedData.get(server).registerFactory(session.roomCode(),
                GlobalPos.of(machineLevel.dimension(), pos));
        session.setFactoryInstalled(true);
        session.setState(EvaluationSession.State.CLEANING);
        data.changed();
        syncCriticalState(server, data);
        notifyOwner(server, session, Component.translatable("message.createcmpor.factory.installed"));
        CreateCMPOR.LOGGER.info("评估会话 {} 工厂已固化（{} 模式），开始清理副本",
                session.id(), result.verdict());
    }

    private void tickPublishing(MinecraftServer server, EvaluationSavedData data,
                                EvaluationSession session) {
        EvaluationManifest manifest = requireManifest(session);
        ServerLevel target = requireTarget(server, manifest);
        RuntimeState runtime = runtime(server, session);

        EvaluationManifest.ChunkRecord unwritten = manifest.chunks().stream()
                .filter(chunk -> chunk.publishStatus() == EvaluationManifest.PublishStatus.NONE)
                .findFirst().orElse(null);
        if (unwritten != null) {
            tickTargetWrite(server, data, session, target, runtime, unwritten);
            return;
        }
        if (!runtime.targetFlushed) {
            EvaluationStorageBridge.flushAll(target);
            runtime.targetFlushed = true;
            return;
        }
        EvaluationManifest.ChunkRecord unverified = manifest.chunks().stream()
                .filter(chunk -> chunk.publishStatus() == EvaluationManifest.PublishStatus.WRITTEN)
                .findFirst().orElse(null);
        if (unverified != null) {
            tickTargetVerify(server, data, session, target, runtime, unverified);
            return;
        }

        session.setState(EvaluationSession.State.PUBLISHED);
        data.changed();
        syncCriticalState(server, data);
        EvaluationTicketManager.add(target, manifest);
        manifest.setTicketsAdded(true);
        syncCriticalState(server, data);
        // 主动在主线程把房间 chunk 加载到 FULL：依赖后台 worldgen 调度在空闲维度上很慢，
        // getChunk(requireChunk=true) 会就地驱动 worldgen 直到 chunk 可加载。
        loadChunksToFull(server, session, target, manifest);
        // 无玩家维度实体不 tick（ServerLevel emptyTime 门控），forced chunk 模拟玩家加载。
        EvaluationTicketManager.setChunksForced(target, manifest, true);
        // 铁路：加载后自动建图 + train 复制注册（车厢实体首次 tick 前完成）
        if (!manifest.railwayRecords().isEmpty()) {
            EvaluationRailwayTransfer.setup(target, session, manifest);
            data.changed();
            syncCriticalState(server, data);
            CreateCMPOR.LOGGER.info("评估会话 {} 铁路复制完成：{} 列火车已注册到 eval_world",
                    session.id(), manifest.railwayRecords().size());
        }
        runtime.clearOperation();
        publishingSession = null;
        notifyOwner(server, session, Component.translatable("message.createcmpor.evaluation.published"));
    }

    private static void loadChunksToFull(MinecraftServer server, EvaluationSession session,
                                         ServerLevel target, EvaluationManifest manifest) {
        for (EvaluationManifest.ChunkRecord record : manifest.chunks()) {
            var access = target.getChunkSource().getChunk(
                    record.chunkPos().x, record.chunkPos().z, ChunkStatus.FULL, true);
            if (access == null) {
                throw new IllegalStateException("目标 chunk 无法加载到 FULL：" + record.chunkPos());
            }
        }
        CreateCMPOR.LOGGER.info("评估会话 {} 的 {} 个房间 chunk 已加载到 FULL，等待 BLOCK_TICKING",
                session.id(), manifest.chunks().size());
    }

    private void tickTargetWrite(MinecraftServer server, EvaluationSavedData data,
                                 EvaluationSession session, ServerLevel target, RuntimeState runtime,
                                 EvaluationManifest.ChunkRecord record) {
        if (runtime.operation == Operation.NONE) {
            runtime.chunk = record.chunkPos();
            runtime.tagFuture = runtime.staging.read(record.chunkPos());
            runtime.operation = Operation.TARGET_READ_STAGING;
            return;
        }
        if (runtime.operation == Operation.TARGET_READ_STAGING) {
            if (!runtime.tagFuture.isDone()) {
                return;
            }
            CompoundTag tag = runtime.tagFuture.join().orElseThrow(() ->
                    new IllegalStateException("发布时 staging 区块缺失：" + runtime.chunk));
            validateStoredChunk(runtime.chunk, tag);
            if (!CanonicalNbtHasher.sha256(tag).equals(record.sourceHash())) {
                throw new IllegalStateException("发布前 staging 摘要变化：" + runtime.chunk);
            }
            runtime.writeFuture = EvaluationStorageBridge.chunkStorage(target).write(runtime.chunk, tag.copy());
            runtime.operation = Operation.TARGET_WRITE;
            return;
        }
        if (!runtime.writeFuture.isDone()) {
            return;
        }
        runtime.writeFuture.join();
        // 同窗口写入改写后的实体记录（目标 chunk 实体尚未加载，emptyChunks 无缓存）
        List<CompoundTag> entities = runtime.rewrittenEntities.get(runtime.chunk);
        if (entities != null && !entities.isEmpty()) {
            ListTag entityList = new ListTag();
            entities.forEach(entityList::add);
            EvaluationStorageBridge.writeEntities(target, runtime.chunk, entityList);
        }
        // 同窗口写入源 POI 记录（目标 chunk 未加载，POI 存储无缓存）
        CompoundTag poi = runtime.sourcePoi.get(runtime.chunk);
        if (poi != null) {
            EvaluationStorageBridge.writePoi(target, runtime.chunk, poi);
        }
        record.setPublishStatus(EvaluationManifest.PublishStatus.WRITTEN);
        data.changed();
        notifyCloneProgress(server, session, requireManifest(session));
        runtime.clearOperation();
    }

    private void tickTargetVerify(MinecraftServer server, EvaluationSavedData data,
                                  EvaluationSession session, ServerLevel target, RuntimeState runtime,
                                  EvaluationManifest.ChunkRecord record) {
        if (runtime.operation == Operation.NONE) {
            runtime.chunk = record.chunkPos();
            runtime.storedFuture = EvaluationStorageBridge.readStoredRecords(target, record.chunkPos());
            runtime.operation = Operation.TARGET_VERIFY;
            return;
        }
        if (!runtime.storedFuture.isDone()) {
            return;
        }
        EvaluationStorageBridge.StoredRecords records = runtime.storedFuture.join();
        CompoundTag tag = records.chunk().orElseThrow(() ->
                new IllegalStateException("目标区块回读缺失：" + runtime.chunk));
        // POI：本会话写入过则校验内容一致；未写入则必须为空（POI 复制参与评估后允许写入）。
        CompoundTag expectedPoi = runtime.sourcePoi.get(runtime.chunk);
        if (records.poi().isPresent()) {
            if (expectedPoi == null) {
                throw new IllegalStateException("目标区块意外生成 POI 记录：" + runtime.chunk);
            }
            if (!records.poi().get().getCompound("Sections")
                    .equals(expectedPoi.getCompound("Sections"))) {
                throw new IllegalStateException("目标区块 POI 记录与写入不一致：" + runtime.chunk);
            }
        } else if (expectedPoi != null) {
            throw new IllegalStateException("目标区块 POI 记录回读缺失：" + runtime.chunk);
        }
        // 实体：本会话写入过则校验内容一致；未写入则必须为空（Phase 5 起实体允许复制）。
        List<CompoundTag> expectedEntities = runtime.rewrittenEntities.get(runtime.chunk);
        if (records.entities().isPresent()) {
            if (expectedEntities == null || expectedEntities.isEmpty()) {
                throw new IllegalStateException("目标区块意外生成实体记录：" + runtime.chunk);
            }
            ListTag expectedList = new ListTag();
            expectedEntities.forEach(expectedList::add);
            if (!records.entities().get().getList("Entities", net.minecraft.nbt.Tag.TAG_COMPOUND)
                    .equals(expectedList)) {
                throw new IllegalStateException("目标区块实体记录与写入不一致：" + runtime.chunk);
            }
        } else if (expectedEntities != null && !expectedEntities.isEmpty()) {
            throw new IllegalStateException("目标区块实体记录回读缺失：" + runtime.chunk);
        }
        validateStoredChunk(runtime.chunk, tag);
        String hash = CanonicalNbtHasher.sha256(tag);
        if (!hash.equals(record.sourceHash())) {
            throw new IllegalStateException("目标区块摘要不一致：" + runtime.chunk);
        }
        record.setTargetHash(hash);
        record.setPublishStatus(EvaluationManifest.PublishStatus.VERIFIED);
        data.changed();
        notifyCloneProgress(server, session, requireManifest(session));
        runtime.clearOperation();
    }

    private void tickPublished(MinecraftServer server, EvaluationSavedData data,
                               EvaluationSession session) {
        EvaluationManifest manifest = requireManifest(session);
        if (manifest.targetReady()) {
            return;
        }
        ServerLevel target = requireTarget(server, manifest);
        if (EvaluationTicketManager.allReady(target, manifest)) {
            manifest.setTargetReady(true);
            data.changed();
            syncCriticalState(server, data);
            notifyOwner(server, session, Component.translatable("message.createcmpor.evaluation.copy_ready"));
            CreateCMPOR.LOGGER.info("评估会话 {} 的 eval_world 副本已达到 BLOCK_TICKING", session.id());
            // Phase 6 接管：进入评估
            session.setState(EvaluationSession.State.EVALUATING);
            data.changed();
            syncCriticalState(server, data);
            EvaluationScheduler.start(target, session, manifest);
            return;
        }
        session.tickState();
        data.changed();
        if (session.stateTicks() % 100 == 1) {
            EvaluationTicketManager.logPending(target, manifest);
        }
    }

    private void tickCleaning(MinecraftServer server, EvaluationSavedData data,
                              EvaluationSession session) {
        EvaluationManifest manifest = requireManifest(session);
        ServerLevel target = requireTarget(server, manifest);
        RuntimeState runtime = runtime(server, session);
        if (!runtime.ticketsRemoved) {
            EvaluationTicketManager.remove(target, manifest);
            EvaluationTicketManager.setChunksForced(target, manifest, false);
            manifest.setTicketsAdded(false);
            manifest.setTargetReady(false);
            runtime.ticketsRemoved = true;
            syncCriticalState(server, data);
            return;
        }
        if (!runtime.evaluationCleaned) {
            EvaluationScheduler.cleanup(session.id(), session.roomCode());
            runtime.evaluationCleaned = true;
        }
        if (!runtime.railwayCleaned) {
            EvaluationRailwayTransfer.cleanup(target, session, manifest);
            runtime.railwayCleaned = true;
            syncCriticalState(server, data);
        }
        if (manifest.targetWriteIntent() || session.cleanTargetOnCancel()) {
            List<ChunkPos> chunks = chunkPositions(manifest);
            if (!EvaluationStorageBridge.areChunksIdle(target, chunks)) {
                session.tickState();
                data.changed();
                return;
            }
            if (runtime.operation == Operation.NONE) {
                EvaluationStorageBridge.flushAll(target);
                runtime.writeFuture = EvaluationStorageBridge.deleteRecords(target, chunks);
                runtime.operation = Operation.CLEANUP_DELETE;
                return;
            }
            if (runtime.operation == Operation.CLEANUP_DELETE) {
                if (!runtime.writeFuture.isDone()) {
                    return;
                }
                runtime.writeFuture.join();
                EvaluationStorageBridge.flushAll(target);
                runtime.operation = Operation.CLEANUP_VERIFY;
                runtime.footprintIndex = 0;
                return;
            }
            if (runtime.footprintIndex < chunks.size()) {
                tickCleanupVerify(target, runtime, chunks.get(runtime.footprintIndex));
                return;
            }
            manifest.setTargetWriteIntent(false);
            session.clearCleanTargetOnCancel();
            for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
                chunk.setPublishStatus(EvaluationManifest.PublishStatus.CLEANED);
            }
            syncCriticalState(server, data);
        }

        closeRuntime(session.id());
        EvaluationStorageBridge.deleteStaging(server, manifest);
        if (session.factoryInstalled()) {
            // 固化收尾：工厂保留（还原数据在工厂 BE 内），删除会话、退还启动棒、释放房间锁
            if (session.launcherReturnEligible()) {
                data.addPendingLauncherReturn(session.id(), session.owner());
            }
            data.remove(session.id());
            server.overworld().getDataStorage().save();
            net.neoforged.neoforge.common.IOUtilities.waitUntilIOWorkerComplete();
            EvaluationCloneManager.INSTANCE.afterSessionRemoved(server, data);
            var owner = server.getPlayerList().getPlayer(session.owner());
            if (owner != null) {
                EvaluationManager.deliverPendingLaunchers(owner, data);
                owner.displayClientMessage(
                        Component.translatable("message.createcmpor.factory.ready"), false);
                // 一次性聊天提醒：启动棒可还原工厂（空手右键提示已移除）
                owner.displayClientMessage(
                        Component.translatable("message.createcmpor.factory.revert_hint"), false);
            }
            CreateCMPOR.LOGGER.info("评估会话 {} 固化收尾完成：副本已清理，工厂就绪", session.id());
            return;
        }
        session.setState(EvaluationSession.State.ROLLING_BACK);
        data.changed();
        syncCriticalState(server, data);
    }

    private void tickCleanupVerify(ServerLevel target, RuntimeState runtime, ChunkPos pos) {
        if (runtime.storedFuture == null) {
            runtime.chunk = pos;
            runtime.storedFuture = EvaluationStorageBridge.readStoredRecords(target, pos);
            return;
        }
        if (!runtime.storedFuture.isDone()) {
            return;
        }
        if (!runtime.storedFuture.join().allAbsent()) {
            runtime.operation = Operation.NONE;
            runtime.storedFuture = null;
            runtime.footprintIndex = 0;
            return;
        }
        runtime.footprintIndex++;
        runtime.storedFuture = null;
    }

    private RuntimeState runtime(MinecraftServer server, EvaluationSession session) {
        return runtimes.computeIfAbsent(session.id(), ignored ->
                new RuntimeState(EvaluationStorageBridge.openStaging(server, requireManifest(session))));
    }

    private boolean isQueueHead(EvaluationSavedData data, EvaluationSession session) {
        return data.sessions().stream()
                .filter(candidate -> candidate.state() == EvaluationSession.State.QUEUED)
                .findFirst().map(candidate -> candidate.id().equals(session.id())).orElse(false);
    }

    private int activeCount(EvaluationSavedData data) {
        // PUBLISHED 之后副本加载与克隆并发无关（由 ticket 维持），释放槽位让排队会话前进。
        return (int) data.sessions().stream().filter(session -> switch (session.state()) {
            case STAGING_SOURCE, STAGING_WRITTEN, STAGING_VERIFIED,
                    RAILWAY_TRANSFER, PUBLISHING, CLEANING -> true;
            default -> false;
        }).count();
    }

    private static boolean sameRoomChunks(EvaluationSession session, EvaluationManifest manifest) {
        List<Long> current = CompactMachines.roomChunks(session.roomCode()).stream()
                .map(ChunkPos::toLong).sorted().toList();
        List<Long> frozen = manifest.chunks().stream().map(chunk -> chunk.chunkPos().toLong())
                .sorted().toList();
        return current.equals(frozen);
    }

    private static List<ChunkPos> chunkPositions(EvaluationManifest manifest) {
        return manifest.chunks().stream().map(EvaluationManifest.ChunkRecord::chunkPos).toList();
    }

    private static EvaluationManifest requireManifest(EvaluationSession session) {
        EvaluationManifest manifest = session.manifest();
        if (manifest == null) {
            throw new IllegalStateException("Phase 4 会话缺少 manifest");
        }
        manifest.validate();
        return manifest;
    }

    private static EvaluationManifest.ChunkRecord requireChunk(EvaluationManifest manifest, ChunkPos pos) {
        EvaluationManifest.ChunkRecord record = manifest.chunk(pos);
        if (record == null) {
            throw new IllegalStateException("manifest 缺少区块：" + pos);
        }
        return record;
    }

    private static RoomInstance requireRoom(MinecraftServer server, EvaluationSession session) {
        return CompactMachines.room(server, session.roomCode()).orElseThrow(() ->
                new IllegalStateException("源房间不存在：" + session.roomCode()));
    }

    private static ServerLevel requireTarget(MinecraftServer server, EvaluationManifest manifest) {
        ServerLevel target = server.getLevel(manifest.targetDimension());
        if (target == null) {
            throw new IllegalStateException("目标维度未加载：" + manifest.targetDimension().location());
        }
        return target;
    }

    private static void validateStoredChunk(ChunkPos pos, CompoundTag tag) {
        if (tag.getInt("xPos") != pos.x || tag.getInt("zPos") != pos.z
                || !tag.contains("Status", net.minecraft.nbt.Tag.TAG_STRING)) {
            throw new IllegalStateException("区块 NBT 坐标或状态无效：" + pos);
        }
    }

    private static void syncCriticalState(MinecraftServer server, EvaluationSavedData data) {
        syncCritical(server, data);
    }

    static void syncCritical(MinecraftServer server, EvaluationSavedData data) {
        if (data.criticalSessions().isEmpty()) {
            EvaluationCriticalJournal.deleteIfEmpty(server, data);
        } else {
            EvaluationCriticalJournal.write(server, data);
        }
        server.overworld().getDataStorage().save();
    }

    private static void notifyOwner(MinecraftServer server, EvaluationSession session, Component message) {
        var owner = server.getPlayerList().getPlayer(session.owner());
        if (owner != null) {
            owner.displayClientMessage(message, false);
        }
    }

    private static void notifyCloneProgress(MinecraftServer server, EvaluationSession session,
                                            EvaluationManifest manifest) {
        int total = manifest.chunks().size();
        int verified = (int) manifest.chunks().stream()
                .filter(chunk -> chunk.stagingStatus() == EvaluationManifest.StagingStatus.VERIFIED).count();
        int published = (int) manifest.chunks().stream()
                .filter(chunk -> chunk.publishStatus() == EvaluationManifest.PublishStatus.VERIFIED).count();
        CreateCMPOR.LOGGER.info("评估会话 {} 克隆进度：区块 {}/{} 已校验，{}/{} 已发布",
                session.id(), verified, total, published, total);
        var owner = server.getPlayerList().getPlayer(session.owner());
        if (owner != null) {
            owner.displayClientMessage(Component.translatable(
                    "message.createcmpor.evaluation.clone_progress", verified, total, published), true);
        }
    }

    private void closeAllRuntimes() {
        for (UUID id : new ArrayList<>(runtimes.keySet())) {
            closeRuntime(id);
        }
    }

    private void closeRuntime(UUID sessionId) {
        if (sessionId.equals(publishingSession)) {
            publishingSession = null;
        }
        RuntimeState runtime = runtimes.remove(sessionId);
        if (runtime == null) {
            return;
        }
        try {
            runtime.staging.close();
        } catch (IOException exception) {
            throw new IllegalStateException("无法关闭评估 staging", exception);
        }
    }

    private enum Operation {
        NONE,
        STAGING_WRITE,
        STAGING_VERIFY,
        TARGET_CONFLICT_CHECK,
        TARGET_READ_STAGING,
        TARGET_WRITE,
        TARGET_VERIFY,
        CLEANUP_DELETE,
        CLEANUP_VERIFY
    }

    private static final class RuntimeState {
        private final ChunkStorage staging;
        private final Map<ChunkPos, ListTag> sourceEntities = new LinkedHashMap<>();
        private final Map<ChunkPos, CompoundTag> sourcePoi = new LinkedHashMap<>();
        /** 探针：写 staging 时的源区块 tag（diff 排查摘要不一致）。 */
        private final Map<ChunkPos, CompoundTag> sourceTags = new LinkedHashMap<>();
        private Map<ChunkPos, List<CompoundTag>> rewrittenEntities = Map.of();
        private Operation operation = Operation.NONE;
        private ChunkPos chunk;
        private CompletableFuture<EvaluationStorageBridge.SourceChunk> sourceFuture;
        private CompletableFuture<Optional<CompoundTag>> tagFuture;
        private CompletableFuture<EvaluationStorageBridge.StoredRecords> storedFuture;
        private CompletableFuture<Void> writeFuture;
        private int footprintIndex;
        private boolean targetFlushed;
        private boolean ticketsRemoved;
        private boolean railwayCleaned;
        private boolean evaluationCleaned;

        private RuntimeState(ChunkStorage staging) {
            this.staging = staging;
        }

        private void clearOperation() {
            operation = Operation.NONE;
            chunk = null;
            sourceFuture = null;
            tagFuture = null;
            storedFuture = null;
            writeFuture = null;
        }
    }

    private static final class TargetConflictException extends RuntimeException {
        private TargetConflictException(String message) {
            super(message);
        }
    }
}
