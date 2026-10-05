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
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        /**
         * T3：本分支（串行） / 本 lane（并行）激活 IO 方块时采集到的触发物品身份签名。
         * <p>去重、保持方块扫描顺序；由 {@code activateIoBlocks} 写入，随 {@link EvaluationVerdict.Result} 一并保存。</p>
         */
        final List<String> triggerItems = new ArrayList<>();
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
        EvaluationVerdict.Result rejectedResult = null;
        for (EvaluationBranch branch : session.parallelBranches()) {
            if (branch.result() != null) {
                if (branch.result().rejected()) {
                    rejected = true;
                    rejectedResult = rejectedResult == null ? branch.result() : rejectedResult;
                }
                continue;
            }
            allDone = false;
            tickParallelBranch(server, data, session, branch);
            if (branch.result() != null && branch.result().rejected()) {
                rejected = true;
                rejectedResult = rejectedResult == null ? branch.result() : rejectedResult;
            }
        }
        if (rejected) {
            // 并行评估失败（同样"评估结束"）：不等清理完成就先送出观察者
            EvaluationObservationManager.exitObserversOf(server, session.id(),
                    "message.createcmpor.observation.finished");
            // T3：失败提示必须说明是哪条分支，物品用本地化显示名（47 文档 §5.4；整场放弃语义不变）
            notifyOwner(server, session, branchFailedMessage(rejectedResult));
            CreateCMPOR.LOGGER.info("评估会话 {} 并行评估中止：{}",
                    session.id(), rejectedResult == null ? "未知原因" : rejectedResult.rejectReason());
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
        // 并行评估已全部结束、副本不再需要观察：同样在固化开始时先踢出观察者
        EvaluationObservationManager.exitObserversOf(server, session.id(),
                "message.createcmpor.observation.finished");
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
        int ioCount = activateIoBlocks(target, bounds, key, session, branch.index(), session.branchCount(),
                state.triggerItems);
        CreateCMPOR.LOGGER.info("评估会话 {} 分支 {}/{} 已激活 {} 个 IO 方块（触发物品：{}）",
                session.id(), branch.index() + 1, session.branchCount(), ioCount,
                state.triggerItems.isEmpty() ? "无（默认模式）" : state.triggerItems);

        Map<EvaluationTrace.FlowKey, Long> floor = new HashMap<>();
        for (ItemEntity itemEntity : target.getEntitiesOfClass(ItemEntity.class, bounds)) {
            ItemStack floorStack = itemEntity.getItem();
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(floorStack.getItem());
            // 掉落物同样按身份签名记账（组件变体各自成桶）
            floor.merge(EvaluationTrace.FlowKey.item(id,
                            com.yansunsky.createcmpor.evaluation.ItemIdentity.of(
                                    floorStack, target.registryAccess())),
                    (long) floorStack.getCount(), Long::sum);
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
            result = EvaluationVerdict.reject("采样数据缺失", "trace_missing", state.triggerItems);
        } else {
            result = EvaluationVerdict.decide(
                    trace, state.s0, state.s1, state.warmup, state.stressProfile, state.triggerItems);
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
        int ioCount = activateIoBlocks(target, bounds, session.roomCode(), session, state.triggerItems);
        CreateCMPOR.LOGGER.info("评估会话 {} 已激活 {} 个 IO 方块（分支 {}/{} 触发物品：{}）",
                session.id(), ioCount, session.branchIndex() + 1, session.branchCount(),
                state.triggerItems.isEmpty() ? "无（默认模式）" : state.triggerItems);

        Map<EvaluationTrace.FlowKey, Long> floor = new HashMap<>();
        for (ItemEntity itemEntity : target.getEntitiesOfClass(ItemEntity.class, bounds)) {
            ItemStack floorStack = itemEntity.getItem();
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(floorStack.getItem());
            floor.merge(EvaluationTrace.FlowKey.item(id,
                            com.yansunsky.createcmpor.evaluation.ItemIdentity.of(
                                    floorStack, target.registryAccess())),
                    (long) floorStack.getCount(), Long::sum);
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
            state.result = EvaluationVerdict.reject("采样数据缺失", "trace_missing", state.triggerItems);
        } else {
            state.result = EvaluationVerdict.decide(
                    trace, state.s0, state.s1, state.warmup, state.stressProfile, state.triggerItems);
        }
        EvaluationTicketManager.switchToBlockTicking(target, manifest);
        session.setEvaluationResult(state.result);
        if (state.result.rejected()) {
            // REJECTED 直接回滚
            session.setState(EvaluationSession.State.ROLLING_BACK);
            data.changed();
            EvaluationCloneManager.syncCritical(server, data);
            // 评估已被拒绝（同样"评估结束"）：不等回滚完成就先送出观察者
            EvaluationObservationManager.exitObserversOf(server, session.id(),
                    "message.createcmpor.observation.finished");
            // T3：失败提示必须说清是哪条分支（物品用本地化显示名，47 文档 §5.4；整场放弃语义不变）
            notifyOwner(server, session, branchFailedMessage(state.result));
            CreateCMPOR.LOGGER.info("评估会话 {} 分支 {}/{} 结论 {}：{}",
                    session.id(), session.branchIndex() + 1, session.branchCount(),
                    state.result.verdict(), state.result.detail());
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
        // 评估已结束、副本不再需要观察：**在此刻先踢出观察者**，而不等清理完成。
        // 原因：CLEANING 阶段会等待区块 idle / IO future / 清理校验，可能耗时很久甚至卡住
        // （表现为"评估完成、工厂已固化、副本清理中"之后玩家仍留在副本里）。
        EvaluationObservationManager.exitObserversOf(server, session.id(),
                "message.createcmpor.observation.finished");
        notifyOwner(server, session, Component.literal("评估结论：" + state.result.verdict()));
        CreateCMPOR.LOGGER.info("评估会话 {} 全部分支完成，结论 {}：{}",
                session.id(), state.result.verdict(), state.result.detail());
        cleanup(session.id(), session.roomCode());
    }

    private static int activateIoBlocks(ServerLevel target, AABB bounds, String roomCode,
                                        EvaluationSession session, List<String> triggerItemsOut) {
        return activateIoBlocks(target, bounds, roomCode, session,
                session.branchIndex(), session.branchCount(), triggerItemsOut);
    }

    /**
     * 激活副本内的全部 IO 方块，并采集本分支的触发物品。
     *
     * @param triggerItemsOut T3：输出参数——本分支（并行时本 lane）内所有 {@code PARALLEL_INPUT}
     *                        方块在该 {@code branchIndex} 处暴露的物品身份签名（去重、越界忽略、
     *                        保持方块扫描顺序）。空 = 无并行方块（默认模式）。
     */
    private static int activateIoBlocks(ServerLevel target, AABB bounds, String roomCode,
                                        EvaluationSession session, int branchIndex, int branchCount,
                                        List<String> triggerItemsOut) {
        int[] count = new int[1];
        Set<String> triggerItems = new LinkedHashSet<>();
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
                    // T3 触发物品采集：分支索引设置完成后，取该分支实际暴露的物品身份签名。
                    // 走公开的 item handler（与真实抽取同一来源，含白名单登记形态模板）：
                    // 越界条目（branchIndex ≥ 配置数）→ getStackInSlot(0) 返回空 → 忽略。
                    // 注意：并行模式下 roomCode 被替换成 "branch:<uuid>" 合成键，与本采集无关。
                    ItemStack exposed = entity.getItemHandler().getStackInSlot(0);
                    String signature = ItemIdentity.of(exposed, target.registryAccess());
                    if (!signature.isEmpty()) {
                        triggerItems.add(signature);
                    }
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
        triggerItemsOut.clear();
        triggerItemsOut.addAll(triggerItems);
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

    // ===== T3：失败提示本地化（47 文档 §5.4；提示必须说清是哪条分支，物品用本地化显示名）=====

    /**
     * {@code 输入为「沙子」的分支评估失败（原因）}。
     *
     * <p>整场放弃的语义不变，仅把提示换成可本地化的两参文案：{@code %1$s} = 触发物品显示名
     * （多物品已用本地化分隔符拼好；无触发物品 → lang 的"（无输入）"），{@code %2$s} = 失败原因。</p>
     */
    private static Component branchFailedMessage(EvaluationVerdict.Result result) {
        List<String> triggers = result == null ? List.of() : result.triggerItems();
        return Component.translatable("message.createcmpor.evaluation.branch_failed",
                triggerItemsComponent(triggers),
                rejectReasonComponent(result == null ? null : result.rejectReason()));
    }

    /** 触发物品显示名拼接：无 → 本地化"（无输入）"；多物品 → 本地化分隔符按序拼接。 */
    private static Component triggerItemsComponent(List<String> triggerItems) {
        if (triggerItems == null || triggerItems.isEmpty()) {
            return Component.translatable("message.createcmpor.evaluation.no_input");
        }
        MutableComponent joined = Component.empty();
        for (int index = 0; index < triggerItems.size(); index++) {
            if (index > 0) {
                joined.append(Component.translatable("message.createcmpor.evaluation.trigger_separator"));
            }
            joined.append(triggerItemName(triggerItems.get(index)));
        }
        return joined;
    }

    /** 身份签名 → 本地化显示名（{@link ItemIdentity} 只保留 id + 组件摘要 ⇒ 组件变体只能取回基础物品名）。 */
    private static Component triggerItemName(String signature) {
        ResourceLocation id = ItemIdentity.idOf(signature);
        Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return Component.literal(signature == null ? "" : signature);
        }
        return new ItemStack(item).getHoverName();
    }

    /** 失败原因：{@code message.*} 形式的按本地化键解析，其余（历史中文文案）按字面量呈现。 */
    private static Component rejectReasonComponent(String reason) {
        if (reason == null || reason.isBlank()) {
            return Component.literal(EvaluationVerdict.VERDICT_REJECTED);
        }
        return reason.startsWith("message.") ? Component.translatable(reason) : Component.literal(reason);
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
