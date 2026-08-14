package com.createcmpor.evaluation;

import com.createcmpor.Config;
import com.createcmpor.CreateCMPOR;
import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.room.RoomInstance;
import net.minecraft.nbt.CompoundTag;
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
                case PUBLISHING -> tickPublishing(server, data, session);
                case PUBLISHED -> tickPublished(server, data, session);
                case CLEANING -> tickCleaning(server, data, session);
                default -> throw new IllegalStateException("非 Phase 4 状态进入克隆管理器：" + session.state());
            }
        } catch (EvaluationStorageBridge.UnsupportedContentException exception) {
            requestCleanup(server, data, session, exception.messageKey());
        } catch (TargetConflictException exception) {
            CreateCMPOR.LOGGER.warn("评估会话 {} 目标冲突：{}", session.id(), exception.getMessage());
            requestCleanup(server, data, session, "message.createcmpor.evaluation.target_conflict");
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof EvaluationStorageBridge.UnsupportedContentException unsupported) {
                requestCleanup(server, data, session, unsupported.messageKey());
                return;
            }
            if (cause instanceof TargetConflictException conflict) {
                CreateCMPOR.LOGGER.warn("评估会话 {} 目标冲突：{}", session.id(), conflict.getMessage());
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
            runtime.staging.flushWorker();
            session.setState(EvaluationSession.State.STAGING_WRITTEN);
            data.changed();
            syncCriticalState(server, data);
            runtime.clearOperation();
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
            session.setState(EvaluationSession.State.PUBLISHING);
            data.changed();
            syncCriticalState(server, data);
            runtime.clearOperation();
            CreateCMPOR.LOGGER.info("评估会话 {} 冲突检查完成，开始发布 {} 个区块", session.id(),
                    manifest.chunks().size());
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
        if (records.entities().isPresent() || records.poi().isPresent()) {
            throw new IllegalStateException("目标区块意外生成实体或 POI 记录：" + runtime.chunk);
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
            manifest.setTicketsAdded(false);
            manifest.setTargetReady(false);
            runtime.ticketsRemoved = true;
            syncCriticalState(server, data);
            return;
        }
        if (manifest.targetWriteIntent()) {
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
            for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
                chunk.setPublishStatus(EvaluationManifest.PublishStatus.CLEANED);
            }
            syncCriticalState(server, data);
        }

        closeRuntime(session.id());
        EvaluationStorageBridge.deleteStaging(server, manifest);
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
                    PUBLISHING, CLEANING -> true;
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
        private Operation operation = Operation.NONE;
        private ChunkPos chunk;
        private CompletableFuture<EvaluationStorageBridge.SourceChunk> sourceFuture;
        private CompletableFuture<Optional<CompoundTag>> tagFuture;
        private CompletableFuture<EvaluationStorageBridge.StoredRecords> storedFuture;
        private CompletableFuture<Void> writeFuture;
        private int footprintIndex;
        private boolean targetFlushed;
        private boolean ticketsRemoved;

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
