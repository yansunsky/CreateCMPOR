package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.util.VirtualMachineErrors;
import net.minecraft.server.TickTask;
import net.minecraft.util.thread.BlockableEventLoop;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Real Minecraft event-loop behavior, without opening a world or connecting a player. */
public final class EvaluationFatalCallbackTest {
    private static boolean inlineControl;
    private static boolean unguardedControl;

    public static void main(String[] args) {
        inlineControl = List.of(args).contains("--inline-callback-control");
        unguardedControl = List.of(args).contains("--unguarded-callback-control");
        for (boolean asynchronous : new boolean[]{false, true}) {
            checkCompletion(asynchronous, null, null);
            checkCompletion(asynchronous, new IOException("ordinary callback failure"), null);
            SyntheticVmError direct = new SyntheticVmError();
            checkCompletion(asynchronous, direct, direct);
            SyntheticVmError wrapped = new SyntheticVmError();
            checkCompletion(asynchronous, new CompletionException(new RuntimeException(wrapped)), wrapped);
        }
        System.out.println("EvaluationFatalCallbackTest: 8 real event-loop scenarios passed");
    }

    private static void checkCompletion(boolean asynchronous, Throwable failure, VirtualMachineError expected) {
        TestLoop loop = new TestLoop();
        int[] effects = {0};
        CompletableFuture<Void> operation = new CompletableFuture<>();
        operation.whenComplete((ignored, error) -> {
            Runnable task = () -> {
                if (!unguardedControl) {
                    VirtualMachineErrors.rethrowIfPresent(error);
                }
                effects[0]++;
            };
            if (inlineControl) {
                loop.execute(task);
            } else {
                loop.tell(new TickTask(0, task));
            }
        });
        Runnable complete = () -> {
            if (failure == null) {
                operation.complete(null);
            } else {
                operation.completeExceptionally(failure);
            }
        };
        if (asynchronous) {
            CompletableFuture.runAsync(complete).join();
        } else {
            complete.run();
        }
        if (effects[0] != 0) {
            throw new AssertionError("Completion ran inline instead of entering the server queue");
        }
        VirtualMachineError seen = null;
        try {
            if (!loop.pollTask()) {
                throw new AssertionError("Completion task was lost in an ignored dependent future");
            }
        } catch (VirtualMachineError fatal) {
            seen = fatal;
        }
        if (seen != expected) {
            throw new AssertionError("Server queue failed to propagate the original fatal instance");
        }
        if (effects[0] != (expected == null ? 1 : 0)) {
            throw new AssertionError("Fatal side effects ran, or ordinary completion did not run exactly once");
        }
        if (loop.getPendingTasksCount() != 0) {
            throw new AssertionError("Completion was queued more than once");
        }
        loop.close();
    }

    private static final class SyntheticVmError extends VirtualMachineError {
        SyntheticVmError() {
            super("synthetic callback fatal; no actual resource exhaustion");
        }
    }

    private static final class TestLoop extends BlockableEventLoop<TickTask> {
        private final Thread owner = Thread.currentThread();

        TestLoop() {
            super("createcmpor-fatal-callback-test");
        }

        @Override
        protected TickTask wrapRunnable(Runnable runnable) {
            return new TickTask(0, runnable);
        }

        @Override
        protected boolean shouldRun(TickTask task) {
            return true;
        }

        @Override
        protected Thread getRunningThread() {
            return owner;
        }
    }
}
