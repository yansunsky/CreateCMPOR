package com.createcmpor.evaluation;

import com.createcmpor.CreateCMPOR;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

/** 将普通玩家和重生点排除在隔离评估维度之外。 */
public final class EvalWorldGuard {
    private static final int OP_PERMISSION_LEVEL = 2;

    private EvalWorldGuard() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        ServerLevel evalWorld = event.getServer().getLevel(CreateCMPOR.EVAL_WORLD);
        if (evalWorld == null) {
            return;
        }

        for (ServerPlayer player : List.copyOf(evalWorld.players())) {
            if (!player.hasPermissions(OP_PERMISSION_LEVEL)) {
                moveToOverworld(player, false);
            }
        }
    }

    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.hasPermissions(OP_PERMISSION_LEVEL)) {
            return;
        }
        if (!CreateCMPOR.EVAL_WORLD.equals(player.level().dimension())
                && !CreateCMPOR.EVAL_WORLD.equals(player.getRespawnDimension())) {
            return;
        }
        moveToOverworld(player, true);
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
