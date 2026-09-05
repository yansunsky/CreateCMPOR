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
import net.minecraft.world.level.block.state.BlockState;
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
                case PARALLEL_PREPARING -> tickParallelPreparing(server, data, session);
                case PARALLEL_PUBLISHING -> tickParallelPublishing(server, data, session);
                case PARALLEL_PUBLISHED -> tickParallelPublished(server, data, session);
                case PARALLEL_EVALUATING -> EvaluationScheduler.tickParallel(server, data, session);
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
        // 失败清理：不再是正常分支间过渡，清除标记防止 CLEANING 完成后误入下一分支
        session.clearBranchTransitionPending();
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
            String fallbackReason = EvaluationModePolicy.serialFallbackReason(
                    runtime.sourceTags, runtime.rewrittenEntities);
            // 铁路回退增强：内容扫描未命中但源房间存在铁路（车厢实体或源房间轨道图节点）时，
            // 同样退回串行分支（铁轨网络是全局 SavedData，并行 lane 无法隔离）。
            if (fallbackReason == null && EvaluationRailwayTransfer.hasSourceRailway(
                    server, session, runtime.rewrittenEntities)) {
                fallbackReason = "create_railway";
            }
            if (fallbackReason != null && session.parallelAllowed()) {
                session.setSerialFallbackReason(fallbackReason);
                CreateCMPOR.LOGGER.info("评估会话 {} 检测到并行不安全内容（{}），本场退回串行分支",
                        session.id(), fallbackReason);
                notifyOwner(server, session, Component.literal("检测到 " + fallbackReason
                        + "，本场评估退回串行模式"));
            }
            data.changed();
            // staging 写入 future 已在逐区块操作中完成；同一 IOWorker 的 read 会看到 pending write，
            // 不在 ServerTick 上额外 flush/等待整个 staging worker。
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
                CreateCMPOR.LOGGER.warn("[staging 摘要不一致] @{}: 仅源有={} 仅staging有={} 共同但不同={}（已忽略附加字段降级继续）",
                        runtime.chunk, onlySource, onlyStaging, changedCommon);
            }
            CreateCMPOR.LOGGER.warn("[staging 摘要不一致] @{}: staging={} source={} | stagingDV={} sourceDV={} | stagingStatus={}"
                            + "（以 staging 内容为权威，继续评估；若反复出现请检查第三方模组附加字段）",
                    runtime.chunk,
                    hash.length() > 16 ? hash.substring(0, 16) : hash,
                    record.sourceHash().length() > 16 ? record.sourceHash().substring(0, 16) : record.sourceHash(),
                    tag.getInt("DataVersion"), record.dataVersion(),
                    tag.getString("Status"));
            // 兜底：不以摘要不一致拒绝评估（第三方模组可能在写盘路径给 NBT 附加字段，
            // 如 Railways_DataVersion）。staging 内容已是完整可用数据，以它为权威继续。
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
        if (shouldUseParallel(session)) {
            initializeParallelBranches(server, data, session);
            return;
        }
        if (session.branchCount() > ParallelEvaluationWorlds.LANE_COUNT
                && session.parallelAllowed()) {
            session.setSerialFallbackReason("parallel_lanes_exceeded");
            CreateCMPOR.LOGGER.info("评估会话 {} 分支数 {} 超过并行 lane {}，退回串行分支",
                    session.id(), session.branchCount(), ParallelEvaluationWorlds.LANE_COUNT);
            data.changed();
        }
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

    private static boolean shouldUseParallel(EvaluationSession session) {
        return session.branchCount() > 1
                && session.parallelAllowed()
                && session.parallelBranches().isEmpty()
                && session.branchCount() <= ParallelEvaluationWorlds.LANE_COUNT;
    }

    private void initializeParallelBranches(MinecraftServer server, EvaluationSavedData data,
                                            EvaluationSession session) {
        EvaluationManifest base = requireManifest(session);
        List<EvaluationBranch> branches = new ArrayList<>();
        for (int index = 0; index < session.branchCount(); index++) {
            EvaluationManifest branchManifest = base.copyForBranchTarget(
                    ParallelEvaluationWorlds.lane(index));
            branches.add(new EvaluationBranch(index, UUID.randomUUID(), branchManifest));
        }
        session.setParallelBranches(branches);
        session.setState(EvaluationSession.State.PARALLEL_PREPARING);
        data.changed();
        syncCriticalState(server, data);
        CreateCMPOR.LOGGER.info("评估会话 {} 建立并行分支：{} 个独立 lane",
                session.id(), branches.size());
        notifyOwner(server, session, Component.literal("检测到并行输入，使用并行评估（"
                + branches.size() + " 个独立空间）"));
    }

    private void tickParallelPreparing(MinecraftServer server, EvaluationSavedData data,
                                       EvaluationSession session) {
        if (session.parallelBranches().isEmpty()) {
            requestCleanup(server, data, session, "message.createcmpor.evaluation.clone_failed");
            return;
        }
        RuntimeState source = runtime(server, session);
        for (EvaluationBranch branch : session.parallelBranches()) {
            RuntimeState branchRuntime = runtimeSharing(branch.branchId(), source);
            if (branchRuntime.rewrittenEntities.isEmpty()) {
                branchRuntime.rewrittenEntities = new LinkedHashMap<>(source.rewrittenEntities);
            }
            if (branchRuntime.sourcePoi.isEmpty()) {
                branchRuntime.sourcePoi.putAll(source.sourcePoi);
            }
        }
        session.setState(EvaluationSession.State.PARALLEL_PUBLISHING);
        data.changed();
        syncCriticalState(server, data);
    }

    private void tickParallelPublishing(MinecraftServer server, EvaluationSavedData data,
                                        EvaluationSession session) {
        if (session.parallelBranches().isEmpty()) {
            requestCleanup(server, data, session, "message.createcmpor.evaluation.clone_failed");
            return;
        }
        boolean allPublished = true;
        for (EvaluationBranch branch : session.parallelBranches()) {
            if (branch.phase() == EvaluationBranch.Phase.STAGED) {
                branch.setPhase(EvaluationBranch.Phase.PUBLISHING);
            }
            if (branch.phase() == EvaluationBranch.Phase.PUBLISHING) {
                tickParallelPublishBranch(server, data, session, branch);
                allPublished = false;
            } else if (branch.phase() != EvaluationBranch.Phase.PUBLISHED
                    && branch.phase() != EvaluationBranch.Phase.EVALUATING) {
                allPublished = false;
            }
        }
        if (!allPublished) {
            return;
        }
        session.setState(EvaluationSession.State.PARALLEL_PUBLISHED);
        data.changed();
        syncCriticalState(server, data);
        notifyOwner(server, session, Component.literal("并行副本全部发布完成"));
    }

    private void tickParallelPublishBranch(MinecraftServer server, EvaluationSavedData data,
                                           EvaluationSession session, EvaluationBranch branch) {
        EvaluationManifest manifest = branch.manifest();
        ServerLevel target = requireTarget(server, manifest);
        RuntimeState runtime = runtime(server, branch.branchId(), manifest);
        if (!manifest.targetWriteIntent()) {
            List<ChunkPos> targets = chunkPositions(manifest);
            if (runtime.footprintIndex >= targets.size()) {
                manifest.setTargetWriteIntent(true);
                data.changed();
                syncCriticalState(server, data);
                runtime.clearOperation();
                return;
            }
            ChunkPos pos = targets.get(runtime.footprintIndex);
            if (!EvaluationStorageBridge.isChunkIdle(target, pos)) {
                throw new TargetConflictException("并行目标 chunk 已加载或仍在卸载：" + pos);
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
            if (!records.allAbsent()) {
                throw new TargetConflictException("并行目标存储已存在未知记录：" + pos);
            }
            runtime.footprintIndex++;
            runtime.clearOperation();
            return;
        }

        EvaluationManifest.ChunkRecord unwritten = manifest.chunks().stream()
                .filter(chunk -> chunk.publishStatus() == EvaluationManifest.PublishStatus.NONE)
                .findFirst().orElse(null);
        if (unwritten != null) {
            tickTargetWrite(server, data, session, target, manifest, runtime, unwritten);
            return;
        }
        EvaluationManifest.ChunkRecord unverified = manifest.chunks().stream()
                .filter(chunk -> chunk.publishStatus() == EvaluationManifest.PublishStatus.WRITTEN)
                .findFirst().orElse(null);
        if (unverified != null) {
            tickTargetVerify(server, data, session, target, manifest, runtime, unverified);
            return;
        }

        EvaluationTicketManager.add(target, manifest);
        manifest.setTicketsAdded(true);
        syncCriticalState(server, data);
        loadChunksToFull(server, session, target, manifest);
        EvaluationTicketManager.setChunksForced(target, manifest, true);
        runtime.clearOperation();
        branch.setPhase(EvaluationBranch.Phase.PUBLISHED);
        CreateCMPOR.LOGGER.info("评估会话 {} 并行分支 {}/{} 已发布到 {}",
                session.id(), branch.index() + 1, session.branchCount(), manifest.targetDimension().location());
    }

    private void tickParallelPublished(MinecraftServer server, EvaluationSavedData data,
                                       EvaluationSession session) {
        boolean allReady = true;
        for (EvaluationBranch branch : session.parallelBranches()) {
            EvaluationManifest manifest = branch.manifest();
            ServerLevel target = requireTarget(server, manifest);
            if (branch.targetReady()) {
                continue;
            }
            if (EvaluationTicketManager.allReady(target, manifest)) {
                manifest.setTargetReady(true);
                branch.setTargetReady(true);
                branch.setPhase(EvaluationBranch.Phase.EVALUATING);
                data.changed();
                syncCriticalState(server, data);
                CreateCMPOR.LOGGER.info("评估会话 {} 并行分支 {}/{} 达到 BLOCK_TICKING",
                        session.id(), branch.index() + 1, session.branchCount());
            } else {
                allReady = false;
            }
        }
        if (!allReady) {
            return;
        }
        session.setState(EvaluationSession.State.PARALLEL_EVALUATING);
        data.changed();
        syncCriticalState(server, data);
        EvaluationScheduler.startParallel(server, session);
        notifyOwner(server, session, Component.literal("并行副本全部就绪，开始评估"));
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
        EvaluationVerdict.Result lastResult = session.evaluationResult();
        if (lastResult == null || lastResult.rejected()) {
            requestCleanup(server, data, session, "message.createcmpor.evaluation.clone_failed");
            return;
        }
        // 结果列表：单分支用最后一次判定；多分支用全部 branchResults（advanceBranch 已逐个记录）
        List<EvaluationVerdict.Result> results = new ArrayList<>(session.branchResults());
        if (results.isEmpty()) {
            results.add(lastResult);
        }
        ServerLevel machineLevel = server.getLevel(session.machinePos().dimension());
        if (machineLevel == null) {
            throw new IllegalStateException("机器维度未加载：" + session.machinePos().dimension().location());
        }
        BlockPos basePos = session.machinePos().pos();
        int count = results.size();
        // 发布前提示：工厂将占用主位置向上 N 个方块（含覆盖掉落）
        if (count > 1) {
            notifyOwner(server, session, Component.literal(
                    "多分支评估完成，将生成 " + count + " 个工厂：主位置 " + basePos
                            + " 向上 " + count + " 格（重叠方块将被破坏掉落）"));
            CreateCMPOR.LOGGER.info("评估会话 {} 多分支固化：主位置 {} 向上 {} 个工厂",
                    session.id(), basePos, count);
        }
        for (int i = 0; i < count; i++) {
            BlockPos pos = basePos.offset(0, i, 0);
            if (i == 0) {
                // 主位置是评估方块（EvaluatorBlock）的安装位置：
                // 直接替换为工厂，不参与破坏检测（评估开始前已对上方 N-1 格做过预检测）。
                machineLevel.removeBlockEntity(pos);
                machineLevel.setBlockAndUpdate(pos,
                        com.yansunsky.createcmpor.init.ModBlocks.FACTORY.get().defaultBlockState());
                if (!(machineLevel.getBlockEntity(pos) instanceof com.yansunsky.createcmpor.block.FactoryBlockEntity factory)) {
                    throw new IllegalStateException("工厂方块实体未创建 @" + pos);
                }
                factory.setRoomCode(session.roomCode());
                factory.setGroupInfo(i, count);
                EvaluationVerdict.Result branchResult = results.get(i);
                if (EvaluationVerdict.VERDICT_REPLAY.equals(branchResult.verdict())) {
                    factory.installPatterns(branchResult.replayIn(), branchResult.replayOut(),
                            branchResult.energyReplayIn(), branchResult.energyReplayOut(),
                            branchResult.normalBurnDemandPerSecond(), branchResult.superBurnDemandPerSecond());
                } else {
                    factory.installRates(branchResult.inputRates(), branchResult.outputRates(),
                            branchResult.inputEnergyRate(), branchResult.outputEnergyRate(),
                            branchResult.normalBurnDemandPerSecond(), branchResult.superBurnDemandPerSecond());
                }
                factory.installRestoreData(session.originalState(), session.originalBlockEntityNbt(),
                        branchResult.stressProfile());
                continue;
            }
            // 上方位置：破坏重叠方块（掉落）；遇不可破坏方块（如基岩）→ 发布失败（评估开始前已预检测，此处兜底）
            BlockState existing = machineLevel.getBlockState(pos);
            if (!existing.isAir()) {
                float destroySpeed = existing.getDestroySpeed(machineLevel, pos);
                if (destroySpeed < 0) {
                    requestCleanup(server, data, session, "message.createcmpor.evaluation.solidify_blocked");
                    CreateCMPOR.LOGGER.error("评估会话 {} 固化失败：位置 {} 有不可破坏方块 {}",
                            session.id(), pos, existing.getBlock());
                    return;
                }
                machineLevel.destroyBlock(pos, true);
                CreateCMPOR.LOGGER.info("评估会话 {} 固化：破坏 {} 处重叠方块 {}",
                        session.id(), pos, existing.getBlock());
            }
            machineLevel.removeBlockEntity(pos);
            machineLevel.setBlockAndUpdate(pos,
                    com.yansunsky.createcmpor.init.ModBlocks.FACTORY.get().defaultBlockState());
            if (!(machineLevel.getBlockEntity(pos) instanceof com.yansunsky.createcmpor.block.FactoryBlockEntity factory)) {
                throw new IllegalStateException("工厂方块实体未创建 @" + pos);
            }
            factory.setRoomCode(session.roomCode());
            factory.setGroupInfo(i, count);
            EvaluationVerdict.Result branchResult = results.get(i);
            if (EvaluationVerdict.VERDICT_REPLAY.equals(branchResult.verdict())) {
                factory.installPatterns(branchResult.replayIn(), branchResult.replayOut(),
                        branchResult.energyReplayIn(), branchResult.energyReplayOut(),
                        branchResult.normalBurnDemandPerSecond(), branchResult.superBurnDemandPerSecond());
            } else {
                factory.installRates(branchResult.inputRates(), branchResult.outputRates(),
                        branchResult.inputEnergyRate(), branchResult.outputEnergyRate(),
                        branchResult.normalBurnDemandPerSecond(), branchResult.superBurnDemandPerSecond());
            }
            factory.installRestoreData(session.originalState(), session.originalBlockEntityNbt(),
                    branchResult.stressProfile());
        }
        // 登记 roomCode → 全部工厂位置（第一个=主位置；玩家进入压缩空间自动还原防复制 + 多工厂组还原数量校验用）
        List<GlobalPos> groupPositions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            groupPositions.add(GlobalPos.of(machineLevel.dimension(), basePos.offset(0, i, 0)));
        }
        FactoryIndexSavedData.get(server).registerFactory(session.roomCode(), groupPositions);
        session.setFactoryInstalled(true);
        session.setState(EvaluationSession.State.CLEANING);
        data.changed();
        syncCriticalState(server, data);
        notifyOwner(server, session, Component.translatable("message.createcmpor.factory.installed"));
        CreateCMPOR.LOGGER.info("评估会话 {} 已固化 {} 个工厂，开始清理副本",
                session.id(), count);
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
            tickTargetWrite(server, data, session, target, manifest, runtime, unwritten);
            return;
        }
        EvaluationManifest.ChunkRecord unverified = manifest.chunks().stream()
                .filter(chunk -> chunk.publishStatus() == EvaluationManifest.PublishStatus.WRITTEN)
                .findFirst().orElse(null);
        if (unverified != null) {
            tickTargetVerify(server, data, session, target, manifest, runtime, unverified);
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
                                 EvaluationSession session, ServerLevel target,
                                 EvaluationManifest manifest, RuntimeState runtime,
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
            // 发布阶段不再做哈希比较：staging 是本会话私有目录，校验阶段（tickStagingWritten）
            // 已确认其内容完整；第三方模组可能在区块写盘/读盘路径给 NBT 附加字段，
            // 反复比较 sourceHash 会因附加字段误判。直接以 staging 内容写入目标。
            runtime.writeFuture = EvaluationStorageBridge.chunkStorage(target).write(runtime.chunk, tag.copy());
            runtime.operation = Operation.TARGET_WRITE;
            return;
        }
        if (!runtime.writeFuture.isDone()) {
            return;
        }
        // ChunkStorage.write 已完成，不再同步等待实体/POI worker；把辅助写入提交后分帧检查。
        runtime.writeFuture.join();
        if (runtime.auxiliaryWriteFuture == null) {
            List<CompletableFuture<Void>> auxiliaryWrites = new ArrayList<>();
            // 同窗口写入改写后的实体记录（目标 chunk 实体尚未加载，emptyChunks 无缓存）
            List<CompoundTag> entities = runtime.rewrittenEntities.get(runtime.chunk);
            if (entities != null && !entities.isEmpty()) {
                ListTag entityList = new ListTag();
                entities.forEach(entityList::add);
                auxiliaryWrites.add(EvaluationStorageBridge.writeEntities(target, runtime.chunk, entityList));
            }
            // 同窗口写入源 POI 记录（目标 chunk 未加载，POI 存储无缓存）
            CompoundTag poi = runtime.sourcePoi.get(runtime.chunk);
            if (poi != null) {
                auxiliaryWrites.add(EvaluationStorageBridge.writePoi(target, runtime.chunk, poi));
            }
            runtime.auxiliaryWriteFuture = CompletableFuture.allOf(
                    auxiliaryWrites.toArray(CompletableFuture[]::new));
            return;
        }
        if (!runtime.auxiliaryWriteFuture.isDone()) {
            return;
        }
        runtime.auxiliaryWriteFuture.join();
        record.setPublishStatus(EvaluationManifest.PublishStatus.WRITTEN);
        data.changed();
        notifyCloneProgress(server, session, manifest);
        runtime.clearOperation();
    }

    private void tickTargetVerify(MinecraftServer server, EvaluationSavedData data,
                                  EvaluationSession session, ServerLevel target,
                                  EvaluationManifest manifest, RuntimeState runtime,
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
        // 目标读回比较：用校验阶段记录的 stagingHash（同一内容、忽略第三方附加字段），
        // 不再与 sourceHash 比（源快照不含写盘路径附加的字段，必然不同）。
        if (record.stagingHash() == null || record.stagingHash().isBlank()
                || !hash.equals(record.stagingHash())) {
            throw new IllegalStateException("目标区块摘要不一致：" + runtime.chunk);
        }
        record.setTargetHash(hash);
        record.setPublishStatus(EvaluationManifest.PublishStatus.VERIFIED);
        data.changed();
        notifyCloneProgress(server, session, manifest);
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
        if (session.hasParallelBranches()) {
            tickParallelCleaning(server, data, session);
            return;
        }
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
                runtime.writeFuture = EvaluationStorageBridge.deleteRecords(target, chunks);
                runtime.operation = Operation.CLEANUP_DELETE;
                return;
            }
            if (runtime.operation == Operation.CLEANUP_DELETE) {
                if (!runtime.writeFuture.isDone()) {
                    return;
                }
                runtime.writeFuture.join();
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
        // 多分支评估：正常分支间清理（advanceBranch 后进入 CLEANING）且还有分支 → 重新克隆下一分支
        if (session.branchTransitionPending() && session.branchIndex() < session.branchCount()) {
            session.clearBranchTransitionPending();
            manifest.resetForBranch();
            session.setState(EvaluationSession.State.STAGING_SOURCE);
            data.changed();
            syncCriticalState(server, data);
            CreateCMPOR.LOGGER.info("评估会话 {} 分支 {}/{} 副本已清理，开始下一分支克隆",
                    session.id(), session.branchIndex(), session.branchCount());
            return;
        }
        session.setState(EvaluationSession.State.ROLLING_BACK);
        data.changed();
        syncCriticalState(server, data);
    }

    private void tickParallelCleaning(MinecraftServer server, EvaluationSavedData data,
                                      EvaluationSession session) {
        boolean allBranchesCleaned = true;
        for (EvaluationBranch branch : session.parallelBranches()) {
            EvaluationManifest manifest = branch.manifest();
            RuntimeState branchRuntime = runtime(server, branch.branchId(), manifest);
            ServerLevel target = requireTarget(server, manifest);
            if (!branch.ticketsRemoved()) {
                EvaluationTicketManager.remove(target, manifest);
                EvaluationTicketManager.setChunksForced(target, manifest, false);
                manifest.setTicketsAdded(false);
                manifest.setTargetReady(false);
                branch.setTicketsRemoved(true);
                syncCriticalState(server, data);
                allBranchesCleaned = false;
                continue;
            }
            if (!branchRuntime.evaluationCleaned) {
                EvaluationScheduler.cleanup(branch.branchId(), "branch:" + branch.branchId());
                branchRuntime.evaluationCleaned = true;
            }
            if (manifest.targetWriteIntent() || session.cleanTargetOnCancel()) {
                if (!branch.targetCleaned()) {
                    List<ChunkPos> chunks = chunkPositions(manifest);
                    if (!EvaluationStorageBridge.areChunksIdle(target, chunks)) {
                        allBranchesCleaned = false;
                        continue;
                    }
                    if (branchRuntime.operation != Operation.NONE
                            && branchRuntime.operation != Operation.CLEANUP_DELETE
                            && branchRuntime.operation != Operation.CLEANUP_VERIFY) {
                        branchRuntime.clearOperation();
                        branchRuntime.footprintIndex = 0;
                    }
                    if (branchRuntime.operation == Operation.NONE) {
                        branchRuntime.writeFuture = EvaluationStorageBridge.deleteRecords(target, chunks);
                        branchRuntime.operation = Operation.CLEANUP_DELETE;
                        allBranchesCleaned = false;
                        continue;
                    }
                    if (branchRuntime.operation == Operation.CLEANUP_DELETE) {
                        if (!branchRuntime.writeFuture.isDone()) {
                            allBranchesCleaned = false;
                            continue;
                        }
                        branchRuntime.writeFuture.join();
                        branchRuntime.operation = Operation.CLEANUP_VERIFY;
                        branchRuntime.footprintIndex = 0;
                        allBranchesCleaned = false;
                        continue;
                    }
                    if (branchRuntime.footprintIndex < chunks.size()) {
                        tickCleanupVerify(target, branchRuntime, chunks.get(branchRuntime.footprintIndex));
                        allBranchesCleaned = false;
                        continue;
                    }
                    manifest.setTargetWriteIntent(false);
                    for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
                        chunk.setPublishStatus(EvaluationManifest.PublishStatus.CLEANED);
                    }
                    branch.setTargetCleaned(true);
                    branch.setPhase(EvaluationBranch.Phase.CLEANED);
                    syncCriticalState(server, data);
                }
            }
        }
        if (!allBranchesCleaned) {
            return;
        }
        for (EvaluationBranch branch : session.parallelBranches()) {
            closeRuntime(branch.branchId());
        }
        session.clearCleanTargetOnCancel();
        session.setParallelBranches(List.of());
        // 分支清理完成：立即持久化“无分支 + 主 eval 副本未写”的状态，
        // 避免父 manifest 清理与 journal 重启恢复读到残留分支。
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
        return runtime(server, session.id(), requireManifest(session));
    }

    private RuntimeState runtime(MinecraftServer server, UUID key, EvaluationManifest manifest) {
        return runtimes.computeIfAbsent(key, ignored ->
                new RuntimeState(EvaluationStorageBridge.openStaging(server, manifest)));
    }

    /** 并行分支复用父会话 staging 句柄；分支 runtime 不拥有该句柄，closeRuntime 不会关闭它。 */
    private RuntimeState runtimeSharing(UUID key, RuntimeState source) {
        return runtimes.computeIfAbsent(key, ignored -> new RuntimeState(source.staging, false));
    }

    private boolean isQueueHead(EvaluationSavedData data, EvaluationSession session) {
        return data.sessions().stream()
                .filter(candidate -> candidate.state() == EvaluationSession.State.QUEUED)
                .findFirst().map(candidate -> candidate.id().equals(session.id())).orElse(false);
    }

    private int activeCount(EvaluationSavedData data) {
        // PUBLISHED 之后副本加载与克隆并发无关（由 ticket 维持），释放槽位让排队会话前进。
        // 并行分支的 PARALLEL_PREPARING/PARALLEL_PUBLISHING 仍在做与串行 PUBLISHING 同级的
        // 克隆/发布 IO，必须占用克隆槽；PARALLEL_PUBLISHED 起已全部发布完成，同样释放槽位。
        return (int) data.sessions().stream().filter(session -> switch (session.state()) {
            case STAGING_SOURCE, STAGING_WRITTEN, STAGING_VERIFIED,
                    RAILWAY_TRANSFER, PUBLISHING, CLEANING,
                    PARALLEL_PREPARING, PARALLEL_PUBLISHING -> true;
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
        if (runtime == null || !runtime.ownsStaging) {
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
        /** 是否拥有 staging 句柄：父会话/独立 runtime=true；并行分支共享父句柄=false。 */
        private final boolean ownsStaging;
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
        private CompletableFuture<Void> auxiliaryWriteFuture;
        private int footprintIndex;
        private boolean targetFlushed;
        private boolean ticketsRemoved;
        private boolean railwayCleaned;
        private boolean evaluationCleaned;

        private RuntimeState(ChunkStorage staging) {
            this(staging, true);
        }

        private RuntimeState(ChunkStorage staging, boolean ownsStaging) {
            this.staging = staging;
            this.ownsStaging = ownsStaging;
        }

        private void clearOperation() {
            operation = Operation.NONE;
            chunk = null;
            sourceFuture = null;
            tagFuture = null;
            storedFuture = null;
            writeFuture = null;
            auxiliaryWriteFuture = null;
        }
    }

    private static final class TargetConflictException extends RuntimeException {
        private TargetConflictException(String message) {
            super(message);
        }
    }
}
