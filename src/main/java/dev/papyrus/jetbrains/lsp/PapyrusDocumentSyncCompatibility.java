package dev.papyrus.jetbrains.lsp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspServerState;
import kotlin.Unit;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Compatibility bridge for papyrus-lang v3.3.0-prerelease.1 incremental synchronization.
 *
 * <p>The server relies on the deprecated optional LSP rangeLength field when applying text changes.
 * IntelliJ Platform 2026.2 correctly omits that field, so replacements and deletions corrupt the server-side
 * document buffer. Native didOpen/didClose handling remains owned by the IntelliJ Platform LSP client; only didChange is
 * supplied here with rangeLength populated.
 */
@Service(Service.Level.PROJECT)
public final class PapyrusDocumentSyncCompatibility implements Disposable {

    private final Project project;
    private boolean started;

    public PapyrusDocumentSyncCompatibility(@NotNull Project project) {
        this.project = project;
    }

    public synchronized void ensureStarted() {
        if (started || project.isDisposed()) {
            return;
        }

        started = true;
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override
            public void beforeDocumentChange(@NotNull DocumentEvent event) {
                forwardChange(event);
            }
        }, this);
    }

    private void forwardChange(@NotNull DocumentEvent event) {
        if (project.isDisposed()) {
            return;
        }

        Document document = event.getDocument();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        if (file == null || !isPapyrusFile(file) || !ProjectFileIndex.getInstance(project).isInContent(file)) {
            return;
        }

        Range range = new Range(
                toPosition(document, event.getOffset()),
                toPosition(document, event.getOffset() + event.getOldLength())
        );

        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent();
        change.setRange(range);
        setLegacyRangeLength(change, event.getOldLength());
        change.setText(event.getNewFragment().toString());

        for (LspClient client : LspClientManager.getInstance(project)
                .getClients(PapyrusLspIntegrationProvider.class)) {
            if (client.getState() != LspServerState.Running) {
                continue;
            }

            String uri = client.getDocumentIdentifier(file).getUri();
            int version = client.getDocumentVersion(document) + 1;
            VersionedTextDocumentIdentifier identifier = new VersionedTextDocumentIdentifier(uri, version);
            DidChangeTextDocumentParams params = new DidChangeTextDocumentParams(identifier, List.of(change));

            client.sendNotification(server -> {
                server.getTextDocumentService().didChange(params);
                return Unit.INSTANCE;
            });
        }
    }

    @SuppressWarnings("deprecation") // papyrus-lang 3.3 prerelease requires the legacy LSP rangeLength field.
    private static void setLegacyRangeLength(@NotNull TextDocumentContentChangeEvent change, int rangeLength) {
        change.setRangeLength(rangeLength);
    }

    private static @NotNull Position toPosition(@NotNull Document document, int offset) {
        int safeOffset = Math.clamp(offset, 0, document.getTextLength());
        int line = document.getLineNumber(safeOffset);
        int lineStart = document.getLineStartOffset(line);
        return new Position(line, safeOffset - lineStart);
    }

    private static boolean isPapyrusFile(@NotNull VirtualFile file) {
        String extension = file.getExtension();
        return "psc".equalsIgnoreCase(extension);
    }

    @Override
    public void dispose() {
    }
}
