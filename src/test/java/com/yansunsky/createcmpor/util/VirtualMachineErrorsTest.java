package com.yansunsky.createcmpor.util;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Pure Java rules; --negative-control replaces the aggregate with plain allOf and must fail. */
public final class VirtualMachineErrorsTest {
    private static boolean negativeControl;
    private static int checks;

    public static void main(String[] args) {
        if (args.length > 1 || (args.length == 1 && !"--negative-control".equals(args[0]))) {
            throw new IllegalArgumentException("Expected no arguments or --negative-control");
        }
        negativeControl = args.length == 1;
        checks = 0;
        ordinaryAndFatalCauses();
        cyclicCauses();
        ordinaryAggregates();
        mixedAggregates();
        cancellationAndInputSnapshot();
        System.out.println("VirtualMachineErrors rules passed: " + checks
                + " checks (causes, cycles, mixed errors, barrier, cancellation, values).");
    }

    private static void ordinaryAndFatalCauses() {
        VirtualMachineErrors.rethrowIfPresent(null);
        IOException ordinary = new IOException("ordinary");
        RuntimeException wrapper = new RuntimeException(ordinary);
        VirtualMachineErrors.rethrowIfPresent(ordinary);
        VirtualMachineErrors.rethrowIfPresent(wrapper);
        VirtualMachineErrors.rethrowIfPresent(new AssertionError("non-VM error"));
        check(wrapper.getCause() == ordinary && ordinary.getCause() == null,
                "ordinary causes must remain unchanged");
        TestVmError fatal = new TestVmError();
        expectFatal(fatal, fatal, "direct VM error");
        expectFatal(new CompletionException(fatal), fatal, "CompletionException");
        expectFatal(new RuntimeException(new CompletionException(new CompletionException(fatal))),
                fatal, "nested RuntimeException/CompletionException");
        Throwable deep = fatal;
        for (int index = 0; index < 1000; index++) {
            deep = new RuntimeException(deep);
        }
        expectFatal(deep, fatal, "the entire cause chain must be scanned");
    }

    private static void cyclicCauses() {
        VirtualMachineErrors.rethrowIfPresent(new SelfCause());
        SelfVmError selfFatal = new SelfVmError();
        expectFatal(selfFatal, selfFatal, "self-cause VM error");
        for (int prefix = 0; prefix < 6; prefix++) {
            for (int length = 1; length < 9; length++) {
                VirtualMachineErrors.rethrowIfPresent(cycle(prefix, length, null, -1));
                for (int fatalIndex = 0; fatalIndex < length; fatalIndex++) {
                    TestVmError fatal = length == 1 ? new SelfVmError() : new TestVmError();
                    expectFatal(cycle(prefix, length, fatal, fatalIndex), fatal,
                            "VM error anywhere in a cycle must be found");
                }
            }
        }
    }

    private static Throwable cycle(int prefix, int length, TestVmError fatal, int fatalIndex) {
        Throwable[] nodes = new Throwable[prefix + length];
        for (int index = 0; index < nodes.length; index++) {
            nodes[index] = index == prefix + fatalIndex && fatal != null
                    ? fatal : length == 1 && index == prefix ? new SelfCause() : new RuntimeException();
        }
        for (int index = 0; index < nodes.length - 1; index++) {
            nodes[index].initCause(nodes[index + 1]);
        }
        if (length > 1) {
            nodes[nodes.length - 1].initCause(nodes[prefix]);
        }
        return nodes[0];
    }

    private static void ordinaryAggregates() {
        check(aggregate().join() == null, "empty allOf must succeed with null");
        Object value = new Object();
        CompletableFuture<Object> first = CompletableFuture.completedFuture(value);
        CompletableFuture<String> second = CompletableFuture.completedFuture("second");
        check(aggregate(first, second).join() == null, "successful allOf returns null");
        check(first.join() == value && "second".equals(second.join()), "child values must not change");

        IOException io = new IOException("left");
        CompletableFuture<Object> left = CompletableFuture.failedFuture(io);
        CompletableFuture<Object> right = CompletableFuture.failedFuture(new IllegalStateException("right"));
        Throwable expected = failure(CompletableFuture.allOf(left, right));
        Throwable actual = failure(aggregate(left, right));
        check(actual.getClass() == expected.getClass() && actual.getCause() == expected.getCause(),
                "ordinary aggregate failure selection and cause identity must be preserved");
        CompletionException wrapped = new CompletionException(io);
        CompletableFuture<Object> wrappedChild = CompletableFuture.failedFuture(wrapped);
        check(failure(aggregate(wrappedChild)) == wrapped,
                "an existing ordinary CompletionException must not be replaced");
        nullRejected(() -> aggregate((CompletableFuture<?>[]) null));
        nullRejected(() -> aggregate(first, null));
    }

    private static void mixedAggregates() {
        for (boolean fatalFirstArgument : new boolean[]{false, true}) {
            for (boolean fatalFirstCompletion : new boolean[]{false, true}) {
                for (boolean wrapped : new boolean[]{false, true}) {
                    TrackingFuture<Object> ordinary = new TrackingFuture<>();
                    TrackingFuture<Object> fatalChild = new TrackingFuture<>();
                    TrackingFuture<Object> pending = new TrackingFuture<>();
                    TestVmError fatal = new TestVmError();
                    Throwable fatalFailure = wrapped
                            ? new RuntimeException(new CompletionException(new CompletionException(fatal))) : fatal;
                    CompletableFuture<Void> result = fatalFirstArgument
                            ? aggregate(fatalChild, ordinary, pending) : aggregate(ordinary, fatalChild, pending);
                    if (fatalFirstCompletion) {
                        fatalChild.completeExceptionally(fatalFailure);
                    } else {
                        ordinary.completeExceptionally(new IOException("ordinary first"));
                    }
                    check(!result.isDone(), "a failure must not finish the barrier early");
                    if (fatalFirstCompletion) {
                        ordinary.completeExceptionally(new IOException("ordinary later"));
                    } else {
                        fatalChild.completeExceptionally(fatalFailure);
                    }
                    check(!result.isDone(), "allOf must wait for the successful pending child too");
                    check(ordinary.joins == 0 && fatalChild.joins == 0 && pending.joins == 0,
                            "no child may be joined before every child settles");
                    check(ordinary.cancels == 0 && fatalChild.cancels == 0 && pending.cancels == 0,
                            "allOf must not cancel children");
                    Object value = new Object();
                    pending.complete(value);
                    check(result.isDone(), "the completed barrier must settle");
                    expectFatal(failure(result), fatal,
                            "mixed allOf must expose the same VM error (plain allOf control must fail)");
                    check(pending.join() == value, "the pending child's eventual value must remain unchanged");
                }
            }
        }
    }

    private static void cancellationAndInputSnapshot() {
        TrackingFuture<Object> cancelled = new TrackingFuture<>();
        cancelled.cancel(false);
        Throwable ordinaryCancellation = failure(aggregate(cancelled));
        Throwable plainCancellation = failure(CompletableFuture.allOf(cancelled));
        check(ordinaryCancellation.getCause() == plainCancellation.getCause(),
                "ordinary child cancellation semantics must be preserved");
        check(cancelled.cancels == 1, "the aggregate must not cancel an already cancelled child again");

        TrackingFuture<Object> fatalChild = new TrackingFuture<>();
        TestVmError fatal = new TestVmError();
        CompletableFuture<Void> mixed = aggregate(cancelled, fatalChild);
        check(!mixed.isDone(), "cancelled child must not skip the remaining child");
        fatalChild.completeExceptionally(fatal);
        expectFatal(failure(mixed), fatal, "cancellation must not hide a sibling VM error");

        TrackingFuture<Object> pending = new TrackingFuture<>();
        CompletableFuture<Void> cancelledAggregate = aggregate(pending);
        cancelledAggregate.cancel(false);
        check(!pending.isDone() && pending.cancels == 0, "cancelling the aggregate must not cancel its child");
        pending.complete("survives");
        check("survives".equals(pending.join()), "child must still complete after aggregate cancellation");

        CompletableFuture<Object> ordinary = CompletableFuture.failedFuture(new IOException("ordinary"));
        CompletableFuture<Object> lateFatal = new CompletableFuture<>();
        CompletableFuture<?>[] supplied = {ordinary, lateFatal};
        CompletableFuture<Void> snapshot = aggregate(supplied);
        supplied[1] = CompletableFuture.completedFuture("replacement");
        TestVmError snapshotFatal = new TestVmError();
        lateFatal.completeExceptionally(snapshotFatal);
        expectFatal(failure(snapshot), snapshotFatal, "the original child set must survive caller array mutation");
    }

    private static CompletableFuture<Void> aggregate(CompletableFuture<?>... futures) {
        return negativeControl ? CompletableFuture.allOf(futures) : VirtualMachineErrors.allOf(futures);
    }

    private static Throwable failure(CompletableFuture<?> future) {
        check(future.isDone(), "test must not join a pending aggregate");
        try {
            future.join();
        } catch (CompletionException | CancellationException exception) {
            return exception;
        }
        throw new AssertionError("expected an exceptional aggregate");
    }

    private static void expectFatal(Throwable failure, VirtualMachineError expected, String message) {
        try {
            VirtualMachineErrors.rethrowIfPresent(failure);
        } catch (VirtualMachineError actual) {
            check(actual == expected, message + ": VM error identity changed");
            return;
        }
        throw new AssertionError(message + ": VM error was hidden");
    }

    private static void nullRejected(Runnable action) {
        try {
            action.run();
        } catch (NullPointerException expected) {
            checks++;
            return;
        }
        throw new AssertionError("null allOf input must fail synchronously");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static class TestVmError extends VirtualMachineError {
    }

    private static final class SelfVmError extends TestVmError {
        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    private static final class SelfCause extends RuntimeException {
        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }

    private static final class TrackingFuture<T> extends CompletableFuture<T> {
        private int joins;
        private int cancels;

        @Override
        public T join() {
            check(isDone(), "helper must never join a pending child");
            joins++;
            return super.join();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancels++;
            return super.cancel(mayInterruptIfRunning);
        }
    }
}
