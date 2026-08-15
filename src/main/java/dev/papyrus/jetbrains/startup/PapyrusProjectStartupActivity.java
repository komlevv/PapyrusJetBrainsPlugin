package dev.papyrus.jetbrains.startup;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.platform.lsp.api.LspClientManager;
import dev.papyrus.jetbrains.config.PapyrusSettings;
import dev.papyrus.jetbrains.lsp.PapyrusDocumentSyncCompatibility;
import dev.papyrus.jetbrains.lsp.PapyrusLspClientDescriptor;
import dev.papyrus.jetbrains.lsp.PapyrusLspIntegrationProvider;
import dev.papyrus.jetbrains.lsp.PapyrusWorkspaceFileWatcher;
import dev.papyrus.jetbrains.projects.PapyrusProjectsService;
import org.jetbrains.annotations.NotNull;

/**
 * Activates Papyrus project services after IDE project startup, without requiring a .psc editor tab.
 *
 * <p>The activity is intentionally gated by {@link PapyrusProjectPresenceDetector}; unrelated IDE
 * projects do not create the Papyrus project service, watcher, import library, or LSP process.</p>
 */
@SuppressWarnings("deprecation") // StartupActivity.Background remains the Java-compatible post-startup API on the supported 262 platform.
public final class PapyrusProjectStartupActivity implements StartupActivity.Background {

    @Override
    public void runActivity(@NotNull Project project) {
        if (project.isDisposed()
                || project.isDefault()
                || !PapyrusSettings.getInstance().getState().enabled
                || !PapyrusProjectPresenceDetector.hasPapyrusProjectSignal(project)) {
            return;
        }

        // Initialize the same guarded services that fileOpened() historically initialized, but do it
        // as soon as a Papyrus project itself is opened. All calls are idempotent.
        PapyrusProjectsService.getInstance(project);
        project.getService(PapyrusWorkspaceFileWatcher.class).ensureStarted();
        project.getService(PapyrusDocumentSyncCompatibility.class).ensureStarted();

        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Papyrus Projects");
            if (toolWindow != null) {
                toolWindow.setAvailable(true);
            }
        });

        // Unlike startClientsIfNeeded(), ensureClientStarted() is explicitly designed to start an
        // LSP client even when no editor file is open. The manager deduplicates an already-running
        // client with the same descriptor roots, so a later .psc open remains a harmless fallback.
        LspClientManager.getInstance(project).ensureClientStarted(
                PapyrusLspIntegrationProvider.class,
                new PapyrusLspClientDescriptor(project)
        );
    }
}
