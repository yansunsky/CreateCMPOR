package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.util.VirtualMachineErrors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;

/** Observes owned operations without waiting for, cancelling, or forgetting unfinished IO. */
final class CleanupFutures {
    private CleanupFutures() {
    }

    static Observation inspect(CompletableFuture<?>... futures) {
        boolean pending = false;
        boolean cancelled = false;
        List<RuntimeException> failures = new ArrayList<>();
        for (CompletableFuture<?> future : futures) {
            if (future == null) continue;
            if (!future.isDone()) {
                pending = true;
                continue;
            }
            if (future.isCancelled()) {
                // CompletableFuture cancellation does not prove the producer has stopped touching storage.
                cancelled = true;
            }
            try {
                future.join();
            } catch (RuntimeException failure) {
                VirtualMachineErrors.rethrowIfPresent(failure);
                if (containsCancellation(failure)) cancelled = true;
                failures.add(failure);
            }
        }
        return new Observation(pending, cancelled, List.copyOf(failures));
    }

    /** A real IOException anywhere in the wrapper chain, with cyclic causes handled safely. */
    static boolean isIoFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof IOException) return true;
        }
        return false;
    }

    private static boolean containsCancellation(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof CancellationException) return true;
        }
        return false;
    }

    record Observation(boolean pending, boolean cancelled, List<RuntimeException> failures) {
        boolean settled() {
            return !pending && !cancelled;
        }

        RuntimeException failure() {
            RuntimeException io = null;
            for (RuntimeException failure : failures) {
                if (!isIoFailure(failure)) return failure;
                if (io == null) io = failure;
            }
            return io;
        }
    }
}
