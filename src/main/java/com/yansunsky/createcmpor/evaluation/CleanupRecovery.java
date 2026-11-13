package com.yansunsky.createcmpor.evaluation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Objects;

/** Per-target cleanup budget. Callers persist a proposal before allowing further cleanup work. */
public final class CleanupRecovery {
    public static final String KEY = "cleanup_recovery";
    public static final String STAGE_NONE = "NONE";
    public static final String STAGE_UNKNOWN = "UNKNOWN";
    public static final String STAGE_PREPARE = "PREPARE";
    public static final String STAGE_DRAIN_IO = "DRAIN_IO";
    public static final String STAGE_REMOVE_TICKETS = "REMOVE_TICKETS";
    public static final String STAGE_RELEASE_REFERENCES = "RELEASE_REFERENCES";
    public static final String STAGE_WAIT_IDLE = "WAIT_IDLE";
    public static final String STAGE_DELETE_RECORDS = "DELETE_RECORDS";
    public static final String STAGE_VERIFY_EMPTY = "VERIFY_EMPTY";
    public static final String STAGE_FINALIZE = "FINALIZE";
    public static final String REASON_NONE = "NONE";
    public static final String REASON_IO_FAILURE = "IO_FAILURE";
    public static final String REASON_VERIFICATION_NOT_EMPTY = "VERIFICATION_NOT_EMPTY";
    public static final String REASON_UNEXPECTED_FAILURE = "UNEXPECTED_FAILURE";
    public static final String REASON_CANCELLED = "CANCELLED";
    public static final String REASON_MISSING_RUNTIME = "MISSING_RUNTIME";
    public static final String REASON_MISSING_DIMENSION = "MISSING_DIMENSION";
    public static final String REASON_INVALID_CLOCK = "INVALID_CLOCK";
    public static final String REASON_CLOCK_OVERFLOW = "CLOCK_OVERFLOW";
    public static final String REASON_CORRUPT_STATE = "CORRUPT_STATE";
    public static final String REASON_UNKNOWN_VERSION = "UNKNOWN_VERSION";
    private static final int VERSION = 1;
    private static final int FAILURE_LIMIT = 3;

    private int failures;
    private boolean blocked;
    private long retryNotBefore;
    private String stage = STAGE_NONE;
    private String reason = REASON_NONE;
    // Unknown/corrupt input is never rewritten as a synthetic v1 state or resumed implicitly.
    private Tag preservedRaw;

    public int failures() { return failures; }
    public boolean blocked() { return blocked; }
    public long retryNotBefore() { return retryNotBefore; }
    public String stage() { return stage; }
    public String reason() { return reason; }

    /** Read-only gate for new attempts, not for observing existing futures for fatal failures. */
    public boolean canAttempt(long now) {
        return !blocked && now >= 0 && now >= retryNotBefore;
    }

    public boolean canResume() {
        return blocked && preservedRaw == null
                && !REASON_CORRUPT_STATE.equals(reason) && !REASON_UNKNOWN_VERSION.equals(reason);
    }

    public boolean recordIoFailure(long now, String failedStage) {
        return recordFailure(now, failedStage, REASON_IO_FAILURE);
    }

    public boolean recordVerificationFailure(long now) {
        return recordFailure(now, STAGE_VERIFY_EMPTY, REASON_VERIFICATION_NOT_EMPTY);
    }

    private boolean recordFailure(long now, String failedStage, String failureReason) {
        if (blocked) return false;
        if (!isFailureStage(failedStage)) return blockUnexpected(STAGE_UNKNOWN, REASON_UNEXPECTED_FAILURE);
        if (now < 0) return blockUnexpected(failedStage, REASON_INVALID_CLOCK);
        if (!canAttempt(now)) return false;
        failures++;
        stage = failedStage;
        reason = failureReason;
        retryNotBefore = 0;
        if (failures == FAILURE_LIMIT) {
            blocked = true;
            return true;
        }
        long delay = failures == 1 ? 20 : 40;
        if (now > Long.MAX_VALUE - delay) {
            blocked = true;
            reason = REASON_CLOCK_OVERFLOW;
            return true;
        }
        retryNotBefore = now + delay;
        return true;
    }

    /** Persists bounded codes only; exception messages and stack traces never enter this state. */
    public boolean blockUnexpected(String failedStage, String failureReason) {
        if (blocked) return false;
        blocked = true;
        retryNotBefore = 0;
        stage = isKnownStage(failedStage) && !STAGE_NONE.equals(failedStage) ? failedStage : STAGE_UNKNOWN;
        reason = isBlockReason(failureReason) ? failureReason : REASON_UNEXPECTED_FAILURE;
        return true;
    }

    /** Explicit administrator action. Unknown/corrupt schemas require a separate safe migration. */
    public boolean resume() {
        if (!canResume()) return false;
        failures = 0;
        blocked = false;
        retryNotBefore = 0;
        stage = STAGE_NONE;
        reason = REASON_NONE;
        return true;
    }

    public CleanupRecovery copy() {
        CleanupRecovery copy = new CleanupRecovery();
        copy.failures = failures;
        copy.blocked = blocked;
        copy.retryNotBefore = retryNotBefore;
        copy.stage = stage;
        copy.reason = reason;
        copy.preservedRaw = preservedRaw == null ? null : preservedRaw.copy();
        return copy;
    }

    /** Returns an independent value, including the original type for invalid/future input. */
    public Tag save() {
        if (preservedRaw != null) return preservedRaw.copy();
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", VERSION);
        tag.putInt("failures", failures);
        tag.putBoolean("blocked", blocked);
        tag.putLong("retry_not_before", retryNotBefore);
        tag.putString("stage", stage);
        tag.putString("reason", reason);
        return tag;
    }

    public void saveInto(CompoundTag manifest) {
        Objects.requireNonNull(manifest, "manifest").put(KEY, save());
    }

    /** Only an absent key is legacy. Present invalid input stays blocked and is preserved verbatim. */
    public static CleanupRecovery load(CompoundTag manifest) {
        if (manifest == null) return invalid(REASON_CORRUPT_STATE, null);
        if (!manifest.contains(KEY)) return new CleanupRecovery();
        Tag raw = manifest.get(KEY);
        if (!(raw instanceof CompoundTag tag)) return invalid(REASON_CORRUPT_STATE, raw);
        if (!tag.contains("version", Tag.TAG_INT)) return invalid(REASON_CORRUPT_STATE, raw);
        if (tag.getInt("version") != VERSION) return invalid(REASON_UNKNOWN_VERSION, raw);
        if (tag.getAllKeys().size() != 6
                || !tag.contains("failures", Tag.TAG_INT)
                || !tag.contains("blocked", Tag.TAG_BYTE)
                || !tag.contains("retry_not_before", Tag.TAG_LONG)
                || !tag.contains("stage", Tag.TAG_STRING)
                || !tag.contains("reason", Tag.TAG_STRING)
                || tag.getByte("blocked") < 0 || tag.getByte("blocked") > 1) {
            return invalid(REASON_CORRUPT_STATE, raw);
        }
        CleanupRecovery recovery = new CleanupRecovery();
        recovery.failures = tag.getInt("failures");
        recovery.blocked = tag.getBoolean("blocked");
        recovery.retryNotBefore = tag.getLong("retry_not_before");
        recovery.stage = tag.getString("stage");
        recovery.reason = tag.getString("reason");
        return recovery.valid() ? recovery : invalid(REASON_CORRUPT_STATE, raw);
    }

    private boolean valid() {
        if (failures < 0 || failures > FAILURE_LIMIT || retryNotBefore < 0 || !isKnownStage(stage)) return false;
        if (!blocked && failures == 0) {
            return retryNotBefore == 0 && STAGE_NONE.equals(stage) && REASON_NONE.equals(reason);
        }
        boolean retryReason = REASON_IO_FAILURE.equals(reason)
                || REASON_VERIFICATION_NOT_EMPTY.equals(reason) && STAGE_VERIFY_EMPTY.equals(stage);
        if (blocked) {
            return retryNotBefore == 0 && !STAGE_NONE.equals(stage)
                    && (isBlockReason(reason) || failures == FAILURE_LIMIT && isFailureStage(stage) && retryReason);
        }
        return failures < FAILURE_LIMIT && isFailureStage(stage) && retryReason
                && retryNotBefore >= (failures == 1 ? 20 : 60);
    }

    private static CleanupRecovery invalid(String failureReason, Tag raw) {
        CleanupRecovery recovery = new CleanupRecovery();
        recovery.failures = FAILURE_LIMIT;
        recovery.blockUnexpected(STAGE_UNKNOWN, failureReason);
        recovery.preservedRaw = raw == null ? null : raw.copy();
        return recovery;
    }

    private static boolean isKnownStage(String value) {
        return STAGE_NONE.equals(value) || STAGE_UNKNOWN.equals(value) || STAGE_WAIT_IDLE.equals(value)
                || isFailureStage(value);
    }

    private static boolean isFailureStage(String value) {
        if (value == null) return false;
        return switch (value) {
            case STAGE_PREPARE, STAGE_DRAIN_IO, STAGE_REMOVE_TICKETS, STAGE_RELEASE_REFERENCES,
                    STAGE_DELETE_RECORDS, STAGE_VERIFY_EMPTY, STAGE_FINALIZE -> true;
            default -> false;
        };
    }

    private static boolean isBlockReason(String value) {
        if (value == null) return false;
        return switch (value) {
            case REASON_UNEXPECTED_FAILURE, REASON_CANCELLED, REASON_MISSING_RUNTIME, REASON_MISSING_DIMENSION,
                    REASON_INVALID_CLOCK, REASON_CLOCK_OVERFLOW, REASON_CORRUPT_STATE, REASON_UNKNOWN_VERSION -> true;
            default -> false;
        };
    }
}
