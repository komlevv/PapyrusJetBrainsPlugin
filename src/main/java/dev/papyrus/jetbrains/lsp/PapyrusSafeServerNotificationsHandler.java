package dev.papyrus.jetbrains.lsp;

import com.intellij.platform.lsp.api.LspServerNotificationsHandler;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.LogTraceParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ShowDocumentParams;
import org.eclipse.lsp4j.ShowDocumentResult;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class PapyrusSafeServerNotificationsHandler implements LspServerNotificationsHandler {

    private static final String BLOCK_REASON =
            "Papyrus blocks unsolicited workspace/applyEdit requests from the language server.";

    private final LspServerNotificationsHandler delegate;
    private final Supplier<List<WorkspaceFolder>> workspaceFoldersSupplier;

    PapyrusSafeServerNotificationsHandler(@NotNull LspServerNotificationsHandler delegate) {
        this(delegate, null);
    }

    PapyrusSafeServerNotificationsHandler(
            @NotNull LspServerNotificationsHandler delegate,
            Supplier<List<WorkspaceFolder>> workspaceFoldersSupplier
    ) {
        this.delegate = delegate;
        this.workspaceFoldersSupplier = workspaceFoldersSupplier;
    }

    @Override
    public @NotNull CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(@NotNull ApplyWorkspaceEditParams params) {
        ApplyWorkspaceEditResponse response = new ApplyWorkspaceEditResponse();
        response.setApplied(false);
        response.setFailureReason(BLOCK_REASON);
        return CompletableFuture.completedFuture(response);
    }

    @Override
    public @NotNull CompletableFuture<Void> registerCapability(@NotNull RegistrationParams params) {
        return delegate.registerCapability(params);
    }

    @Override
    public @NotNull CompletableFuture<Void> unregisterCapability(@NotNull UnregistrationParams params) {
        return delegate.unregisterCapability(params);
    }

    @Override
    public void telemetryEvent(@NotNull Object object) {
        delegate.telemetryEvent(object);
    }

    @Override
    public void publishDiagnostics(@NotNull PublishDiagnosticsParams params) {
        delegate.publishDiagnostics(params);
    }

    @Override
    public void showMessage(@NotNull MessageParams params) {
        delegate.showMessage(params);
    }

    @Override
    public @NotNull CompletableFuture<MessageActionItem> showMessageRequest(@NotNull ShowMessageRequestParams params) {
        return delegate.showMessageRequest(params);
    }

    @Override
    public @NotNull CompletableFuture<ShowDocumentResult> showDocument(@NotNull ShowDocumentParams params) {
        return delegate.showDocument(params);
    }

    @Override
    public void logMessage(@NotNull MessageParams params) {
        delegate.logMessage(params);
    }

    @Override
    public @NotNull CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
        if (workspaceFoldersSupplier != null) {
            return CompletableFuture.completedFuture(workspaceFoldersSupplier.get());
        }
        return delegate.workspaceFolders();
    }

    @Override
    public @NotNull CompletableFuture<List<Object>> configuration(@NotNull ConfigurationParams params) {
        return delegate.configuration(params);
    }

    @Override
    public @NotNull CompletableFuture<Void> createProgress(@NotNull WorkDoneProgressCreateParams params) {
        return delegate.createProgress(params);
    }

    @Override
    public void notifyProgress(@NotNull ProgressParams params) {
        delegate.notifyProgress(params);
    }

    @Override
    public void logTrace(@NotNull LogTraceParams params) {
        delegate.logTrace(params);
    }

    @Override
    public @NotNull CompletableFuture<Void> refreshSemanticTokens() {
        return delegate.refreshSemanticTokens();
    }

    @Override
    public @NotNull CompletableFuture<Void> refreshCodeLenses() {
        return delegate.refreshCodeLenses();
    }

    @Override
    public @NotNull CompletableFuture<Void> refreshInlayHints() {
        return delegate.refreshInlayHints();
    }

    @Override
    public @NotNull CompletableFuture<Void> refreshInlineValues() {
        return delegate.refreshInlineValues();
    }

    @Override
    public @NotNull CompletableFuture<Void> refreshDiagnostics() {
        return delegate.refreshDiagnostics();
    }
}
