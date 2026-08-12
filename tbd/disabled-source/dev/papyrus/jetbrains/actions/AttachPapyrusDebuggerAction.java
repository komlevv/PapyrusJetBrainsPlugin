package dev.papyrus.jetbrains.actions;

import com.intellij.execution.ExecutionException;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import dev.papyrus.jetbrains.debug.PapyrusDebugProcess;
import dev.papyrus.jetbrains.debug.PapyrusDebugSupport;
import dev.papyrus.jetbrains.runtime.PapyrusRuntimePaths;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AttachPapyrusDebuggerAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        try {
            if (!Files.isRegularFile(PapyrusRuntimePaths.getDebugAdapterExecutable())) {
                throw new IOException("Papyrus debug adapter was not found in the configured VSIX runtime.");
            }

            PapyrusDebugSupport.InstallState state = PapyrusDebugSupport.getInstallState();
            if (state != PapyrusDebugSupport.InstallState.INSTALLED) {
                String message = state == PapyrusDebugSupport.InstallState.NOT_INSTALLED
                        ? "Papyrus debugging support is not installed. Run 'Papyrus: Install Skyrim Debug Support' first."
                        : "The installed Papyrus debug server does not match the VSIX runtime. Reinstall debugging support first.";
                PapyrusActionUtil.showError(project, message);
                return;
            }

            if (!PapyrusDebugSupport.isSkyrimRunning()) {
                int answer = Messages.showYesNoDialog(
                        project,
                        "SkyrimSE.exe does not appear to be running. Start Skyrim through SKSE and wait until the main menu or game is loaded. Continue anyway?",
                        "Papyrus Debugger",
                        "Continue",
                        "Cancel",
                        null
                );
                if (answer != Messages.YES) {
                    return;
                }
            }

            Path projectPath = selectedProjectPath(event);
            XDebuggerManager.getInstance(project)
                    .newSessionBuilder(new XDebugProcessStarter() {
                        @Override
                        public @NotNull XDebugProcess start(@NotNull XDebugSession session) throws ExecutionException {
                            return new PapyrusDebugProcess(session, projectPath);
                        }
                    })
                    .sessionName("Papyrus: Skyrim Special Edition")
                    .showTab(true)
                    .showToolWindowOnSuspendOnly(false)
                    .startSession();
        } catch (ExecutionException | IOException | RuntimeException exception) {
            PapyrusActionUtil.showError(project, "Failed to attach the Papyrus debugger: " + rootMessage(exception));
        }
    }

    private static Path selectedProjectPath(@NotNull AnActionEvent event) {
        VirtualFile file = PapyrusActionUtil.getFile(event);
        return PapyrusActionUtil.isExtension(file, "ppj") ? file.toNioPath() : null;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
