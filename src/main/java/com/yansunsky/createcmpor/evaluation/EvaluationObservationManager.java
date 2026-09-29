package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.room.RoomInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 评估副本观察（黑盒查看产线）。
 *
 * <p><b>目的</b>：评估期间玩家只能看到"进行中"，若产线本身有问题则要等整场评估跑完才发现。
 * 允许玩家以观察者模式进入评估副本实时旁观产线，提前发现错误。
 *
 * <p><b>为什么不影响评估结果（vanilla 语义确证）</b>：
 * <ul>
 * <li>{@code ChunkMap.skipPlayer}：观察者默认不产生区块票据（{@code spectatorsGenerateChunks=false}），
 * 不会额外加载/保持区块；</li>
 * <li>{@code ChunkMap.playerIsCloseEnoughForSpawning}：观察者被直接排除，不参与刷怪；</li>
 * <li>评估副本本来就靠 {@code setChunkForced} 常 tick，玩家进出不改变这一点。</li>
 * </ul>
 *
 * <p><b>进入方式</b>：手持 {@code compactmachines:personal_shrinking_device} 右键评估方块。
 * 已观察中再次右键 → 切换到下一个<b>已就绪</b>的并行分支；潜行右键 → 退出观察。
 *
 * <p><b>进入来源统一</b>：被传送模组/指令直接送进评估维度的玩家，也会在下一 tick 被自动转为观察者，
 * 保证"任何人进入评估空间都是观察者"。
 *
 * <p><b>退出时机</b>：飞出房间外边界（含 Y 轴）、会话结束、玩家重新登录（自愈）。
 * 退出时传回进入前位置并恢复原游戏模式。
 *
 * <p><b>OP 豁免的范围（0.4.5 修正）</b>：豁免只针对<b>没有观察记录</b>的 OP
 * （保留 {@code /ccmpor room enter eval} 调试自由、不强制转观察者）；
 * 玩家一旦自己用缩小设备进入观察，就必须和普通玩家一样受越界/会话监管——
 * 否则"飞出房间 → 倒计时 → 自动退出"整条对 OP 失效（0.3.49 起如此，0.4.4 实机报告）。
 *
 * <p><b>诊断探针（低频）</b>：进入/切换、退出（带原因与目的地）、越界倒计时开始、飞回取消
 * 各打一条 INFO；"OP 未被观察记录接管"与"拿不到房间信息致越界判定跳过"各打一条——
 * 全部只在<b>状态变化</b>时打，不刷屏。
 *
 * <p><b>离线/崩溃安全</b>：观察记录写在玩家 {@code PersistentData}（随玩家存档持久化），
 * 与既有启动棒事务（{@code EvaluationManager.markLauncherTransaction}）同一模式，
 * 因此跨下线、跨服务器重启都能在下次登录时自愈（见 {@link #onPlayerLogin}）。
 */
public final class EvaluationObservationManager {

    public static final EvaluationObservationManager INSTANCE = new EvaluationObservationManager();

    /** 观察记录根键（玩家 PersistentData）。 */
    private static final String OBSERVATION_KEY = "createcmpor_observation";
    /** 最后安全位置根键（用于"被传送进评估维度"的玩家回退）。 */
    private static final String LAST_SAFE_KEY = "createcmpor_last_safe";
    /** 缩小设备注册名（避免编译期强依赖 CM 的 Item 类）。 */
    private static final ResourceLocation SHRINKING_DEVICE_ID =
            ResourceLocation.fromNamespaceAndPath("compactmachines", "personal_shrinking_device");
    /** OP 权限等级：OP 不被强制转观察者（保留 /ccmpor 调试路径）。 */
    private static final int OP_PERMISSION_LEVEL = 2;

    private EvaluationObservationManager() {
    }

    // ===================== 进入 / 切换 =====================

    /** 手持物是否为 CompactMachines 个人缩小设备。 */
    public static boolean isShrinkingDevice(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && SHRINKING_DEVICE_ID.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    /** 该玩家当前是否处于观察状态（有观察记录）。 */
    public boolean isObserving(ServerPlayer player) {
        return !player.getPersistentData().getCompound(OBSERVATION_KEY).isEmpty();
    }

    /**
     * 进入评估副本；已在观察同一会话时切换到下一个已就绪的并行分支。
     *
     * @param roomCode 源房间号（来自评估方块 BE）
     */
    public void enterOrSwitch(ServerPlayer player, String roomCode) {
        MinecraftServer server = player.server;
        if (!Config.ENABLE_EVALUATION_OBSERVATION.get()) {
            message(player, "message.createcmpor.observation.disabled");
            return;
        }
        if (!player.hasPermissions(Config.OBSERVATION_PERMISSION_LEVEL.get())) {
            message(player, "message.createcmpor.observation.no_permission");
            return;
        }
        Optional<EvaluationSession> sessionOptional = EvaluationManager.INSTANCE.sessionByRoom(server, roomCode);
        if (sessionOptional.isEmpty()) {
            message(player, "message.createcmpor.observation.not_ready");
            return;
        }
        EvaluationSession session = sessionOptional.get();
        if (!isCopyLive(session)) {
            message(player, "message.createcmpor.observation.not_ready");
            return;
        }

        List<ResourceKey<Level>> readyLanes = readyLaneDimensions(server, session);
        ResourceKey<Level> targetKey = pickTargetDimension(session, readyLanes);
        if (targetKey == null) {
            message(player, "message.createcmpor.observation.not_ready");
            return;
        }
        ServerLevel target = server.getLevel(targetKey);
        if (target == null) {
            message(player, "message.createcmpor.observation.not_ready");
            return;
        }

        boolean switching = isObserving(player);
        if (!switching) {
            // 首次进入：记录进入前的位置与游戏模式（跨下线/崩溃持久化）
            writeRecord(player, session, roomCode, targetKey);
        } else {
            updateObservedLane(player, targetKey);
        }
        // 记录最后安全位置（若当前不在评估维度，用于被 tp 进来的玩家回退）
        recordLastSafePosition(player);

        if (player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
            player.setGameMode(GameType.SPECTATOR);
        }
        teleportIntoRoom(player, target, roomCode);
        // 低频探针：每次进入/切换只打一条，便于实机从日志确认"到底有没有进入观察记录"
        CreateCMPOR.LOGGER.info("观察：{} {}评估副本（房间 {}，维度 {}）",
                player.getName().getString(), switching ? "切换到" : "进入", roomCode,
                targetKey.location());

        if (switching && readyLanes.size() > 1) {
            int position = readyLanes.indexOf(targetKey) + 1;
            player.displayClientMessage(Component.translatable(
                    "message.createcmpor.observation.switched", position, readyLanes.size()), true);
        } else {
            message(player, "message.createcmpor.observation.entered");
        }
    }

    /** 副本是否已就绪（与 EvalWorldCommands.enterEval 同一判定口径）。 */
    private static boolean isCopyLive(EvaluationSession session) {
        EvaluationSession.State state = session.state();
        if (!session.parallelBranches().isEmpty()) {
            return state == EvaluationSession.State.PARALLEL_PUBLISHED
                    || state == EvaluationSession.State.PARALLEL_EVALUATING
                    || state == EvaluationSession.State.SOLIDIFYING;
        }
        return session.manifest() != null
                && (state == EvaluationSession.State.PUBLISHED
                || state == EvaluationSession.State.EVALUATING
                || state == EvaluationSession.State.EVALUATED
                || state == EvaluationSession.State.SOLIDIFYING);
    }

    /** 已就绪（targetReady）的并行分支维度，按分支序号升序；串行时为空表。 */
    private static List<ResourceKey<Level>> readyLaneDimensions(MinecraftServer server, EvaluationSession session) {
        List<ResourceKey<Level>> lanes = new ArrayList<>();
        for (EvaluationBranch branch : session.parallelBranches()) {
            if (!branch.targetReady()) {
                continue;
            }
            ResourceKey<Level> dimension = branch.manifest().targetDimension();
            if (server.getLevel(dimension) != null) {
                lanes.add(dimension);
            }
        }
        lanes.sort(Comparator.comparingInt(dimension -> ParallelEvaluationWorlds.laneIndex(dimension)));
        return lanes;
    }

    /**
     * 选择要进入的维度：并行取"下一个已就绪分支"（循环），串行取会话的副本维度。
     *
     * <p>只允许进入已就绪分支——未就绪的分支跳过，避免玩家看到半成品以为是产线错误。
     */
    private static ResourceKey<Level> pickTargetDimension(EvaluationSession session,
                                                          List<ResourceKey<Level>> readyLanes) {
        if (readyLanes.isEmpty()) {
            return session.liveCopyDimension();
        }
        ResourceKey<Level> current = session.liveCopyDimension();
        int currentIndex = current == null ? -1 : readyLanes.indexOf(current);
        if (currentIndex < 0) {
            return readyLanes.getFirst();
        }
        return readyLanes.get((currentIndex + 1) % readyLanes.size());
    }

    /** 传送到副本房间的默认出生点（与 EvalWorldCommands.enterEval 同一做法）。 */
    private static void teleportIntoRoom(ServerPlayer player, ServerLevel target, String roomCode) {
        RoomInstance room = CompactMachines.room(player.server, roomCode).orElse(null);
        Vec3 pos = room == null
                ? Vec3.atBottomCenterOf(target.getSharedSpawnPos())
                : room.boundaries().defaultSpawn();
        BlockPos blockPos = BlockPos.containing(pos);
        target.getChunkAt(blockPos);
        player.teleportTo(target, pos.x(), pos.y(), pos.z(), player.getYRot(), player.getXRot());
    }

    // ===================== 退出 =====================

    /** 退出观察：传回进入前位置、恢复原游戏模式、清除记录。可安全重复调用。 */
    public void exit(ServerPlayer player, String reasonKey) {
        CompoundTag data = player.getPersistentData().getCompound(OBSERVATION_KEY);
        if (data.isEmpty()) {
            return;
        }
        GameType returnMode = GameType.byName(data.getString("return_gametype"), GameType.SURVIVAL);
        player.getPersistentData().remove(OBSERVATION_KEY);
        // 清理越界倒计时状态与屏幕字样（避免退出后标题残留）
        cancelOutOfBounds(player);

        // 仅在仍处于观察者模式时恢复，避免覆盖玩家自己切换的模式
        if (player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
            player.setGameMode(returnMode);
        }

        ResourceKey<Level> returnDimension = data.contains("return_dim")
                ? ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.parse(data.getString("return_dim")))
                : Level.OVERWORLD;
        MinecraftServer server = player.server;
        ServerLevel target = server.getLevel(returnDimension);
        if (target == null) {
            target = server.overworld();
        }
        double x = data.contains("return_x") ? data.getDouble("return_x") : target.getSharedSpawnPos().getX() + 0.5;
        double y = data.contains("return_y") ? data.getDouble("return_y") : target.getSharedSpawnPos().getY() + 1.0;
        double z = data.contains("return_z") ? data.getDouble("return_z") : target.getSharedSpawnPos().getZ() + 0.5;
        float yRot = data.contains("return_yrot") ? data.getFloat("return_yrot") : 0.0F;
        float xRot = data.contains("return_xrot") ? data.getFloat("return_xrot") : 0.0F;
        player.teleportTo(target, x, y, z, yRot, xRot);
        // 低频探针：每次退出只打一条，注明原因与目的地（排查"退出被拦/坐标丢失"）
        CreateCMPOR.LOGGER.info("观察：{} 退出观察（原因 {}），已传回 {} @({}, {}, {})",
                player.getName().getString(), reasonKey, returnDimension.location(),
                (int) x, (int) y, (int) z);
        if (reasonKey != null) {
            message(player, reasonKey);
        }
    }

    /** 会话结束时把仍在观察该会话副本的玩家全部送出（防遗留观察者）。 */
    public static void exitObserversOf(MinecraftServer server, UUID sessionId, String reasonKey) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CompoundTag data = player.getPersistentData().getCompound(OBSERVATION_KEY);
            if (data.isEmpty() || !data.hasUUID("session_id")) {
                continue;
            }
            if (sessionId.equals(data.getUUID("session_id"))) {
                INSTANCE.exit(player, reasonKey);
            }
        }
    }

    // ===================== 每 tick 监管 =====================

    /** 服务端每 tick：纳入被传送进入者、检查越界、检查会话是否已结束。 */
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!Config.ENABLE_EVALUATION_OBSERVATION.get()) {
            return;
        }
        EvaluationSavedData data = EvaluationSavedData.get(server);
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            ResourceKey<Level> dimension = player.level().dimension();
            if (!ParallelEvaluationWorlds.isAnyEvaluationWorld(dimension)) {
                clearRegulationState(player);
                continue;
            }
            if (!INSTANCE.isObserving(player)) {
                // 说明：0.3.49 起这里曾有一个"OP 豁免"（权限 >=2 时不纳入监管），导致 OP 账号
                // "飞出 outerBounds 不退出、只有评估结束才回去"（0.4.4 实机发现）。0.4.5 先把豁免
                // 收窄到"无观察记录"，0.4.6 按用户要求**彻底移除**——现在不再有豁免分支。
                // 用户 2026-09-29 决定：**移除 OP 豁免**（该功能已收尾）——权限高低一律同待遇。
                // 只要在评估维度里没有观察记录，就在下一 tick 转为观察者并纳入越界/会话监管，
                // 即"飞出 outerBounds → 倒计时 → 自动退出"对所有人一致。
                markRegulated(player);
                // 被传送模组/指令送进来：下一 tick 转为观察者并纳入监管
                adoptTeleportedPlayer(server, player);
                continue;
            }
            markRegulated(player);
            CompoundTag record = player.getPersistentData().getCompound(OBSERVATION_KEY);
            if (record.hasUUID("session_id") && data.session(record.getUUID("session_id")).isEmpty()) {
                INSTANCE.exit(player, "message.createcmpor.observation.finished");
                continue;
            }
            // 越界不立即退出：给 OUT_OF_BOUNDS_GRACE_TICKS 的缓冲倒计时（屏幕中央大字提示），
            // 飞回房间内立即取消并清除字样。超时才真正退出观察。
            Bounds bounds = boundsOf(player, record);
            if (bounds == Bounds.OUTSIDE) {
                INSTANCE.tickOutOfBounds(player);
            } else {
                if (INSTANCE.cancelOutOfBounds(player)) {
                    CreateCMPOR.LOGGER.info("观察：{} 飞回评估房间内，取消越界倒计时",
                            player.getName().getString());
                }
                if (bounds == Bounds.UNKNOWN) {
                    noteBoundsUnknown(player, record);
                } else {
                    markRegulated(player);
                }
            }
        }
    }

    // ===================== 越界缓冲倒计时 =====================

    /** 越界容忍时长（tick）；5 秒。 */
    private static final int OUT_OF_BOUNDS_GRACE_TICKS = 100;
    /** 越界倒计时状态：玩家 UUID → 剩余 tick（仅内存，退出/飞回即清除）。 */
    private static final java.util.Map<UUID, Integer> OUT_OF_BOUNDS_TIMERS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 越界缓冲倒计时（每 tick）。
     *
     * <p>仅当"秒数"变化时才发包刷新标题，避免每 tick 刷屏；倒计时归零则真正退出观察。
     * 提示位于屏幕中央大字（title + subtitle），与史诗级标题类模组的做法一致。
     */
    public void tickOutOfBounds(ServerPlayer player) {
        UUID id = player.getUUID();
        Integer tracked = OUT_OF_BOUNDS_TIMERS.get(id);
        if (tracked == null) {
            // 低频探针：只在"开始倒计时"这一次打（每 tick 调用，但只有 crossing 才命中）
            CreateCMPOR.LOGGER.info("观察：{} 飞出评估房间外壁，{} 秒后自动退出（飞回房间内立即取消）",
                    player.getName().getString(), OUT_OF_BOUNDS_GRACE_TICKS / 20);
        }
        int remaining = tracked == null ? OUT_OF_BOUNDS_GRACE_TICKS : tracked;
        if (remaining <= 0) {
            OUT_OF_BOUNDS_TIMERS.remove(id);
            clearOutOfBoundsTitle(player);
            exit(player, "message.createcmpor.observation.out_of_bounds");
            return;
        }
        int seconds = (remaining + 19) / 20; // 向上取整，1..5
        Integer shown = OUT_OF_BOUNDS_SHOWN_SECONDS.get(id);
        if (shown == null || shown != seconds) {
            OUT_OF_BOUNDS_SHOWN_SECONDS.put(id, seconds);
            sendOutOfBoundsTitle(player, seconds);
        }
        OUT_OF_BOUNDS_TIMERS.put(id, remaining - 1);
    }

    /**
     * 飞回房间内：取消倒计时并清除屏幕字样（下次飞出重新从整段开始）。
     *
     * @return {@code true} 表示本次调用<b>真的取消了</b>一个进行中的倒计时（供调用方打低频日志）
     */
    public boolean cancelOutOfBounds(ServerPlayer player) {
        UUID id = player.getUUID();
        boolean counting = OUT_OF_BOUNDS_TIMERS.remove(id) != null;
        if (counting || OUT_OF_BOUNDS_SHOWN_SECONDS.remove(id) != null) {
            clearOutOfBoundsTitle(player);
        }
        return counting;
    }

    /** 越界提示当前已显示的秒数（用于"只在秒数变化时发包"）。 */
    private static final java.util.Map<UUID, Integer> OUT_OF_BOUNDS_SHOWN_SECONDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static void sendOutOfBoundsTitle(ServerPlayer player, int seconds) {
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(0, 25, 5));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(
                Component.translatable("message.createcmpor.observation.out_of_bounds_title")));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(
                Component.translatable("message.createcmpor.observation.out_of_bounds_countdown", seconds)));
    }

    /** 清除屏幕中央标题（飞回房间内或退出观察时调用）。 */
    private static void clearOutOfBoundsTitle(ServerPlayer player) {
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(0, 0, 0));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.empty()));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.empty()));
    }

    /** 把被传送进入评估维度的玩家转为观察者（记录其"最后安全位置"作为回退点）。 */
    private static void adoptTeleportedPlayer(MinecraftServer server, ServerPlayer player) {
        if (!player.hasPermissions(Config.OBSERVATION_PERMISSION_LEVEL.get())) {
            // 无观察权限：按原逻辑送回主世界
            ServerLevel overworld = server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5,
                    overworld.getSharedSpawnAngle(), 0.0F);
            message(player, "message.createcmpor.observation.no_permission");
            return;
        }
        CompoundTag safe = player.getPersistentData().getCompound(LAST_SAFE_KEY);
        CompoundTag record = new CompoundTag();
        // 被 tp 进来时记录里没有房间号 → 用 chunk→room 反查补齐，使这类观察者同样受越界监管
        String roomCode = recordRoomCodeAt(player);
        record.putString("room_code", roomCode);
        record.putString("observed_dim", player.level().dimension().location().toString());
        record.putString("return_gametype", player.gameMode.getGameModeForPlayer().getName());
        if (!safe.isEmpty()) {
            record.putString("return_dim", safe.getString("dim"));
            record.putDouble("return_x", safe.getDouble("x"));
            record.putDouble("return_y", safe.getDouble("y"));
            record.putDouble("return_z", safe.getDouble("z"));
            record.putFloat("return_yrot", safe.getFloat("yrot"));
            record.putFloat("return_xrot", safe.getFloat("xrot"));
        } else {
            ServerLevel overworld = server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            record.putString("return_dim", Level.OVERWORLD.location().toString());
            record.putDouble("return_x", spawn.getX() + 0.5);
            record.putDouble("return_y", spawn.getY() + 1.0);
            record.putDouble("return_z", spawn.getZ() + 0.5);
        }
        player.getPersistentData().put(OBSERVATION_KEY, record);
        if (player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
            player.setGameMode(GameType.SPECTATOR);
        }
        message(player, "message.createcmpor.observation.entered");
        CreateCMPOR.LOGGER.info("玩家 {} 被传送进入评估维度 {}，已转为观察者",
                player.getName().getString(), player.level().dimension().location());
    }

    /** 越界判定结果。 */
    private enum Bounds {
        /** 仍在 outerBounds 内。 */
        INSIDE,
        /** 已飞出外壁（含 Y 轴），应进入倒计时。 */
        OUTSIDE,
        /** 拿不到房间信息，无法判定（保持旧行为：不退出，但打一条低频 WARN）。 */
        UNKNOWN
    }

    /**
     * 玩家是否已飞出观察房间的外边界（含 Y 轴——评估维度高度仅 48）。
     *
     * <p>{@link Bounds#UNKNOWN} 出现在两种情况：观察记录里没有 {@code room_code}
     * （只有"被 tp 进入者"才可能，见 {@link #recordRoomCodeAt}），或 CM 查不到该房间。
     */
    private static Bounds boundsOf(ServerPlayer player, CompoundTag record) {
        String roomCode = record.getString("room_code");
        if (roomCode == null || roomCode.isBlank()) {
            return Bounds.UNKNOWN; // 无房间信息：不做过界判定
        }
        RoomInstance room = CompactMachines.room(player.server, roomCode).orElse(null);
        if (room == null) {
            return Bounds.UNKNOWN;
        }
        AABB bounds = room.boundaries().outerBounds();
        return bounds.contains(player.getX(), player.getY(), player.getZ()) ? Bounds.INSIDE : Bounds.OUTSIDE;
    }

    /**
     * 玩家当前所在区块对应的房间号（空串 = 查不到）。
     *
     * <p>CM 的 chunk→room 映射与维度无关（{@code GraphChunkManager} 按 {@link ChunkPos} 索引），
     * 而评估副本发布在<b>源房间的同一坐标</b>上，所以在评估维度里同样能查到源房间号——
     * 用它给"被 tp 进入者"补上 room_code，这类玩家才能享受"飞出房间 → 倒计时 → 退出"。
     */
    private static String recordRoomCodeAt(ServerPlayer player) {
        return CompactMachines.chunkManager()
                .findRoomByChunk(new ChunkPos(player.blockPosition()))
                .orElse("");
    }

    // ===================== 低频诊断日志（只在状态变化时打一条） =====================

    /** 玩家 UUID → 上一次已记录的监管状态；仅状态变化时打日志，避免每 tick 刷屏。 */
    private static final java.util.Map<UUID, String> REGULATION_STATE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final String STATE_OP_EXEMPT = "op-exempt";
    private static final String STATE_REGULATED = "regulated";
    private static final String STATE_BOUNDS_UNKNOWN = "bounds-unknown";

    /** 记录状态；返回 {@code true} = 状态发生变化（需要打日志）。 */
    private static boolean markState(ServerPlayer player, String state) {
        return !state.equals(REGULATION_STATE.put(player.getUUID(), state));
    }

    /** 离开评估维度：忘掉状态，下次进来重新记录一条。 */
    private static void clearRegulationState(ServerPlayer player) {
        REGULATION_STATE.remove(player.getUUID());
    }

    /** 已纳入监管（观察中 / 即将被收纳）：仅记录状态，不产生日志。 */
    private static void markRegulated(ServerPlayer player) {
        markState(player, STATE_REGULATED);
    }

    /** OP 在评估维度内但没有观察记录 → 保持豁免；每次进入评估维度只说明一条。 */
    private static void noteOpExempt(ServerPlayer player) {
        if (markState(player, STATE_OP_EXEMPT)) {
            CreateCMPOR.LOGGER.info("观察诊断：{} 是 OP（权限 ≥{}）且在评估维度内没有观察记录，"
                            + "保持豁免（不转观察者、不做越界/会话监管）。要观察请手持缩小设备右键评估方块进入。",
                    player.getName().getString(), OP_PERMISSION_LEVEL);
        }
    }

    /** 观察者在评估维度内但拿不到房间信息 → 越界判定跳过；每次进入评估维度只警告一条。 */
    private static void noteBoundsUnknown(ServerPlayer player, CompoundTag record) {
        if (markState(player, STATE_BOUNDS_UNKNOWN)) {
            CreateCMPOR.LOGGER.warn("观察诊断：{} 在评估维度内但拿不到房间信息（room_code=\"{}\"，查不到房间），"
                            + "越界判定已跳过——飞出房间不会自动退出（其它退出路径不受影响）。",
                    player.getName().getString(), record.getString("room_code"));
        }
    }

    // ===================== 登录自愈 =====================

    /**
     * 玩家登录：若存在未正常结束的观察记录（下线/崩溃遗留），立即恢复其位置与游戏模式。
     *
     * <p>不依赖会话是否仍存在——只要记录在就执行退出，避免留下孤儿观察者。
     */
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (INSTANCE.isObserving(player)) {
            CreateCMPOR.LOGGER.info("玩家 {} 存在未结束的观察记录，登录时自动恢复",
                    player.getName().getString());
            INSTANCE.exit(player, "message.createcmpor.observation.recovered");
        }
    }

    /** 记录"最后安全位置"（玩家不在评估维度时），供被传送进入者回退。 */
    public static void recordLastSafePosition(ServerPlayer player) {
        if (ParallelEvaluationWorlds.isAnyEvaluationWorld(player.level().dimension())) {
            return;
        }
        CompoundTag safe = new CompoundTag();
        safe.putString("dim", player.level().dimension().location().toString());
        safe.putDouble("x", player.getX());
        safe.putDouble("y", player.getY());
        safe.putDouble("z", player.getZ());
        safe.putFloat("yrot", player.getYRot());
        safe.putFloat("xrot", player.getXRot());
        player.getPersistentData().put(LAST_SAFE_KEY, safe);
    }

    // ===================== 记录读写 =====================

    private static void writeRecord(ServerPlayer player, EvaluationSession session, String roomCode,
                                    ResourceKey<Level> observedDimension) {
        CompoundTag record = new CompoundTag();
        record.putUUID("session_id", session.id());
        record.putString("room_code", roomCode == null ? "" : roomCode);
        record.putString("observed_dim", observedDimension.location().toString());
        record.putString("return_dim", player.level().dimension().location().toString());
        record.putDouble("return_x", player.getX());
        record.putDouble("return_y", player.getY());
        record.putDouble("return_z", player.getZ());
        record.putFloat("return_yrot", player.getYRot());
        record.putFloat("return_xrot", player.getXRot());
        record.putString("return_gametype", player.gameMode.getGameModeForPlayer().getName());
        player.getPersistentData().put(OBSERVATION_KEY, record);
    }

    private static void updateObservedLane(ServerPlayer player, ResourceKey<Level> observedDimension) {
        CompoundTag record = player.getPersistentData().getCompound(OBSERVATION_KEY);
        if (record.isEmpty()) {
            return;
        }
        record.putString("observed_dim", observedDimension.location().toString());
        player.getPersistentData().put(OBSERVATION_KEY, record);
    }

    private static void message(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }
}
