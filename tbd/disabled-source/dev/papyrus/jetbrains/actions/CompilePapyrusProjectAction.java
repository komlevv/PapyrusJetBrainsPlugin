package dev.papyrus.jetbrains.actions;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessHandlerFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import dev.papyrus.jetbrains.config.PapyrusSettings;
import dev.papyrus.jetbrains.runtime.PapyrusRuntimePaths;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

public final class CompilePapyrusProjectAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile ppjFile = PapyrusActionUtil.getFile(event);
        if (project == null || !PapyrusActionUtil.isExtension(ppjFile, "ppj")) {
            return;
        }

        Document ppjDocument = FileDocumentManager.getInstance().getDocument(ppjFile);
        if (ppjDocument != null && FileDocumentManager.getInstance().isDocumentUnsaved(ppjDocument)) {
            PapyrusActionUtil.showError(
                    project,
                    "The Papyrus project file has unsaved changes. Save it explicitly before compiling. "
                            + "The plugin will not auto-save editor documents."
            );
            return;
        }

        try {
            Path pyro = PapyrusRuntimePaths.getPyroExecutable();
            String gamePath = PapyrusSettings.getInstance().getState().creationKitInstallPath;
            if (gamePath == null || gamePath.isBlank()) {
                throw new IllegalStateException("Skyrim Special Edition path is not configured in Settings | Papyrus.");
            }

            GeneralCommandLine commandLine = new GeneralCommandLine(pyro.toString())
                    .withParameters(List.of(
                            "--input-path", ppjFile.getPath(),
                            "--game-type", "sse",
                            "--game-path", Path.of(gamePath).toAbsolutePath().normalize().toString()
                    ));
            String workDirectory = ppjFile.getParent() != null ? ppjFile.getParent().getPath() : project.getBasePath();
            if (workDirectory != null && !workDirectory.isBlank()) {
                commandLine.withWorkDirectory(workDirectory);
            }

            OSProcessHandler handler = ProcessHandlerFactory.getInstance().createColoredProcessHandler(commandLine);
            showProcessWithoutSavingDocuments(project, handler, ppjFile.getName());
        } catch (ExecutionException | RuntimeException exception) {
            PapyrusActionUtil.showError(project, "Failed to start Pyro: " + exception.getMessage());
        }
    }

    private static void showProcessWithoutSavingDocuments(
            @NotNull Project project,
            @NotNull OSProcessHandler handler,
            @NotNull String projectFileName
    ) {
        TextConsoleBuilder consoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(project);
        consoleBuilder.addFilter(new PapyrusCompilerFilter(project));
        ConsoleView console = consoleBuilder.getConsole();

        RunContentDescriptor descriptor = new RunContentDescriptor(
                console,
                handler,
                console.getComponent(),
                "Papyrus: " + projectFileName
        );
        descriptor.setActivateToolWindowWhenAdded(true);
        descriptor.setAutoFocusContent(false);

        RunContentManager.getInstance(project).showRunContent(
                DefaultRunExecutor.getRunExecutorInstance(),
                descriptor
        );
        console.attachToProcess(handler);
        handler.startNotify();
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(
                event.getProject() != null && PapyrusActionUtil.isExtension(PapyrusActionUtil.getFile(event), "ppj")
        );
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
