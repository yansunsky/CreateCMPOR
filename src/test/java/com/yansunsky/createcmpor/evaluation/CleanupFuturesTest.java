package com.yansunsky.createcmpor.evaluation;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;

/** No world, threads, disk, or sleeps: exercises the production owned-future observation helper. */
public final class CleanupFuturesTest {
    public static void main(String[] args) {
        pendingIsNeverJoined();
        settledFailuresDoNotHideLateFatal();
        allFivePositionsAreObserved();
        cancellationIsNotQuiescence();
        ordinaryFailuresRemainObservable();
        cyclicIoClassificationTerminates();
        unknownFailureTakesPriorityOverIo();
        System.out.println("CleanupFuturesTest: 7 checks passed");
    }

    private static void pendingIsNeverJoined() {
        CompletableFuture<Void> pending = new CompletableFuture<>() {
            @Override public Void join() { throw new AssertionError("Joined an unfinished operation"); }
        };
        var result = CleanupFutures.inspect(pending, null, CompletableFuture.completedFuture(null));
        require(result.pending() && !result.settled() && result.failure() == null, "pending must wait");
    }

    private static void settledFailuresDoNotHideLateFatal() {
        var pending = new CompletableFuture<Void>();
        var late = new CompletableFuture<Void>();
        var io = CompletableFuture.failedFuture(new IOException("ordinary"));
        require(!CleanupFutures.inspect(pending, io, late).settled(), "ordinary sibling must not stop scan");
        var fatal = new OutOfMemoryError("late fatal");
        late.completeExceptionally(new CompletionException(new IllegalStateException(fatal)));
        expectFatal(fatal, () -> CleanupFutures.inspect(pending, io, late));
        require(!pending.isDone(), "observing fatal must not cancel the pending producer");
    }

    private static void allFivePositionsAreObserved() {
        for (int index = 0; index < 5; index++) {
            CompletableFuture<?>[] owned = new CompletableFuture<?>[5];
            for (int i = 0; i < owned.length; i++) owned[i] = new CompletableFuture<>();
            var fatal = new StackOverflowError("position " + index);
            owned[index].completeExceptionally(fatal);
            expectFatal(fatal, () -> CleanupFutures.inspect(owned));
        }
    }

    private static void cancellationIsNotQuiescence() {
        var cancelled = new CompletableFuture<Void>();
        cancelled.cancel(false);
        var result = CleanupFutures.inspect(cancelled);
        require(result.cancelled() && !result.pending() && !result.settled(), "cancel is not producer completion");
        var fatal = new OutOfMemoryError("after cancelled sibling");
        expectFatal(fatal, () -> CleanupFutures.inspect(cancelled, CompletableFuture.failedFuture(fatal)));
        var cancelledWithFatal = new CancellationException("cancelled wrapper");
        cancelledWithFatal.initCause(fatal);
        expectFatal(fatal, () -> CleanupFutures.inspect(CompletableFuture.failedFuture(cancelledWithFatal)));
        var wrappedCancellation = CleanupFutures.inspect(CompletableFuture.failedFuture(
                new CompletionException(new CancellationException("producer state unknown"))));
        require(!wrappedCancellation.settled() && wrappedCancellation.cancelled(), "wrapped cancel is also unsafe");
    }

    private static void ordinaryFailuresRemainObservable() {
        var io = CompletableFuture.failedFuture(new CompletionException(new IOException("disk")));
        var result = CleanupFutures.inspect(io, CompletableFuture.completedFuture(null));
        require(result.settled() && CleanupFutures.isIoFailure(result.failure()), "settled IOException is retryable");
        require(CleanupFutures.inspect(io).failure() == result.failure(), "inspection must preserve original result");
    }

    private static void cyclicIoClassificationTerminates() {
        var first = new IllegalStateException("first");
        var second = new IllegalStateException("second");
        first.initCause(second);
        second.initCause(first);
        require(!CleanupFutures.isIoFailure(first), "unknown cycle must fail closed");
        var io = new IOException("io");
        var wrapper = new CompletionException(io);
        io.initCause(wrapper);
        require(CleanupFutures.isIoFailure(wrapper), "real IO remains identifiable inside a cycle");
    }

    private static void unknownFailureTakesPriorityOverIo() {
        var unknown = CompletableFuture.failedFuture(new IllegalArgumentException("invalid data"));
        var result = CleanupFutures.inspect(CompletableFuture.failedFuture(new IOException("disk")), unknown);
        require(!CleanupFutures.isIoFailure(result.failure()) && result.failures().size() == 2,
                "an ordinary IO sibling must not turn an unknown failure into retries");
    }

    private static void expectFatal(VirtualMachineError expected, Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected original VM error");
        } catch (VirtualMachineError actual) {
            require(actual == expected, "fatal must be rethrown as the same instance");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
