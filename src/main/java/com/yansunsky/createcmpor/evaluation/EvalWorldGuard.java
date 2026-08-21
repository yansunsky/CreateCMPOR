package com.yansunsky.createcmpor.evaluation;

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

/** 防止普通玩家进入 eval_world 或已冻结的 CompactMachines 房间。 */
public final class EvalWorldGuard {
    private static final int OP_PERMISSION_LEVEL = 2;
    private EvalWorldGuard() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel evalWorld = event.getServer().getLevel(CreateCMPOR.EVAL_WORLD);
        if (evalWorld != null) {
            for (ServerPlayer player : List.copyOf(evalWorld.players())) {
                if (!player.hasPermissions(OP_PERMISSION_LEVEL)) {
                    moveToOverworld(player, false);
                }
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
        if (CreateCMPOR.EVAL_WORLD.equals(player.level().dimension())
                || CreateCMPOR.EVAL_WORLD.equals(player.getRespawnDimension())) {
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
