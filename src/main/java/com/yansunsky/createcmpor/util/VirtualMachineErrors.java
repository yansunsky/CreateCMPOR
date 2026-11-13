package com.yansunsky.createcmpor.util;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Keeps fatal VM failures out of ordinary recovery paths. */
public final class VirtualMachineErrors {
    private VirtualMachineErrors() {
    }

    /** Rethrows the original VM error, including through cyclic cause chains, without allocating. */
    public static void rethrowIfPresent(Throwable failure) {
        Throwable slow = failure;
        Throwable fast = failure;
        while (slow != null) {
            if (slow instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            slow = slow.getCause();
            fast = fast == null ? null : fast.getCause();
            fast = fast == null ? null : fast.getCause();
            if (slow != null && slow == fast) {
                // Floyd's meeting point may precede an unchecked part of the cycle.
                Throwable cycle = slow;
                do {
                    if (cycle instanceof VirtualMachineError fatal) {
                        throw fatal;
                    }
                    cycle = cycle.getCause();
                } while (cycle != slow);
                return;
            }
        }
    }

    /**
     * Waits for every child like CompletableFuture.allOf, but prioritizes a VM error
     * hidden by an ordinary sibling failure. No child is joined until the barrier completes.
     */
    public static CompletableFuture<Void> allOf(CompletableFuture<?>... futures) {
        CompletableFuture<?>[] children = futures.clone();
        return CompletableFuture.allOf(children).exceptionally(failure -> {
            rethrowIfPresent(failure);
            for (CompletableFuture<?> child : children) {
                if (child.isCompletedExceptionally()) {
                    try {
                        child.join();
                    } catch (CompletionException | CancellationException exception) {
                        rethrowIfPresent(exception);
                    }
                }
            }
            if (failure instanceof CompletionException completion) {
                throw completion;
            }
            throw new CompletionException(failure);
        });
    }
}
