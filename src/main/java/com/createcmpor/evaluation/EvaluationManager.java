package com.createcmpor.evaluation;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.block.EvaluatorBlockEntity;
import com.createcmpor.init.ModBlocks;
import com.createcmpor.init.ModItems;
import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.dimension.CompactDimension;
import dev.compactmods.machines.api.room.RoomInstance;
import dev.compactmods.machines.room.RoomHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.common.IOUtilities;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 管理 Phase 3 的持久化冻结事务。 */
public final class EvaluationManager {
    public static final EvaluationManager INSTANCE = new EvaluationManager();

    private static final int UNLOAD_TIMEOUT_TICKS = 200;
    private static final int PLAYER_EXIT_TIMEOUT_TICKS = 100;
    private static final int PLAYER_EXIT_FALLBACK_TICKS = 40;
    private static final String LAUNCHER_TRANSACTIONS_TAG = "createcmpor_launcher_transactions";
    private final Map<UUID, EvictionAttempt> playersBeingEvicted = new HashMap<>();

    private EvaluationManager() {
    }

    public StartResult start(ServerPlayer player, GlobalPos machinePos, String roomCode, ItemStack launcher) {
        MinecraftServer server = player.server;
        EvaluationSavedData data = EvaluationSavedData.get(server);
        if (data.sessionByRoom(roomCode).isPresent() || data.sessionByMachine(machinePos).isPresent()) {
            return StartResult.failure(Component.translatable("message.createcmpor.evaluation.already_running"));
        }
        if (CompactMachines.room(server, roomCode).isEmpty()) {
            return StartResult.failure(Component.translatable("message.createcmpor.evaluation.room_missing", roomCode));
        }

        ServerLevel machineLevel = server.getLevel(machinePos.dimension());
        if (machineLevel == null) {
            return StartResult.failure(Component.translatable("message.createcmpor.evaluation.dimension_missing"));
        }
        BlockEntity machineBlockEntity = machineLevel.getBlockEntity(machinePos.pos());
        if (machineBlockEntity == null) {
            return StartResult.failure(Component.translatable("message.createcmpor.evaluation.machine_missing"));
        }

        BlockState originalState = machineLevel.getBlockState(machinePos.pos());
        boolean consumeLauncher = !player.isCreative();
        EvaluationSession session = new EvaluationSession(
                UUID.randomUUID(), player.getUUID(), machinePos, roomCode, originalState,
                machineBlockEntity.saveWithFullMetadata(machineLevel.registryAccess()));
        if (!consumeLauncher) {
            session = new EvaluationSession(session.id(), session.owner(), session.machinePos(), session.roomCode(),
                    session.originalState(), session.originalBlockEntityNbt(), false);
        }
        data.put(session);
        try {
            flushTransactions(server);
            if (consumeLauncher) {
                markLauncherTransaction(player, session.id(), true);
                launcher.shrink(1);
                server.getPlayerList().saveAll();
            }
            installEvaluator(machineLevel, session);
            transition(data, session, EvaluationSession.State.EVALUATOR_INSTALLED);
            transition(data, session, EvaluationSession.State.EVICTING_PLAYERS);
            return StartResult.success(session);
        } catch (RuntimeException exception) {
            CreateCMPOR.LOGGER.error("无法建立评估会话 {}", session.id(), exception);
            rollback(server, data, session,
                    Component.translatable("message.createcmpor.evaluation.start_failed"));
            return StartResult.failure(Component.translatable("message.createcmpor.evaluation.start_failed"));
        }
    }

    public boolean isRoomLocked(MinecraftServer server, String roomCode) {
        return EvaluationSavedData.get(server).sessionByRoom(roomCode).isPresent();
    }

    public boolean isMachineLocked(MinecraftServer server, GlobalPos machinePos) {
        return EvaluationSavedData.get(server).sessionByMachine(machinePos).isPresent();
    }

    public Optional<EvaluationSession> sessionByRoom(MinecraftServer server, String roomCode) {
        return EvaluationSavedData.get(server).sessionByRoom(roomCode);
    }

    public Optional<EvaluationSession> sessionAt(MinecraftServer server, GlobalPos position) {
        if (!CompactDimension.LEVEL_KEY.equals(position.dimension())) {
            return Optional.empty();
        }
        Optional<String> roomCode = CompactMachines.chunkManager().findRoomByChunk(new ChunkPos(position.pos()));
        return roomCode.flatMap(code -> EvaluationSavedData.get(server).sessionByRoom(code));
    }

    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        EvaluationSavedData data = EvaluationSavedData.get(server);
        for (EvaluationSession session : data.sessions()) {
            try {
                tickSession(server, data, session);
            } catch (RuntimeException exception) {
                CreateCMPOR.LOGGER.error("评估会话 {} 执行失败，开始回滚", session.id(), exception);
                failSession(server, data, session,
                        "message.createcmpor.evaluation.runtime_failed");
            }
        }
    }

    public void onServerStarted(ServerStartedEvent event) {
        playersBeingEvicted.clear();
        MinecraftServer server = event.getServer();
        EvaluationSavedData data = EvaluationSavedData.get(server);
        EvaluationCloneManager.INSTANCE.onServerStarted(server, data);
        List<EvaluationSession> sessions = new ArrayList<>(data.sessions());
        if (!sessions.isEmpty()) {
            CreateCMPOR.LOGGER.warn("检测到 {} 个未完成评估会话，开始恢复", sessions.size());
        }
        for (EvaluationSession session : sessions) {
            if (session.hasPhase4Manifest()) {
                continue;
            }
            try {
                rollback(server, data, session,
                        Component.translatable("message.createcmpor.evaluation.recovered_after_restart"));
            } catch (RuntimeException exception) {
                CreateCMPOR.LOGGER.error("启动恢复会话 {} 失败，将在服务端 tick 中重试", session.id(), exception);
            }
        }
    }

    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        EvaluationSavedData data = EvaluationSavedData.get(player.server);
        deliverPendingLaunchers(player, data);
    }

    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!event.getState().is(ModBlocks.EVALUATOR.get())) {
            return;
        }
        event.setCanceled(true);
        if (!(event.getPlayer() instanceof ServerPlayer player)
                || !(player.hasPermissions(2) && player.isCreative())) {
            event.getPlayer().displayClientMessage(
                    Component.translatable("message.createcmpor.evaluation.protected"), true);
            return;
        }
        EvaluationSavedData data = EvaluationSavedData.get(player.server);
        GlobalPos machinePos = GlobalPos.of(player.level().dimension(), event.getPos());
        Optional<EvaluationSession> session = data.sessionByMachine(machinePos);
        if (session.isPresent()) {
            failSession(player.server, data, session.get(),
                    "message.createcmpor.evaluation.admin_rollback");
            return;
        }
        if (event.getLevel() instanceof ServerLevel level
                && level.getBlockEntity(event.getPos()) instanceof EvaluatorBlockEntity evaluator) {
            if (evaluator.isPhase4CleanupRequired()) {
                player.displayClientMessage(
                        Component.translatable("message.createcmpor.evaluation.orphan_cleanup_required"), false);
                return;
            }
            if (restoreOrphanEvaluator(level, evaluator)) {
                queueOrphanLauncherReturn(player.server, evaluator);
                player.displayClientMessage(
                        Component.translatable("message.createcmpor.evaluation.orphan_restored"), false);
            } else {
                event.setCanceled(false);
                player.displayClientMessage(
                        Component.translatable("message.createcmpor.evaluation.orphan_removed"), false);
            }
        } else {
            event.setCanceled(false);
        }
    }

    private void tickSession(MinecraftServer server, EvaluationSavedData data, EvaluationSession session) {
        if (session.state() != EvaluationSession.State.PREPARED
                && session.state() != EvaluationSession.State.ROLLING_BACK
                && evaluatorMissing(server, session)) {
            failSession(server, data, session,
                    "message.createcmpor.evaluation.evaluator_missing");
            return;
        }
        switch (session.state()) {
            case PREPARED -> rollback(server, data, session,
                    Component.translatable("message.createcmpor.evaluation.incomplete_transaction"));
            case EVALUATOR_INSTALLED -> transition(data, session, EvaluationSession.State.EVICTING_PLAYERS);
            case EVICTING_PLAYERS -> tickPlayerEviction(server, data, session);
            case SAVING_SOURCE -> tickSaving(server, data, session);
            case WAITING_UNLOAD -> tickWaitingForUnload(server, data, session);
            case FROZEN, QUEUED, STAGING_SOURCE, STAGING_WRITTEN, STAGING_VERIFIED,
                    RAILWAY_TRANSFER, PUBLISHING, PUBLISHED, CLEANING ->
                    EvaluationCloneManager.INSTANCE.tick(server, data, session);
            case ROLLING_BACK -> rollback(server, data, session,
                    Component.translatable(session.rollbackMessageKey()));
        }
    }

    private void tickPlayerEviction(MinecraftServer server, EvaluationSavedData data,
                                    EvaluationSession session) {
        RoomInstance room = CompactMachines.room(server, session.roomCode()).orElse(null);
        if (room == null) {
            rollback(server, data, session,
                    Component.translatable("message.createcmpor.evaluation.room_missing", session.roomCode()));
            return;
        }

        AABB bounds = room.boundaries().outerBounds();
        List<ServerPlayer> players = new ArrayList<>(room.level().getEntitiesOfClass(ServerPlayer.class, bounds));
        if (players.isEmpty()) {
            transition(data, session, EvaluationSession.State.SAVING_SOURCE);
            return;
        }

        for (ServerPlayer player : players) {
            requestRoomExit(player, session);
        }
        session.tickState();
        data.changed();
        if (session.stateTicks() >= PLAYER_EXIT_TIMEOUT_TICKS) {
            playersBeingEvicted.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(session.id()));
            rollback(server, data, session,
                    Component.translatable("message.createcmpor.evaluation.players_remain"));
        }
    }

    private void tickSaving(MinecraftServer server, EvaluationSavedData data, EvaluationSession session) {
        RoomInstance room = CompactMachines.room(server, session.roomCode()).orElse(null);
        if (room == null) {
            rollback(server, data, session,
                    Component.translatable("message.createcmpor.evaluation.room_missing", session.roomCode()));
            return;
        }
        room.level().save(null, true, false);
        IOUtilities.waitUntilIOWorkerComplete();
        transition(data, session, EvaluationSession.State.WAITING_UNLOAD);
    }

    private void tickWaitingForUnload(MinecraftServer server, EvaluationSavedData data,
                                      EvaluationSession session) {
        RoomInstance room = CompactMachines.room(server, session.roomCode()).orElse(null);
        if (room == null) {
            rollback(server, data, session,
                    Component.translatable("message.createcmpor.evaluation.room_missing", session.roomCode()));
            return;
        }
        boolean anyLoaded = CompactMachines.roomChunks(session.roomCode()).stream()
                .anyMatch(chunkPos -> room.level().getChunkSource().hasChunk(chunkPos.x, chunkPos.z));
        if (!anyLoaded) {
            transition(data, session, EvaluationSession.State.FROZEN);
            notifyOwner(server, session, Component.translatable("message.createcmpor.evaluation.frozen"));
            CreateCMPOR.LOGGER.info("房间 {} 已冻结，会话 {}", session.roomCode(), session.id());
            return;
        }

        session.tickState();
        data.changed();
        if (session.stateTicks() >= UNLOAD_TIMEOUT_TICKS) {
            rollback(server, data, session,
                    Component.translatable("message.createcmpor.evaluation.unload_timeout"));
        }
    }

    private static void installEvaluator(ServerLevel level, EvaluationSession session) {
        BlockPos pos = session.machinePos().pos();
        if (!level.setBlock(pos, ModBlocks.EVALUATOR.get().defaultBlockState(), Block.UPDATE_ALL)) {
            throw new IllegalStateException("无法替换原 CompactMachines 机器");
        }
        if (!(level.getBlockEntity(pos) instanceof EvaluatorBlockEntity evaluator)) {
            throw new IllegalStateException("评估方块实体未创建");
        }
        evaluator.initialize(session.id(), session.owner(), session.launcherReturnEligible(),
                session.roomCode(), session.originalState(), session.originalBlockEntityNbt());
    }

    private boolean evaluatorMissing(MinecraftServer server, EvaluationSession session) {
        ServerLevel machineLevel = server.getLevel(session.machinePos().dimension());
        if (machineLevel == null || !machineLevel.isLoaded(session.machinePos().pos())) {
            return false;
        }
        if (!machineLevel.getBlockState(session.machinePos().pos()).is(ModBlocks.EVALUATOR.get())) {
            return true;
        }
        if (!(machineLevel.getBlockEntity(session.machinePos().pos()) instanceof EvaluatorBlockEntity evaluator)) {
            return true;
        }
        return !session.id().equals(evaluator.getSessionId())
                || !session.owner().equals(evaluator.getOwnerId())
                || session.launcherReturnEligible() != evaluator.isLauncherReturnEligible()
                || !session.roomCode().equals(evaluator.getRoomCode())
                || evaluator.getOriginalMachineState() == null
                || evaluator.getSavedOriginalNbt() == null;
    }

    public void requestRoomExit(ServerPlayer player, EvaluationSession session) {
        int currentTick = player.server.getTickCount();
        EvictionAttempt current = playersBeingEvicted.get(player.getUUID());
        if (current != null) {
            if (current.sessionId().equals(session.id())
                    && currentTick - current.startedTick() >= PLAYER_EXIT_FALLBACK_TICKS) {
                playersBeingEvicted.remove(player.getUUID());
                moveToOverworldSpawn(player);
                player.displayClientMessage(
                        Component.translatable("message.createcmpor.evaluation.room_exiled"), false);
            }
            return;
        }

        playersBeingEvicted.put(player.getUUID(), new EvictionAttempt(session.id(), currentTick));
        RoomHelper.teleportPlayerOutOfRoom(player).whenComplete((result, error) ->
                player.server.execute(() -> {
                    playersBeingEvicted.remove(player.getUUID(), new EvictionAttempt(session.id(), currentTick));
                    boolean sessionStillActive = EvaluationSavedData.get(player.server)
                            .sessionByRoom(session.roomCode())
                            .map(active -> active.id().equals(session.id()))
                            .orElse(false);
                    if (sessionStillActive && (error != null || result == null || !result.successful())) {
                        moveToOverworldSpawn(player);
                    }
                    if (sessionStillActive) {
                        player.displayClientMessage(
                                Component.translatable("message.createcmpor.evaluation.room_exiled"), false);
                    }
                }));
    }

    private static boolean restoreOrphanEvaluator(ServerLevel level, EvaluatorBlockEntity evaluator) {
        if (evaluator.getOriginalMachineState() == null || evaluator.getSavedOriginalNbt() == null) {
            return false;
        }
        try {
            BlockPos pos = evaluator.getBlockPos();
            BlockState originalState = NbtUtils.readBlockState(
                    level.registryAccess().lookupOrThrow(Registries.BLOCK),
                    evaluator.getOriginalMachineState());
            BlockEntity restored = BlockEntity.loadStatic(
                    pos, originalState, evaluator.getSavedOriginalNbt(), level.registryAccess());
            if (restored == null) {
                return false;
            }
            level.setBlock(pos, originalState, Block.UPDATE_ALL);
            level.setBlockEntity(restored);
            restored.setChanged();
            return true;
        } catch (RuntimeException exception) {
            CreateCMPOR.LOGGER.error("无法从孤立 Evaluator 镜像恢复原机器", exception);
            return false;
        }
    }

    private static void queueOrphanLauncherReturn(MinecraftServer server, EvaluatorBlockEntity evaluator) {
        if (!evaluator.isLauncherReturnEligible()
                || evaluator.getSessionId() == null
                || evaluator.getOwnerId() == null) {
            return;
        }
        EvaluationSavedData data = EvaluationSavedData.get(server);
        data.addPendingLauncherReturn(evaluator.getSessionId(), evaluator.getOwnerId());
        flushTransactions(server);
        ServerPlayer owner = server.getPlayerList().getPlayer(evaluator.getOwnerId());
        if (owner != null) {
            deliverPendingLaunchers(owner, data);
        }
    }

    private void rollback(MinecraftServer server, EvaluationSavedData data, EvaluationSession session,
                          Component reason) {
        playersBeingEvicted.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(session.id()));
        if (session.hasPhase4Manifest()) {
            EvaluationManifest manifest = session.manifest();
            if (manifest.targetWriteIntent() || manifest.ticketsAdded() || manifest.targetReady()
                    || session.state() != EvaluationSession.State.ROLLING_BACK) {
                EvaluationCloneManager.INSTANCE.requestCleanup(
                        server, data, session, session.rollbackMessageKey());
                return;
            }
        }
        if (session.state() != EvaluationSession.State.ROLLING_BACK) {
            session.setState(EvaluationSession.State.ROLLING_BACK);
            data.changed();
        } else if (session.stateTicks() > 0 && session.stateTicks() < 100) {
            session.tickState();
            data.changed();
            return;
        } else if (session.stateTicks() >= 100) {
            session.resetStateTicks();
        }
        ServerLevel machineLevel = server.getLevel(session.machinePos().dimension());
        if (machineLevel != null) {
            try {
                restoreMachine(machineLevel, session);
                machineLevel.getChunkSource().save(true);
            } catch (RuntimeException exception) {
                CreateCMPOR.LOGGER.error("恢复会话 {} 的原机器失败，将在 100 tick 后重试", session.id(), exception);
                session.tickState();
                data.changed();
                return;
            }
        } else {
            CreateCMPOR.LOGGER.error("无法恢复会话 {}：机器维度 {} 未加载",
                    session.id(), session.machinePos().dimension().location());
            return;
        }

        if (session.launcherReturnEligible()) {
            data.addPendingLauncherReturn(session.id(), session.owner());
        }
        data.remove(session.id());
        flushTransactions(server);
        EvaluationCloneManager.INSTANCE.afterSessionRemoved(server, data);
        ServerPlayer owner = server.getPlayerList().getPlayer(session.owner());
        if (owner != null) {
            deliverPendingLaunchers(owner, data);
        }
        notifyOwner(server, session, reason);
        CreateCMPOR.LOGGER.warn("评估会话 {} 已回滚：{}", session.id(), reason.getString());
    }

    private static void restoreMachine(ServerLevel level, EvaluationSession session) {
        BlockPos pos = session.machinePos().pos();
        BlockState current = level.getBlockState(pos);
        if (!current.equals(session.originalState())) {
            level.setBlock(pos, session.originalState(), Block.UPDATE_ALL);
        }
        BlockEntity restored = BlockEntity.loadStatic(
                pos, session.originalState(), session.originalBlockEntityNbt(), level.registryAccess());
        if (restored == null) {
            throw new IllegalStateException("无法从持久化 NBT 恢复 CompactMachines 机器");
        }
        level.setBlockEntity(restored);
        restored.setChanged();
        level.sendBlockUpdated(pos, session.originalState(), session.originalState(), Block.UPDATE_ALL);
    }

    void markEvaluatorPhase4(MinecraftServer server, EvaluationSession session) {
        ServerLevel machineLevel = server.getLevel(session.machinePos().dimension());
        if (machineLevel == null
                || !(machineLevel.getBlockEntity(session.machinePos().pos()) instanceof EvaluatorBlockEntity evaluator)
                || !session.id().equals(evaluator.getSessionId())) {
            throw new IllegalStateException("无法在 EvaluatorBlockEntity 上登记 Phase 4 清理责任");
        }
        evaluator.markPhase4CleanupRequired();
        machineLevel.save(null, true, false);
        IOUtilities.waitUntilIOWorkerComplete();
    }

    private void failSession(MinecraftServer server, EvaluationSavedData data,
                             EvaluationSession session, String messageKey) {
        session.setRollbackMessageKey(messageKey);
        if (session.hasPhase4Manifest()) {
            EvaluationCloneManager.INSTANCE.requestCleanup(server, data, session, messageKey);
        } else {
            rollback(server, data, session, Component.translatable(messageKey));
        }
    }

    private static void transition(EvaluationSavedData data, EvaluationSession session,
                                   EvaluationSession.State state) {
        session.setState(state);
        data.changed();
    }

    private static void deliverPendingLaunchers(ServerPlayer player, EvaluationSavedData data) {
        boolean inventoryChanged = false;
        for (UUID sessionId : data.pendingLauncherReturns(player.getUUID()).keySet()) {
            if (!hasLauncherTransaction(player, sessionId)) {
                data.removePendingLauncherReturn(sessionId);
                continue;
            }
            ItemStack launcher = new ItemStack(ModItems.LAUNCHER_STICK.get());
            if (!player.getInventory().add(launcher)) {
                player.displayClientMessage(
                        Component.translatable("message.createcmpor.evaluation.launcher_inventory_full"), false);
                continue;
            }
            markLauncherTransaction(player, sessionId, false);
            inventoryChanged = true;
            player.displayClientMessage(Component.translatable("message.createcmpor.evaluation.launcher_returned"), false);
        }
        if (inventoryChanged) {
            player.server.getPlayerList().saveAll();
        }
        for (UUID sessionId : data.pendingLauncherReturns(player.getUUID()).keySet()) {
            if (!hasLauncherTransaction(player, sessionId)) {
                data.removePendingLauncherReturn(sessionId);
            }
        }
        flushTransactions(player.server);
    }

    private static boolean hasLauncherTransaction(ServerPlayer player, UUID sessionId) {
        return player.getPersistentData().getCompound(LAUNCHER_TRANSACTIONS_TAG)
                .getBoolean(sessionId.toString());
    }

    private static void markLauncherTransaction(ServerPlayer player, UUID sessionId, boolean present) {
        net.minecraft.nbt.CompoundTag transactions = player.getPersistentData()
                .getCompound(LAUNCHER_TRANSACTIONS_TAG);
        if (present) {
            transactions.putBoolean(sessionId.toString(), true);
        } else {
            transactions.remove(sessionId.toString());
        }
        player.getPersistentData().put(LAUNCHER_TRANSACTIONS_TAG, transactions);
    }

    public static void moveToOverworldSpawn(ServerPlayer player) {
        ServerLevel overworld = player.server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();
        player.teleportTo(overworld, spawn.getX() + 0.5, spawn.getY() + 1.0, spawn.getZ() + 0.5,
                overworld.getSharedSpawnAngle(), 0.0F);
    }

    private static void notifyOwner(MinecraftServer server, EvaluationSession session, Component message) {
        ServerPlayer owner = server.getPlayerList().getPlayer(session.owner());
        if (owner != null) {
            owner.displayClientMessage(message, false);
        }
    }

    private static void flushTransactions(MinecraftServer server) {
        server.overworld().getDataStorage().save();
        IOUtilities.waitUntilIOWorkerComplete();
    }

    public record StartResult(boolean successful, EvaluationSession session, Component message) {
        private static StartResult success(EvaluationSession session) {
            return new StartResult(true, session,
                    Component.translatable("message.createcmpor.evaluation.started"));
        }

        private static StartResult failure(Component message) {
            return new StartResult(false, null, message);
        }
    }

    private record EvictionAttempt(UUID sessionId, int startedTick) {
    }
}
