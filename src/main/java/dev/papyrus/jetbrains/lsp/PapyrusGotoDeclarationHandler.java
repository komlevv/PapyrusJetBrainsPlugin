package dev.papyrus.jetbrains.lsp;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import dev.papyrus.jetbrains.projects.PapyrusImportLibraryService;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Provides Go To Declaration while the active Papyrus script is an import library source.
 *
 * <p>IntelliJ Platform 2026.2 native LSP support deliberately accepts only project-content
 * documents. Papyrus import scripts are modeled as library sources instead, so this bridge sends
 * the same textDocument/definition request directly to the already-running papyrus-lang client.
 * Project-content scripts return {@code null} here and continue through the native LSP path.</p>
 */
public final class PapyrusGotoDeclarationHandler implements GotoDeclarationHandler {
    private static final int REQUEST_TIMEOUT_MS = 10_000;

    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(
            @Nullable PsiElement sourceElement,
            int offset,
            Editor editor
    ) {
        if (sourceElement == null || editor == null) {
            return null;
        }

        PsiFile sourcePsi = sourceElement.getContainingFile();
        VirtualFile sourceFile = sourcePsi == null ? null : sourcePsi.getVirtualFile();
        if (sourceFile == null || !isPapyrusScript(sourceFile)) {
            return null;
        }

        Project project = sourceElement.getProject();
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        if (fileIndex.isInContent(sourceFile)) {
            // Native IntelliJ LSP owns project-content files. Returning null also avoids duplicate targets.
            return null;
        }
        if (!fileIndex.isInLibrarySource(sourceFile)
                && !PapyrusImportLibraryService.getInstance(project).isImportFile(sourceFile)) {
            return null;
        }

        LspClient client = findRunningClient(project);
        if (client == null) {
            return null;
        }

        Position position = toPosition(editor.getDocument(), offset);
        List<DefinitionTarget> definitions = requestDefinitions(client, sourceFile, position);
        if (definitions.isEmpty()) {
            return null;
        }

        List<PsiElement> targets = new ArrayList<>();
        for (DefinitionTarget definition : definitions) {
            PsiElement target = toPsiTarget(project, client, definition);
            if (target != null) {
                targets.add(target);
            }
        }
        return targets.isEmpty() ? null : targets.toArray(PsiElement[]::new);
    }

    private static @Nullable LspClient findRunningClient(@NotNull Project project) {
        return LspClientManager.getInstance(project)
                .getClients(PapyrusLspIntegrationProvider.class)
                .stream()
                .filter(client -> client.getState() == LspServerState.Running)
                .findFirst()
                .orElse(null);
    }

    private static @NotNull List<DefinitionTarget> requestDefinitions(
            @NotNull LspClient client,
            @NotNull VirtualFile sourceFile,
            @NotNull Position position
    ) {
        Future<List<DefinitionTarget>> future = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            DefinitionParams params = new DefinitionParams();
            params.setTextDocument(client.getDocumentIdentifier(sourceFile));
            params.setPosition(position);

            Either<List<? extends Location>, List<? extends LocationLink>> response = client.sendRequestSync(
                    REQUEST_TIMEOUT_MS,
                    server -> server.getTextDocumentService().definition(params)
            );
            return definitionTargets(response);
        });

        try {
            return future.get(REQUEST_TIMEOUT_MS + 1_000L, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            future.cancel(true);
            return List.of();
        }
    }

    private static @NotNull List<DefinitionTarget> definitionTargets(
            @Nullable Either<List<? extends Location>, List<? extends LocationLink>> response
    ) {
        if (response == null) {
            return List.of();
        }

        Map<String, DefinitionTarget> unique = new LinkedHashMap<>();
        if (response.isLeft()) {
            List<? extends Location> locations = response.getLeft();
            if (locations != null) {
                for (Location location : locations) {
                    if (location == null || location.getUri() == null || location.getRange() == null) {
                        continue;
                    }
                    DefinitionTarget target = new DefinitionTarget(location.getUri(), location.getRange());
                    unique.putIfAbsent(targetKey(target), target);
                }
            }
        } else {
            List<? extends LocationLink> links = response.getRight();
            if (links != null) {
                for (LocationLink link : links) {
                    if (link == null || link.getTargetUri() == null) {
                        continue;
                    }
                    Range range = link.getTargetSelectionRange() != null
                            ? link.getTargetSelectionRange()
                            : link.getTargetRange();
                    if (range == null) {
                        continue;
                    }
                    DefinitionTarget target = new DefinitionTarget(link.getTargetUri(), range);
                    unique.putIfAbsent(targetKey(target), target);
                }
            }
        }
        return List.copyOf(unique.values());
    }

    private static @Nullable PsiElement toPsiTarget(
            @NotNull Project project,
            @NotNull LspClient client,
            @NotNull DefinitionTarget definition
    ) {
        VirtualFile targetFile = client.getDescriptor().findFileByUri(definition.uri());
        if (targetFile == null || !targetFile.isValid()) {
            return null;
        }

        PsiFile psiFile = PsiManager.getInstance(project).findFile(targetFile);
        Document document = FileDocumentManager.getInstance().getDocument(targetFile);
        if (psiFile == null || document == null) {
            return null;
        }

        int targetOffset = toOffset(document, definition.range().getStart());
        PsiElement element = psiFile.findElementAt(targetOffset);
        return element != null ? element : psiFile;
    }

    private static @NotNull Position toPosition(@NotNull Document document, int offset) {
        int safeOffset = Math.clamp(offset, 0, document.getTextLength());
        int line = document.getLineNumber(safeOffset);
        int character = safeOffset - document.getLineStartOffset(line);
        return new Position(line, character);
    }

    private static int toOffset(@NotNull Document document, @NotNull Position position) {
        if (document.getLineCount() == 0) {
            return 0;
        }
        int line = Math.clamp(position.getLine(), 0, document.getLineCount() - 1);
        int lineStart = document.getLineStartOffset(line);
        int lineEnd = document.getLineEndOffset(line);
        return Math.clamp(lineStart + Math.max(0, position.getCharacter()), lineStart, lineEnd);
    }

    private static @NotNull String targetKey(@NotNull DefinitionTarget target) {
        Position start = target.range().getStart();
        Position end = target.range().getEnd();
        return target.uri() + '#'
                + start.getLine() + ':' + start.getCharacter() + '-'
                + end.getLine() + ':' + end.getCharacter();
    }

    private static boolean isPapyrusScript(@NotNull VirtualFile file) {
        return "psc".equalsIgnoreCase(file.getExtension());
    }

    private record DefinitionTarget(@NotNull String uri, @NotNull Range range) {
    }
}
