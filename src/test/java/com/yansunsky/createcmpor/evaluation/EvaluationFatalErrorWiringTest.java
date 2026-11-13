package com.yansunsky.createcmpor.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Static integration check; runtime propagation is exercised by VirtualMachineErrorsTest. */
public final class EvaluationFatalErrorWiringTest {
    private static final Pattern CATCH = Pattern.compile("catch\\s*\\([^)]*\\s+(\\w+)\\s*\\)\\s*\\{");

    public static void main(String[] args) throws IOException {
        Path project = Path.of(System.getProperty("createcmpor.projectRoot", "."));
        List<String> paths = List.of(
                "evaluation/EvaluationCloneManager.java",
                "evaluation/EvaluationManager.java",
                "evaluation/EvaluationStorageBridge.java",
                "evaluation/EvaluationCriticalJournal.java",
                "evaluation/EvaluationObservationManager.java",
                "preview/PreviewCapture.java");
        boolean control = List.of(args).contains("--unguarded-control");
        int boundaries = 0;
        int aggregates = 0;
        for (String file : paths) {
            String source = Files.readString(project.resolve(
                    "src/main/java/com/yansunsky/createcmpor/" + file));
            if (control && file.endsWith("EvaluationCloneManager.java")) {
                source = source.replaceFirst("VirtualMachineErrors[.]rethrowIfPresent[(]exception[)];", "");
            }
            if (file.endsWith("EvaluationManager.java")) {
                if (List.of(args).contains("--unguarded-callback-control")) {
                    source = source.replace("VirtualMachineErrors.rethrowIfPresent(error);", "");
                }
                if (List.of(args).contains("--inline-callback-control")) {
                    source = source.replace("player.server.tell(new net.minecraft.server.TickTask(", "player.server.execute(");
                }
                int callbackStart = source.indexOf("RoomHelper.teleportPlayerOutOfRoom(player).whenComplete");
                int callbackEnd = source.indexOf("private static boolean restoreOrphanEvaluator", callbackStart);
                if (callbackStart < 0 || callbackEnd < 0) {
                    throw new AssertionError("Teleport completion boundary was not found");
                }
                String callback = source.substring(callbackStart, callbackEnd);
                if (!callback.contains("player.server.tell(new net.minecraft.server.TickTask(")) {
                    throw new AssertionError("Teleport completion must always enter the server queue");
                }
                String taskStart = "() -> {";
                int taskBody = callback.indexOf(taskStart);
                if (taskBody < 0 || !callback.substring(taskBody + taskStart.length()).stripLeading()
                        .startsWith("VirtualMachineErrors.rethrowIfPresent(error);")) {
                    throw new AssertionError("Teleport callback must guard fatal errors before side effects");
                }
            }
            Matcher catches = CATCH.matcher(source);
            int local = 0;
            while (catches.find()) {
                String firstStatement = source.substring(catches.end()).stripLeading();
                String guard = "VirtualMachineErrors.rethrowIfPresent(" + catches.group(1) + ");";
                if (!firstStatement.startsWith(guard)) {
                    throw new AssertionError(file + ": fatal guard must precede every catch side effect");
                }
                local++;
            }
            if (local == 0) {
                throw new AssertionError("No exception boundaries found in " + file);
            }
            boundaries += local;
            if (file.endsWith("EvaluationCloneManager.java") || file.endsWith("EvaluationStorageBridge.java")) {
                if (source.contains("CompletableFuture.allOf(")) {
                    throw new AssertionError(file + ": ordinary allOf can hide a sibling fatal failure");
                }
                aggregates += source.split("VirtualMachineErrors[.]allOf[(]", -1).length - 1;
            }
        }
        if (aggregates != 5) {
            throw new AssertionError("Expected all five storage aggregation boundaries, got " + aggregates);
        }
        System.out.println("EvaluationFatalErrorWiringTest: " + boundaries
                + " catch boundaries and " + aggregates + " aggregations guarded");
    }
}
