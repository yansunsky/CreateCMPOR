package com.createcmpor.command;

import com.createcmpor.compat.cm.CMAdapterV7;
import com.createcmpor.compat.cm.ICompactMachinesAdapter;
import com.createcmpor.compat.cm.RoomCloner;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.attachment.CMDataAttachments;
import dev.compactmods.machines.api.room.RoomDebugInformation;
import dev.compactmods.machines.api.room.RoomDimensions;
import dev.compactmods.machines.api.room.RoomInstance;
import dev.compactmods.machines.api.room.template.RoomTemplate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CreateCMPOR 调试命令。
 *
 * <p>Phase 2 仅用于验证 CompactMachines 房间复制链路，正式玩家入口会在后续阶段由启动棒触发。</p>
 */
public final class ModCommands {

    private static final ICompactMachinesAdapter CM_ADAPTER = new CMAdapterV7();
    private static final RoomCloner ROOM_CLONER = new RoomCloner();

    private ModCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("createcmpor")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("clone_room")
                        .then(Commands.argument("room", StringArgumentType.word())
                                .executes(context -> cloneRoom(context.getSource(), StringArgumentType.getString(context, "room")))))
                .then(Commands.literal("clear_room")
                        .then(Commands.argument("room", StringArgumentType.word())
                                .executes(context -> clearRoom(context.getSource(), StringArgumentType.getString(context, "room")))))
                .then(Commands.literal("enter_room")
                        .then(Commands.argument("room", StringArgumentType.word())
                                .executes(context -> enterRoom(context.getSource(), StringArgumentType.getString(context, "room"))))));
    }

    private static int cloneRoom(CommandSourceStack source, String sourceRoomCode) {
        Optional<RoomInstance> sourceRoomOptional = CM_ADAPTER.getRoom(source.getServer(), sourceRoomCode);
        if (sourceRoomOptional.isEmpty()) {
            source.sendFailure(Component.literal("找不到 CompactMachines 房间：" + sourceRoomCode));
            return 0;
        }

        RoomInstance sourceRoom = sourceRoomOptional.get();
        UUID ticketId = UUID.randomUUID();
        UUID owner = getCommandOwner(source);

        int width = (int) Math.round(sourceRoom.boundaries().innerBounds().getXsize());
        int height = (int) Math.round(sourceRoom.boundaries().innerBounds().getYsize());
        int depth = (int) Math.round(sourceRoom.boundaries().innerBounds().getZsize());
        RoomTemplate template = new RoomTemplate(new RoomDimensions(width, depth, height),
                sourceRoom.defaultMachineColor(), List.of(), Optional.empty());

        RoomInstance evaluationRoom = CM_ADAPTER.createEvaluationRoom(source.getServer(), template, owner);
        CM_ADAPTER.initializeDefaultSpawn(evaluationRoom);

        try {
            CM_ADAPTER.setRoomForced(sourceRoom, ticketId, true);
            CM_ADAPTER.setRoomForced(evaluationRoom, ticketId, true);
            loadChunks(sourceRoom);
            loadChunks(evaluationRoom);

            var snapshot = ROOM_CLONER.snapshot(CM_ADAPTER.getRoomLevel(sourceRoom), sourceRoom.boundaries());
            ROOM_CLONER.apply(CM_ADAPTER.getRoomLevel(evaluationRoom), evaluationRoom.boundaries(), snapshot);

            source.sendSuccess(() -> Component.literal("已复制房间 " + sourceRoomCode + " -> " + evaluationRoom.code()
                    + "，方块 " + snapshot.blockCount() + "，方块实体 " + snapshot.blockEntityCount()), true);
            source.sendSuccess(() -> Component.literal("进入克隆房间：/createcmpor enter_room " + evaluationRoom.code()), false);
            source.sendSuccess(() -> Component.literal("清空评估房间：/createcmpor clear_room " + evaluationRoom.code()), false);
            return 1;
        } finally {
            CM_ADAPTER.setRoomForced(sourceRoom, ticketId, false);
            CM_ADAPTER.setRoomForced(evaluationRoom, ticketId, false);
        }
    }

    private static int clearRoom(CommandSourceStack source, String roomCode) {
        Optional<RoomInstance> roomOptional = CM_ADAPTER.getRoom(source.getServer(), roomCode);
        if (roomOptional.isEmpty()) {
            source.sendFailure(Component.literal("找不到 CompactMachines 房间：" + roomCode));
            return 0;
        }

        RoomInstance room = roomOptional.get();
        UUID ticketId = UUID.randomUUID();
        try {
            CM_ADAPTER.setRoomForced(room, ticketId, true);
            loadChunks(room);
            ROOM_CLONER.clearInner(CM_ADAPTER.getRoomLevel(room), room.boundaries());
            source.sendSuccess(() -> Component.literal("已清空房间内部区域：" + roomCode), true);
            return 1;
        } finally {
            CM_ADAPTER.setRoomForced(room, ticketId, false);
        }
    }

    private static int enterRoom(CommandSourceStack source, String roomCode) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Optional<RoomInstance> roomOptional = CM_ADAPTER.getRoom(source.getServer(), roomCode);
        if (roomOptional.isEmpty()) {
            source.sendFailure(Component.literal("找不到 CompactMachines 房间：" + roomCode));
            return 0;
        }

        RoomInstance room = roomOptional.get();
        UUID ticketId = UUID.randomUUID();
        try {
            CM_ADAPTER.initializeDefaultSpawn(room);
            CM_ADAPTER.setRoomForced(room, ticketId, true);
            loadChunks(room);

            var spawn = CompactMachines.spawnManagers().get(room.code()).spawns().defaultSpawn();
            Vec3 pos = spawn.position();
            player.teleportTo(room.level(), pos.x(), pos.y(), pos.z(), spawn.rotation().y, spawn.rotation().x);
            player.setData(CMDataAttachments.CURRENT_ROOM_CODE, room.code());
            player.setData(CMDataAttachments.CURRENT_ROOM_DEBUG_INFO,
                    new RoomDebugInformation(room.code(), room.getData(CMDataAttachments.ROOM_OWNER)));

            source.sendSuccess(() -> Component.literal("已进入 CompactMachines 房间：" + roomCode), true);
            return 1;
        } finally {
            CM_ADAPTER.setRoomForced(room, ticketId, false);
        }
    }

    private static void loadChunks(RoomInstance room) {
        CM_ADAPTER.getInnerChunks(room.boundaries()).forEach(chunkPos -> room.level().getChunk(chunkPos.x, chunkPos.z));
    }

    private static UUID getCommandOwner(CommandSourceStack source) {
        var entity = source.getEntity();
        if (entity != null) {
            return entity.getUUID();
        }
        return new UUID(0L, 0L);
    }
}
