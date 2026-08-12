package dev.papyrus.jetbrains.lsp;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.platform.lsp.api.Lsp4jClient;
import com.intellij.platform.lsp.api.LspServerNotificationsHandler;
import dev.papyrus.jetbrains.projects.PapyrusProjectsService;
import dev.papyrus.jetbrains.status.PapyrusScriptStatusService;
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.jetbrains.annotations.NotNull;

public final class PapyrusLsp4jClient extends Lsp4jClient {

    private static final Logger LOG = Logger.getInstance(PapyrusLsp4jClient.class);

    private final Project project;

    public PapyrusLsp4jClient(
            @NotNull Project project,
            @NotNull LspServerNotificationsHandler serverNotificationsHandler
    ) {
        super(serverNotificationsHandler);
        this.project = project;
    }

    @JsonNotification("papyrus/projectsUpdated")
    public void projectsUpdated(Object ignored) {
        LOG.debug("Papyrus projects updated");
        PapyrusProjectsService.getInstance(project).projectsUpdated();
        PapyrusScriptStatusService.getInstance(project).invalidateAll();
    }
}
