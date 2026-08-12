package dev.papyrus.jetbrains.lsp;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.platform.lsp.api.LspIntegrationProvider;
import dev.papyrus.jetbrains.config.PapyrusSettings;
import org.jetbrains.annotations.NotNull;

public final class PapyrusLspIntegrationProvider
        implements LspIntegrationProvider {

    @Override
    public void fileOpened(
            @NotNull Project project,
            @NotNull VirtualFile file,
            @NotNull LspIntegrationProvider.LspClientStarter clientStarter
    ) {
        if (!PapyrusSettings.getInstance().getState().enabled) {
            return;
        }
        String extension = file.getExtension();
        if ("psc".equalsIgnoreCase(extension) || "ppj".equalsIgnoreCase(extension)) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Papyrus Projects");
                    if (toolWindow != null) {
                        toolWindow.setAvailable(true);
                    }
                }
            });
            project.getService(PapyrusWorkspaceFileWatcher.class).ensureStarted();
            project.getService(PapyrusDocumentSyncCompatibility.class).ensureStarted();
            clientStarter.ensureClientStarted(
                    new PapyrusLspClientDescriptor(project)
            );
        }
    }
}