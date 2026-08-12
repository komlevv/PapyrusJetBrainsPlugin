package dev.papyrus.jetbrains.lsp;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspServerState;
import dev.papyrus.jetbrains.protocol.DocumentAssembly;
import dev.papyrus.jetbrains.protocol.DocumentAssemblyParams;
import dev.papyrus.jetbrains.protocol.DocumentScriptInfo;
import dev.papyrus.jetbrains.protocol.DocumentScriptInfoParams;
import dev.papyrus.jetbrains.protocol.DocumentSyntaxTree;
import dev.papyrus.jetbrains.protocol.DocumentSyntaxTreeParams;
import dev.papyrus.jetbrains.protocol.PapyrusLsp4jServer;
import dev.papyrus.jetbrains.protocol.ProjectInfos;
import dev.papyrus.jetbrains.protocol.ProjectInfosParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

@Service(Service.Level.PROJECT)
public final class PapyrusLanguageService {

    public record ClientBoundResult<T>(@NotNull LspClient client, @NotNull T value) {
    }

    private final Project project;

    public PapyrusLanguageService(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull PapyrusLanguageService getInstance(@NotNull Project project) {
        return project.getService(PapyrusLanguageService.class);
    }

    public @Nullable ProjectInfos requestProjectInfos() {
        ClientBoundResult<ProjectInfos> result = requestProjectInfosBound();
        return result != null ? result.value() : null;
    }

    public @Nullable ClientBoundResult<ProjectInfos> requestProjectInfosBound() {
        for (LspClient client : runningClients()) {
            ProjectInfos result = client.sendRequestSync(
                    LspClient.DEFAULT_REQUEST_TIMEOUT_MS,
                    server -> ((PapyrusLsp4jServer) server).projectInfos(new ProjectInfosParams())
            );
            if (result != null && isCurrentRunningClient(client)) {
                return new ClientBoundResult<>(client, result);
            }
        }
        return null;
    }

    public @Nullable DocumentAssembly requestAssembly(@NotNull VirtualFile file) {
        for (LspClient client : runningClients()) {
            TextDocumentIdentifier identifier = new TextDocumentIdentifier(client.getDocumentIdentifier(file).getUri());
            DocumentAssembly result = client.sendRequestSync(
                    LspClient.DEFAULT_REQUEST_TIMEOUT_MS,
                    server -> ((PapyrusLsp4jServer) server).documentAssembly(new DocumentAssemblyParams(identifier))
            );
            if (result != null && isCurrentRunningClient(client)) {
                return result;
            }
        }
        return null;
    }

    public @Nullable DocumentScriptInfo requestScriptInfo(@NotNull VirtualFile file) {
        ClientBoundResult<DocumentScriptInfo> result = requestScriptInfoBound(file);
        return result != null ? result.value() : null;
    }

    public @Nullable ClientBoundResult<DocumentScriptInfo> requestScriptInfoBound(@NotNull VirtualFile file) {
        for (LspClient client : runningClients()) {
            TextDocumentIdentifier identifier = new TextDocumentIdentifier(client.getDocumentIdentifier(file).getUri());
            DocumentScriptInfo result = client.sendRequestSync(
                    LspClient.DEFAULT_REQUEST_TIMEOUT_MS,
                    server -> ((PapyrusLsp4jServer) server).documentScriptInfo(new DocumentScriptInfoParams(identifier))
            );
            if (result != null && isCurrentRunningClient(client)) {
                return new ClientBoundResult<>(client, result);
            }
        }
        return null;
    }

    public @Nullable DocumentSyntaxTree requestSyntaxTree(@NotNull VirtualFile file) {
        for (LspClient client : runningClients()) {
            TextDocumentIdentifier identifier = new TextDocumentIdentifier(client.getDocumentIdentifier(file).getUri());
            DocumentSyntaxTree result = client.sendRequestSync(
                    LspClient.DEFAULT_REQUEST_TIMEOUT_MS,
                    server -> ((PapyrusLsp4jServer) server).documentSyntaxTree(new DocumentSyntaxTreeParams(identifier))
            );
            if (result != null && isCurrentRunningClient(client)) {
                return result;
            }
        }
        return null;
    }

    public boolean hasRunningClient() {
        return !runningClients().isEmpty();
    }

    public boolean isCurrentRunningClient(@NotNull LspClient candidate) {
        return runningClients().stream().anyMatch(client -> client == candidate);
    }

    private @NotNull Collection<LspClient> runningClients() {
        return LspClientManager.getInstance(project)
                .getClients(PapyrusLspIntegrationProvider.class)
                .stream()
                .filter(client -> client.getState() == LspServerState.Running)
                .toList();
    }
}
