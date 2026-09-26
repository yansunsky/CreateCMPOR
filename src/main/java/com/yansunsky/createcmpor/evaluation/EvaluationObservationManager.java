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
                continue;
            }
            // OP 豁免：保留管理员自由进出评估维度的调试能力
            if (player.hasPermissions(OP_PERMISSION_LEVEL)) {
                continue;
            }
            if (!INSTANCE.isObserving(player)) {
                // 被传送模组/指令送进来：下一 tick 转为观察者并纳入监管
                adoptTeleportedPlayer(server, player);
                continue;
            }
            CompoundTag record = player.getPersistentData().getCompound(OBSERVATION_KEY);
            if (record.hasUUID("session_id") && data.session(record.getUUID("session_id")).isEmpty()) {
                INSTANCE.exit(player, "message.createcmpor.observation.finished");
                continue;
            }
            // 越界不立即退出：给 OUT_OF_BOUNDS_GRACE_TICKS 的缓冲倒计时（屏幕中央大字提示），
            // 飞回房间内立即取消并清除字样。超时才真正退出观察。
            if (isOutsideRoom(player, record)) {
                INSTANCE.tickOutOfBounds(player);
            } else {
                INSTANCE.cancelOutOfBounds(player);
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
        int remaining = OUT_OF_BOUNDS_TIMERS.getOrDefault(id, OUT_OF_BOUNDS_GRACE_TICKS);
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

    /** 飞回房间内：取消倒计时并清除屏幕字样（下次飞出重新从整段开始）。 */
    public void cancelOutOfBounds(ServerPlayer player) {
        UUID id = player.getUUID();
        if (OUT_OF_BOUNDS_TIMERS.remove(id) != null || OUT_OF_BOUNDS_SHOWN_SECONDS.remove(id) != null) {
            clearOutOfBoundsTitle(player);
        }
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
        record.putString("room_code", "");
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

    /** 是否已飞出观察房间的外边界（含 Y 轴——评估维度高度仅 48）。 */
    private static boolean isOutsideRoom(ServerPlayer player, CompoundTag record) {
        String roomCode = record.getString("room_code");
        if (roomCode == null || roomCode.isBlank()) {
            return false; // 被 tp 进入且无房间信息：不做过界判定
        }
        RoomInstance room = CompactMachines.room(player.server, roomCode).orElse(null);
        if (room == null) {
            return false;
        }
        AABB bounds = room.boundaries().outerBounds();
        return !bounds.contains(player.getX(), player.getY(), player.getZ());
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
