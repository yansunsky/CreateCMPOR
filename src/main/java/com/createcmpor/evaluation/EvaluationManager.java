package com.createcmpor.evaluation;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.compat.cm.CMAdapterV7;
import com.createcmpor.compat.cm.ICompactMachinesAdapter;
import com.createcmpor.compat.cm.RoomCloner;
import dev.compactmods.machines.api.room.RoomDimensions;
import dev.compactmods.machines.api.room.RoomInstance;
import dev.compactmods.machines.api.room.template.RoomTemplate;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 平行房间评估会话管理器。
 *
 * <p>Phase 3 使用纯内存状态表。服务器重启后未完成会话直接丢弃，后续阶段再补充恢复/回滚策略。</p>
 */
public final class EvaluationManager {

    public static final EvaluationManager INSTANCE = new EvaluationManager();

    private final ICompactMachinesAdapter cmAdapter = new CMAdapterV7();
    private final RoomCloner roomCloner = new RoomCloner();
    private final Map<GlobalPos, EvaluationSession> sessionsByMachine = new HashMap<>();

    private EvaluationManager() {
    }

    public boolean isMachineLocked(GlobalPos machinePos) {
        EvaluationSession session = sessionsByMachine.get(machinePos);
        return session != null && session.isActive();
    }

    public Optional<EvaluationSession> sessionAt(GlobalPos machinePos) {
        return Optional.ofNullable(sessionsByMachine.get(machinePos));
    }

    /**
     * 启动一次 Phase 3 评估会话：锁定原机器并复制房间。
     */
    public EvaluationSession startPhase3Clone(ServerPlayer player, GlobalPos machinePos, String sourceRoomCode) {
        EvaluationSession existing = sessionsByMachine.get(machinePos);
        if (existing != null && existing.isActive()) {
            return existing;
        }

        EvaluationSession session = new EvaluationSession(UUID.randomUUID(), player.getUUID(), machinePos, sourceRoomCode);
        sessionsByMachine.put(machinePos, session);

        try {
            session.setState(EvaluationSession.State.LOCKING);
            session.setState(EvaluationSession.State.CLONING);
            RoomInstance evaluationRoom = cloneRoom(player.server, sourceRoomCode, player.getUUID());
            session.setEvaluationRoomCode(evaluationRoom.code());
            session.setState(EvaluationSession.State.DONE);
            CreateCMPOR.LOGGER.info("评估会话 {} 已完成 Phase 3 复制：{} -> {}", session.id(), sourceRoomCode, evaluationRoom.code());
            return session;
        } catch (Exception exception) {
            Component reason = Component.literal("评估启动失败：" + exception.getMessage());
            session.fail(reason);
            sessionsByMachine.remove(machinePos);
            CreateCMPOR.LOGGER.error("评估会话 {} 启动失败", session.id(), exception);
            return session;
        }
    }

    private RoomInstance cloneRoom(MinecraftServer server, String sourceRoomCode, UUID owner) {
        RoomInstance sourceRoom = cmAdapter.getRoom(server, sourceRoomCode)
                .orElseThrow(() -> new IllegalArgumentException("找不到 CompactMachines 房间：" + sourceRoomCode));

        AABB inner = sourceRoom.boundaries().innerBounds();
        int width = (int) Math.round(inner.getXsize());
        int height = (int) Math.round(inner.getYsize());
        int depth = (int) Math.round(inner.getZsize());
        RoomTemplate template = new RoomTemplate(new RoomDimensions(width, depth, height),
                sourceRoom.defaultMachineColor(), List.of(), Optional.empty());

        RoomInstance evaluationRoom = cmAdapter.createEvaluationRoom(server, template, owner);
        cmAdapter.initializeDefaultSpawn(evaluationRoom);

        UUID ticketId = UUID.randomUUID();
        try {
            cmAdapter.setRoomForced(sourceRoom, ticketId, true);
            cmAdapter.setRoomForced(evaluationRoom, ticketId, true);
            loadChunks(sourceRoom);
            loadChunks(evaluationRoom);

            var snapshot = roomCloner.snapshot(cmAdapter.getRoomLevel(sourceRoom), sourceRoom.boundaries());
            roomCloner.apply(cmAdapter.getRoomLevel(evaluationRoom), evaluationRoom.boundaries(), snapshot);
            return evaluationRoom;
        } finally {
            cmAdapter.setRoomForced(sourceRoom, ticketId, false);
            cmAdapter.setRoomForced(evaluationRoom, ticketId, false);
        }
    }

    private void loadChunks(RoomInstance room) {
        cmAdapter.getInnerChunks(room.boundaries()).forEach(chunkPos -> room.level().getChunk(chunkPos.x, chunkPos.z));
    }
}
