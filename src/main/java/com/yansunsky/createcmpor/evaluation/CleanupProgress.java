package com.yansunsky.createcmpor.evaluation;

import java.util.Objects;
import java.util.UUID;

/** Ephemeral server-thread diagnostics. No world, futures or persistence are retained here. */
final class CleanupProgress {
    static final long SAMPLE_INTERVAL = 100;
    static final long REPORT_INTERVAL = 600;

    enum Phase {
        PREPARE, REMOVE_TICKETS, RELEASE_REFERENCES, WAIT_IDLE,
        DELETE_RECORDS, VERIFY_EMPTY, FINALIZE, DONE
    }

    private final UUID sessionId;
    private final long startedTick;
    private final long[] phaseDurations = new long[Phase.values().length];
    private final long[] highWater = new long[Phase.values().length];
    private Phase phase = Phase.PREPARE;
    private long phaseStartedTick;
    private long lastProgressTick;
    private long lastBlockerChangeTick;
    private long lastSampleTick = -1;
    private long lastReportTick = -1;
    private long completedTick = -1;
    private int verificationRetries;
    private String blockerKey;
    private String reportedKey;
    private boolean reportedStall;

    CleanupProgress(UUID sessionId, long tick) {
        this.sessionId = Objects.requireNonNull(sessionId);
        startedTick = phaseStartedTick = lastProgressTick = lastBlockerChangeTick = tick;
    }

    UUID sessionId() { return sessionId; }
    Phase phase() { return phase; }
    long lastProgressTick() { return lastProgressTick; }
    long lastBlockerChangeTick() { return lastBlockerChangeTick; }
    long highWater(Phase stage) { return highWater[stage.ordinal()]; }
    int verificationRetries() { return verificationRetries; }
    boolean completed() { return completedTick >= 0; }

    void phase(Phase next, long tick) {
        if (completed() || phase == next) {
            return;
        }
        phaseDurations[phase.ordinal()] += Math.max(0, tick - phaseStartedTick);
        phase = next;
        phaseStartedTick = tick;
    }

    /** Replayed work and DELETE/VERIFY phase oscillation must not hide a stall. */
    void advance(Phase stage, long units, long tick) {
        if (!completed() && units > highWater[stage.ordinal()]) {
            highWater[stage.ordinal()] = units;
            lastProgressTick = tick;
        }
    }

    void verificationFailed() {
        if (!completed() && verificationRetries < Integer.MAX_VALUE) {
            verificationRetries++;
        }
    }

    boolean beginSample(long tick) {
        if (completed() || lastSampleTick >= 0 && tick - lastSampleTick < SAMPLE_INTERVAL) {
            return false;
        }
        lastSampleTick = tick;
        return true;
    }

    boolean stalled(long tick) {
        return !completed() && tick - lastProgressTick >= REPORT_INTERVAL;
    }

    boolean shouldReport(String key, long tick) {
        if (!Objects.equals(blockerKey, key)) {
            blockerKey = key;
            lastBlockerChangeTick = tick;
        }
        boolean stall = stalled(tick);
        if (lastReportTick >= 0 && (tick - lastReportTick < SAMPLE_INTERVAL
                || Objects.equals(reportedKey, key) && stall == reportedStall
                && tick - lastReportTick < REPORT_INTERVAL)) {
            return false;
        }
        lastReportTick = tick;
        reportedKey = key;
        reportedStall = stall;
        return true;
    }

    boolean complete(long tick) {
        if (completed()) {
            return false;
        }
        phase(Phase.DONE, tick);
        completedTick = tick;
        return true;
    }

    long phaseTicks(Phase stage, long tick) {
        long effectiveTick = completed() ? completedTick : tick;
        return phaseDurations[stage.ordinal()]
                + (phase == stage ? Math.max(0, effectiveTick - phaseStartedTick) : 0);
    }

    String summary(long tick) {
        long effectiveTick = completed() ? completedTick : tick;
        StringBuilder text = new StringBuilder("startedTick=").append(startedTick)
                .append(", phase=").append(phase)
                .append(", elapsedTicks=").append(Math.max(0, effectiveTick - startedTick))
                .append(", phaseTicks=").append(phaseTicks(phase, effectiveTick))
                .append(", lastProgressTick=").append(lastProgressTick)
                .append(", noProgressTicks=").append(Math.max(0, effectiveTick - lastProgressTick))
                .append(", lastBlockerChangeTick=").append(lastBlockerChangeTick)
                .append(", stalled=").append(stalled(tick))
                .append(", removedTicketOps=").append(highWater(Phase.REMOVE_TICKETS))
                .append(", verifiedHighWater=").append(highWater(Phase.VERIFY_EMPTY))
                .append(", verifyRetries=").append(verificationRetries)
                .append(", stageTicks={");
        for (Phase stage : Phase.values()) {
            if (stage != Phase.PREPARE) {
                text.append(',');
            }
            text.append(stage).append(':').append(phaseTicks(stage, effectiveTick));
        }
        return text.append('}').toString();
    }
}
