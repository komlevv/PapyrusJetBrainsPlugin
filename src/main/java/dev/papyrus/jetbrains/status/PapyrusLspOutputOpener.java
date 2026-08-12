package dev.papyrus.jetbrains.status;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.ui.content.Content;
import dev.papyrus.jetbrains.lsp.PapyrusLspIntegrationProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.util.Collection;

/**
 * Opens the shared read-only Papyrus output view.
 *
 * <p>IntelliJ Platform 2026.2 (262) has no LSP ServiceView contributor. Reuse the existing Papyrus
 * Projects tool window and select its Output tab instead. The status-bar entry still requires an
 * existing client; explicitly invoked tools may open the same tab without starting one.</p>
 */
public final class PapyrusLspOutputOpener {
    public static final String TOOL_WINDOW_ID = "Papyrus Projects";
    public static final String OUTPUT_CONTENT_NAME = "Output";
    private static volatile String lastOpenDiagnostic = "not invoked";

    private PapyrusLspOutputOpener() {
    }

    public static void open(@NotNull Project project) {
        if (project.isDisposed()) {
            setDiagnostic("project disposed");
            return;
        }

        LspClient client = preferredClient(
                LspClientManager.getInstance(project).getClients(PapyrusLspIntegrationProvider.class)
        );
        if (client == null) {
            setDiagnostic("no Papyrus LSP client");
            return;
        }

        openOutputInternal(project, "client=" + client.getState());
    }

    /** Opens the shared Papyrus Output tab without requiring a running language client. */
    public static void openOutput(@NotNull Project project) {
        if (project.isDisposed()) {
            setDiagnostic("project disposed");
            return;
        }
        openOutputInternal(project, "direct output");
    }

    private static void openOutputInternal(@NotNull Project project, @NotNull String diagnosticPrefix) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) {
            setDiagnostic(diagnosticPrefix + "; tool window missing");
            return;
        }

        toolWindow.setAvailable(true);
        setDiagnostic(diagnosticPrefix + "; output show requested");
        toolWindow.show(() -> {
            Content output = toolWindow.getContentManager().findContent(OUTPUT_CONTENT_NAME);
            if (output == null) {
                setDiagnostic(diagnosticPrefix + "; output content missing");
                return;
            }
            toolWindow.getContentManager().setSelectedContent(output, true);
            setDiagnostic(diagnosticPrefix + "; output selected");
        });
    }

    @TestOnly
    public static void clearDiagnosticForTests() {
        lastOpenDiagnostic = "not invoked";
    }

    @TestOnly
    public static @NotNull String diagnosticForTests() {
        return lastOpenDiagnostic;
    }

    private static void setDiagnostic(@NotNull String diagnostic) {
        if (Boolean.getBoolean("papyrus.ui.integration.test")) {
            lastOpenDiagnostic = diagnostic;
        }
    }

    static @Nullable LspClient preferredClient(@NotNull Collection<LspClient> clients) {
        LspClient best = null;
        int bestRank = Integer.MAX_VALUE;
        for (LspClient client : clients) {
            int rank = statePreference(client.getState());
            if (best == null || rank < bestRank) {
                best = client;
                bestRank = rank;
            }
        }
        return best;
    }

    static int statePreference(@NotNull LspServerState state) {
        return switch (state) {
            case Running -> 0;
            case Initializing -> 1;
            case ShutdownUnexpectedly -> 2;
            case ShutdownNormally -> 3;
        };
    }
}
