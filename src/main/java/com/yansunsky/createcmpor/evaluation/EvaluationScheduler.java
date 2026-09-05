package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.block.BaseIOBlock;
import com.yansunsky.createcmpor.block.BaseIOBlockEntity;
import com.yansunsky.createcmpor.block.EvaluatorBlockEntity;
import com.yansunsky.createcmpor.block.ParallelInputBlockEntity;
import com.yansunsky.createcmpor.block.StressInputBlock;
import com.yansunsky.createcmpor.block.StressInputBlockEntity;
import com.yansunsky.createcmpor.block.StressOutputBlock;
import com.yansunsky.createcmpor.block.StressOutputBlockEntity;
import com.yansunsky.createcmpor.init.ModBlocks;
import com.yansunsky.createcmpor.stress.StressEvaluationRegistry;
import com.yansunsky.createcmpor.stress.StressProfile;
import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.room.RoomInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 6 评估调度器：STARTING → WARMING → SAMPLING → FINISHING → VERDICTING。
 *
 * <p>子阶段仅运行时内存（重启后按 cleanup-first 回滚，评估不跨重启续跑）。</p>
 */
final class EvaluationScheduler {
    enum Phase {
        STARTING,
        WARMING,
        SAMPLING,
        FINISHING,
        VERDICTING
    }

    static final class State {
        Phase phase = Phase.STARTING;
        long phaseStartTick;
        EvaluationAudit.InventorySnapshot s0;
        EvaluationAudit.InventorySnapshot warmup;
        EvaluationAudit.InventorySnapshot s1;
        StressProfile stressProfile = StressProfile.EMPTY;
        EvaluationVerdict.Result result;
        boolean entityTickingApplied;
        Map<EvaluationTrace.FlowKey, Long> floorItems = Map.of();
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private EvaluationScheduler() {
    }

    static void start(ServerLevel target, EvaluationSession session, EvaluationManifest manifest) {
        STATES.put(session.id(), new State());
        CreateCMPOR.LOGGER.info("评估会话 {} 进入评估：目标 BLOCK_TICKING → ENTITY_TICKING", session.id());
    }

    static void tick(MinecraftServer server, EvaluationSavedData data, EvaluationSession session) {
        State state = STATES.computeIfAbsent(session.id(), ignored -> new State());
        EvaluationManifest manifest = session.manifest();
        if (manifest == null) {
            throw new IllegalStateException("评估会话缺少 manifest");
        }
        ServerLevel target = server.getLevel(manifest.targetDimension());
        if (target == null) {
            throw new IllegalStateException("目标维度未加载：" + manifest.targetDimension().location());
        }
        switch (state.phase) {
            case STARTING -> tickStarting(server, data, session, target, state);
            case WARMING -> tickWarming(server, data, session, target, state);
            case SAMPLING -> tickSampling(server, data, session, target, state);
            case FINISHING -> tickFinishing(server, data, session, target, state);
            case VERDICTING -> tickVerdicting(server, data, session, target, state);
        }
    }

    static void cleanup(UUID sessionId, String roomCode) {
        STATES.remove(sessionId);
        EvaluationTrace.Hub.INSTANCE.remove(roomCode);
        StressEvaluationRegistry.clear(roomCode);
    }

    /** 并行模式：所有分支达到 BLOCK_TICKING 后登记各自 scheduler state。 */
    static void startParallel(MinecraftServer server, EvaluationSession session) {
        for (EvaluationBranch branch : session.parallelBranches()) {
            STATES.put(branch.branchId(), new State());
        }
        CreateCMPOR.LOGGER.info("评估会话 {} 进入并行评估：{} 个分支",
                session.id(), session.parallelBranches().size());
    }

    /** 并行模式：每个 server tick 推进所有分支。 */
    static void tickParallel(MinecraftServer server, EvaluationSavedData data, EvaluationSession session) {
        boolean barrierApplied = false;
        boolean allStartingReady = true;
        for (EvaluationBranch branch : session.parallelBranches()) {
            if (branch.result() != null) {
                continue;
            }
            State state = STATES.computeIfAbsent(branch.branchId(), ignored -> new State());
            if (state.phase != Phase.STARTING) {
                continue;
            }
            if (!state.entityTickingApplied) {
                EvaluationManifest manifest = branch.manifest();
                ServerLevel target = server.getLevel(manifest.targetDimension());
                if (target == null) {
                    branch.setResult(EvaluationVerdict.reject(
                            "目标维度未加载", manifest.targetDimension().location().toString()));
                    continue;
                }
                EvaluationTicketManager.switchToEntityTicking(target, manifest);
                state.entityTickingApplied = true;
                barrierApplied = true;
            }
            ServerLevel target = server.getLevel(branch.manifest().targetDimension());
            if (target == null) {
                branch.setResult(EvaluationVerdict.reject(
                        "目标维度未加载", branch.manifest().targetDimension().location().toString()));
                allStartingReady = false;
            } else if (!EvaluationTicketManager.allEntityTickingReady(target, branch.manifest())) {
                allStartingReady = false;
            }
        }
        if (barrierApplied || !allStartingReady) {
            return;
        }

        boolean allDone = true;
        boolean rejected = false;
        for (EvaluationBranch branch : session.parallelBranches()) {
            if (branch.result() != null) {
                if (branch.result().rejected()) {
                    rejected = true;
                }
                continue;
            }
            allDone = false;
            tickParallelBranch(server, data, session, branch);
            if (branch.result() != null && branch.result().rejected()) {
                rejected = true;
            }
        }
        if (rejected) {
            EvaluationCloneManager.INSTANCE.requestCleanup(
                    server, data, session, "message.createcmpor.evaluation.runtime_failed");
            return;
        }
        if (!allDone) {
            return;
        }
        session.branchResults().clear();
        EvaluationVerdict.Result last = null;
        for (EvaluationBranch branch : session.parallelBranches()) {
            session.branchResults().add(branch.result());
            last = branch.result();
        }
        session.setEvaluationResult(last);
        session.setState(EvaluationSession.State.SOLIDIFYING);
        data.changed();
        EvaluationCloneManager.syncCritical(server, data);
        notifyOwner(server, session, Component.literal("并行评估全部完成，开始固化"));
    }

    private static void tickParallelBranch(MinecraftServer server, EvaluationSavedData data,
                                           EvaluationSession session, EvaluationBranch branch) {
        State state = STATES.computeIfAbsent(branch.branchId(), ignored -> new State());
        EvaluationManifest manifest = branch.manifest();
        ServerLevel target = server.getLevel(manifest.targetDimension());
        if (target == null) {
            branch.setResult(EvaluationVerdict.reject("目标维度未加载", manifest.targetDimension().location().toString()));
            return;
        }
        switch (state.phase) {
            case STARTING -> tickParallelStarting(server, data, session, branch, target, state);
            case WARMING -> tickParallelWarming(server, data, session, branch, target, state);
            case SAMPLING -> tickParallelSampling(server, data, session, branch, target, state);
            case FINISHING -> tickParallelFinishing(server, data, session, branch, target, state);
            case VERDICTING -> tickParallelVerdicting(server, data, session, branch, target, state);
        }
    }

    private static String parallelTraceKey(EvaluationBranch branch) {
        return "branch:" + branch.branchId();
    }

    private static void tickParallelStarting(MinecraftServer server, EvaluationSavedData data,
                                             EvaluationSession session, EvaluationBranch branch,
                                             ServerLevel target, State state) {
        EvaluationManifest manifest = branch.manifest();
        if (!state.entityTickingApplied) {
            EvaluationTicketManager.switchToEntityTicking(target, manifest);
            state.entityTickingApplied = true;
            return;
        }
        if (!EvaluationTicketManager.allEntityTickingReady(target, manifest)) {
            session.tickState();
            data.changed();
            return;
        }
        RoomInstance room = requireRoom(server, session);
        AABB bounds = room.boundaries().outerBounds();
        state.s0 = EvaluationAudit.scan(target, bounds);
        EvaluationAudit.logInventory("S0", state.s0);
        String key = parallelTraceKey(branch);
        int ioCount = activateIoBlocks(target, bounds, key, session, branch.index(), session.branchCount());
        CreateCMPOR.LOGGER.info("评估会话 {} 分支 {}/{} 已激活 {} 个 IO 方块",
                session.id(), branch.index() + 1, session.branchCount(), ioCount);

        Map<EvaluationTrace.FlowKey, Long> floor = new HashMap<>();
        for (ItemEntity itemEntity : target.getEntitiesOfClass(ItemEntity.class, bounds)) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(itemEntity.getItem().getItem());
            floor.merge(EvaluationTrace.FlowKey.item(id), (long) itemEntity.getItem().getCount(), Long::sum);
        }
        state.floorItems = floor;
        if (!floor.isEmpty()) {
            CreateCMPOR.LOGGER.info("评估会话 {} 分支 {}/{} 地板掉落物 S0：{}",
                    session.id(), branch.index() + 1, session.branchCount(), floor);
        }

        state.phaseStartTick = target.getGameTime();
        int warmupSeconds = Config.RECORD_START.get();
        if (warmupSeconds > 0) {
            state.phase = Phase.WARMING;
            if (branch.index() == 0) {
                notifyEvaluatorCountdown(server, session, EvaluatorBlockEntity.EvaluationStage.WARMING, warmupSeconds);
            }
        } else {
            beginParallelSampling(server, target, session, branch, state);
        }
        notifyOwner(server, session, Component.translatable(
                "message.createcmpor.evaluation.evaluation_started",
                Config.EVALUATE_SECONDS.get(), warmupSeconds));
    }

    private static void beginParallelSampling(MinecraftServer server, ServerLevel target,
                                              EvaluationSession session, EvaluationBranch branch, State state) {
        int seconds = Config.EVALUATE_SECONDS.get();
        if (branch.index() == 0) {
            notifyEvaluatorCountdown(server, session, EvaluatorBlockEntity.EvaluationStage.SAMPLING, seconds);
        }
        String key = parallelTraceKey(branch);
        EvaluationTrace.Hub.INSTANCE.start(key, seconds, target.getGameTime());
        EvaluationTrace.Hub.INSTANCE.setFloorItems(key, state.floorItems);
        state.phaseStartTick = target.getGameTime();
        state.phase = Phase.SAMPLING;
    }

    private static void tickParallelWarming(MinecraftServer server, EvaluationSavedData data,
                                            EvaluationSession session, EvaluationBranch branch,
                                            ServerLevel target, State state) {
        int warmupSeconds = Config.RECORD_START.get();
        if (target.getGameTime() - state.phaseStartTick < warmupSeconds * 20L) {
            return;
        }
        RoomInstance room = requireRoom(server, session);
        state.warmup = EvaluationAudit.scan(target, room.boundaries().outerBounds());
        EvaluationAudit.logInventory("S_warmup", state.warmup);
        beginParallelSampling(server, target, session, branch, state);
    }

    private static void tickParallelSampling(MinecraftServer server, EvaluationSavedData data,
                                             EvaluationSession session, EvaluationBranch branch,
                                             ServerLevel target, State state) {
        int seconds = Config.EVALUATE_SECONDS.get();
        if (target.getGameTime() - state.phaseStartTick < seconds * 20L) {
            long elapsed = target.getGameTime() - state.phaseStartTick;
            if (elapsed > 0 && elapsed % 100 == 0) {
                CreateCMPOR.LOGGER.info("评估会话 {} 分支 {}/{} 采样诊断 [{}s/{}s]：{}",
                        session.id(), branch.index() + 1, session.branchCount(), elapsed / 20, seconds,
                        EvaluationTrace.Hub.INSTANCE.stats(parallelTraceKey(branch)));
            }
            session.tickState();
            data.changed();
            return;
        }
        state.phase = Phase.FINISHING;
    }

    private static void tickParallelFinishing(MinecraftServer server, EvaluationSavedData data,
                                              EvaluationSession session, EvaluationBranch branch,
                                              ServerLevel target, State state) {
        RoomInstance room = requireRoom(server, session);
        AABB bounds = room.boundaries().outerBounds();
        deactivateIoBlocks(target, bounds);
        state.s1 = EvaluationAudit.scan(target, bounds);
        EvaluationAudit.logInventory("S1", state.s1);
        state.stressProfile = StressEvaluationRegistry.consume(parallelTraceKey(branch));
        if (!state.stressProfile.isEmpty()) {
            CreateCMPOR.LOGGER.info("评估会话 {} 分支 {}/{} 应力评估结果：inputSU={} outputSU={}",
                    session.id(), branch.index() + 1, session.branchCount(),
                    state.stressProfile.inputSU(), state.stressProfile.outputSU());
        }
        state.phase = Phase.VERDICTING;
    }

    private static void tickParallelVerdicting(MinecraftServer server, EvaluationSavedData data,
                                               EvaluationSession session, EvaluationBranch branch,
                                               ServerLevel target, State state) {
        EvaluationManifest manifest = branch.manifest();
        EvaluationTrace trace = EvaluationTrace.Hub.INSTANCE.get(parallelTraceKey(branch));
        EvaluationVerdict.Result result;
        if (trace == null) {
            result = EvaluationVerdict.reject("采样数据缺失", "trace_missing");
        } else {
            result = EvaluationVerdict.decide(
                    trace, state.s0, state.s1, state.warmup, state.stressProfile);
        }
        EvaluationTicketManager.switchToBlockTicking(target, manifest);
        branch.setResult(result);
        session.setEvaluationResult(result);
        CreateCMPOR.LOGGER.info("评估会话 {} 分支 {}/{} 结论 {}：{}",
                session.id(), branch.index() + 1, session.branchCount(),
                result.verdict(), result.detail());
        cleanup(branch.branchId(), parallelTraceKey(branch));
    }

    private static void tickStarting(MinecraftServer server, EvaluationSavedData data,
                                     EvaluationSession session, ServerLevel target, State state) {
        EvaluationManifest manifest = session.manifest();
        if (!state.entityTickingApplied) {
            EvaluationTicketManager.switchToEntityTicking(target, manifest);
            state.entityTickingApplied = true;
            return;
        }
        if (!EvaluationTicketManager.allEntityTickingReady(target, manifest)) {
            session.tickState();
            data.changed();
            return;
        }
        RoomInstance room = requireRoom(server, session);
        AABB bounds = room.boundaries().outerBounds();
        // S0 初次扫描必须在 IO 激活前：代表房间"未开工"的初始态（防刷兜底基准，过滤中间产物用）
        state.s0 = EvaluationAudit.scan(target, bounds);
        EvaluationAudit.logInventory("S0", state.s0);
        int ioCount = activateIoBlocks(target, bounds, session.roomCode(), session);
        CreateCMPOR.LOGGER.info("评估会话 {} 已激活 {} 个 IO 方块", session.id(), ioCount);

        Map<EvaluationTrace.FlowKey, Long> floor = new HashMap<>();
        for (ItemEntity itemEntity : target.getEntitiesOfClass(ItemEntity.class, bounds)) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(itemEntity.getItem().getItem());
            floor.merge(EvaluationTrace.FlowKey.item(id), (long) itemEntity.getItem().getCount(), Long::sum);
        }
        state.floorItems = floor;
        if (!floor.isEmpty()) {
            CreateCMPOR.LOGGER.info("评估会话 {} 地板掉落物 S0：{}", session.id(), floor);
        }

        state.phaseStartTick = target.getGameTime();
        int warmupSeconds = Config.RECORD_START.get();
        if (warmupSeconds > 0) {
            state.phase = Phase.WARMING;
            // 预热开始：一次性通知主世界评估方块（剩余 RECORD_START 秒）
            notifyEvaluatorCountdown(server, session, EvaluatorBlockEntity.EvaluationStage.WARMING, warmupSeconds);
        } else {
            beginSampling(server, target, session, state);
        }
        notifyOwner(server, session, Component.translatable(
                "message.createcmpor.evaluation.evaluation_started",
                Config.EVALUATE_SECONDS.get(), warmupSeconds));
        CreateCMPOR.LOGGER.info("评估会话 {} 评估启动：{} 秒（预热 {} 秒）",
                session.id(), Config.EVALUATE_SECONDS.get(), warmupSeconds);
    }

    /** 采样起点：trace 从此刻开始计时（预热期数据不混入）。 */
    private static void beginSampling(MinecraftServer server, ServerLevel target,
                                      EvaluationSession session, State state) {
        int seconds = Config.EVALUATE_SECONDS.get();
        // 通知主世界评估方块：进入采样阶段（剩余 EVALUATE_SECONDS 秒）
        notifyEvaluatorCountdown(server, session, EvaluatorBlockEntity.EvaluationStage.SAMPLING, seconds);
        EvaluationTrace.Hub.INSTANCE.start(session.roomCode(), seconds, target.getGameTime());
        // trace 建立后再写入地板掉落物快照（此前 setFloorItems 找不到 trace 会被丢弃）
        EvaluationTrace.Hub.INSTANCE.setFloorItems(session.roomCode(), state.floorItems);
        state.phaseStartTick = target.getGameTime();
        state.phase = Phase.SAMPLING;
    }

    private static void tickWarming(MinecraftServer server, EvaluationSavedData data,
                                    EvaluationSession session, ServerLevel target, State state) {
        int warmupSeconds = Config.RECORD_START.get();
        if (target.getGameTime() - state.phaseStartTick < warmupSeconds * 20L) {
            return;
        }
        RoomInstance room = requireRoom(server, session);
        state.warmup = EvaluationAudit.scan(target, room.boundaries().outerBounds());
        EvaluationAudit.logInventory("S_warmup", state.warmup);
        beginSampling(server, target, session, state);
        notifyOwner(server, session, Component.translatable("message.createcmpor.evaluation.warmup_done"));
        CreateCMPOR.LOGGER.info("评估会话 {} 预热结束，进入正式采样", session.id());
    }

    private static void tickSampling(MinecraftServer server, EvaluationSavedData data,
                                     EvaluationSession session, ServerLevel target, State state) {
        int seconds = Config.EVALUATE_SECONDS.get();
        if (target.getGameTime() - state.phaseStartTick < seconds * 20L) {
            // 诊断：每 5 秒打印一次采样摘要（排查流体/FE 不记录）
            long elapsed = target.getGameTime() - state.phaseStartTick;
            if (elapsed > 0 && elapsed % 100 == 0) {
                CreateCMPOR.LOGGER.info("评估会话 {} 采样诊断 [{}s/{}s]：{}",
                        session.id(), elapsed / 20, seconds,
                        EvaluationTrace.Hub.INSTANCE.stats(session.roomCode()));
            }
            session.tickState();
            data.changed();
            return;
        }
        state.phase = Phase.FINISHING;
        notifyOwner(server, session, Component.translatable("message.createcmpor.evaluation.sampling_done"));
        CreateCMPOR.LOGGER.info("评估会话 {} 采样结束，开始收尾", session.id());
    }

    private static void tickFinishing(MinecraftServer server, EvaluationSavedData data,
                                      EvaluationSession session, ServerLevel target, State state) {
        RoomInstance room = requireRoom(server, session);
        AABB bounds = room.boundaries().outerBounds();
        deactivateIoBlocks(target, bounds);
        state.s1 = EvaluationAudit.scan(target, bounds);
        EvaluationAudit.logInventory("S1", state.s1);
        state.stressProfile = StressEvaluationRegistry.consume(session.roomCode());
        if (!state.stressProfile.isEmpty()) {
            CreateCMPOR.LOGGER.info("评估会话 {} 应力评估结果：inputSU={} outputSU={}",
                    session.id(), state.stressProfile.inputSU(), state.stressProfile.outputSU());
        }
        state.phase = Phase.VERDICTING;
    }

    private static void tickVerdicting(MinecraftServer server, EvaluationSavedData data,
                                       EvaluationSession session, ServerLevel target, State state) {
        EvaluationManifest manifest = session.manifest();
        EvaluationTrace trace = EvaluationTrace.Hub.INSTANCE.get(session.roomCode());
        if (trace == null) {
            state.result = EvaluationVerdict.reject("采样数据缺失", "trace_missing");
        } else {
            state.result = EvaluationVerdict.decide(
                    trace, state.s0, state.s1, state.warmup, state.stressProfile);
        }
        EvaluationTicketManager.switchToBlockTicking(target, manifest);
        session.setEvaluationResult(state.result);
        if (state.result.rejected()) {
            // REJECTED 直接回滚
            session.setState(EvaluationSession.State.ROLLING_BACK);
            data.changed();
            EvaluationCloneManager.syncCritical(server, data);
            notifyOwner(server, session, Component.literal("评估结论：" + state.result.verdict()
                    + "（" + state.result.rejectReason() + "）"));
            CreateCMPOR.LOGGER.info("评估会话 {} 结论 {}：{}",
                    session.id(), state.result.verdict(), state.result.detail());
            cleanup(session.id(), session.roomCode());
            return;
        }
        // 多分支评估：还有分支则清理副本并重新克隆（A2：每分支独立克隆），否则固化
        boolean hasNextBranch = session.advanceBranch(state.result);
        CreateCMPOR.LOGGER.info("评估会话 {} 分支 {}/{} 结论 {}：{}",
                session.id(), session.branchIndex(), session.branchCount(),
                state.result.verdict(), state.result.detail());
        if (hasNextBranch) {
            // 进入 CLEANING 清理副本；完成后由 CloneManager 判断还有分支 → 回 STAGING_SOURCE
            session.setState(EvaluationSession.State.CLEANING);
            data.changed();
            EvaluationCloneManager.syncCritical(server, data);
            notifyOwner(server, session, Component.literal("分支 " + session.branchIndex()
                    + "/" + session.branchCount() + " 评估完成，开始下一分支"));
            cleanup(session.id(), session.roomCode());
            return;
        }
        // 全部分支完成：进入固化流程（SOLIDIFYING 由 CloneManager 编排）
        session.setState(EvaluationSession.State.SOLIDIFYING);
        data.changed();
        EvaluationCloneManager.syncCritical(server, data);
        notifyOwner(server, session, Component.literal("评估结论：" + state.result.verdict()));
        CreateCMPOR.LOGGER.info("评估会话 {} 全部分支完成，结论 {}：{}",
                session.id(), state.result.verdict(), state.result.detail());
        cleanup(session.id(), session.roomCode());
    }

    private static int activateIoBlocks(ServerLevel target, AABB bounds, String roomCode, EvaluationSession session) {
        return activateIoBlocks(target, bounds, roomCode, session,
                session.branchIndex(), session.branchCount());
    }

    private static int activateIoBlocks(ServerLevel target, AABB bounds, String roomCode,
                                        EvaluationSession session, int branchIndex, int branchCount) {
        int[] count = new int[1];
        forEachBlock(target, bounds, (pos, state) -> {
            Block block = state.getBlock();
            if (block == ModBlocks.INPUT.get() || block == ModBlocks.OUTPUT.get()) {
                target.setBlock(pos, state.setValue(BaseIOBlock.ACTIVE, true), Block.UPDATE_CLIENTS);
                // 源房间放置 IO 方块时 roomCode 可能为空（旧 scanRoom 由评估流程补写）；
                // 副本 BE 必须绑定房间码，否则 isActive() 恒为 false、能力全部拒绝。
                if (target.getBlockEntity(pos) instanceof BaseIOBlockEntity entity) {
                    entity.setRoomCode(roomCode);
                    // 诊断：打印 IO 方块详情（白名单是否完整复制）
                    CreateCMPOR.LOGGER.info("评估会话 IO 激活 {} {} 白名单: {}",
                            block == ModBlocks.INPUT.get() ? "输入" : "输出", pos, entity.describeIoFilter());
                }
                count[0]++;
            } else if (block == ModBlocks.PARALLEL_INPUT.get()) {
                // 并行空间输入方块：激活 + 绑定房间码 + 设置当前分支索引（只暴露第 N 个物品）
                target.setBlock(pos, state.setValue(BaseIOBlock.ACTIVE, true), Block.UPDATE_CLIENTS);
                if (target.getBlockEntity(pos) instanceof ParallelInputBlockEntity entity) {
                    entity.setRoomCode(roomCode);
                    entity.setBranchIndex(branchIndex);
                    // 首个分支时读取配置物品数作为总分支数（兼容旧会话启动路径）
                    if (branchIndex == 0 && branchCount <= 1 && entity.getBranchIndex() == 0) {
                        int itemCount = entity.configuredItemCount();
                        if (itemCount > 1) {
                            session.setBranchCount(itemCount);
                            CreateCMPOR.LOGGER.info("评估会话 {} 并行空间输入方块 @{} 配置 {} 个物品，启用多分支评估",
                                    session.id(), pos, itemCount);
                        }
                    }
                }
                count[0]++;
            } else if (block == ModBlocks.STRESS_INPUT.get()) {
                target.setBlock(pos, state.setValue(StressInputBlock.ACTIVE, true), Block.UPDATE_CLIENTS);
                if (target.getBlockEntity(pos) instanceof StressInputBlockEntity entity) {
                    entity.bindEvaluation(roomCode);
                }
            } else if (block == ModBlocks.STRESS_OUTPUT.get()) {
                target.setBlock(pos, state.setValue(StressOutputBlock.ACTIVE, true), Block.UPDATE_CLIENTS);
                if (target.getBlockEntity(pos) instanceof StressOutputBlockEntity entity) {
                    entity.bindEvaluation(roomCode);
                }
            }
        });
        return count[0];
    }

    private static void deactivateIoBlocks(ServerLevel target, AABB bounds) {
        forEachBlock(target, bounds, (pos, state) -> {
            Block block = state.getBlock();
            if (block == ModBlocks.INPUT.get() || block == ModBlocks.OUTPUT.get()) {
                target.setBlock(pos, state.setValue(BaseIOBlock.ACTIVE, false), Block.UPDATE_CLIENTS);
            } else if (block == ModBlocks.PARALLEL_INPUT.get()) {
                target.setBlock(pos, state.setValue(BaseIOBlock.ACTIVE, false), Block.UPDATE_CLIENTS);
            } else if (block == ModBlocks.STRESS_INPUT.get()) {
                target.setBlock(pos, state.setValue(StressInputBlock.ACTIVE, false), Block.UPDATE_CLIENTS);
            } else if (block == ModBlocks.STRESS_OUTPUT.get()) {
                target.setBlock(pos, state.setValue(StressOutputBlock.ACTIVE, false), Block.UPDATE_CLIENTS);
            }
        });
    }

    private static void forEachBlock(ServerLevel target, AABB bounds, BlockVisitor visitor) {
        int startX = (int) Math.floor(bounds.minX);
        int startY = (int) Math.floor(bounds.minY);
        int startZ = (int) Math.floor(bounds.minZ);
        int endX = (int) Math.floor(bounds.maxX - 1.0E-5);
        int endY = (int) Math.floor(bounds.maxY - 1.0E-5);
        int endZ = (int) Math.floor(bounds.maxZ - 1.0E-5);
        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    visitor.visit(pos, target.getBlockState(pos));
                }
            }
        }
    }

    private static RoomInstance requireRoom(MinecraftServer server, EvaluationSession session) {
        return CompactMachines.room(server, session.roomCode()).orElseThrow(() ->
                new IllegalStateException("源房间不存在：" + session.roomCode()));
    }

    private static void notifyOwner(MinecraftServer server, EvaluationSession session, Component message) {
        var owner = server.getPlayerList().getPlayer(session.owner());
        if (owner != null) {
            owner.displayClientMessage(message, false);
        }
    }

    /** 通知主世界评估方块进入指定倒计时阶段（读配置预设秒数）。 */
    private static void notifyEvaluatorCountdown(MinecraftServer server, EvaluationSession session,
                                                 EvaluatorBlockEntity.EvaluationStage stage, int seconds) {
        GlobalPos machinePos = session.machinePos();
        ServerLevel level = server.getLevel(machinePos.dimension());
        if (level == null || !level.isLoaded(machinePos.pos())) {
            return;
        }
        if (level.getBlockEntity(machinePos.pos()) instanceof EvaluatorBlockEntity evaluator) {
            evaluator.beginCountdown(stage, seconds);
        }
    }

    @FunctionalInterface
    private interface BlockVisitor {
        void visit(BlockPos pos, BlockState state);
    }
}
