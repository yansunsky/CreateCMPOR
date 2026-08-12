package com.createcmpor.command;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.compat.cm.CMAdapterV7;
import com.createcmpor.compat.cm.ICompactMachinesAdapter;
import com.createcmpor.compat.cm.RoomCloner;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.attachment.CMDataAttachments;
import dev.compactmods.machines.api.dimension.CompactDimension;
import dev.compactmods.machines.api.room.RoomDebugInformation;
import dev.compactmods.machines.api.room.RoomInstance;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Optional;

/** Phase 2 调试命令：检查原房间与 eval_world 同坐标副本。 */
public final class EvalWorldCommands {
    private static final ICompactMachinesAdapter CM_ADAPTER = new CMAdapterV7();
    private static final RoomCloner ROOM_CLONER = new RoomCloner();

    private EvalWorldCommands() {
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("ccmpor")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("room")
                        .then(Commands.literal("code")
                                .executes(context -> showRoomCode(context.getSource())))
                        .then(Commands.literal("enter")
                                .then(Commands.literal("original")
                                        .then(Commands.argument("room", StringArgumentType.word())
                                                .executes(context -> enterOriginal(context.getSource(),
                                                        StringArgumentType.getString(context, "room")))))
                                .then(Commands.literal("eval")
                                        .then(Commands.argument("room", StringArgumentType.word())
                                                .executes(context -> enterEval(context.getSource(),
                                                        StringArgumentType.getString(context, "room"))))))
                        .then(Commands.literal("diff")
                                .then(Commands.argument("room", StringArgumentType.word())
                                        .executes(context -> diffRoom(context.getSource(),
                                                StringArgumentType.getString(context, "room")))))));
    }

    private static int showRoomCode(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!CompactDimension.LEVEL_KEY.equals(player.level().dimension())
                && !CreateCMPOR.EVAL_WORLD.equals(player.level().dimension())) {
            source.sendFailure(Component.literal("当前不在 CompactMachines 房间维度或 eval_world。"));
            return 0;
        }

        Optional<String> roomCode = CompactMachines.chunkManager()
                .findRoomByChunk(new ChunkPos(player.blockPosition()));
        if (roomCode.isEmpty()) {
            source.sendFailure(Component.literal("当前位置不属于已注册的 CompactMachines 房间。"));
            return 0;
        }

        String code = roomCode.get();
        Component copyableCode = Component.literal(code)
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("点击复制房间号"))));
        source.sendSuccess(() -> Component.literal("当前房间号：").append(copyableCode), false);
        return 1;
    }

    private static int enterOriginal(CommandSourceStack source, String roomCode) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Optional<RoomInstance> roomOptional = CM_ADAPTER.getRoom(source.getServer(), roomCode);
        if (roomOptional.isEmpty()) {
            source.sendFailure(Component.literal("找不到 CompactMachines 房间：" + roomCode));
            return 0;
        }

        RoomInstance room = roomOptional.get();
        CM_ADAPTER.initializeDefaultSpawn(room);
        loadRoomChunks(room.level(), room);

        var spawn = CompactMachines.spawnManagers().get(room.code()).spawns().defaultSpawn();
        Vec3 pos = spawn.position();
        player.teleportTo(room.level(), pos.x(), pos.y(), pos.z(), spawn.rotation().y, spawn.rotation().x);
        player.setData(CMDataAttachments.CURRENT_ROOM_CODE, room.code());
        player.setData(CMDataAttachments.CURRENT_ROOM_DEBUG_INFO,
                new RoomDebugInformation(room.code(), room.getData(CMDataAttachments.ROOM_OWNER)));

        source.sendSuccess(() -> Component.literal("已进入原房间：" + roomCode), true);
        return 1;
    }

    private static int enterEval(CommandSourceStack source, String roomCode) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Optional<RoomInstance> roomOptional = CM_ADAPTER.getRoom(source.getServer(), roomCode);
        if (roomOptional.isEmpty()) {
            source.sendFailure(Component.literal("找不到 CompactMachines 房间：" + roomCode));
            return 0;
        }

        ServerLevel evalWorld = source.getServer().getLevel(CreateCMPOR.EVAL_WORLD);
        if (evalWorld == null) {
            source.sendFailure(Component.literal("eval_world 未加载，请检查维度数据包。"));
            return 0;
        }

        Vec3 pos = roomOptional.get().boundaries().defaultSpawn();
        evalWorld.getChunkAt(BlockPos.containing(pos));
        player.teleportTo(evalWorld, pos.x(), pos.y(), pos.z(), player.getYRot(), player.getXRot());
        source.sendSuccess(() -> Component.literal("已进入 eval_world 同坐标位置：" + roomCode), true);
        return 1;
    }

    private static int diffRoom(CommandSourceStack source, String roomCode) {
        Optional<RoomInstance> roomOptional = CM_ADAPTER.getRoom(source.getServer(), roomCode);
        if (roomOptional.isEmpty()) {
            source.sendFailure(Component.literal("找不到 CompactMachines 房间：" + roomCode));
            return 0;
        }

        ServerLevel evalWorld = source.getServer().getLevel(CreateCMPOR.EVAL_WORLD);
        if (evalWorld == null) {
            source.sendFailure(Component.literal("eval_world 未加载，请检查维度数据包。"));
            return 0;
        }

        RoomInstance room = roomOptional.get();
        loadRoomChunks(room.level(), room);
        loadRoomChunks(evalWorld, room);

        RoomCloner.DiffSummary diff = ROOM_CLONER.compareSameCoordinates(
                room.level(), evalWorld, room.boundaries());
        source.sendSuccess(() -> Component.literal("房间 " + roomCode + " 差异：方块="
                + diff.blockMismatches() + "，方块实体=" + diff.blockEntityMismatches()
                + "，非玩家实体 original/eval=" + diff.sourceEntities() + "/" + diff.targetEntities()), false);
        return 1;
    }

    private static void loadRoomChunks(ServerLevel level, RoomInstance room) {
        room.boundaries().innerChunkPositions().forEach(chunkPos -> level.getChunk(chunkPos.x, chunkPos.z));
    }
}
