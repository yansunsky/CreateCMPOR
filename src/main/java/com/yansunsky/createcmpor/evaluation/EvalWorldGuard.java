package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.dimension.CompactDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.Optional;

/** 防止玩家进入已冻结的 CompactMachines 房间，并在评估副本清理时清空受影响的玩家票据。 */
public final class EvalWorldGuard {
    private static final int OP_PERMISSION_LEVEL = 2;
    private EvalWorldGuard() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel evalWorld = event.getServer().getLevel(CreateCMPOR.EVAL_WORLD);
        if (evalWorld != null) {
            ejectPlayersIfNeeded(evalWorld);
        }
        for (int index = 0; index < ParallelEvaluationWorlds.LANE_COUNT; index++) {
            ServerLevel lane = event.getServer().getLevel(ParallelEvaluationWorlds.lane(index));
            if (lane != null) {
                ejectPlayersIfNeeded(lane);
            }
        }

        ServerLevel compactWorld = event.getServer().getLevel(CompactDimension.LEVEL_KEY);
        if (compactWorld == null) {
            return;
        }
        for (ServerPlayer player : List.copyOf(compactWorld.players())) {
            if (player.hasPermissions(OP_PERMISSION_LEVEL)) {
                continue;
            }
            ChunkPos chunkPos = new ChunkPos(player.blockPosition());
            Optional<String> roomCode = CompactMachines.chunkManager().findRoomByChunk(chunkPos);
            roomCode.flatMap(code -> EvaluationManager.INSTANCE.sessionByRoom(event.getServer(), code))
                    .ifPresent(session -> EvaluationManager.INSTANCE.requestRoomExit(player, session));
        }
    }

    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.hasPermissions(OP_PERMISSION_LEVEL)) {
            return;
        }
        if (ParallelEvaluationWorlds.isAnyEvaluationWorld(player.level().dimension())
                || ParallelEvaluationWorlds.isAnyEvaluationWorld(player.getRespawnDimension())) {
            moveToOverworld(player, true);
            return;
        }
        boolean currentlyInFrozenRoom = EvaluationManager.INSTANCE.sessionAt(
                player.server, GlobalPos.of(player.level().dimension(), player.blockPosition())).isPresent();
        BlockPos respawnPos = player.getRespawnPosition();
        boolean frozenRespawnPoint = respawnPos != null && EvaluationManager.INSTANCE.sessionAt(
                player.server, GlobalPos.of(player.getRespawnDimension(), respawnPos)).isPresent();
        if (currentlyInFrozenRoom || frozenRespawnPoint) {
            moveToOverworld(player, true);
        }
    }

    /**
     * 评估维度的玩家处置。
     *
     * <p>无论观察功能是否开启，先移出正在清理的副本区块及其玩家 ticket 影响范围；
     * 这避免 spectator/player ticket 阻塞 areChunksIdle。其余玩家按观察配置处理，
     * 关闭观察时仍保留 OP 在非清理区域的调试入口。</p>
     */
    private static void ejectPlayersIfNeeded(ServerLevel level) {
        for (ServerPlayer player : List.copyOf(level.players())) {
            if (EvaluationObservationManager.evictPlayerIfCopyCleaning(player)) {
                continue;
            }
            if (Config.ENABLE_EVALUATION_OBSERVATION.get()) {
                continue; // 正常评估期间由观察管理器接管；仅清理 footprint 在上面 fail-closed 退出
            }
            if (!player.hasPermissions(OP_PERMISSION_LEVEL)) {
                moveToOverworld(player, false);
            }
        }
    }

    private static void moveToOverworld(ServerPlayer player, boolean resetRespawn) {
        ServerLevel overworld = player.server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        float spawnAngle = overworld.getSharedSpawnAngle();
        if (resetRespawn) {
            player.setRespawnPosition(Level.OVERWORLD, spawn, spawnAngle, false, false);
        }
        player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5,
                spawnAngle, 0.0F);
        player.displayClientMessage(Component.translatable("message.createcmpor.eval_world.exiled"), false);
    }
}
