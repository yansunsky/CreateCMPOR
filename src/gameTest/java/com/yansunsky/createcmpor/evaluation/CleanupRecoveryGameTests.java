package com.yansunsky.createcmpor.evaluation;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Real recovery fixtures, restricted to the isolated GameTestServer; no production test hooks. */
@GameTestHolder("createcmpor")
@PrefixGameTestTemplate(false)
public final class CleanupRecoveryGameTests {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TEMPLATE = "io_fixture";
    private static final BlockPos MARKER = new BlockPos(1, 2, 1);
    private static final UUID RESTART_ID = UUID.fromString("b9ee3809-fdaf-435f-ae26-5619b7560515");
    private static final String RESTART_ROOM = "cleanup-restart-0515";
    private static final ResourceKey<Level> RESTART_TARGET = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath("createcmpor", "cleanup_restart_missing_0515"));
    private static final String[] FUTURES = {"sourceFuture", "tagFuture", "storedFuture", "writeFuture", "auxiliaryWriteFuture"};

    private CleanupRecoveryGameTests() { }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void pending_late_fatal_keeps_owned_io(GameTestHelper helper) throws Exception {
        MinecraftServer server = isolatedServer(helper);
        EvaluationSavedData data = EvaluationSavedData.get(server);
        EvaluationSession session = newSession(helper, "late-fatal", helper.getLevel().dimension());
        CompletableFuture<Object> pending = new CompletableFuture<>();
        try {
            session.manifest().cleanupRecovery(exhausted());
            Tag budget = session.manifest().cleanupRecovery().save();
            Object runtime = seedRuntime(session.id(), false);
            writeField(runtime, "sourceFuture", pending);
            EvaluationCloneManager.syncCritical(server, data);
            EvaluationCloneManager.INSTANCE.requestCleanup(server, data, session,
                    "message.createcmpor.evaluation.runtime_failed");
            EvaluationCloneManager.INSTANCE.tick(server, data, session);
            helper.assertTrue(runtimes().get(session.id()) == runtime
                            && readField(runtime, "sourceFuture") == pending && !pending.isDone(),
                    "requestCleanup/tick must retain the exact pending owned future and runtime");
            helper.assertTrue(!Boolean.TRUE.equals(readField(runtime, "stagingClosed"))
                            && EvaluationCloneManager.INSTANCE.hasPendingCleanupIo(session),
                    "blocked cleanup cannot close an owner or disregard its pending IO");
            SyntheticVmError fatal = new SyntheticVmError();
            pending.completeExceptionally(new CompletionException(new IllegalStateException("synthetic wrapper", fatal)));
            boolean observed = false;
            try {
                EvaluationCloneManager.INSTANCE.observePendingFatalErrors();
            } catch (VirtualMachineError actual) {
                if (actual != fatal) throw actual;
                observed = true;
            }
            helper.assertTrue(observed && budget.equals(session.manifest().cleanupRecovery().save()),
                    "late fatal must escape without consuming or resetting the blocked budget");
            assertResponsible(helper, session);
        } finally {
            pending.complete(null);
            discard(server, session);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void blocked_and_backoff_gate_before_runtime(GameTestHelper helper) throws Exception {
        MinecraftServer server = isolatedServer(helper);
        EvaluationSavedData data = EvaluationSavedData.get(server);
        EvaluationSession blocked = newSession(helper, "blocked-gate", missingTarget());
        EvaluationSession backoff = newSession(helper, "backoff-gate", missingTarget());
        try {
            blocked.manifest().cleanupRecovery(exhausted());
            backoff.manifest().cleanupRecovery().recordIoFailure(server.overworld().getGameTime(),
                    CleanupRecovery.STAGE_DELETE_RECORDS);
            helper.assertTrue(!backoff.manifest().cleanupRecovery().blocked()
                            && backoff.manifest().cleanupRecovery().retryNotBefore() > server.overworld().getGameTime(),
                    "backoff fixture must have a real future deadline rather than a blocked budget");
            Tag blockedBefore = blocked.manifest().cleanupRecovery().save();
            Tag backoffBefore = backoff.manifest().cleanupRecovery().save();
            EvaluationCloneManager.syncCritical(server, data);
            for (int tick = 0; tick < 4; tick++) {
                EvaluationCloneManager.INSTANCE.tick(server, data, blocked);
                EvaluationCloneManager.INSTANCE.tick(server, data, backoff);
            }
            helper.assertTrue(server.getLevel(blocked.manifest().targetDimension()) == null
                            && server.getLevel(backoff.manifest().targetDimension()) == null,
                    "gate fixtures must use genuinely missing target dimensions");
            helper.assertTrue(!runtimes().containsKey(blocked.id()) && !runtimes().containsKey(backoff.id()),
                    "blocked/backoff gates must run before opening staging or a new runtime");
            helper.assertTrue(blockedBefore.equals(blocked.manifest().cleanupRecovery().save())
                            && backoffBefore.equals(backoff.manifest().cleanupRecovery().save()),
                    "a gated target must not requireTarget, refresh backoff, or consume budget");
            assertResponsible(helper, blocked);
            assertResponsible(helper, backoff);
        } finally {
            discard(server, blocked, backoff);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void loaded_chunk_wait_idle_does_not_consume_budget(GameTestHelper helper) throws Exception {
        MinecraftServer server = isolatedServer(helper);
        EvaluationSavedData data = EvaluationSavedData.get(server);
        ServerLevel level = helper.getLevel();
        EvaluationSession session = newSession(helper, "wait-idle", level.dimension());
        BlockPos absolute = helper.absolutePos(MARKER);
        BlockState previous = level.getBlockState(absolute);
        try {
            helper.setBlock(MARKER, Blocks.GOLD_BLOCK);
            session.manifest().setTargetWriteIntent(true);
            Object runtime = seedRuntime(session.id(), true);
            Tag budget = session.manifest().cleanupRecovery().save();
            EvaluationCloneManager.syncCritical(server, data);
            for (int tick = 0; tick < 4; tick++) EvaluationCloneManager.INSTANCE.tick(server, data, session);
            helper.assertTrue(runtimes().get(session.id()) == runtime
                            && CleanupRecovery.STAGE_WAIT_IDLE.equals(readField(runtime, "cleanupStage")),
                    "cleanup must reach the real WAIT_IDLE gate before the loaded-chunk assertions");
            helper.assertTrue(level.getChunkSource().hasChunk(absolute.getX() >> 4, absolute.getZ() >> 4),
                    "WAIT_IDLE fixture must retain its genuinely loaded structure chunk");
            for (String name : FUTURES) {
                helper.assertTrue(readField(runtime, name) == null, "WAIT_IDLE must not create IO: " + name);
            }
            helper.assertTrue(level.getBlockState(absolute).is(Blocks.GOLD_BLOCK)
                            && session.manifest().targetWriteIntent()
                            && budget.equals(session.manifest().cleanupRecovery().save()),
                    "loaded chunks must keep waiting without deletion, budget changes, or lost write responsibility");
            assertResponsible(helper, session);
        } finally {
            helper.setBlock(MARKER, previous);
            discard(server, session);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void failed_lane_does_not_starve_healthy_lane(GameTestHelper helper) throws Exception {
        MinecraftServer server = isolatedServer(helper);
        EvaluationSavedData data = EvaluationSavedData.get(server);
        EvaluationSession parent = newSession(helper, "lane-isolation", missingTarget());
        EvaluationBranch failing = branch(parent, 0, missingTarget());
        EvaluationBranch healthy = branch(parent, 1, helper.getLevel().dimension());
        parent.setParallelBranches(List.of(failing, healthy));
        parent.setBranchCount(2);
        try {
            Object parentRuntime = seedRuntime(parent.id(), true);
            Object failingRuntime = seedRuntime(failing.branchId(), true);
            seedRuntime(healthy.branchId(), true);
            failing.setTicketsRemoved(true);
            healthy.setTicketsRemoved(true);
            EvaluationCloneManager.syncCritical(server, data);
            for (int tick = 0; tick < 4; tick++) EvaluationCloneManager.INSTANCE.tick(server, data, parent);
            helper.assertTrue(failing.manifest().cleanupRecovery().blocked()
                            && CleanupRecovery.REASON_MISSING_DIMENSION.equals(failing.manifest().cleanupRecovery().reason())
                            && !failing.targetCleaned(),
                    "the missing lane must block locally and retain its target responsibility");
            helper.assertTrue(healthy.targetCleaned() && !runtimes().containsKey(healthy.branchId())
                            && healthy.manifest().cleanupRecovery().failures() == 0,
                    "a later healthy lane must finish without sharing the failed lane budget");
            helper.assertTrue(runtimes().get(parent.id()) == parentRuntime
                            && runtimes().get(failing.branchId()) == failingRuntime,
                    "unfinished blocked lane and shared parent must retain their original runtime responsibility");
            assertResponsible(helper, parent);
        } finally {
            discard(server, parent);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void resume_command_guards(GameTestHelper helper) throws Exception {
        MinecraftServer server = isolatedServer(helper);
        EvaluationSavedData data = EvaluationSavedData.get(server);
        EvaluationSession permission = newSession(helper, "permission", helper.getLevel().dimension());
        EvaluationSession active = newSession(helper, "active-budget", helper.getLevel().dimension());
        EvaluationSession unknown = newSession(helper, "unknown-budget", helper.getLevel().dimension());
        EvaluationSession corrupt = newSession(helper, "corrupt-budget", helper.getLevel().dimension());
        EvaluationSession pending = newSession(helper, "pending-resume", helper.getLevel().dimension());
        CompletableFuture<Object> owned = new CompletableFuture<>();
        try {
            permission.manifest().cleanupRecovery(exhausted());
            pending.manifest().cleanupRecovery(exhausted());
            active.manifest().cleanupRecovery().recordIoFailure(server.overworld().getGameTime(), CleanupRecovery.STAGE_DELETE_RECORDS);
            CompoundTag futureSchema = new CompoundTag();
            futureSchema.putInt("version", 17);
            futureSchema.putString("future_obligation", "retain this evidence");
            unknown.manifest().cleanupRecovery(rawRecovery(futureSchema));
            EvaluationBranch knownFirst = branch(unknown, 0, helper.getLevel().dimension());
            EvaluationBranch knownSecond = branch(unknown, 1, helper.getLevel().dimension());
            knownFirst.manifest().cleanupRecovery(exhausted());
            knownSecond.manifest().cleanupRecovery(exhausted());
            unknown.setParallelBranches(List.of(knownFirst, knownSecond));
            unknown.setBranchCount(2);
            CleanupRecovery knownFirstBefore = knownFirst.manifest().cleanupRecovery();
            CleanupRecovery knownSecondBefore = knownSecond.manifest().cleanupRecovery();
            Tag knownFirstTag = knownFirstBefore.save();
            Tag knownSecondTag = knownSecondBefore.save();
            corrupt.manifest().cleanupRecovery(rawRecovery(StringTag.valueOf("invalid original field type")));
            Object pendingRuntime = seedRuntime(pending.id(), false);
            writeField(pendingRuntime, "sourceFuture", owned);
            EvaluationCloneManager.syncCritical(server, data);
            Tag permissionBefore = permission.manifest().cleanupRecovery().save();
            boolean rejected;
            try {
                rejected = execute(server, permission.id().toString(), 1) == 0;
            } catch (CommandSyntaxException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected && permissionBefore.equals(permission.manifest().cleanupRecovery().save()),
                    "permission 1 must not resume a ready blocked target");
            helper.assertTrue(execute(server, "not-a-uuid", 2) == 0, "invalid UUID must return zero through the dispatcher");
            Tag activeBefore = active.manifest().cleanupRecovery().save();
            assertCommandRejected(helper, active, "no_blocked_targets");
            helper.assertTrue(activeBefore.equals(active.manifest().cleanupRecovery().save()),
                    "ordinary backoff cannot be used to reset an active budget");
            for (EvaluationSession invalid : List.of(unknown, corrupt)) {
                Tag raw = invalid.manifest().cleanupRecovery().save();
                assertCommandRejected(helper, invalid, "invalid_state");
                helper.assertTrue(raw.equals(invalid.manifest().cleanupRecovery().save())
                                && !invalid.manifest().cleanupRecovery().canResume(),
                        "command rejection must retain unknown/corrupt raw NBT");
            }
            helper.assertTrue(knownFirst.manifest().cleanupRecovery() == knownFirstBefore
                            && knownSecond.manifest().cleanupRecovery() == knownSecondBefore
                            && knownFirstTag.equals(knownFirstBefore.save()) && knownSecondTag.equals(knownSecondBefore.save()),
                    "an invalid parent must reject the entire batch without changing eligible branch budgets");
            Tag pendingBefore = pending.manifest().cleanupRecovery().save();
            assertCommandRejected(helper, pending, "pending_io");
            helper.assertTrue(!owned.isDone() && runtimes().get(pending.id()) == pendingRuntime
                            && readField(pendingRuntime, "sourceFuture") == owned
                            && pendingBefore.equals(pending.manifest().cleanupRecovery().save()),
                    "pending IO rejection must retain the future and blocked budget");
            for (EvaluationSession session : List.of(permission, active, unknown, corrupt, pending)) assertResponsible(helper, session);
        } finally {
            owned.complete(null);
            discard(server, permission, active, unknown, corrupt, pending);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void resume_journal_failure_rolls_back_all_lanes(GameTestHelper helper) throws Exception {
        MinecraftServer server = isolatedServer(helper);
        EvaluationSavedData data = EvaluationSavedData.get(server);
        EvaluationSession parent = newSession(helper, "journal-rollback", missingTarget());
        EvaluationBranch first = branch(parent, 0, helper.getLevel().dimension());
        EvaluationBranch second = branch(parent, 1, helper.getLevel().dimension());
        parent.setParallelBranches(List.of(first, second));
        parent.setBranchCount(2);
        first.manifest().cleanupRecovery(exhausted());
        second.manifest().cleanupRecovery(exhausted());
        CleanupRecovery firstBefore = first.manifest().cleanupRecovery();
        CleanupRecovery secondBefore = second.manifest().cleanupRecovery();
        Tag firstTag = firstBefore.save();
        Tag secondTag = secondBefore.save();
        Path journal = server.getWorldPath(LevelResource.ROOT).resolve("data/createcmpor/evaluation-critical-journal.nbt");
        Path backup = journal.resolveSibling(journal.getFileName() + ".fixture-" + UUID.randomUUID() + ".backup");
        Path blocker = journal.resolve("fixture-write-blocker");
        boolean moved = false;
        boolean directory = false;
        try {
            EvaluationCloneManager.syncCritical(server, data);
            helper.assertTrue(Files.isRegularFile(journal), "a real journal must exist before injecting its write failure");
            Files.move(journal, backup);
            moved = true;
            Files.createDirectory(journal);
            directory = true;
            Files.writeString(blocker, "owned cleanup recovery fault fixture", StandardOpenOption.CREATE_NEW);
            EvaluationManager.CleanupResumeResult result = EvaluationManager.resumeCleanup(server, parent.id());
            helper.assertTrue(result.resumed() == 0 && "persistence_failed".equals(result.reason()),
                    "failed real journal IO must not acknowledge any resumed lane");
            helper.assertTrue(first.manifest().cleanupRecovery() == firstBefore
                            && second.manifest().cleanupRecovery() == secondBefore
                            && firstTag.equals(firstBefore.save()) && secondTag.equals(secondBefore.save()),
                    "all proposals must roll back to the original budget object identities and contents");
            helper.assertTrue(EvaluationManager.INSTANCE.isJournalFenced(server),
                    "critical journal failure must fence further evaluation work");
            assertResponsible(helper, parent);
        } finally {
            if (directory) {
                Files.deleteIfExists(blocker);
                Files.delete(journal);
            }
            if (moved) Files.move(backup, journal, StandardCopyOption.REPLACE_EXISTING);
            discardState(server, parent);
            if (EvaluationManager.INSTANCE.isJournalFenced(server)) {
                writeField(EvaluationManager.INSTANCE, "journalRetryTicks", 0);
                EvaluationManager.INSTANCE.onServerTick(new ServerTickEvent.Post(() -> true, server));
                helper.assertTrue(!EvaluationManager.INSTANCE.isJournalFenced(server),
                        "restoring the file must recover through the real journal retry tick");
            }
            EvaluationCloneManager.syncCritical(server, data);
            helper.assertTrue(EvaluationCriticalJournal.read(server).sessions().stream().noneMatch(session -> session.id().equals(parent.id())),
                    "rollback fixture cleanup must durably remove its own session after real fence recovery");
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void restart_journal_seed_verify(GameTestHelper helper) throws Exception {
        MinecraftServer server = isolatedServer(helper);
        EvaluationSavedData data = EvaluationSavedData.get(server);
        String phase = System.getProperty("createcmpor.cleanupRestartPhase", "roundtrip");
        helper.assertTrue(List.of("seed", "verify", "roundtrip").contains(phase), "unknown cleanupRestartPhase: " + phase);
        helper.assertTrue(server.getLevel(RESTART_TARGET) == null, "restart target must remain missing");
        EvaluationSession sentinel = null;
        boolean keep = false;
        try {
            if ("verify".equals(phase)) {
                EvaluationSession existing = data.session(RESTART_ID).orElse(null);
                helper.assertTrue(existing != null && RESTART_ROOM.equals(existing.roomCode()),
                        "verify requires the sentinel recovered by normal startup in the second process");
                sentinel = existing;
                helper.assertTrue(sentinel.state() == EvaluationSession.State.CLEANING
                                && sentinel.manifest().cleanupRecovery().blocked()
                                && sentinel.manifest().cleanupRecovery().failures() == 3
                                && sentinel.manifest().targetDimension().equals(RESTART_TARGET)
                                && sentinel.machinePos().pos().equals(new BlockPos(0, 64, 0)),
                        "restart must retain CLEANING, the exhausted target budget, and original responsibility");
                assertResponsible(helper, sentinel);
                helper.assertTrue(!runtimes().containsKey(RESTART_ID), "startup must not open a blocked target runtime");
                helper.assertTrue(execute(server, RESTART_ID.toString(), 2) == 1,
                        "permission 2 must durably resume the known blocked restart sentinel");
                EvaluationSession durable = journalSession(server, RESTART_ID);
                helper.assertTrue(!durable.manifest().cleanupRecovery().blocked()
                                && durable.manifest().cleanupRecovery().failures() == 0,
                        "resume must commit the cleared budget to the actual journal before returning");
            } else {
                helper.assertTrue(data.session(RESTART_ID).isEmpty() && data.sessionByRoom(RESTART_ROOM).isEmpty(),
                        "seed/roundtrip must fail rather than overwrite an existing sentinel");
                sentinel = createSession(helper, RESTART_ID, RESTART_ROOM, RESTART_TARGET, new BlockPos(0, 64, 0));
                sentinel.manifest().cleanupRecovery(exhausted());
                EvaluationCloneManager.syncCritical(server, data);
                EvaluationSession durable = journalSession(server, RESTART_ID);
                helper.assertTrue(durable.state() == EvaluationSession.State.CLEANING
                                && durable.manifest().cleanupRecovery().blocked()
                                && durable.manifest().cleanupRecovery().failures() == 3,
                        "seed must write a real loadable exhausted cleanup journal");
                if ("seed".equals(phase)) {
                    keep = true;
                    LOGGER.info("CLEANUP_RESTART_SEEDED uuid={} room={}", RESTART_ID, RESTART_ROOM);
                }
            }
        } finally {
            if (!keep && sentinel != null) discard(server, sentinel);
        }
        if ("verify".equals(phase)) LOGGER.info("CLEANUP_RESTART_VERIFIED uuid={} room={}", RESTART_ID, RESTART_ROOM);
        if ("roundtrip".equals(phase)) LOGGER.info("CLEANUP_RESTART_ROUNDTRIP uuid={} room={} (same process)", RESTART_ID, RESTART_ROOM);
        helper.succeed();
    }

    private static MinecraftServer isolatedServer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertTrue(server instanceof GameTestServer, "recovery fixtures require an isolated GameTestServer, never a player server");
        helper.assertTrue(!EvaluationManager.INSTANCE.isJournalFenced(server), "fixture requires a healthy initial journal");
        return server;
    }

    private static EvaluationSession newSession(GameTestHelper helper, String label, ResourceKey<Level> target) {
        UUID id = UUID.randomUUID();
        return createSession(helper, id, "cleanup-recovery-" + label + "-" + id, target, helper.absolutePos(MARKER));
    }

    private static EvaluationSession createSession(GameTestHelper helper, UUID id, String room,
                                                    ResourceKey<Level> target, BlockPos machine) {
        ServerLevel level = helper.getLevel();
        EvaluationSavedData data = EvaluationSavedData.get(level.getServer());
        if (data.session(id).isPresent() || data.sessionByRoom(room).isPresent()) throw new IllegalStateException("fixture session collision: " + id);
        BlockPos marker = helper.absolutePos(MARKER);
        ResourceKey<Level> source = level.dimension().equals(Level.NETHER) ? Level.OVERWORLD : Level.NETHER;
        EvaluationManifest manifest = EvaluationManifest.create(id, room, source, target, level.getGameTime(),
                List.of(new ChunkPos(marker)), new AABB(marker.getX(), marker.getY(), marker.getZ(),
                        marker.getX() + 1, marker.getY() + 1, marker.getZ() + 1));
        manifest.validate();
        EvaluationSession session = new EvaluationSession(id, id, GlobalPos.of(level.dimension(), machine), room,
                Blocks.AIR.defaultBlockState(), new CompoundTag());
        session.setManifest(manifest);
        session.setState(EvaluationSession.State.CLEANING);
        data.put(session);
        return session;
    }

    private static EvaluationBranch branch(EvaluationSession parent, int index, ResourceKey<Level> target) {
        return new EvaluationBranch(index, UUID.randomUUID(), parent.manifest().copyForBranchTarget(target));
    }

    private static ResourceKey<Level> missingTarget() {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("createcmpor",
                "cleanup_missing_" + UUID.randomUUID().toString().replace("-", "")));
    }

    private static CleanupRecovery exhausted() {
        CleanupRecovery budget = new CleanupRecovery();
        budget.recordIoFailure(0, CleanupRecovery.STAGE_DRAIN_IO);
        budget.recordIoFailure(20, CleanupRecovery.STAGE_DELETE_RECORDS);
        budget.recordVerificationFailure(60);
        if (!budget.blocked() || budget.failures() != 3) throw new IllegalStateException("invalid exhausted test budget");
        return budget;
    }

    private static CleanupRecovery rawRecovery(Tag raw) {
        CompoundTag outer = new CompoundTag();
        outer.put(CleanupRecovery.KEY, raw);
        return CleanupRecovery.load(outer);
    }

    private static int execute(MinecraftServer server, String id, int permission) throws CommandSyntaxException {
        CommandSourceStack source = server.createCommandSourceStack().withPermission(permission).withSuppressedOutput();
        return server.getCommands().getDispatcher().execute("ccmpor evaluation resume " + id, source);
    }

    private static void assertCommandRejected(GameTestHelper helper, EvaluationSession session, String reason) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertTrue(execute(server, session.id().toString(), 2) == 0, "dispatcher must reject: " + reason);
        EvaluationManager.CleanupResumeResult result = EvaluationManager.resumeCleanup(server, session.id());
        helper.assertTrue(result.resumed() == 0 && reason.equals(result.reason()), "production resume guard must return " + reason);
    }

    private static EvaluationSession journalSession(MinecraftServer server, UUID id) {
        return EvaluationCriticalJournal.read(server).sessions().stream().filter(session -> session.id().equals(id))
                .findFirst().orElseThrow(() -> new IllegalStateException("test session absent from real journal: " + id));
    }

    private static void assertResponsible(GameTestHelper helper, EvaluationSession session) {
        EvaluationSavedData data = EvaluationSavedData.get(helper.getLevel().getServer());
        helper.assertTrue(data.session(session.id()).orElse(null) == session
                        && data.sessionByRoom(session.roomCode()).orElse(null) == session
                        && session.state() == EvaluationSession.State.CLEANING,
                "session and room lock must retain the original CLEANING responsibility");
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> runtimes() throws ReflectiveOperationException {
        return (Map<UUID, Object>) readField(EvaluationCloneManager.INSTANCE, "runtimes");
    }

    private static Object seedRuntime(UUID id, boolean referencesCleaned) throws ReflectiveOperationException {
        Class<?> type = Class.forName(EvaluationCloneManager.class.getName() + "$RuntimeState");
        Constructor<?> constructor = type.getDeclaredConstructor(ChunkStorage.class, boolean.class);
        constructor.setAccessible(true);
        Object runtime = constructor.newInstance(null, false);
        if (referencesCleaned) {
            for (String name : List.of("ticketsRemoved", "evaluationCleaned", "railwayCleaned", "backpackCleaned")) writeField(runtime, name, true);
        }
        if (runtimes().putIfAbsent(id, runtime) != null) throw new IllegalStateException("fixture runtime collision: " + id);
        return runtime;
    }

    private static Object readField(Object owner, String name) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void writeField(Object owner, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void discard(MinecraftServer server, EvaluationSession... sessions) throws ReflectiveOperationException {
        discardState(server, sessions);
        EvaluationCloneManager.syncCritical(server, EvaluationSavedData.get(server));
    }

    private static void discardState(MinecraftServer server, EvaluationSession... sessions) throws ReflectiveOperationException {
        for (EvaluationSession session : sessions) {
            discardRuntime(session.id());
            for (EvaluationBranch branch : session.parallelBranches()) discardRuntime(branch.branchId());
        }
        EvaluationSavedData data = EvaluationSavedData.get(server);
        for (EvaluationSession session : sessions) data.remove(session.id());
    }

    private static void discardRuntime(UUID id) throws ReflectiveOperationException {
        Object runtime = runtimes().get(id);
        if (runtime == null) return;
        if (Boolean.TRUE.equals(readField(runtime, "ownsStaging"))) throw new IllegalStateException("unexpected owning fixture runtime: " + id);
        for (String name : FUTURES) {
            Object value = readField(runtime, name);
            if (value instanceof CompletableFuture<?> future && !future.isDone()) {
                throw new IllegalStateException("refusing to discard unexpected pending fixture IO: " + name);
            }
        }
        runtimes().remove(id);
    }

    private static final class SyntheticVmError extends VirtualMachineError {
        private SyntheticVmError() { super("synthetic cleanup GameTest VM error; no heap exhaustion"); }
    }
}
