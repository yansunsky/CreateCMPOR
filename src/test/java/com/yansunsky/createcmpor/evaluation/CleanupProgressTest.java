package com.yansunsky.createcmpor.evaluation;

import java.util.UUID;

/** Executable rules regression, independent of a Minecraft world or test framework. */
public final class CleanupProgressTest {
    public static void main(String[] args) {
        retriesDoNotHideStalls();
        samplesAndReportsAreBounded();
        progressAndBlockerChangesAreIndependent();
        phasesAndCompletionFreeze();
        independentTargetsDoNotShareProgress();
        System.out.println("Cleanup progress rules passed (retry high water, cooldown, phase timing, completion, target isolation).");
    }

    private static CleanupProgress fresh() {
        return new CleanupProgress(UUID.randomUUID(), 0);
    }

    private static void retriesDoNotHideStalls() {
        CleanupProgress progress = fresh();
        progress.advance(CleanupProgress.Phase.REMOVE_TICKETS, 32, 10);
        progress.advance(CleanupProgress.Phase.VERIFY_EMPTY, 4, 20);
        for (int tick = 21; tick <= 700; tick++) {
            progress.phase(tick % 2 == 0 ? CleanupProgress.Phase.DELETE_RECORDS
                    : CleanupProgress.Phase.VERIFY_EMPTY, tick);
            progress.advance(CleanupProgress.Phase.REMOVE_TICKETS, 32, tick);
            progress.advance(CleanupProgress.Phase.VERIFY_EMPTY, tick % 5, tick);
            progress.verificationFailed();
        }
        check(progress.lastProgressTick() == 20, "retrying already verified work must not refresh progress");
        check(progress.stalled(620), "repeated phase changes must not hide a stall");
        check(progress.verificationRetries() == 680, "verification retries remain visible");
        progress.advance(CleanupProgress.Phase.VERIFY_EMPTY, 5, 701);
        check(progress.lastProgressTick() == 701 && !progress.stalled(701), "new verified work resumes progress");
    }

    private static void samplesAndReportsAreBounded() {
        CleanupProgress progress = fresh();
        int samples = 0;
        int reports = 0;
        for (int tick = 0; tick <= 1200; tick++) {
            if (progress.beginSample(tick)) {
                samples++;
                if (progress.shouldReport("unchanged blocker", tick)) reports++;
            }
        }
        check(samples == 13, "sampling must precede costly probes and occur only every 100 ticks");
        check(reports == 3, "stable blocker must report at 0/600/1200 only");
        CleanupProgress changes = fresh();
        int changedReports = 0;
        for (int tick = 0; tick <= 1000; tick++) {
            if (changes.shouldReport("blocker-" + tick, tick)) changedReports++;
        }
        check(changedReports == 11, "changing reasons must still obey the minimum report interval");
    }

    private static void progressAndBlockerChangesAreIndependent() {
        CleanupProgress progress = fresh();
        check(progress.shouldReport("PLAYER", 0), "first report");
        check(progress.shouldReport("vanilla future", 100), "changed blocker after cooldown");
        check(progress.lastBlockerChangeTick() == 100, "blocker-change tick is separate");
        check(progress.lastProgressTick() == 0 && progress.stalled(600), "different reasons are not progress");
        progress.advance(CleanupProgress.Phase.WAIT_IDLE, 2, 601);
        check(!progress.stalled(601), "new idle chunk high water is real progress");
        progress.advance(CleanupProgress.Phase.WAIT_IDLE, 1, 800);
        progress.advance(CleanupProgress.Phase.WAIT_IDLE, 2, 900);
        check(progress.lastProgressTick() == 601, "a reloaded then re-unloaded chunk must not reset the high water");
    }

    private static void phasesAndCompletionFreeze() {
        CleanupProgress progress = fresh();
        progress.phase(CleanupProgress.Phase.REMOVE_TICKETS, 10);
        progress.phase(CleanupProgress.Phase.WAIT_IDLE, 30);
        progress.phase(CleanupProgress.Phase.REMOVE_TICKETS, 50);
        check(progress.phaseTicks(CleanupProgress.Phase.REMOVE_TICKETS, 60) == 30, "accumulate phase revisits");
        check(progress.phaseTicks(CleanupProgress.Phase.WAIT_IDLE, 60) == 20, "record completed phase time");
        check(progress.complete(70), "first completion is reported");
        String finished = progress.summary(70);
        check(!progress.complete(80), "completion is idempotent");
        progress.phase(CleanupProgress.Phase.DELETE_RECORDS, 90);
        progress.advance(CleanupProgress.Phase.VERIFY_EMPTY, 99, 90);
        check(finished.equals(progress.summary(1000)), "finished lane time is frozen while other lanes wait");
        check(!progress.beginSample(1000), "completed lanes stop sampling");
    }

    private static void independentTargetsDoNotShareProgress() {
        UUID parent = UUID.randomUUID();
        CleanupProgress first = new CleanupProgress(parent, 0);
        CleanupProgress second = new CleanupProgress(parent, 0);
        first.advance(CleanupProgress.Phase.VERIFY_EMPTY, 100, 500);
        check(first.sessionId().equals(second.sessionId()), "parallel targets belong to their parent session");
        check(!first.stalled(600) && second.stalled(600), "progress on one lane cannot hide another lane's stall");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
