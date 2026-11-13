package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.room.RoomInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 评估副本观察（黑盒查看产线）。
 *
 * <p><b>目的</b>：评估期间玩家只能看到"进行中"，若产线本身有问题则要等整场评估跑完才发现。
 * 允许玩家以观察者模式进入评估副本实时旁观产线，提前发现错误。
 *
 * <p><b>评估维度行为</b>：观察者不参与刷怪；目标区块由评估票据持续加载。原版允许 spectator 产生区块 ticket，
 * 因此观察者可能影响邻近区块的加载，并且在会话清理前必须退出。</p>
 * <ul>
 * <li>{@code ChunkMap.playerIsCloseEnoughForSpawning}：观察者被直接排除，不参与刷怪；</li>
 * <li>评估目标区块由 {@code setChunkForced} 常 tick；玩家视距仍可能额外影响周边区块。</li>
 * </ul>
 *
 * <p><b>进入方式</b>：手持 {@code compactmachines:personal_shrinking_device} 右键评估方块。
 * 已观察中再次右键 → 切换到下一个<b>已就绪</b>的并行分支；潜行右键 → 退出观察。
 * 外部传送进入者只有在能唯一绑定活动 session/lane 时才接管，否则 fail-closed 送回。
 *
 * <p><b>玩家处置</b>：观察功能开启时，进入评估维度的玩家由本管理器绑定到唯一活动 session/lane；
 * 无法安全绑定或 session 已开始清理时 fail-closed 送出。观察功能关闭时，OP 仍可用于评估维度调试，
 * 但正在清理的目标 ticket 范围会临时清场。</p>
 *
 * <p><b>退出时机</b>：飞出房间外边界（含 Y 轴）、会话/分支不再可观察、清理目标 ticket 范围、玩家重新登录（自愈）。
 * 退出时传回进入前位置并恢复原游戏模式。</p>
 *
 * <p><b>诊断探针（低频）</b>：进入/切换、退出（带原因与目的地）、越界倒计时开始、飞回取消
 * 各打一条 INFO；状态只在变化时记录，不按 tick 刷屏。</p>
 *
 * <p><b>离线/崩溃安全</b>：观察记录写在玩家 {@code PersistentData}（随玩家存档持久化），
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

    private static final int CLEANUP_PLAYER_SAMPLES = 4;
    private static final int CLEANUP_PLAYER_DETAILS_LIMIT = 1400;

    /** Read saved bindings as-is; candidate distance does not identify an actual PLAYER ticket owner. */
    static String cleanupPlayerSummary(ServerLevel level, EvaluationSession session, List<ChunkPos> chunks) {
        if (!level.getServer().isSameThread()) {
            return "players=unknown(not_server_thread) candidates=unknown";
        }
        StringBuilder summary = new StringBuilder(512);
        try {
            boolean spectatorsGenerateChunks = level.getGameRules().getBoolean(GameRules.RULE_SPECTATORSGENERATECHUNKS);
            int viewDistance = Math.max(2, Math.min(32, level.getServer().getPlayerList().getViewDistance()));
            List<ServerPlayer> players = level.players();
            ServerPlayer[] candidates = new ServerPlayer[CLEANUP_PLAYER_SAMPLES];
            ServerPlayer[] others = new ServerPlayer[CLEANUP_PLAYER_SAMPLES];
            int candidateSamples = 0;
            int otherSamples = 0;
            int eligible = 0;
            int inRange = 0;
            int candidateCount = 0;
            int unknown = 0;
            for (ServerPlayer player : players) {
                try {
                    boolean mayHoldTickets = ObservationSessionBinding.mayHoldPlayerTickets(
                            player.isSpectator(), spectatorsGenerateChunks);
                    boolean near = cleanupPlayerInViewRange(player, chunks, viewDistance);
                    if (mayHoldTickets) eligible++;
                    if (near) inRange++;
                    if (mayHoldTickets && near) {
                        candidateCount++;
                        if (candidateSamples < candidates.length) candidates[candidateSamples++] = player;
                    } else if (otherSamples < others.length) {
                        others[otherSamples++] = player;
                    }
                } catch (RuntimeException exception) {
                    unknown++;
                    if (otherSamples < others.length) others[otherSamples++] = player;
                }
            }
            summary.append("players=").append(players.size()).append(" eligibleKnown=").append(eligible)
                    .append(" inViewRangeKnown=").append(inRange).append(" candidatesKnown=").append(candidateCount)
                    .append(" unknownPlayers=").append(unknown).append(" spectatorsGenerateChunks=")
                    .append(spectatorsGenerateChunks).append(" viewDistance=").append(viewDistance)
                    .append(" candidateOnly=true");
            for (int i = 0; i < candidateSamples; i++) {
                appendCleanupPlayer(summary, level, session, candidates[i], chunks, viewDistance,
                        spectatorsGenerateChunks);
            }
            for (int i = 0; i < otherSamples && candidateSamples + i < CLEANUP_PLAYER_SAMPLES; i++) {
                appendCleanupPlayer(summary, level, session, others[i], chunks, viewDistance,
                        spectatorsGenerateChunks);
            }
        } catch (RuntimeException exception) {
            summary.append(" playersOrCandidates=unknown(").append(exception.getClass().getSimpleName()).append(')');
        }
        return cleanupPlayerBounded(summary);
    }

    private static boolean cleanupPlayerInViewRange(ServerPlayer player, List<ChunkPos> chunks, int viewDistance) {
        ChunkPos position = player.chunkPosition();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkPos target = chunks.get(i);
            if (ObservationSessionBinding.withinPlayerTicketRange(
                    position.x, position.z, target.x, target.z, viewDistance)) {
                return true;
            }
        }
        return false;
    }

    private static void appendCleanupPlayer(StringBuilder summary, ServerLevel level, EvaluationSession session,
                                            ServerPlayer player, List<ChunkPos> chunks, int viewDistance,
                                            boolean spectatorsGenerateChunks) {
        summary.append(" | player{");
        try {
            ChunkPos position = player.chunkPosition();
            boolean near = cleanupPlayerInViewRange(player, chunks, viewDistance);
            boolean candidate = near && ObservationSessionBinding.mayHoldPlayerTickets(
                    player.isSpectator(), spectatorsGenerateChunks);
            summary.append("uuid=").append(player.getUUID()).append(",chunk=(").append(position.x).append(',')
                    .append(position.z).append("),spectator=").append(player.isSpectator())
                    .append(",inViewRange=").append(near).append(",ticketCandidate=").append(candidate);
            CompoundTag persistent = player.getPersistentData();
            if (!persistent.contains(OBSERVATION_KEY)) {
                summary.append(",binding=untracked,rawSession=<missing>,rawRoom=<missing>,rawDim=<missing>");
                return;
            }
            if (!persistent.contains(OBSERVATION_KEY, Tag.TAG_COMPOUND)) {
                summary.append(",binding=invalid,rawRecord=unknown(invalid_record_type)");
                return;
            }
            CompoundTag record = persistent.getCompound(OBSERVATION_KEY);
            UUID recordedSession = record.hasUUID("session_id") ? record.getUUID("session_id") : null;
            boolean invalidSession = record.contains("session_id") && recordedSession == null;
            boolean self = session.id().equals(recordedSession);
            summary.append(",binding=").append(invalidSession ? "invalid" : recordedSession == null ? "legacy"
                    : self ? "self" : "other_or_stale").append(",rawSession=");
            if (recordedSession != null) {
                summary.append(recordedSession);
            } else {
                summary.append(invalidSession ? "<invalid_uuid>" : "<missing>");
            }
            summary.append(",rawRoom=");
            appendCleanupRecordedString(summary, record, "room_code");
            summary.append(",rawDim=");
            appendCleanupRecordedString(summary, record, "observed_dim");
            boolean validRoom = record.contains("room_code", Tag.TAG_STRING) && !record.getString("room_code").isBlank();
            boolean validDimension = record.contains("observed_dim", Tag.TAG_STRING)
                    && !record.getString("observed_dim").isBlank();
            boolean dimensionMatches = validDimension
                    && record.getString("observed_dim").equals(level.dimension().location().toString());
            boolean roomMatches = validRoom && record.getString("room_code").equals(session.roomCode());
            summary.append(",recordedDimMatches=").append(validDimension ? Boolean.toString(dimensionMatches) : "unknown")
                    .append(",recordedRoomMatches=").append(validRoom ? Boolean.toString(roomMatches) : "unknown");
            if ((validDimension && !dimensionMatches) || (self && validRoom && !roomMatches)) {
                summary.append(",recordConsistency=stale_record");
            } else if (!validDimension || !validRoom) {
                summary.append(",recordConsistency=unknown(missing_or_invalid_fields)");
            }
            summary.append(",boundSessionLiveness=unknown");
        } catch (RuntimeException exception) {
            summary.append(",recordOrPlayer=unknown(").append(exception.getClass().getSimpleName()).append(')');
        } finally {
            summary.append('}');
        }
    }

    private static void appendCleanupRecordedString(StringBuilder summary, CompoundTag record, String key) {
        if (!record.contains(key)) {
            summary.append("<missing>");
        } else if (!record.contains(key, Tag.TAG_STRING)) {
            summary.append("<invalid_type>");
        } else {
            String value = record.getString(key);
            int length = Math.min(value.length(), 64);
            summary.append('"');
            for (int i = 0; i < length; i++) {
                char character = value.charAt(i);
                summary.append(Character.isISOControl(character) ? ' ' : character);
            }
            if (value.length() > length) summary.append("...");
            summary.append('"');
        }
    }

    private static String cleanupPlayerBounded(StringBuilder summary) {
        if (summary.length() > CLEANUP_PLAYER_DETAILS_LIMIT) {
            int end = CLEANUP_PLAYER_DETAILS_LIMIT - 3;
            if (Character.isHighSurrogate(summary.charAt(end - 1))) end--;
            summary.setLength(end);
            summary.append("...");
        }
        return summary.toString();
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
        if (targetKey == null || !isObservationLiveInDimension(session, targetKey)) {
            message(player, "message.createcmpor.observation.not_ready");
            return;
        }
        ServerLevel target = server.getLevel(targetKey);
        if (target == null) {
            message(player, "message.createcmpor.observation.not_ready");
            return;
        }

        boolean switching = isObserving(player);
        if (switching) {
            CompoundTag current = player.getPersistentData().getCompound(OBSERVATION_KEY);
            if (!current.hasUUID("session_id") || !session.id().equals(current.getUUID("session_id"))) {
                // 切换会话必须先完成旧记录的返回流程，再建立新返回点。
                INSTANCE.exit(player, null);
                switching = false;
            }
        }
        if (!switching) {
            // 首次进入：记录进入前的位置与游戏模式（跨下线/崩溃持久化）
            writeRecord(player, session, roomCode, targetKey);
        } else {
            updateObservedLane(player, session, targetKey);
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

    /** 副本是否已就绪，且会话还没有进入固化/清理阶段。 */
    private static boolean isCopyLive(EvaluationSession session) {
        EvaluationSession.State state = session.state();
        if (!session.parallelBranches().isEmpty()) {
            return state == EvaluationSession.State.PARALLEL_PUBLISHED
                    || state == EvaluationSession.State.PARALLEL_EVALUATING;
        }
        return session.manifest() != null
                && (state == EvaluationSession.State.PUBLISHED
                || state == EvaluationSession.State.EVALUATING
                || state == EvaluationSession.State.EVALUATED);
    }

    private static boolean isObservationLiveInDimension(EvaluationSession session,
                                                        ResourceKey<Level> dimension) {
        EvaluationSession.State state = session.state();
        if (state == EvaluationSession.State.PARALLEL_PUBLISHED
                || state == EvaluationSession.State.PARALLEL_EVALUATING) {
            return session.parallelBranches().stream().anyMatch(branch -> branch.targetReady()
                    && branch.result() == null
                    && branch.manifest().targetDimension().equals(dimension));
        }
        return (state == EvaluationSession.State.PUBLISHED
                || state == EvaluationSession.State.EVALUATING
                || state == EvaluationSession.State.EVALUATED)
                && session.manifest() != null
                && session.manifest().targetDimension().equals(dimension);
    }

    private static Optional<EvaluationSession> resolveObservationSession(EvaluationSavedData data,
                                                                         ServerPlayer player,
                                                                         ResourceKey<Level> dimension,
                                                                         String roomCode) {
        List<ObservationSessionBinding.Candidate> candidates = new ArrayList<>();
        for (EvaluationSession session : data.sessions()) {
            for (EvaluationManifest manifest : sessionManifests(session)) {
                boolean live = isObservationLiveInDimension(session, manifest.targetDimension());
                Set<Long> chunks = manifest.chunks().stream()
                        .map(chunk -> chunk.chunkPos().toLong())
                        .collect(java.util.stream.Collectors.toSet());
                candidates.add(new ObservationSessionBinding.Candidate(session.id(), session.roomCode(),
                        manifest.targetDimension().location().toString(), chunks, live));
            }
        }
        long playerChunk = new ChunkPos(player.blockPosition()).toLong();
        return ObservationSessionBinding.resolve(candidates, dimension.location().toString(),
                roomCode, playerChunk).flatMap(data::session);
    }

    /** 已就绪（targetReady）的并行分支维度，按分支序号升序；串行时为空表。 */
    private static List<ResourceKey<Level>> readyLaneDimensions(MinecraftServer server, EvaluationSession session) {
        List<ResourceKey<Level>> lanes = new ArrayList<>();
        for (EvaluationBranch branch : session.parallelBranches()) {
            if (!branch.targetReady() || branch.result() != null) {
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

    /** 清理会话副本前退出该 session 的观察者并移走其 footprint 内无主玩家。 */
    public static void exitPlayersFromSessionCopies(MinecraftServer server, EvaluationSession session,
                                                   String reasonKey) {
        List<EvaluationManifest> manifests = cleanupManifests(session);
        Map<ResourceKey<Level>, Set<Long>> footprint = new HashMap<>();
        for (EvaluationManifest manifest : manifests) {
            Set<Long> chunks = footprint.computeIfAbsent(manifest.targetDimension(), ignored -> new HashSet<>());
            for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
                chunks.add(chunk.chunkPos().toLong());
            }
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CompoundTag record = player.getPersistentData().getCompound(OBSERVATION_KEY);
            boolean sessionRecord = record.hasUUID("session_id")
                    && session.id().equals(record.getUUID("session_id"));
            boolean legacySessionRecord = !record.isEmpty() && !record.hasUUID("session_id")
                    && footprint.containsKey(player.level().dimension())
                    && belongsToCleanupSession(record, player, session, footprint.keySet());
            if (sessionRecord || legacySessionRecord) {
                INSTANCE.exit(player, reasonKey);
                continue;
            }

            boolean mayHoldPlayerTickets = mayHoldPlayerTickets(player);
            Set<Long> chunks = footprint.get(player.level().dimension());
            if (chunks == null) {
                continue;
            }
            if (!record.hasUUID("session_id") && !mayHoldPlayerTickets) {
                continue;
            }
            boolean insideTargetChunk = chunks.contains(new ChunkPos(player.blockPosition()).toLong());
            boolean boundToOtherLiveSession = false;
            if (record.hasUUID("session_id") && !session.id().equals(record.getUUID("session_id"))) {
                EvaluationSession other = EvaluationSavedData.get(server)
                        .session(record.getUUID("session_id")).orElse(null);
                boundToOtherLiveSession = other != null
                        && isObservationLiveInDimension(other, player.level().dimension());
            }
            boolean targetChunkOwnedByOtherSession = insideTargetChunk
                    && isTargetChunkOwnedByOtherSession(EvaluationSavedData.get(server), session,
                    player.level().dimension(), new ChunkPos(player.blockPosition()).toLong());
            if (!ObservationSessionBinding.mayEvictCleanupOccupant(
                    boundToOtherLiveSession, insideTargetChunk, targetChunkOwnedByOtherSession)) {
                continue;
            }
            if (record.hasUUID("session_id")) {
                if (!withinPlayerTicketRange(player, chunks)) {
                    continue;
                }
                INSTANCE.exit(player, reasonKey);
                continue;
            }
            if (!withinPlayerTicketRange(player, chunks)) {
                continue;
            }
            String recordedRoom = record.getString("room_code");
            if (!record.isEmpty() && !recordedRoom.isBlank()
                    && !session.roomCode().equals(recordedRoom) && !insideTargetChunk) {
                continue;
            }
            if (record.isEmpty()) {
                ejectUntrackedPlayer(player, reasonKey);
            } else {
                // 无 session_id 的旧记录无法证明归属；只在目标 chunk 冲突或房间匹配时 fail-closed。
                INSTANCE.exit(player, reasonKey);
            }
        }
    }

    /** 清理阶段兜底：不允许玩家继续占住即将清理的目标 chunk 或其票据视距。 */
    public static boolean evictPlayerIfCopyCleaning(ServerPlayer player) {
        boolean spectatorsGenerateChunks = player.serverLevel().getGameRules()
                .getBoolean(GameRules.RULE_SPECTATORSGENERATECHUNKS);
        if (!ObservationSessionBinding.mayHoldPlayerTickets(player.isSpectator(), spectatorsGenerateChunks)) {
            return false;
        }
        ResourceKey<Level> dimension = player.level().dimension();
        EvaluationSavedData data = EvaluationSavedData.get(player.server);
        for (EvaluationSession session : data.sessions()) {
            EvaluationSession.State state = session.state();
            if (state != EvaluationSession.State.CLEANING
                    && state != EvaluationSession.State.SOLIDIFYING) {
                continue;
            }
            List<EvaluationManifest> manifests = cleanupManifests(session);
            boolean ticketRange = withinPlayerTicketRange(player, dimension, manifests);
            if (!ticketRange) {
                continue;
            }
            ChunkPos playerChunk = new ChunkPos(player.blockPosition());
            boolean insideTargetChunk = manifests.stream()
                    .filter(manifest -> manifest.targetDimension().equals(dimension))
                    .flatMap(manifest -> manifest.chunks().stream())
                    .anyMatch(chunk -> chunk.chunkPos().equals(playerChunk));

            CompoundTag record = player.getPersistentData().getCompound(OBSERVATION_KEY);
            if (record.hasUUID("session_id")) {
                UUID recordedSession = record.getUUID("session_id");
                if (session.id().equals(recordedSession)) {
                    INSTANCE.exit(player, "message.createcmpor.observation.finished");
                    return true;
                }
                EvaluationSession other = data.session(recordedSession).orElse(null);
                if (other != null && isObservationLiveInDimension(other, dimension) && !insideTargetChunk) {
                    return false;
                }
            }
            if (insideTargetChunk && isTargetChunkOwnedByOtherSession(
                    data, session, dimension, playerChunk.toLong())) {
                return false;
            }
            String recordedRoom = record.getString("room_code");
            if (!record.hasUUID("session_id") && !record.isEmpty() && !recordedRoom.isBlank()
                    && !session.roomCode().equals(recordedRoom) && !insideTargetChunk) {
                continue;
            }
            if (record.isEmpty()) {
                ejectUntrackedPlayer(player, "message.createcmpor.observation.finished");
            } else {
                INSTANCE.exit(player, "message.createcmpor.observation.finished");
            }
            return true;
        }
        return false;
    }

    private static boolean mayHoldPlayerTickets(ServerPlayer player) {
        return ObservationSessionBinding.mayHoldPlayerTickets(player.isSpectator(),
                player.serverLevel().getGameRules().getBoolean(GameRules.RULE_SPECTATORSGENERATECHUNKS));
    }

    private static boolean withinPlayerTicketRange(ServerPlayer player, Set<Long> targetChunks) {
        ChunkPos position = new ChunkPos(player.blockPosition());
        int viewDistance = Math.max(2, Math.min(32, player.server.getPlayerList().getViewDistance()));
        for (long packed : targetChunks) {
            if (withinPlayerTicketRange(position, new ChunkPos(packed), viewDistance)) {
                return true;
            }
        }
        return false;
    }

    private static boolean withinPlayerTicketRange(ServerPlayer player, ResourceKey<Level> dimension,
                                                   List<EvaluationManifest> manifests) {
        ChunkPos position = new ChunkPos(player.blockPosition());
        int viewDistance = Math.max(2, Math.min(32, player.server.getPlayerList().getViewDistance()));
        for (EvaluationManifest manifest : manifests) {
            if (!manifest.targetDimension().equals(dimension)) {
                continue;
            }
            for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
                if (withinPlayerTicketRange(position, chunk.chunkPos(), viewDistance)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean withinPlayerTicketRange(ChunkPos player, ChunkPos target, int viewDistance) {
        return ObservationSessionBinding.withinPlayerTicketRange(
                player.x, player.z, target.x, target.z, viewDistance);
    }

    private static List<EvaluationManifest> sessionManifests(EvaluationSession session) {
        List<EvaluationManifest> manifests = new ArrayList<>();
        if (session.manifest() != null) {
            manifests.add(session.manifest());
        }
        manifests.addAll(session.parallelBranchManifests());
        return manifests;
    }

    private static List<EvaluationManifest> cleanupManifests(EvaluationSession session) {
        List<EvaluationManifest> manifests;
        if (session.hasParallelBranches()) {
            manifests = session.parallelBranchManifests();
        } else if (session.branchCount() > 1 && session.parallelAllowed()) {
            // 并行 lane 已被清空或尚未建立时，父级 manifest 仅是 staging 清单，不是已发布目标副本。
            return List.of();
        } else {
            manifests = session.manifest() == null ? List.of() : List.of(session.manifest());
        }
        return manifests.stream()
                .filter(manifest -> ObservationSessionBinding.mayHoldPlayerTickets(
                        session.cleanTargetOnCancel(), manifest.ticketsAdded(), manifest.targetReady(),
                        manifest.targetWriteIntent()))
                .toList();
    }

    private static boolean isTargetChunkOwnedByOtherSession(EvaluationSavedData data,
                                                             EvaluationSession cleanupSession,
                                                             ResourceKey<Level> dimension,
                                                             long targetChunk) {
        for (EvaluationSession other : data.sessions()) {
            if (other.id().equals(cleanupSession.id())) {
                continue;
            }
            for (EvaluationManifest manifest : cleanupManifests(other)) {
                if (manifest.targetDimension().equals(dimension)
                        && manifest.chunks().stream().anyMatch(chunk ->
                                chunk.chunkPos().toLong() == targetChunk)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean belongsToCleanupSession(CompoundTag record, ServerPlayer player,
                                                   EvaluationSession session,
                                                   Set<ResourceKey<Level>> dimensions) {
        String observedDimension = record.getString("observed_dim");
        if (observedDimension.isBlank()) {
            observedDimension = player.level().dimension().location().toString();
        }
        Set<String> dimensionIds = dimensions.stream()
                .map(dimension -> dimension.location().toString())
                .collect(java.util.stream.Collectors.toSet());
        return ObservationSessionBinding.belongsToCleanup(null, session.id(),
                record.getString("room_code"), session.roomCode(), observedDimension, dimensionIds);
    }

    private static void ejectUntrackedPlayer(ServerPlayer player, String reasonKey) {
        CompoundTag safe = player.getPersistentData().getCompound(LAST_SAFE_KEY);
        ResourceKey<Level> dimension = Level.OVERWORLD;
        boolean useSafePosition = false;
        if (!safe.isEmpty() && !safe.getString("dim").isBlank()) {
            try {
                ResourceKey<Level> savedDimension = ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION,
                        ResourceLocation.parse(safe.getString("dim")));
                if (!ParallelEvaluationWorlds.isAnyEvaluationWorld(savedDimension)
                        && player.server.getLevel(savedDimension) != null) {
                    dimension = savedDimension;
                    useSafePosition = true;
                }
            } catch (RuntimeException ignored) {
                // Invalid saved return point: fall back to the overworld spawn.
            }
        }
        ServerLevel target = player.server.getLevel(dimension);
        if (target == null) {
            target = player.server.overworld();
            useSafePosition = false;
        }
        double x = useSafePosition ? safe.getDouble("x") : target.getSharedSpawnPos().getX() + 0.5;
        double y = useSafePosition ? safe.getDouble("y") : target.getSharedSpawnPos().getY() + 1.0;
        double z = useSafePosition ? safe.getDouble("z") : target.getSharedSpawnPos().getZ() + 0.5;
        float yRot = useSafePosition ? safe.getFloat("yrot") : 0.0F;
        float xRot = useSafePosition ? safe.getFloat("xrot") : 0.0F;
        player.teleportTo(target, x, y, z, yRot, xRot);
        clearRegulationState(player);
        player.displayClientMessage(Component.translatable(reasonKey), false);
        CreateCMPOR.LOGGER.info("观察清理：无主玩家 {} 已移出评估副本 {}", player.getName().getString(),
                target.dimension().location());
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
                markRegulated(player);
                // 被传送模组/指令送进来：仅能唯一绑定到活动 session/lane 时才接管。
                adoptTeleportedPlayer(server, data, player);
                continue;
            }
            markRegulated(player);
            CompoundTag record = player.getPersistentData().getCompound(OBSERVATION_KEY);
            ResourceKey<Level> observedDimension = player.level().dimension();
            EvaluationSession observedSession;
            if (record.hasUUID("session_id")) {
                observedSession = data.session(record.getUUID("session_id")).orElse(null);
                String recordedDimension = record.getString("observed_dim");
                if (observedSession == null
                        || !isObservationLiveInDimension(observedSession, observedDimension)
                        || (!recordedDimension.isBlank()
                        && !recordedDimension.equals(observedDimension.location().toString()))
                        || (!record.getString("room_code").isBlank()
                        && !record.getString("room_code").equals(observedSession.roomCode()))) {
                    INSTANCE.exit(player, "message.createcmpor.observation.finished");
                    continue;
                }
                if (recordedDimension.isBlank()) {
                    record.putString("observed_dim", observedDimension.location().toString());
                    player.getPersistentData().put(OBSERVATION_KEY, record);
                }
            } else {
                String recordedRoomCode = record.getString("room_code");
                String currentRoomCode = recordRoomCodeAt(player);
                if (!recordedRoomCode.isBlank() && !currentRoomCode.isBlank()
                        && !recordedRoomCode.equals(currentRoomCode)) {
                    INSTANCE.exit(player, "message.createcmpor.observation.finished");
                    continue;
                }
                String roomCode = recordedRoomCode.isBlank() ? currentRoomCode : recordedRoomCode;
                observedSession = resolveObservationSession(data, player, observedDimension, roomCode)
                        .orElse(null);
                if (observedSession == null) {
                    INSTANCE.exit(player, "message.createcmpor.observation.finished");
                    continue;
                }
                record.putUUID("session_id", observedSession.id());
                record.putString("room_code", observedSession.roomCode());
                record.putString("observed_dim", observedDimension.location().toString());
                player.getPersistentData().put(OBSERVATION_KEY, record);
            }
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

    /** 把被传送进入评估维度的玩家转为观察者；无唯一活动 session 时 fail-closed 送回。 */
    private static void adoptTeleportedPlayer(MinecraftServer server, EvaluationSavedData data,
                                              ServerPlayer player) {
        if (!player.hasPermissions(Config.OBSERVATION_PERMISSION_LEVEL.get())) {
            // 无观察权限：按原逻辑送回主世界
            ServerLevel overworld = server.overworld();
            BlockPos spawn = overworld.getSharedSpawnPos();
            player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5,
                    overworld.getSharedSpawnAngle(), 0.0F);
            message(player, "message.createcmpor.observation.no_permission");
            return;
        }

        ResourceKey<Level> observedDimension = player.level().dimension();
        String roomCode = recordRoomCodeAt(player);
        EvaluationSession session = resolveObservationSession(
                data, player, observedDimension, roomCode).orElse(null);
        if (session == null) {
            ejectUntrackedPlayer(player, "message.createcmpor.observation.not_ready");
            return;
        }

        CompoundTag safe = player.getPersistentData().getCompound(LAST_SAFE_KEY);
        CompoundTag record = new CompoundTag();
        record.putUUID("session_id", session.id());
        record.putString("room_code", session.roomCode());
        record.putString("observed_dim", observedDimension.location().toString());
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
        CreateCMPOR.LOGGER.info("玩家 {} 被传送进入评估维度 {}，已绑定会话 {} 并转为观察者",
                player.getName().getString(), observedDimension.location(), session.id());
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

    private static void updateObservedLane(ServerPlayer player, EvaluationSession session,
                                            ResourceKey<Level> observedDimension) {
        CompoundTag record = player.getPersistentData().getCompound(OBSERVATION_KEY);
        if (record.isEmpty()) {
            return;
        }
        record.putUUID("session_id", session.id());
        record.putString("room_code", session.roomCode());
        record.putString("observed_dim", observedDimension.location().toString());
        player.getPersistentData().put(OBSERVATION_KEY, record);
    }

    private static void message(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }
}
