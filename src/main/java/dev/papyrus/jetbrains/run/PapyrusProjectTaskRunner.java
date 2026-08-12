package dev.papyrus.jetbrains.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.task.ModuleBuildTask;
import com.intellij.task.ProjectTask;
import com.intellij.task.ProjectTaskContext;
import com.intellij.task.ProjectTaskRunner;
import dev.papyrus.jetbrains.config.PapyrusProjectSettings;
import dev.papyrus.jetbrains.status.PapyrusLspOutputOpener;
import dev.papyrus.jetbrains.status.PapyrusLspOutputService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.AsyncPromise;
import org.jetbrains.concurrency.Promise;

import java.nio.file.Path;

public final class PapyrusProjectTaskRunner extends ProjectTaskRunner {
    @Override
    public boolean canRun(
            @NotNull Project project,
            @NotNull ProjectTask projectTask,
            @Nullable ProjectTaskContext context
    ) {
        return !project.isDisposed()
                && !project.isDefault()
                && projectTask instanceof ModuleBuildTask
                && PapyrusProjectSettings.usesPapyrusBuild(project);
    }

    @Override
    public @NotNull Promise<Result> run(
            @NotNull Project project,
            @NotNull ProjectTaskContext context,
            ProjectTask @NotNull ... tasks
    ) {
        AsyncPromise<Result> promise = new AsyncPromise<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                promise.setResult(runPapyrusBuild(project));
            } catch (RuntimeException exception) {
                PapyrusLspOutputService output = PapyrusLspOutputService.getInstance(project);
                output.appendLine("[build] FAILED: " + safeMessage(exception));
                output.appendBlankLine();
                openOutput(project);
                PapyrusProjectCompileService.release(project);
                promise.setResult(PapyrusTaskResult.FAILURE);
            } catch (Error error) {
                promise.setError(error);
                throw error;
            }
        });
        return promise;
    }

    private static @NotNull Result runPapyrusBuild(@NotNull Project project) {
        PapyrusLspOutputService output = PapyrusLspOutputService.getInstance(project);
        if (!PapyrusProjectCompileService.tryAcquire(project)) {
            output.appendLine("[build] FAILED: another Papyrus project compilation is already running.");
            output.appendBlankLine();
            openOutput(project);
            return PapyrusTaskResult.FAILURE;
        }

        PapyrusProjectCompileService.PreparedCompile prepared = null;
        try {
            Path projectFile = PapyrusProjectSettings.resolveProjectFile(project);
            prepared = PapyrusProjectCompileService.prepare(project, projectFile);
            openOutput(project);
            output.appendLine("[build] Build system: Papyrus (Pyro)");
            output.appendLine("[build] Project: " + prepared.plan().projectFile());
            output.appendLine("[build] Output: " + prepared.plan().outputDirectory());
            output.appendLine("[build] Starting bundled Pyro for Skyrim SE/AE...");

            ProcessOutput processOutput = new CapturingProcessHandler(prepared.commandLine()).runProcess();
            appendProcessOutput(output, processOutput);
            if (processOutput.isTimeout() || processOutput.isCancelled()) {
                output.appendLine("[build] ABORTED.");
                return PapyrusTaskResult.ABORTED;
            }
            if (processOutput.getExitCode() != 0) {
                output.appendLine("[build] FAILED (exit code " + processOutput.getExitCode() + ")");
                return PapyrusTaskResult.FAILURE;
            }

            output.appendLine("[build] Completed successfully.");
            return PapyrusTaskResult.SUCCESS;
        } catch (ExecutionException exception) {
            output.appendLine("[build] FAILED to start Pyro: " + safeMessage(exception));
            openOutput(project);
            return PapyrusTaskResult.FAILURE;
        } catch (Exception exception) {
            output.appendLine("[build] BLOCKED: " + safeMessage(exception));
            openOutput(project);
            return PapyrusTaskResult.FAILURE;
        } finally {
            if (prepared != null) {
                PapyrusProjectCompileService.cleanup(prepared);
            }
            output.appendBlankLine();
            PapyrusProjectCompileService.release(project);
        }
    }

    private static void appendProcessOutput(@NotNull PapyrusLspOutputService service, @NotNull ProcessOutput processOutput) {
        for (String line : processOutput.getStdoutLines(false)) {
            service.appendLine(line);
        }
        for (String line : processOutput.getStderrLines(false)) {
            service.appendLine("[stderr] " + line);
        }
    }

    private static void openOutput(@NotNull Project project) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                PapyrusLspOutputOpener.openOutput(project);
            }
        });
    }

    private enum PapyrusTaskResult implements Result {
        SUCCESS(false, false),
        FAILURE(false, true),
        ABORTED(true, false);

        private final boolean aborted;
        private final boolean errors;

        PapyrusTaskResult(boolean aborted, boolean errors) {
            this.aborted = aborted;
            this.errors = errors;
        }

        @Override
        public boolean isAborted() {
            return aborted;
        }

        @Override
        public boolean hasErrors() {
            return errors;
        }
    }

    private static @NotNull String safeMessage(@NotNull Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
