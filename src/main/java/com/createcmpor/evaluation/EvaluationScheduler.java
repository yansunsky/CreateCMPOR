package com.createcmpor.evaluation;

import com.createcmpor.Config;
import com.createcmpor.CreateCMPOR;
import com.createcmpor.block.BaseIOBlock;
import com.createcmpor.block.BaseIOBlockEntity;
import com.createcmpor.block.StressInputBlock;
import com.createcmpor.block.StressInputBlockEntity;
import com.createcmpor.block.StressOutputBlock;
import com.createcmpor.block.StressOutputBlockEntity;
import com.createcmpor.init.ModBlocks;
import com.createcmpor.stress.StressEvaluationRegistry;
import com.createcmpor.stress.StressProfile;
import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.room.RoomInstance;
import net.minecraft.core.BlockPos;
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
        int ioCount = activateIoBlocks(target, bounds, session.roomCode());
        CreateCMPOR.LOGGER.info("评估会话 {} 已激活 {} 个 IO 方块", session.id(), ioCount);
        state.s0 = EvaluationAudit.scan(target, bounds);
        EvaluationAudit.logInventory("S0", state.s0);

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
        } else {
            beginSampling(target, session, state);
        }
        notifyOwner(server, session, Component.translatable(
                "message.createcmpor.evaluation.evaluation_started",
                Config.EVALUATE_SECONDS.get(), warmupSeconds));
        CreateCMPOR.LOGGER.info("评估会话 {} 评估启动：{} 秒（预热 {} 秒）",
                session.id(), Config.EVALUATE_SECONDS.get(), warmupSeconds);
    }

    /** 采样起点：trace 从此刻开始计时（预热期数据不混入）。 */
    private static void beginSampling(ServerLevel target, EvaluationSession session, State state) {
        int seconds = Config.EVALUATE_SECONDS.get();
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
        beginSampling(target, session, state);
        notifyOwner(server, session, Component.translatable("message.createcmpor.evaluation.warmup_done"));
        CreateCMPOR.LOGGER.info("评估会话 {} 预热结束，进入正式采样", session.id());
    }

    private static void tickSampling(MinecraftServer server, EvaluationSavedData data,
                                     EvaluationSession session, ServerLevel target, State state) {
        int seconds = Config.EVALUATE_SECONDS.get();
        if (target.getGameTime() - state.phaseStartTick < seconds * 20L) {
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
        // REJECTED 直接回滚；否则进入固化流程（SOLIDIFYING 由 CloneManager 编排）
        session.setState(state.result.rejected()
                ? EvaluationSession.State.ROLLING_BACK
                : EvaluationSession.State.SOLIDIFYING);
        data.changed();
        EvaluationCloneManager.syncCritical(server, data);
        notifyOwner(server, session, Component.literal("评估结论：" + state.result.verdict()
                + (state.result.rejected() ? "（" + state.result.rejectReason() + "）" : "")));
        CreateCMPOR.LOGGER.info("评估会话 {} 结论 {}：{}",
                session.id(), state.result.verdict(), state.result.detail());
        cleanup(session.id(), session.roomCode());
    }

    private static int activateIoBlocks(ServerLevel target, AABB bounds, String roomCode) {
        int[] count = new int[1];
        forEachBlock(target, bounds, (pos, state) -> {
            Block block = state.getBlock();
            if (block == ModBlocks.INPUT.get() || block == ModBlocks.OUTPUT.get()) {
                target.setBlock(pos, state.setValue(BaseIOBlock.ACTIVE, true), Block.UPDATE_CLIENTS);
                // 源房间放置 IO 方块时 roomCode 可能为空（旧 scanRoom 由评估流程补写）；
                // 副本 BE 必须绑定房间码，否则 isActive() 恒为 false、能力全部拒绝。
                if (target.getBlockEntity(pos) instanceof BaseIOBlockEntity entity) {
                    entity.setRoomCode(roomCode);
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

    @FunctionalInterface
    private interface BlockVisitor {
        void visit(BlockPos pos, BlockState state);
    }
}
