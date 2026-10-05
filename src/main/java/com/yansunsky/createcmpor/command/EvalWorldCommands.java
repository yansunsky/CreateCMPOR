package com.yansunsky.createcmpor.command;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.block.FactoryBlockEntity;
import com.yansunsky.createcmpor.compat.cm.CMAdapterV7;
import com.yansunsky.createcmpor.compat.cm.ICompactMachinesAdapter;
import com.yansunsky.createcmpor.compat.cm.RoomCloner;
import com.yansunsky.createcmpor.preview.PreviewCapture;
import com.yansunsky.createcmpor.preview.PreviewSnapshot;
import com.yansunsky.createcmpor.evaluation.EvaluationCloneManager;
import com.yansunsky.createcmpor.evaluation.EvaluationManager;
import com.yansunsky.createcmpor.evaluation.EvaluationManifest;
import com.yansunsky.createcmpor.evaluation.EvaluationSavedData;
import com.yansunsky.createcmpor.evaluation.EvaluationSession;
import com.yansunsky.createcmpor.evaluation.FactoryIndexSavedData;
import com.yansunsky.createcmpor.evaluation.ParallelEvaluationWorlds;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
                                                StringArgumentType.getString(context, "room"))))))
                .then(Commands.literal("evaluation")
                        .then(Commands.literal("list")
                                .executes(context -> listEvaluations(context.getSource())))
                        .then(Commands.literal("max")
                                .executes(context -> showMaxEvaluations(context.getSource()))
                                .then(Commands.argument("value", IntegerArgumentType.integer(1, 16))
                                        .executes(context -> setMaxEvaluations(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "value"))))))
                .then(Commands.literal("factory")
                        .then(Commands.literal("legacy")
                                .then(Commands.literal("list")
                                        .executes(context -> legacyList(context.getSource())))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("room", StringArgumentType.word())
                                                .executes(context -> legacyRemove(context.getSource(),
                                                        StringArgumentType.getString(context, "room")))))
                                .then(Commands.literal("revert")
                                        .then(Commands.argument("room", StringArgumentType.word())
                                                .executes(context -> legacyRevert(context.getSource(),
                                                        StringArgumentType.getString(context, "room")))))))
                .then(Commands.literal("preview")
                        .then(Commands.literal("info")
                                .executes(context -> showPreviewInfo(context.getSource())))
                        .then(Commands.literal("entities")
                                .executes(context -> showEntitySizes(context.getSource())))
                        .then(Commands.literal("bumprev")
                                .executes(context -> bumpPreviewRev(context.getSource())))));
    }

    /**
     * 旧版多工厂组的清理命令组（T5，48 文档 §6.4）：
     * {@code /ccmpor factory legacy <list|remove|revert> [room]}。
     *
     * <p>0.5.0 起一次评估只固化一个工厂，{@code factory_count > 1} 的组只可能来自旧存档。
     * 这些组仍可运行、可用启动棒整组还原，本命令组给管理员三条出口：
     * <ul>
     *   <li>{@code list}：盘点所有遗留组（房间码、成员数、维度与坐标、成员现状）；</li>
     *   <li>{@code remove <roomCode>}：直接清除该组全部工厂方块与索引条目（<b>不掉落</b>）；</li>
     *   <li>{@code revert <roomCode>}：按组还原语义变回原机器（等效玩家用启动棒右键）。</li>
     * </ul>
     * 全部为 OP 命令（{@code ccmpor} 根节点已 requires(2)）。</p>
     */
    private static int legacyList(CommandSourceStack source) {
        Map<String, List<GlobalPos>> all = FactoryIndexSavedData.get(source.getServer()).snapshotAll();
        List<String> groups = new ArrayList<>();
        for (Map.Entry<String, List<GlobalPos>> entry : all.entrySet()) {
            if (entry.getValue().size() > 1) {
                groups.add(entry.getKey());
            }
        }
        if (groups.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.createcmpor.factory.legacy.none"), false);
            return 0;
        }
        // HashMap 无顺序：排序保证同一条命令每次输出一致（便于对日志/对报告）
        groups.sort(null);
        source.sendSuccess(() -> Component.translatable("commands.createcmpor.factory.legacy.header", groups.size()),
                false);
        for (String roomCode : groups) {
            List<GlobalPos> positions = all.get(roomCode);
            source.sendSuccess(() -> Component.translatable("commands.createcmpor.factory.legacy.entry",
                    roomCode, positions.size()), false);
            for (int index = 0; index < positions.size(); index++) {
                GlobalPos member = positions.get(index);
                final int number = index + 1;
                // 维度/坐标是语言无关的，只有状态词走本地化
                String head = "  #" + number + " " + member.dimension().location() + " @ "
                        + member.pos().toShortString() + " ";
                Component status = describeLegacyMember(source, member);
                source.sendSuccess(() -> Component.literal(head).append(status), false);
            }
        }
        return groups.size();
    }

    /** 遗留组成员现状：已加载的工厂方块（附 factory_count）/ 该位置不是工厂 / 区块未加载。 */
    private static Component describeLegacyMember(CommandSourceStack source, GlobalPos member) {
        ServerLevel level = source.getServer().getLevel(member.dimension());
        if (level == null || !level.isLoaded(member.pos())) {
            return Component.translatable("commands.createcmpor.factory.legacy.status_unloaded");
        }
        if (level.getBlockEntity(member.pos()) instanceof FactoryBlockEntity factory) {
            return Component.translatable("commands.createcmpor.factory.legacy.status_loaded",
                    factory.getFactoryCount());
        }
        return Component.translatable("commands.createcmpor.factory.legacy.status_missing");
    }

    /**
     * {@code /ccmpor factory legacy remove <roomCode>}：清除整组工厂方块与索引条目（不掉落）。
     *
     * <p>与玩家启动棒还原的区别：这里<b>不做</b>还原（原机器不回来），也不消耗任何物品；
     * 区块会被强制加载——管理员命令必须能清干净，不能因区块未加载而静默留下孤儿方块。</p>
     */
    @SuppressWarnings("deprecation") // 遗留命令：有意使用工厂索引的多位置查询 API
    private static int legacyRemove(CommandSourceStack source, String roomCode) {
        FactoryIndexSavedData index = FactoryIndexSavedData.get(source.getServer());
        List<GlobalPos> positions = index.factoriesForRoom(roomCode).orElse(List.of());
        if (positions.isEmpty()) {
            source.sendFailure(Component.translatable(
                    "commands.createcmpor.factory.legacy.no_group", roomCode));
            return 0;
        }
        int removed = 0;
        int skipped = 0;
        for (GlobalPos member : positions) {
            ServerLevel level = source.getServer().getLevel(member.dimension());
            if (level == null) {
                skipped++;
                continue;
            }
            level.getChunkAt(member.pos());
            if (!(level.getBlockEntity(member.pos()) instanceof FactoryBlockEntity)) {
                skipped++;
                continue;
            }
            level.removeBlockEntity(member.pos());
            level.setBlock(member.pos(), Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
            removed++;
        }
        index.removeFactory(roomCode);
        final int removedCount = removed;
        final int skippedCount = skipped;
        source.sendSuccess(() -> Component.translatable("commands.createcmpor.factory.legacy.remove_done",
                roomCode, removedCount, skippedCount), true);
        CreateCMPOR.LOGGER.info("[遗留清理] 房间 {} 的旧版工厂组已清除：移除 {} 个方块，跳过 {} 个",
                roomCode, removedCount, skippedCount);
        return 1;
    }

    /**
     * {@code /ccmpor factory legacy revert <roomCode>}：按组还原语义变回原机器。
     *
     * <p>与玩家右键完全一致的三条前置校验：还原开关、成员数量 == {@code factory_count}、
     * 主位置有还原数据。校验通过后：主位置变回原 CM 机器，组内其余成员直接消失（不掉落），
     * 索引条目由 {@code revertToMachine} 内部清除。</p>
     *
     * <p>命令没有"被右键的那一个"，故目标的选择是可判定的：优先索引主位置（{@code index[0]}），
     * 主位置不可用时退化为第一个已加载成员。</p>
     */
    @SuppressWarnings("deprecation") // 遗留命令：有意使用工厂索引的多位置查询 API
    private static int legacyRevert(CommandSourceStack source, String roomCode) {
        if (!Config.ENABLE_FACTORY_REVERT.get()) {
            source.sendFailure(Component.translatable("message.createcmpor.factory.revert_disabled"));
            return 0;
        }
        FactoryIndexSavedData index = FactoryIndexSavedData.get(source.getServer());
        List<GlobalPos> positions = index.factoriesForRoom(roomCode).orElse(List.of());
        if (positions.isEmpty()) {
            source.sendFailure(Component.translatable(
                    "commands.createcmpor.factory.legacy.no_group", roomCode));
            return 0;
        }
        // 收集"确实存在工厂方块"的成员（区块强制加载，保证命令语义稳定）
        List<GlobalPos> present = new ArrayList<>();
        for (GlobalPos member : positions) {
            ServerLevel level = source.getServer().getLevel(member.dimension());
            if (level == null) {
                continue;
            }
            level.getChunkAt(member.pos());
            if (level.getBlockEntity(member.pos()) instanceof FactoryBlockEntity) {
                present.add(member);
            }
        }
        if (present.isEmpty()) {
            source.sendFailure(Component.translatable(
                    "commands.createcmpor.factory.legacy.revert_no_block", roomCode));
            return 0;
        }
        GlobalPos mainPos = present.contains(positions.get(0)) ? positions.get(0) : present.get(0);
        ServerLevel mainLevel = source.getServer().getLevel(mainPos.dimension());
        if (mainLevel == null
                || !(mainLevel.getBlockEntity(mainPos.pos()) instanceof FactoryBlockEntity main)) {
            source.sendFailure(Component.translatable(
                    "commands.createcmpor.factory.legacy.revert_no_block", roomCode));
            return 0;
        }
        int expected = main.getFactoryCount();
        if (present.size() != expected) {
            final int presentCount = present.size();
            // 与玩家右键同一条提示（数量不符时不要擅自"尽力还原"）
            source.sendFailure(Component.translatable("message.createcmpor.factory.group_rearrange",
                    expected, presentCount));
            return 0;
        }
        if (!main.hasRestoreData()) {
            source.sendFailure(Component.translatable("message.createcmpor.factory.revert_unavailable"));
            return 0;
        }
        // 组还原语义：非主位置成员直接消失（不掉落）
        for (GlobalPos member : present) {
            if (member.equals(mainPos)) {
                continue;
            }
            ServerLevel level = source.getServer().getLevel(member.dimension());
            if (level == null || !(level.getBlockEntity(member.pos()) instanceof FactoryBlockEntity)) {
                continue;
            }
            level.removeBlockEntity(member.pos());
            level.setBlock(member.pos(), Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        final int memberCount = present.size();
        final String mainCoords = mainPos.pos().toShortString();
        if (!main.revertToMachine(mainLevel)) {
            source.sendFailure(Component.translatable("message.createcmpor.factory.revert_failed"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("commands.createcmpor.factory.legacy.revert_done",
                roomCode, memberCount, mainCoords), true);
        CreateCMPOR.LOGGER.info("[遗留清理] 房间 {} 的旧版工厂组已还原为原机器：主位置 {}，其余 {} 个成员已移除",
                roomCode, mainCoords, memberCount - 1);
        return 1;
    }

    /**
     * 报告附近工厂方块的微缩预览统计（P1 数据层验收用）。
     *
     * <p>只看已加载区块，不做任何强制加载；半径 16 格内取最近的一个有预览数据的工厂。
     */
    private static int showPreviewInfo(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        FactoryBlockEntity nearest = null;
        BlockPos nearestPos = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-16, -16, -16), origin.offset(16, 16, 16))) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (!(level.getBlockEntity(pos) instanceof FactoryBlockEntity factory)) {
                continue;
            }
            if (factory.getPreviewSnapshot() == null) {
                continue;
            }
            double distance = pos.distSqr(origin);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = factory;
                nearestPos = pos.immutable();
            }
        }
        if (nearest == null) {
            source.sendFailure(Component.literal("16 格内没有携带微缩预览数据的工厂方块（旧存档/采集失败/配置关闭都会如此）。"));
            return 0;
        }
        // lambda 捕获需要 final/等效 final：这里显式落成常量副本
        final BlockPos foundPos = nearestPos;
        final double foundDistance = bestDistance;
        final PreviewSnapshot snapshot = nearest.getPreviewSnapshot();
        final int encoded = snapshot.encodedSize();
        final int rev = nearest.previewRev();
        source.sendSuccess(() -> Component.literal(String.format(
                "预览 @%s 距离 %.1f（rev %d）：网格 %d×%d×%d，非空气 %d 格，调色板 %d 种，实体 %d 只，编码 %d 字节（%.1f KB）",
                foundPos, Math.sqrt(foundDistance), rev, snapshot.width(), snapshot.height(),
                snapshot.depth(), snapshot.nonAirCount(), snapshot.paletteSize(), snapshot.entityCount(), encoded,
                encoded / 1024.0)), false);
        for (String line : PreviewCapture.describePalette(snapshot, 8)) {
            source.sendSuccess(() -> Component.literal("  " + line), false);
        }
        // v4 实体表：类型分布 + 逐只坐标（实机核对"实体是否真的被采到、位置对不对"的最直接证据）
        if (snapshot.entityCount() > 0) {
            java.util.Map<String, Integer> byType = new java.util.LinkedHashMap<>();
            for (PreviewSnapshot.EntityRecord record : snapshot.entities()) {
                byType.merge(record.type(), 1, Integer::sum);
            }
            source.sendSuccess(() -> Component.literal("  实体分布：" + byType), false);
            int shown = 0;
            for (PreviewSnapshot.EntityRecord record : snapshot.entities()) {
                if (shown++ >= 8) {
                    break;
                }
                source.sendSuccess(() -> Component.literal(String.format("    %s @(%.2f, %.2f, %.2f) yaw %.0f 数据 %d 字节",
                        record.type(), record.x(), record.y(), record.z(), record.yaw(),
                        record.data().sizeInBytes())), false);
            }
        }
        return 1;
    }

    /**
     * 调试命令：把 16 格内最近的工厂的预览版本号顶一格（且不带数据地推一次包）。
     *
     * <p>作用见 {@code FactoryBlockEntity.debugBumpPreviewRev}：强制让客户端走一遍
     * "按需请求 → 服务端响应 → 客户端装配"的完整链路，便于实机验收。
     */
    private static int bumpPreviewRev(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        BlockPos origin = player.blockPosition();
        FactoryBlockEntity nearest = null;
        BlockPos nearestPos = null;
        double best = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-16, -16, -16), origin.offset(16, 16, 16))) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof FactoryBlockEntity factory
                    && factory.getPreviewSnapshot() != null) {
                double d = pos.distSqr(origin);
                if (d < best) {
                    best = d;
                    nearest = factory;
                    nearestPos = pos.immutable();
                }
            }
        }
        if (nearest == null) {
            source.sendFailure(Component.literal("16 格内没有携带预览快照的工厂方块。"));
            return 0;
        }
        nearest.debugBumpPreviewRev();
        final BlockPos foundPos = nearestPos;
        final int rev = nearest.previewRev();
        source.sendSuccess(() -> Component.literal("已把 " + foundPos + " 的预览版本号顶到 rev=" + rev
                + "（并推了一次不带数据的客户端包，客户端下次渲染该工厂时会按需索取）"), false);
        return 1;
    }

    /**
     * 诊断命令：列出周围 32 格内每只实体的 NBT 体积与最大的几个键（{@code /ccmpor preview entities}）。
     *
     * <p>用途：实体预览依赖"实体存档 NBT"重建，而体积预算有可能把某些实体裁掉或丢弃。
     * 这条命令把"到底多大、哪个键大、会不会被裁"直接打出来——不必为了诊断跑一遍完整评估。
     */
    private static int showEntitySizes(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        AABB box = player.getBoundingBox().inflate(32.0D);
        java.util.List<String> lines = PreviewCapture.describeEntitySizes(player.serverLevel(), box);
        // 同时写进服务端日志：诊断结果可以被日志直接读走，不必手抄聊天栏
        CreateCMPOR.LOGGER.info("[预览] 实体体积诊断（{}）：\n  {}", player.getName().getString(),
                String.join("\n  ", lines));
        for (String line : lines) {
            for (String part : line.split("\n")) {
                source.sendSuccess(() -> Component.literal("  " + part), false);
            }
        }
        return 1;
    }

    private static int showRoomCode(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!CompactDimension.LEVEL_KEY.equals(player.level().dimension())
                && !ParallelEvaluationWorlds.isAnyEvaluationWorld(player.level().dimension())) {
            source.sendFailure(Component.literal("当前不在 CompactMachines 房间维度或任一评估维度。"));
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
        if (EvaluationManager.INSTANCE.isRoomLocked(source.getServer(), roomCode)) {
            source.sendFailure(Component.translatable("message.createcmpor.evaluation.source_locked"));
            return 0;
        }
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
        Optional<EvaluationSession> sessionOpt = EvaluationManager.INSTANCE.sessionByRoom(source.getServer(), roomCode);
        if (sessionOpt.isEmpty()) {
            source.sendFailure(Component.translatable("message.createcmpor.evaluation.copy_not_ready"));
            return 0;
        }
        EvaluationSession session = sessionOpt.get();
        boolean copyLive;
        if (!session.parallelBranches().isEmpty()) {
            copyLive = session.state() == EvaluationSession.State.PARALLEL_PUBLISHED
                    || session.state() == EvaluationSession.State.PARALLEL_EVALUATING
                    || session.state() == EvaluationSession.State.SOLIDIFYING;
        } else {
            copyLive = session.manifest() != null
                    && (session.state() == EvaluationSession.State.PUBLISHED
                    || session.state() == EvaluationSession.State.EVALUATING
                    || session.state() == EvaluationSession.State.EVALUATED
                    || session.state() == EvaluationSession.State.SOLIDIFYING);
        }
        if (!copyLive) {
            source.sendFailure(Component.translatable("message.createcmpor.evaluation.copy_not_ready"));
            return 0;
        }
        Optional<RoomInstance> roomOptional = CM_ADAPTER.getRoom(source.getServer(), roomCode);
        if (roomOptional.isEmpty()) {
            source.sendFailure(Component.literal("找不到 CompactMachines 房间：" + roomCode));
            return 0;
        }

        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> copyDimension =
                session.liveCopyDimension();
        ServerLevel target = copyDimension == null ? null : source.getServer().getLevel(copyDimension);
        if (target == null) {
            source.sendFailure(Component.literal("评估副本维度未加载：" + copyDimension));
            return 0;
        }

        Vec3 pos = roomOptional.get().boundaries().defaultSpawn();
        target.getChunkAt(BlockPos.containing(pos));
        player.teleportTo(target, pos.x(), pos.y(), pos.z(), player.getYRot(), player.getXRot());
        source.sendSuccess(() -> Component.literal("已进入评估副本 " + target.dimension().location()
                + " 同坐标位置：" + roomCode), true);
        return 1;
    }

    private static int diffRoom(CommandSourceStack source, String roomCode) {
        Optional<EvaluationSession> sessionOpt = EvaluationManager.INSTANCE.sessionByRoom(source.getServer(), roomCode);
        if (sessionOpt.isPresent()) {
            EvaluationSession session = sessionOpt.get();
            if (session.hasParallelBranches()) {
                // 并行：分别统计每个 lane manifest 的源/目标摘要差异，不能拿 parent eval_world manifest 冒充已发布副本。
                StringBuilder out = new StringBuilder("房间 " + roomCode + " 并行分支差异：");
                java.util.List<EvaluationManifest> manifests = session.parallelBranchManifests();
                for (int index = 0; index < manifests.size(); index++) {
                    EvaluationManifest manifest = manifests.get(index);
                    long mismatches = manifest.chunks().stream()
                            .filter(chunk -> chunk.sourceHash().isBlank()
                                    || !chunk.sourceHash().equals(chunk.targetHash()))
                            .count();
                    out.append(System.lineSeparator())
                            .append("  分支 #").append(index + 1).append('/').append(manifests.size())
                            .append(' ').append(manifest.targetDimension().location())
                            .append("：差异 ").append(mismatches).append('/').append(manifest.chunks().size());
                }
                source.sendSuccess(() -> Component.literal(out.toString()), false);
                return 1;
            }
            if (session.manifest() != null) {
                long mismatches = session.manifest().chunks().stream()
                        .filter(chunk -> chunk.sourceHash().isBlank()
                                || !chunk.sourceHash().equals(chunk.targetHash()))
                        .count();
                source.sendSuccess(() -> Component.literal("房间 " + roomCode + " 持久化区块差异="
                        + mismatches + "/" + session.manifest().chunks().size()), false);
                return 1;
            }
        }
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

    private static int listEvaluations(CommandSourceStack source) {
        EvaluationSavedData data = EvaluationSavedData.get(source.getServer());
        List<EvaluationSession> sessions = new ArrayList<>(data.sessions());
        if (sessions.isEmpty()) {
            source.sendSuccess(() -> Component.literal("没有进行中的评估会话。"), false);
            return 1;
        }
        int queuePosition = 0;
        for (EvaluationSession session : sessions) {
            StringBuilder line = new StringBuilder("房间 ").append(session.roomCode())
                    .append(" | 状态 ").append(session.state());
            if (session.manifest() != null) {
                EvaluationManifest manifest = session.manifest();
                int total = manifest.chunks().size();
                int verified = (int) manifest.chunks().stream()
                        .filter(chunk -> chunk.stagingStatus() == EvaluationManifest.StagingStatus.VERIFIED).count();
                int published = (int) manifest.chunks().stream()
                        .filter(chunk -> chunk.publishStatus() == EvaluationManifest.PublishStatus.VERIFIED).count();
                line.append(" | 区块 ").append(total)
                        .append(" | 已校验 ").append(verified)
                        .append(" | 已发布 ").append(published);
            }
            if (session.state() == EvaluationSession.State.QUEUED) {
                queuePosition++;
                line.append(" | 排队第 ").append(queuePosition);
            }
            String text = line.toString();
            source.sendSuccess(() -> Component.literal(text), false);
        }
        return 1;
    }

    private static int showMaxEvaluations(CommandSourceStack source) {
        int current = EvaluationCloneManager.INSTANCE.maxConcurrentEvaluations();
        source.sendSuccess(() -> Component.literal("当前并发评估上限：" + current), false);
        return 1;
    }

    private static int setMaxEvaluations(CommandSourceStack source, int value) {
        if (!EvaluationCloneManager.INSTANCE.setMaxConcurrentEvaluations(value)) {
            source.sendFailure(Component.literal("并发评估上限必须在 1-16 之间。"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("并发评估上限已设为 " + value
                + "（本次运行生效，重启后恢复配置文件值）"), true);
        return 1;
    }

    private static void loadRoomChunks(ServerLevel level, RoomInstance room) {
        room.boundaries().innerChunkPositions().forEach(chunkPos -> level.getChunk(chunkPos.x, chunkPos.z));
    }
}
