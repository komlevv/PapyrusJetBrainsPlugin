package dev.papyrus.jetbrains.actions;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vfs.VirtualFile;
import dev.papyrus.jetbrains.run.PapyrusProjectCompileService;
import dev.papyrus.jetbrains.run.PapyrusProjectTaskDiscovery;
import dev.papyrus.jetbrains.status.PapyrusLspOutputOpener;
import dev.papyrus.jetbrains.status.PapyrusLspOutputService;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

public final class CompilePapyrusProjectAction extends AnAction implements DumbAware {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        VirtualFile selectedFile = PapyrusActionUtil.getFile(event);
        if (PapyrusActionUtil.isExtension(selectedFile, "ppj")) {
            startCompile(project, selectedFile.toNioPath());
            return;
        }

        discoverAndChooseProjectFile(project);
    }

    private static void discoverAndChooseProjectFile(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            PapyrusActionUtil.showError(project, "Papyrus project compilation requires an IDE project root.");
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<PapyrusProjectTaskDiscovery.Task> tasks;
            try {
                tasks = PapyrusProjectTaskDiscovery.discover(Path.of(basePath));
            } catch (Exception exception) {
                showErrorLater(project, "Failed to discover Papyrus project files: " + safeMessage(exception));
                return;
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    chooseDiscoveredProjectFile(project, tasks);
                }
            });
        });
    }

    private static void chooseDiscoveredProjectFile(
            @NotNull Project project,
            @NotNull List<PapyrusProjectTaskDiscovery.Task> tasks
    ) {
        if (tasks.isEmpty()) {
            PapyrusActionUtil.showError(project, "No project-local .ppj files were found.");
            return;
        }
        if (tasks.size() == 1) {
            startCompile(project, tasks.getFirst().projectFile());
            return;
        }

        if (PapyrusActionTestBridge.isUiIntegrationTest()) {
            String requested = PapyrusActionTestBridge.consumePapyrusCompileSelection();
            if (requested != null) {
                String normalized = requested.replace('\\', '/');
                PapyrusProjectTaskDiscovery.Task selected = tasks.stream()
                        .filter(task -> task.relativePath().equalsIgnoreCase(normalized))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "Requested Papyrus compile task was not discovered: " + requested
                        ));
                startCompile(project, selected.projectFile());
                return;
            }
        }

        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(tasks)
                .setTitle("Compile Papyrus Project")
                .setItemChosenCallback(task -> startCompile(project, task.projectFile()))
                .createPopup()
                .showCenteredInCurrentWindow(project);
    }

    private static void startCompile(@NotNull Project project, @NotNull Path projectFile) {
        if (!PapyrusProjectCompileService.tryAcquire(project)) {
            PapyrusActionUtil.showError(project, "A Papyrus project compilation is already running.");
            return;
        }
        PapyrusLspOutputOpener.openOutput(project);
        ApplicationManager.getApplication().executeOnPooledThread(() -> compile(project, projectFile));
    }

    private static void compile(@NotNull Project project, @NotNull Path projectFile) {
        PapyrusLspOutputService output = PapyrusLspOutputService.getInstance(project);
        PapyrusProjectCompileService.PreparedCompile prepared = null;
        try {
            prepared = PapyrusProjectCompileService.prepare(project, projectFile);
            output.appendLine("[compile] Project: " + prepared.plan().projectFile());
            output.appendLine("[compile] Output: " + prepared.plan().outputDirectory());
            output.appendLine("[compile] Starting bundled Pyro for Skyrim SE/AE...");

            ProcessOutput processOutput = new CapturingProcessHandler(prepared.commandLine()).runProcess();
            appendProcessOutput(output, processOutput);

            if (processOutput.isTimeout() || processOutput.isCancelled() || processOutput.getExitCode() != 0) {
                int exitCode = processOutput.getExitCode();
                output.appendLine("[compile] FAILED (exit code " + exitCode + ")");
                showErrorLater(
                        project,
                        "Papyrus project compilation failed (exit code " + exitCode + "). See Papyrus Projects | Output."
                );
                return;
            }
            output.appendLine("[compile] Completed successfully.");
        } catch (ExecutionException exception) {
            output.appendLine("[compile] FAILED to start Pyro: " + safeMessage(exception));
            showErrorLater(project, "Failed to start Papyrus compilation: " + safeMessage(exception));
        } catch (Exception exception) {
            output.appendLine("[compile] BLOCKED: " + safeMessage(exception));
            showErrorLater(project, "Papyrus project compilation was blocked before starting.\n\n" + safeMessage(exception));
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

    private static void showErrorLater(@NotNull Project project, @NotNull String message) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                PapyrusActionUtil.showError(project, message);
            }
        });
    }

    private static @NotNull String safeMessage(@NotNull Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        boolean visible = project != null;
        event.getPresentation().setVisible(visible);
        event.getPresentation().setEnabled(visible && !PapyrusProjectCompileService.isRunning(project));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
