package com.yansunsky.createcmpor.evaluation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

import java.util.function.Consumer;

/** Pure-main regression using the production recovery object, Manifest and real Minecraft NBT. */
public final class CleanupRecoveryTest {
    private static int checks;

    public static void main(String[] args) {
        if (args.length != 0) throw new IllegalArgumentException("No arguments expected");
        checks = 0;
        legacyAndFresh();
        cumulativeBudgetAndRestart();
        perTargetResumeAndCopies();
        drainIoSharesBudget();
        clocks();
        malformedNbt();
        boundedCodesAndDetachedNbt();
        rawEvidencePreservation();
        manifestPersistence();
        System.out.println("CleanupRecovery rules passed: " + checks
                + " checks (budget, backoff, blocked, restart, resume, clocks, NBT).");
    }

    private static void legacyAndFresh() {
        CompoundTag oldManifest = new CompoundTag();
        oldManifest.putString("unrelated", "preserved");
        CompoundTag before = oldManifest.copy();
        CleanupRecovery legacy = CleanupRecovery.load(oldManifest);
        check(legacy.failures() == 0 && !legacy.blocked() && legacy.retryNotBefore() == 0,
                "only a missing optional compound must load as a fresh legacy state");
        check("NONE".equals(legacy.stage()) && "NONE".equals(legacy.reason()), "initial codes");
        check(legacy.canAttempt(0) && !legacy.canAttempt(-1), "negative clocks cannot attempt");
        check(!legacy.canResume() && !legacy.resume(), "resume must not reset an active target");
        check(before.equals(oldManifest), "load must not mutate the manifest");
        roundTrip(legacy);
    }

    private static void cumulativeBudgetAndRestart() {
        CleanupRecovery target = new CleanupRecovery();
        check(target.recordIoFailure(100, "DELETE_RECORDS"), "first completed IO failure changes state");
        check(target.failures() == 1 && !target.blocked() && target.retryNotBefore() == 120,
                "first failure waits 20 ticks");
        check("DELETE_RECORDS".equals(target.stage()) && "IO_FAILURE".equals(target.reason()), "IO codes");
        target = roundTrip(target);
        Tag beforeWait = target.save();
        for (long now = 0; now < 120; now++) {
            check(!target.canAttempt(now), "saved future deadline must survive restart and clock rewind");
        }
        check(!target.recordVerificationFailure(119), "a duplicate failure during backoff must not count");
        check(beforeWait.equals(target.save()), "waiting/polling must not mutate budget or deadline");
        check(target.canAttempt(120), "retry is allowed exactly at the deadline");
        check(target.recordVerificationFailure(120), "verification failure uses the same budget");
        check(target.failures() == 2 && !target.blocked() && target.retryNotBefore() == 160,
                "second total failure waits 40 ticks, not a fresh verification budget");
        target = roundTrip(target);
        check(!target.canAttempt(159) && target.canAttempt(160), "second retry boundary");
        for (long now = 160; now < 200; now++) target.canAttempt(now);
        check(target.failures() == 2, "read-only progress/success queries cannot reset the budget");
        check(target.recordIoFailure(200, "DELETE_RECORDS"), "third total failure changes state");
        check(target.failures() == 3 && target.blocked() && target.retryNotBefore() == 0,
                "the third failure is permanently blocked until explicit recovery");
        target = roundTrip(target);
        Tag blocked = target.save();
        check(!target.canAttempt(Long.MAX_VALUE), "elapsed time cannot unblock an exhausted target");
        check(!target.recordIoFailure(201, "DELETE_RECORDS") && !target.recordVerificationFailure(202),
                "a blocked target must not accumulate failures or rewrite its reason");
        check(blocked.equals(target.save()), "blocked duplicate observations are idempotent");
    }

    private static void perTargetResumeAndCopies() {
        CleanupRecovery blocked = new CleanupRecovery();
        blocked.recordIoFailure(0, "DELETE_RECORDS");
        blocked.recordVerificationFailure(20);
        blocked.recordIoFailure(60, "DELETE_RECORDS");
        CleanupRecovery otherLane = new CleanupRecovery();
        otherLane.recordIoFailure(90, "VERIFY_EMPTY");
        Tag otherBefore = otherLane.save();
        check(blocked.canResume() && blocked.resume(), "explicit resume resets a blocked target");
        check(blocked.failures() == 0 && !blocked.blocked() && blocked.retryNotBefore() == 0
                && "NONE".equals(blocked.stage()) && "NONE".equals(blocked.reason()), "resume clears all recovery fields");
        check(!otherLane.canResume() && !otherLane.resume() && otherBefore.equals(otherLane.save()), "resume leaves an unblocked lane untouched");
        check(blocked.recordVerificationFailure(0) && blocked.failures() == 1,
                "a resumed target gets exactly one new budget");
        CleanupRecovery copy = otherLane.copy();
        check(copy != otherLane && copy.save().equals(otherLane.save()), "copy must preserve fields independently");
        copy.blockUnexpected("FINALIZE", "UNEXPECTED_FAILURE");
        check(!otherLane.blocked() && otherBefore.equals(otherLane.save()), "mutating a staged copy must not publish it");
        check(copy.resume() && otherBefore.equals(otherLane.save()), "copy resume must not affect the source");
        roundTrip(copy);
    }

    private static void drainIoSharesBudget() {
        CleanupRecovery target = new CleanupRecovery();
        check(target.recordIoFailure(0, "DRAIN_IO") && target.failures() == 1
                && "DRAIN_IO".equals(target.stage()), "settled original IO failure uses the target budget");
        target = roundTrip(target);
        check(target.recordVerificationFailure(20) && target.failures() == 2
                && target.retryNotBefore() == 60, "drain and verify share one cumulative budget");
        target.recordIoFailure(60, "DELETE_RECORDS");
        check(target.blocked() && target.failures() == 3, "drain, verify and delete share the same limit");
        roundTrip(target);
    }

    private static void clocks() {
        CleanupRecovery firstOverflow = new CleanupRecovery();
        check(firstOverflow.recordIoFailure(Long.MAX_VALUE - 19, "DELETE_RECORDS"), "clock overflow changes state");
        check(firstOverflow.blocked() && firstOverflow.failures() == 1
                && "CLOCK_OVERFLOW".equals(firstOverflow.reason()), "first delay must not wrap to an immediate retry");
        firstOverflow = roundTrip(firstOverflow);
        check(firstOverflow.resume() && !firstOverflow.blocked() && firstOverflow.failures() == 0,
                "a known overflow state can be explicitly resumed after the clock is repaired");
        CleanupRecovery boundary = new CleanupRecovery();
        boundary.recordIoFailure(Long.MAX_VALUE - 20, "DELETE_RECORDS");
        boundary = roundTrip(boundary);
        check(!boundary.blocked() && boundary.retryNotBefore() == Long.MAX_VALUE, "exact max deadline is valid");
        check(!boundary.canAttempt(Long.MAX_VALUE - 1) && boundary.canAttempt(Long.MAX_VALUE), "max deadline comparison");
        check(boundary.recordVerificationFailure(Long.MAX_VALUE) && boundary.blocked()
                && boundary.failures() == 2 && "CLOCK_OVERFLOW".equals(boundary.reason()), "second delay overflow must block");
        CleanupRecovery negative = new CleanupRecovery();
        check(negative.recordIoFailure(-1, "DELETE_RECORDS") && negative.blocked()
                && negative.failures() == 0 && "INVALID_CLOCK".equals(negative.reason()), "negative recorded clock blocks without spending a retry");
        negative = roundTrip(negative);
        check(negative.resume() && negative.canAttempt(0), "a repaired negative clock permits explicit resume");
        CleanupRecovery earliest = new CleanupRecovery();
        earliest.recordIoFailure(0, "DELETE_RECORDS");
        earliest.recordVerificationFailure(20);
        check(earliest.retryNotBefore() == 60, "the earliest possible second deadline is tick 60");
        roundTrip(earliest);
    }

    private static void malformedNbt() {
        assertCorrupt(CleanupRecovery.load(null));
        CompoundTag wrongOuter = new CompoundTag();
        wrongOuter.putString(CleanupRecovery.KEY, "not a compound");
        assertCorrupt(CleanupRecovery.load(wrongOuter));
        assertCorrupt(CleanupRecovery.load(wrap(new CompoundTag())));
        String[] keys = {"version", "failures", "blocked", "retry_not_before", "stage", "reason"};
        for (String key : keys) corrupt(tag -> tag.remove(key));
        corrupt(tag -> tag.putByte("version", (byte) 1));
        corrupt(tag -> tag.putLong("failures", 0));
        corrupt(tag -> tag.putInt("blocked", 0));
        corrupt(tag -> tag.putInt("retry_not_before", 0));
        corrupt(tag -> tag.putInt("stage", 0));
        corrupt(tag -> tag.putInt("reason", 0));
        corrupt(tag -> tag.putByte("blocked", (byte) -1));
        corrupt(tag -> tag.putByte("blocked", (byte) 2));
        corrupt(tag -> tag.putInt("failures", -1));
        corrupt(tag -> tag.putInt("failures", 4));
        corrupt(tag -> tag.putLong("retry_not_before", -1));
        corrupt(tag -> tag.putString("stage", "unbounded-user-stage"));
        corrupt(tag -> tag.putString("reason", "Exception: user stack trace"));
        corrupt(tag -> tag.putString("extra_v1_field", "unsupported"));
        corrupt(tag -> tag.putBoolean("blocked", true));
        corrupt(tag -> tag.putLong("retry_not_before", 20));
        for (int version : new int[]{-1, 0, 2, Integer.MAX_VALUE}) {
            CompoundTag tag = (CompoundTag) new CleanupRecovery().save();
            tag.putInt("version", version);
            CleanupRecovery unknown = CleanupRecovery.load(wrap(tag));
            check(unknown.blocked() && "UNKNOWN_VERSION".equals(unknown.reason()), "unknown versions fail closed");
            Tag unknownBefore = unknown.save();
            check(!unknown.canResume() && !unknown.resume() && unknownBefore.equals(unknown.save()),
                    "ordinary resume must not bypass an unknown future schema");
            roundTrip(unknown);
        }
        CleanupRecovery retry = new CleanupRecovery();
        retry.recordIoFailure(0, "DELETE_RECORDS");
        corrupt(retry, tag -> tag.putInt("failures", 0));
        corrupt(retry, tag -> tag.putInt("failures", 3));
        corrupt(retry, tag -> tag.putLong("retry_not_before", 19));
        corrupt(retry, tag -> tag.putBoolean("blocked", true));
        corrupt(retry, tag -> tag.putString("stage", "WAIT_IDLE"));
        corrupt(retry, tag -> tag.putString("reason", "VERIFICATION_NOT_EMPTY"));
        retry.recordVerificationFailure(20);
        corrupt(retry, tag -> tag.putLong("retry_not_before", 59));
        retry.recordVerificationFailure(60);
        corrupt(retry, tag -> tag.putLong("retry_not_before", 1));
    }

    private static void boundedCodesAndDetachedNbt() {
        CleanupRecovery blocked = new CleanupRecovery();
        String arbitrary = "user-supplied-exception-data-".repeat(100);
        check(blocked.blockUnexpected(arbitrary, arbitrary), "unexpected error blocks immediately");
        check("UNKNOWN".equals(blocked.stage()) && "UNEXPECTED_FAILURE".equals(blocked.reason()),
                "untrusted diagnostic text must never become persisted state");
        check(!blocked.blockUnexpected("FINALIZE", "CANCELLED"), "existing blocked reason is stable");
        roundTrip(blocked);
        CleanupRecovery cancelled = new CleanupRecovery();
        cancelled.blockUnexpected("DELETE_RECORDS", "CANCELLED");
        roundTrip(cancelled);
        CleanupRecovery waiting = new CleanupRecovery();
        waiting.recordIoFailure(0, "WAIT_IDLE");
        check(waiting.blocked() && waiting.failures() == 0, "WAIT_IDLE is not an IO attempt or consumed retry");
        CleanupRecovery missingStage = new CleanupRecovery();
        missingStage.blockUnexpected(null, null);
        roundTrip(missingStage);
        CleanupRecovery initial = new CleanupRecovery();
        CompoundTag saved = (CompoundTag) initial.save();
        saved.putInt("failures", 99);
        check(initial.failures() == 0 && !initial.blocked(), "save returns detached NBT");
        CompoundTag manifest = wrap(initial.save());
        CleanupRecovery loaded = CleanupRecovery.load(manifest);
        manifest.getCompound(CleanupRecovery.KEY).putInt("failures", 99);
        check(loaded.failures() == 0 && !loaded.blocked(), "load retains no mutable NBT alias");
        check(((CompoundTag) initial.save()).getAllKeys().size() == 6, "the schema stores only six bounded fields");
    }

    private static void rawEvidencePreservation() {
        CompoundTag future = new CompoundTag();
        future.putInt("version", 2);
        future.putString("future_policy", "do not discard unknown cleanup obligations");
        CompoundTag nested = new CompoundTag();
        nested.putIntArray("coordinates", new int[]{1, 2, 3});
        nested.putLongArray("future_deadlines", new long[]{4, Long.MAX_VALUE});
        future.put("unknown_nested", nested);
        ListTag list = new ListTag();
        list.add(StringTag.valueOf("unknown checkpoint"));
        future.put("unknown_list", list);
        CompoundTag corrupt = (CompoundTag) new CleanupRecovery().save();
        corrupt.putInt("failures", -7);
        corrupt.putString("original_evidence", "keep this exact invalid value");
        Tag[] invalid = {StringTag.valueOf("wrong outer type"), IntTag.valueOf(9),
                new IntArrayTag(new int[]{7, 8}), list, new CompoundTag(), corrupt, future};
        for (Tag raw : invalid) {
            Tag expected = raw.copy();
            CleanupRecovery recovery = CleanupRecovery.load(wrap(raw.copy()));
            for (int restart = 0; restart < 3; restart++) {
                check(recovery.blocked() && !recovery.canResume() && !recovery.resume(),
                        "unknown/corrupt input must stay blocked across restarts");
                check(!recovery.recordIoFailure(100, CleanupRecovery.STAGE_DELETE_RECORDS)
                                && !recovery.blockUnexpected(CleanupRecovery.STAGE_FINALIZE, CleanupRecovery.REASON_CANCELLED),
                        "later observations cannot overwrite the original invalid evidence");
                CompoundTag saved = new CompoundTag();
                saved.putString("unrelated", "preserved");
                recovery.saveInto(saved);
                Tag actual = saved.get(CleanupRecovery.KEY);
                check(actual != null && actual.getId() == expected.getId() && expected.equals(actual),
                        "saveInto must preserve raw NBT type, value and unknown nested fields");
                check("preserved".equals(saved.getString("unrelated")), "saveInto must not alter other manifest fields");
                check(expected.equals(recovery.save()) && expected.equals(recovery.copy().save()),
                        "save and copy must retain the original invalid NBT");
                recovery = CleanupRecovery.load(saved);
            }
        }
        CompoundTag expected = future.copy();
        CleanupRecovery detached = CleanupRecovery.load(wrap(future));
        future.getCompound("unknown_nested").putInt("input_mutation", 1);
        check(expected.equals(detached.save()), "load must detach the original invalid compound");
        CompoundTag exported = (CompoundTag) detached.save();
        exported.getCompound("unknown_nested").getIntArray("coordinates")[0] = 99;
        exported.putString("export_mutation", "must not leak back");
        check(expected.equals(detached.save()) && expected.equals(detached.copy().save()),
                "nested raw arrays and exported compounds must not alias retained evidence");
        CompoundTag saved = new CompoundTag();
        detached.saveInto(saved);
        saved.getCompound(CleanupRecovery.KEY).getCompound("unknown_nested").putInt("saved_mutation", 2);
        check(expected.equals(detached.save()), "saveInto must detach its exported raw value");
    }

    private static void manifestPersistence() {
        EvaluationManifest manifest = manifestFixture();
        CleanupRecovery original = manifest.cleanupRecovery();
        check(original != null && original.canAttempt(0), "new targets start with an independent fresh budget");
        CleanupRecovery proposal = original.copy();
        proposal.blockUnexpected(CleanupRecovery.STAGE_FINALIZE, CleanupRecovery.REASON_UNEXPECTED_FAILURE);
        manifest.cleanupRecovery(proposal);
        check(manifest.cleanupRecovery() == proposal, "setter must retain the proposal identity for transaction rollback");
        manifest.cleanupRecovery(original);
        check(manifest.cleanupRecovery() == original, "rollback must restore the original object");
        try {
            manifest.cleanupRecovery(null);
            throw new AssertionError("manifest setter accepted null");
        } catch (NullPointerException expected) {
            checks++;
        }
        check(manifest.cleanupRecovery() == original, "null rejection must leave the original recovery object installed");
        original.recordIoFailure(0, CleanupRecovery.STAGE_DRAIN_IO);
        original.recordVerificationFailure(20);
        CompoundTag saved = manifest.save();
        saved.putBoolean("target_write_intent", true);
        saved.putBoolean("tickets_added", true);
        saved.putBoolean("target_ready", true);
        EvaluationManifest restarted = EvaluationManifest.load(saved, null);
        check(restarted.cleanupRecovery().failures() == 2 && restarted.cleanupRecovery().retryNotBefore() == 60,
                "manifest restart must preserve the cumulative budget and deadline");
        check(saved.equals(restarted.save()), "manifest roundtrip must preserve recovery and existing responsibility fields");
        EvaluationManifest nextTarget = restarted.copyForBranchTarget(Level.END);
        check(nextTarget.cleanupRecovery() != restarted.cleanupRecovery() && nextTarget.cleanupRecovery().canAttempt(0)
                        && nextTarget.cleanupRecovery().failures() == 0,
                "a new branch target must not inherit the previous target budget");
        CleanupRecovery previous = restarted.cleanupRecovery();
        restarted.resetForBranch();
        check(restarted.cleanupRecovery() != previous && restarted.cleanupRecovery().failures() == 0
                        && restarted.cleanupRecovery().canAttempt(0) && previous.failures() == 2,
                "completed-target reset must install a new object without changing an existing proposal");
        CompoundTag legacy = manifestFixture().save();
        legacy.remove(CleanupRecovery.KEY);
        EvaluationManifest legacyLoaded = EvaluationManifest.load(legacy, null);
        check(legacyLoaded.cleanupRecovery().canAttempt(0) && legacyLoaded.cleanupRecovery().failures() == 0,
                "a manifest with no optional recovery key must remain readable");
        CompoundTag unknown = new CompoundTag();
        unknown.putInt("version", 17);
        unknown.putString("future_policy", "preserve");
        Tag[] rawValues = {unknown, StringTag.valueOf("wrong manifest field type"), new CompoundTag()};
        for (Tag raw : rawValues) {
            CompoundTag withRaw = manifestFixture().save();
            withRaw.put(CleanupRecovery.KEY, raw.copy());
            for (int restart = 0; restart < 3; restart++) {
                EvaluationManifest invalid = EvaluationManifest.load(withRaw, null);
                check(invalid.cleanupRecovery().blocked() && !invalid.cleanupRecovery().canResume(),
                        "manifest unknown/corrupt input must remain non-resumable");
                invalid.cleanupRecovery(invalid.cleanupRecovery().copy());
                check(withRaw.equals(invalid.save()), "manifest saves must retain raw evidence and all responsibility fields");
                withRaw = invalid.save();
            }
        }
    }

    private static EvaluationManifest manifestFixture() {
        return EvaluationManifest.create(UUID.fromString("b3b706c0-750e-4d31-b555-f563f1e048e5"), "cleanup-test",
                Level.OVERWORLD, Level.NETHER, 100, List.of(new ChunkPos(0, 0)), new AABB(0, 0, 0, 16, 16, 16));
    }

    private static CleanupRecovery roundTrip(CleanupRecovery original) {
        Tag saved = original.save();
        CleanupRecovery loaded = CleanupRecovery.load(wrap(saved));
        check(loaded != original && loaded.save().equals(saved), "real CompoundTag save/load must preserve all fields");
        return loaded;
    }

    private static CompoundTag wrap(Tag recovery) {
        CompoundTag manifest = new CompoundTag();
        manifest.put(CleanupRecovery.KEY, recovery);
        return manifest;
    }

    private static void corrupt(Consumer<CompoundTag> change) {
        corrupt(new CleanupRecovery(), change);
    }

    private static void corrupt(CleanupRecovery source, Consumer<CompoundTag> change) {
        CompoundTag broken = (CompoundTag) source.save();
        change.accept(broken);
        assertCorrupt(CleanupRecovery.load(wrap(broken)));
    }

    private static void assertCorrupt(CleanupRecovery recovery) {
        check(recovery.blocked() && recovery.failures() == 3 && !recovery.canAttempt(Long.MAX_VALUE)
                && "CORRUPT_STATE".equals(recovery.reason()), "malformed state must not silently reset the budget");
        Tag beforeResume = recovery.save();
        check(!recovery.canResume() && !recovery.resume() && beforeResume.equals(recovery.save()),
                "corrupt state requires safe migration rather than ordinary resume");
        CleanupRecovery copy = recovery.copy();
        check(!copy.resume(), "copy must not make a corrupt state resumable");
        roundTrip(recovery);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
