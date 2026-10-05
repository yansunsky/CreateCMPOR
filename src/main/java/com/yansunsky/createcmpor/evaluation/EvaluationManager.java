package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.block.EvaluatorBlockEntity;
import com.yansunsky.createcmpor.block.FactoryBlockEntity;
import com.yansunsky.createcmpor.init.ModBlocks;
import com.yansunsky.createcmpor.init.ModItems;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** 管理 Phase 3 的持久化冻结事务。 */
public final class EvaluationManager {
    public static final EvaluationManager INSTANCE = new EvaluationManager();

    private static final int UNLOAD_TIMEOUT_TICKS = 200;
    private static final int PLAYER_EXIT_TIMEOUT_TICKS = 100;
    private static final int PLAYER_EXIT_FALLBACK_TICKS = 40;
    private static final String LAUNCHER_TRANSACTIONS_TAG = "createcmpor_launcher_transactions";
    private final Map<UUID, EvictionAttempt> playersBeingEvicted = new HashMap<>();
    private final Map<UUID, CompletableFuture<Void>> rollbackWrites = new HashMap<>();

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
        // 闸门（0.5.0，T2b）：一个房间只允许一台工厂。
        // 铁律 I1 只覆盖"单次评估 → 一台工厂"，这里补上**时间维度**：registerFactory 是覆盖写、
        // factoryForRoom 只返回 index[0]、防复制护栏也只自动还原那一台 ⇒ 若允许同一房间反复评估，
        // 每次都会固化出新的一台，而索引与护栏只记得最后一台 ⇒ 多台满配工厂永久留产、
        // 且"进空间搬走原机器"的护栏只挡得住一台。
        // 放在评估最前面（而非固化前）：这里是唯一入口，早失败避免让玩家白等 60~120 秒的完整评估。
        if (existingFactory(server, roomCode).isPresent()) {
            return StartResult.failure(Component.translatable(
                    "message.createcmpor.evaluation.room_already_solidified"));
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
        session.setBranchCount(parallelBranchCount(server, roomCode));
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
                    "message.createcmpor.evaluation.start_failed");
            return StartResult.failure(Component.translatable("message.createcmpor.evaluation.start_failed"));
        }
    }

    /**
     * 闸门（0.5.0，T2b）：该房间是否**已存在实际仍在的工厂**。
     *
     * <p><b>判据落在"实体仍在"而不是"索引里有记录"</b>：索引存的是 {@link GlobalPos}，可能是残留条目
     * ——任何没走 {@code FactoryBlock.removeFromFactoryIndex} 的移除（世界编辑、区块回档、
     * {@code /setblock} 等）都会留下它。只有该维度、该位置**当前仍是 {@link FactoryBlockEntity}**
     * 才算命中；维度未加载、区块未加载、方块已被挖走一律视为"不存在"，
     * 避免一条残留索引把该房间的合法重评永久误判成复制。</p>
     *
     * <p>用 {@link FactoryIndexSavedData#snapshotAll()} 而不是多位置遗留 API：旧档的多工厂组
     * （{@code factory_count > 1}）在索引里是**多条位置**，闸门语义就是"这个房间有没有工厂"，
     * 因此任一位置仍有工厂即算命中（旧档玩家需先用启动棒整组还原、或挖走、或用
     * {@code /ccmpor factory legacy} 清理，才能重新评估）。{@code snapshotAll()} 返回副本，
     * 且不是 {@code @Deprecated} 的遗留 API。</p>
     *
     * <p>玩家**挖走**工厂时 {@code FactoryBlock.removeFromFactoryIndex} 会摘掉索引条目 ⇒ 该路径
     * 自然放行，符合预期（他手里那台仍是自己的"工厂"分支）。</p>
     *
     * <p>⚠️ 注意：护栏 {@link AntiDupeSpaceEntryHandler} 是**索引驱动**的（拿 roomCode 去查
     * {@code factoryForRoom}），而"挖走"恰好把索引条目摘掉 ⇒ <b>物品形态的工厂在护栏眼里不存在</b>，
     * 玩家此后从非机器入口进房间不会被拦。这是护栏自身的**结构性缺口**（既有、非本闸门引入），
     * 记档于 {@code docs/48} 的 <b>R-7</b>；<b>本闸门不覆盖它</b>——闸门只看"世界里还有没有实体工厂"，
     * 看不到玩家背包。</p>
     *
     * @return 仍在的工厂位置；该房间没有仍在的工厂返回 {@link Optional#empty()}
     */
    private static Optional<GlobalPos> existingFactory(MinecraftServer server, String roomCode) {
        List<GlobalPos> positions = FactoryIndexSavedData.get(server).snapshotAll().get(roomCode);
        if (positions == null) {
            return Optional.empty();
        }
        for (GlobalPos candidate : positions) {
            ServerLevel level = server.getLevel(candidate.dimension());
            if (level == null || !level.isLoaded(candidate.pos())) {
                continue;
            }
            if (level.getBlockEntity(candidate.pos()) instanceof FactoryBlockEntity) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** 房间内所有并行空间输入方块配置物品数的最大值；无并行方块为 1。 */
    private static int parallelBranchCount(MinecraftServer server, String roomCode) {
        int[] branchCount = {1};
        CompactMachines.room(server, roomCode).ifPresent(room -> {
            AABB bounds = room.boundaries().outerBounds();
            int startX = (int) Math.floor(bounds.minX);
            int startY = (int) Math.floor(bounds.minY);
            int startZ = (int) Math.floor(bounds.minZ);
            int endX = (int) Math.floor(bounds.maxX - 1.0E-5);
            int endY = (int) Math.floor(bounds.maxY - 1.0E-5);
            int endZ = (int) Math.floor(bounds.maxZ - 1.0E-5);
            for (int x = startX; x <= endX; x++) {
                for (int y = startY; y <= endY; y++) {
                    for (int z = startZ; z <= endZ; z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (room.level().getBlockState(pos).is(ModBlocks.PARALLEL_INPUT.get())
                                && room.level().getBlockEntity(pos)
                                instanceof com.yansunsky.createcmpor.block.ParallelInputBlockEntity parallel) {
                            int count = parallel.configuredItemCount();
                            if (count > branchCount[0]) {
                                branchCount[0] = count;
                            }
                        }
                    }
                }
            }
        });
        return branchCount[0];
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
        rollbackWrites.clear();
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
                        "message.createcmpor.evaluation.recovered_after_restart");
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

    /**
     * 评估方块被玩家破坏：把「破坏」接管为还原流程而不是普通移除。
     *
     * <ul>
     *   <li>存在活跃会话：只有会话发起人（owner）或管理员（op）能破坏；破坏 = 主动取消评估，
     *       走 failSession 状态机回滚（清理副本/票据后恢复原机器并退还启动棒）。</li>
     *   <li>孤立评估方块（无会话，如服务器重启后恢复中断残留）：优先用方块实体自带的原机器镜像
     *       直接恢复（{@link #restoreOrphanEvaluator}），任何玩家破坏都会触发；镜像缺失时允许直接移除。</li>
     *   <li>曾进入 Phase 4 且已失去会话/清理 journal 的孤立方块仍保留管理员门禁
     *       （见 orphan_cleanup_required），防止在残留副本状态不明时误恢复。</li>
     * </ul>
     */
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!event.getState().is(ModBlocks.EVALUATOR.get())) {
            return;
        }
        event.setCanceled(true);
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        boolean operator = player.hasPermissions(2);

        EvaluationSavedData data = EvaluationSavedData.get(player.server);
        GlobalPos machinePos = GlobalPos.of(player.level().dimension(), event.getPos());
        Optional<EvaluationSession> session = data.sessionByMachine(machinePos);
        if (session.isPresent()) {
            if (!operator && !session.get().owner().equals(player.getUUID())) {
                player.displayClientMessage(
                        Component.translatable("message.createcmpor.evaluation.protected"), true);
                return;
            }
            failSession(player.server, data, session.get(),
                    "message.createcmpor.evaluation.cancelled_by_break");
            return;
        }

        if (!(event.getLevel() instanceof ServerLevel level)
                || !(level.getBlockEntity(event.getPos()) instanceof EvaluatorBlockEntity evaluator)) {
            // 无方块实体（无镜像可恢复）：允许直接移除残留标记
            event.setCanceled(false);
            return;
        }
        if (evaluator.isPhase4CleanupRequired()) {
            if (!operator) {
                player.displayClientMessage(
                        Component.translatable("message.createcmpor.evaluation.protected"), true);
                return;
            }
            player.displayClientMessage(
                    Component.translatable("message.createcmpor.evaluation.orphan_cleanup_required"), false);
            return;
        }
        if (restoreOrphanEvaluator(level, evaluator)) {
            queueOrphanLauncherReturn(player.server, evaluator);
            player.displayClientMessage(
                    Component.translatable("message.createcmpor.evaluation.orphan_restored"), false);
            return;
        }
        // 镜像缺失且无法恢复：允许直接移除残留标记（原机器只能重建）
        event.setCanceled(false);
        player.displayClientMessage(
                Component.translatable("message.createcmpor.evaluation.orphan_removed"), false);
    }

    private void tickSession(MinecraftServer server, EvaluationSavedData data, EvaluationSession session) {
        if (!session.factoryInstalled()
                && session.state() != EvaluationSession.State.PREPARED
                && session.state() != EvaluationSession.State.ROLLING_BACK
                && evaluatorMissing(server, session)) {
            failSession(server, data, session,
                    "message.createcmpor.evaluation.evaluator_missing");
            return;
        }
        switch (session.state()) {
            case PREPARED -> rollback(server, data, session,
                    "message.createcmpor.evaluation.incomplete_transaction");
            case EVALUATOR_INSTALLED -> transition(data, session, EvaluationSession.State.EVICTING_PLAYERS);
            case EVICTING_PLAYERS -> tickPlayerEviction(server, data, session);
            case SAVING_SOURCE -> tickSaving(server, data, session);
            case WAITING_UNLOAD -> tickWaitingForUnload(server, data, session);
            case FROZEN, QUEUED, STAGING_SOURCE, STAGING_WRITTEN, STAGING_VERIFIED,
                    RAILWAY_TRANSFER, PUBLISHING, PUBLISHED,
                    PARALLEL_PREPARING, PARALLEL_PUBLISHING, PARALLEL_PUBLISHED,
                    PARALLEL_EVALUATING,
                    EVALUATING, EVALUATED, SOLIDIFYING, CLEANING ->
                    EvaluationCloneManager.INSTANCE.tick(server, data, session);
            case ROLLING_BACK -> rollback(server, data, session, session.rollbackMessageKey());
        }
    }

    private void tickPlayerEviction(MinecraftServer server, EvaluationSavedData data,
                                    EvaluationSession session) {
        RoomInstance room = CompactMachines.room(server, session.roomCode()).orElse(null);
        if (room == null) {
            rollback(server, data, session,
                    "message.createcmpor.evaluation.room_missing");
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
                    "message.createcmpor.evaluation.players_remain");
        }
    }

    private void tickSaving(MinecraftServer server, EvaluationSavedData data, EvaluationSession session) {
        RoomInstance room = CompactMachines.room(server, session.roomCode()).orElse(null);
        if (room == null) {
            rollback(server, data, session,
                    "message.createcmpor.evaluation.room_missing");
            return;
        }
        // 源房间的 dirty chunk 会在 WAITING_UNLOAD 的正常卸载流程中逐区块提交到 vanilla IOWorker。
        // 这里不再调用 ServerLevel.save(true) 或等待全局 IO，避免把整维保存压在服务器 tick 上。
        transition(data, session, EvaluationSession.State.WAITING_UNLOAD);
    }

    private void tickWaitingForUnload(MinecraftServer server, EvaluationSavedData data,
                                      EvaluationSession session) {
        RoomInstance room = CompactMachines.room(server, session.roomCode()).orElse(null);
        if (room == null) {
            rollback(server, data, session,
                    "message.createcmpor.evaluation.room_missing");
            return;
        }
        List<ChunkPos> roomChunks = CompactMachines.roomChunks(session.roomCode()).stream().toList();

        // 冻结门控（0.4.28 统一口径）：判据是「源房间内容不在 level 里」，
        // 而不是「vanilla 卸载队列已排空」。
        // 关键事实：ChunkMap.scheduleUnload 把 holder 从 pendingUnloads 拿掉的唯一路径是
        // isReadyForSaving() 为真，而它挂在 saveSync 上；saveSync 的唯一写入者是
        // ChunkHolder.addSaveDependency，喂的是「升级到 FULL/BLOCK_TICKING/ENTITY_TICKING 的 future」
        // ——与磁盘 IO 无关。这些 future 的完成回调排在服务端主线程邮箱上，且 processUnloads 自身
        // 受 tick 时间预算限制 ⇒ 卡顿服上 pendingUnloads 会长时间非空，队列排空后自己消失。
        // 把 !pendingUnloads 当门控 ⇒ 房间几乎永远无法评估（实测 35/35 次失败全部因此）。
        // 详见 EvaluationStorageBridge.areChunksUnloadedForFreeze 的长注释。
        if (EvaluationStorageBridge.areChunksUnloadedForFreeze(room.level(), roomChunks)) {
            if (EvaluationStorageBridge.hasChunkUnloadBacklog(room.level(), roomChunks)) {
                // 内容已卸载、只是 vanilla 收尾没跑完：不再阻塞冻结。
                // 这里刻意保持**单行**——卡顿服上每次评估都会走到，完整逐区块诊断会刷爆日志
                // （MC 日志是同步 IO，会反过来加重卡顿）。需要详情时把本行改成
                // EvaluationStorageBridge.sourceIdleDiagnostics(...) 即可（内含 holder 明细表）。
                CreateCMPOR.LOGGER.info("房间 {} 内容已卸载（仍有区块在 vanilla 卸载队列收尾，不阻塞冻结）：{}",
                        session.roomCode(),
                        EvaluationStorageBridge.describeUnloadBlocker(room.level(), roomChunks));
            }
            transition(data, session, EvaluationSession.State.FROZEN);
            notifyOwner(server, session, Component.translatable("message.createcmpor.evaluation.frozen"));
            CreateCMPOR.LOGGER.info("房间 {} 已冻结，会话 {}", session.roomCode(), session.id());
            return;
        }

        session.tickState();
        data.changed();
        if (session.stateTicks() >= UNLOAD_TIMEOUT_TICKS) {
            // A：按"实际卡住的子条件"给出准确原因（旧文案一律说"被外部票据加载"，
            // 与诊断里 tickets=[] 的事实矛盾，误导排查方向）。注意：0.3.48 只改了日志文案，
            // 玩家侧 lang 的 unload_timeout 直到 0.3.51 才对齐——两处都要改，缺一则继续误导。
            CreateCMPOR.LOGGER.warn("评估会话 {} 等待源房间 {} 卸载超时（{}）。诊断：\n{}",
                    session.id(), session.roomCode(),
                    EvaluationStorageBridge.describeUnloadBlocker(room.level(), roomChunks),
                    EvaluationStorageBridge.sourceIdleDiagnostics(room.level(), roomChunks));
            rollback(server, data, session,
                    "message.createcmpor.evaluation.unload_timeout");
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

    /**
     * 回滚：恢复原机器并结束会话。
     *
     * <p><b>原因必须以「消息键」传入并立即落到会话上</b>：回滚天然跨 tick
     * （{@link #restoreMachine} 之后还要异步等区块存档落地），首 tick 只会把状态推到
     * {@code ROLLING_BACK}，下一 tick 由
     * {@code case ROLLING_BACK -> rollback(..., session.rollbackMessageKey())} 重新进入。
     * 若此处不持久化，补通知时只能拿到会话里的默认键 {@code runtime_failed}，
     * 玩家看到的将是一句与真实原因无关的"冻结失败"（实机证据：18 次"源房间卸载超时"
     * 全部被误报成 runtime_failed，排查方向被带偏）。</p>
     */
    private void rollback(MinecraftServer server, EvaluationSavedData data, EvaluationSession session,
                          String messageKey) {
        if (!messageKey.equals(session.rollbackMessageKey())) {
            session.setRollbackMessageKey(messageKey);
            data.changed();
        }
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
        if (machineLevel == null) {
            CreateCMPOR.LOGGER.error("无法恢复会话 {}：机器维度 {} 未加载",
                    session.id(), session.machinePos().dimension().location());
            return;
        }
        CompletableFuture<Void> restoreWrite = rollbackWrites.get(session.id());
        if (restoreWrite == null) {
            try {
                restoreMachine(machineLevel, session);
                restoreWrite = EvaluationStorageBridge.saveLoadedChunk(
                        machineLevel, new ChunkPos(session.machinePos().pos()));
                rollbackWrites.put(session.id(), restoreWrite);
            } catch (RuntimeException exception) {
                CreateCMPOR.LOGGER.error("恢复会话 {} 的原机器失败，将在下一 tick 重试", session.id(), exception);
                session.tickState();
                data.changed();
                return;
            }
            return;
        }
        if (!restoreWrite.isDone()) {
            session.tickState();
            data.changed();
            return;
        }
        try {
            restoreWrite.join();
        } catch (CompletionException exception) {
            rollbackWrites.remove(session.id());
            CreateCMPOR.LOGGER.error("保存会话 {} 的还原结果失败，将重试", session.id(), exception.getCause());
            session.tickState();
            data.changed();
            return;
        }
        rollbackWrites.remove(session.id());

        if (session.launcherReturnEligible()) {
            data.addPendingLauncherReturn(session.id(), session.owner());
        }
        data.remove(session.id());
        // 会话结束：把仍在观察该副本的玩家送出并恢复其游戏模式（自愈兜底，防遗留观察者）
        EvaluationObservationManager.exitObserversOf(server, session.id(),
                "message.createcmpor.observation.finished");
        flushTransactions(server);
        EvaluationCloneManager.INSTANCE.afterSessionRemoved(server, data);
        ServerPlayer owner = server.getPlayerList().getPlayer(session.owner());
        if (owner != null) {
            deliverPendingLaunchers(owner, data);
        }
        Component reason = rollbackReason(session);
        notifyOwner(server, session, reason);
        CreateCMPOR.LOGGER.warn("评估会话 {} 已回滚：{}", session.id(), reason.getString());
    }

    /**
     * 渲染回滚原因文案。
     *
     * <p>键存放在会话上（跨 tick / 跨重启），需要参数的原因只有 {@code room_missing}，
     * 且其参数恒为会话自己的 roomCode —— 因此"立即通知"与"下一 tick 补通知"
     * 渲染出的是同一句话。</p>
     */
    private static Component rollbackReason(EvaluationSession session) {
        if ("message.createcmpor.evaluation.room_missing".equals(session.rollbackMessageKey())) {
            return Component.translatable(session.rollbackMessageKey(), session.roomCode());
        }
        return Component.translatable(session.rollbackMessageKey());
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
        // critical journal 已在进入 Phase 4 前同步写入；Evaluator 只需标脏，
        // 不再在 ServerTick 中执行整维 save/IO wait。
        evaluator.markPhase4CleanupRequired();
    }

    private void failSession(MinecraftServer server, EvaluationSavedData data,
                             EvaluationSession session, String messageKey) {
        session.setRollbackMessageKey(messageKey);
        if (session.hasPhase4Manifest()) {
            EvaluationCloneManager.INSTANCE.requestCleanup(server, data, session, messageKey);
        } else {
            rollback(server, data, session, messageKey);
        }
    }

    private static void transition(EvaluationSavedData data, EvaluationSession session,
                                   EvaluationSession.State state) {
        session.setState(state);
        data.changed();
    }

    static void deliverPendingLaunchers(ServerPlayer player, EvaluationSavedData data) {
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
        // SavedData 文件很小，保留同步写；禁止在 ServerTick 上等待全局 IOWorker。
        server.overworld().getDataStorage().save();
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