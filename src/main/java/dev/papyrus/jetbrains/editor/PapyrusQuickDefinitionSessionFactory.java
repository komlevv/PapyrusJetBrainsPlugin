package dev.papyrus.jetbrains.editor;

import com.intellij.codeInsight.hint.ImplementationViewElement;
import com.intellij.codeInsight.hint.ImplementationViewSession;
import com.intellij.codeInsight.hint.ImplementationViewSessionFactory;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.psi.PsiElement;
import com.intellij.util.Processor;
import dev.papyrus.jetbrains.lsp.PapyrusLspIntegrationProvider;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Bridges the JetBrains IDE Quick Definition / Quick Implementations popup to Papyrus
 * textDocument/definition results. IntelliJ Platform 2026.2 only wires the native LSP
 * definition provider into Goto Declaration, not ImplementationViewSessionFactory.
 */
public final class PapyrusQuickDefinitionSessionFactory implements ImplementationViewSessionFactory {
    private static final int REQUEST_TIMEOUT_MS = 10_000;

    @Override
    public @Nullable ImplementationViewSession createSession(
            @NotNull DataContext dataContext,
            @NotNull Project project,
            boolean isSearchDeep,
            boolean alwaysIncludeSelf
    ) {
        Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
        VirtualFile sourceFile = CommonDataKeys.VIRTUAL_FILE.getData(dataContext);
        if (editor == null || sourceFile == null || !isPapyrusScript(sourceFile)) {
            return null;
        }

        Document sourceDocument = editor.getDocument();
        int sourceOffset = editor.getCaretModel().getOffset();
        Position sourcePosition = toPosition(sourceDocument, sourceOffset);
        String symbolText = identifierAt(sourceDocument, sourceOffset);

        LspClient client = findPapyrusClient(project, sourceFile);
        if (client == null) {
            return null;
        }

        DefinitionResult definition = requestDefinition(client, sourceFile, sourcePosition);
        if (definition == null) {
            return null;
        }

        ImplementationViewElement element = createElement(project, definition, symbolText);
        if (element == null) {
            return null;
        }

        return new PapyrusQuickDefinitionSession(this, project, sourceFile, editor, symbolText, List.of(element));
    }

    @Override
    public @Nullable ImplementationViewSession createSessionForLookupElement(
            @NotNull Project project,
            @Nullable Editor editor,
            @Nullable VirtualFile file,
            @Nullable Object lookupItemObject,
            boolean isSearchDeep,
            boolean alwaysIncludeSelf
    ) {
        return null;
    }

    private static @Nullable LspClient findPapyrusClient(@NotNull Project project, @NotNull VirtualFile file) {
        return LspClientManager.getInstance(project)
                .getClients(PapyrusLspIntegrationProvider.class)
                .stream()
                .filter(client -> client.getState() == LspServerState.Running)
                .findFirst()
                .orElse(null);
    }

    private static @Nullable DefinitionResult requestDefinition(
            @NotNull LspClient client,
            @NotNull VirtualFile sourceFile,
            @NotNull Position sourcePosition
    ) {
        Future<DefinitionResult> future = ApplicationManager.getApplication().executeOnPooledThread(() -> {
            DefinitionParams params = new DefinitionParams();
            params.setTextDocument(client.getDocumentIdentifier(sourceFile));
            params.setPosition(sourcePosition);

            Either<List<? extends Location>, List<? extends LocationLink>> response = client.sendRequestSync(
                    REQUEST_TIMEOUT_MS,
                    server -> server.getTextDocumentService().definition(params)
            );
            return firstDefinition(response);
        });

        try {
            return future.get(REQUEST_TIMEOUT_MS + 1_000L, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            future.cancel(true);
            return null;
        }
    }

    private static @Nullable DefinitionResult firstDefinition(
            @Nullable Either<List<? extends Location>, List<? extends LocationLink>> response
    ) {
        if (response == null) {
            return null;
        }
        if (response.isLeft()) {
            List<? extends Location> locations = response.getLeft();
            if (locations == null || locations.isEmpty()) {
                return null;
            }
            Location location = locations.getFirst();
            return new DefinitionResult(location.getUri(), location.getRange());
        }

        List<? extends LocationLink> links = response.getRight();
        if (links == null || links.isEmpty()) {
            return null;
        }
        LocationLink link = links.getFirst();
        Range range = link.getTargetSelectionRange() != null
                ? link.getTargetSelectionRange()
                : link.getTargetRange();
        return new DefinitionResult(link.getTargetUri(), range);
    }

    private static @Nullable ImplementationViewElement createElement(
            @NotNull Project project,
            @NotNull DefinitionResult definition,
            @NotNull String symbolText
    ) {
        if (definition.uri() == null || definition.range() == null) {
            return null;
        }
        VirtualFile targetFile = VirtualFileManager.getInstance().findFileByUrl(definition.uri());
        if (targetFile == null || !targetFile.isValid()) {
            return null;
        }
        Document targetDocument = FileDocumentManager.getInstance().getDocument(targetFile);
        if (targetDocument == null) {
            return null;
        }

        int targetOffset = toOffset(targetDocument, definition.range().getStart());
        String preview = previewText(targetDocument, targetOffset);
        String name = symbolText.isBlank() ? targetFile.getName() : symbolText;
        return new PapyrusQuickDefinitionElement(project, targetFile, targetOffset, name, preview);
    }

    private static boolean isPapyrusScript(@NotNull VirtualFile file) {
        return "psc".equalsIgnoreCase(file.getExtension());
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

    private static @NotNull String identifierAt(@NotNull Document document, int offset) {
        CharSequence chars = document.getCharsSequence();
        if (chars.isEmpty()) {
            return "Papyrus definition";
        }
        int safe = Math.clamp(offset, 0, chars.length() - 1);
        int start = safe;
        while (start > 0 && isIdentifierChar(chars.charAt(start - 1))) {
            start--;
        }
        int end = safe;
        while (end < chars.length() && isIdentifierChar(chars.charAt(end))) {
            end++;
        }
        return start < end ? chars.subSequence(start, end).toString() : "Papyrus definition";
    }

    private static boolean isIdentifierChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private static @NotNull String previewText(@NotNull Document document, int targetOffset) {
        int targetLine = document.getLineNumber(Math.clamp(targetOffset, 0, document.getTextLength()));
        int firstLine = Math.max(0, targetLine - 1);
        int lastLine = Math.min(document.getLineCount() - 1, targetLine + 5);
        int start = document.getLineStartOffset(firstLine);
        int end = document.getLineEndOffset(lastLine);
        return document.getText(new TextRange(start, end));
    }

    private record DefinitionResult(String uri, Range range) {
    }

    private record PapyrusQuickDefinitionSession(
            ImplementationViewSessionFactory factory,
            Project project,
            VirtualFile sourceFile,
            Editor editor,
            String text,
            List<ImplementationViewElement> elements
    ) implements ImplementationViewSession {
        @Override
        public @NotNull ImplementationViewSessionFactory getFactory() {
            return factory;
        }

        @Override
        public @NotNull Project getProject() {
            return project;
        }

        @Override
        public @NotNull List<ImplementationViewElement> getImplementationElements() {
            return elements;
        }

        @Override
        public @NotNull VirtualFile getFile() {
            return sourceFile;
        }

        @Override
        public @NotNull String getText() {
            return text;
        }

        @Override
        public @NotNull Editor getEditor() {
            return editor;
        }

        @Override
        public @NotNull List<ImplementationViewElement> searchImplementationsInBackground(
                @NotNull ProgressIndicator indicator,
                @NotNull Processor<? super ImplementationViewElement> processor
        ) {
            return List.of();
        }

        @Override
        public boolean elementRequiresIncludeSelf() {
            return false;
        }

        @Override
        public boolean needUpdateInBackground() {
            return false;
        }

        @Override
        public void dispose() {
        }
    }

    private static final class PapyrusQuickDefinitionElement extends ImplementationViewElement {
        private final Project project;
        private final VirtualFile file;
        private final int offset;
        private final String name;
        private final String preview;

        private PapyrusQuickDefinitionElement(Project project, VirtualFile file, int offset, String name, String preview) {
            this.project = project;
            this.file = file;
            this.offset = offset;
            this.name = name;
            this.preview = preview;
        }

        @Override
        public @NotNull Project getProject() {
            return project;
        }

        @Override
        public boolean isNamed() {
            return true;
        }

        @Override
        public @NotNull String getName() {
            return name;
        }

        @Override
        public @NotNull String getPresentableText() {
            return name + " — " + file.getName();
        }

        @Override
        public @NotNull VirtualFile getContainingFile() {
            return file;
        }

        @Override
        public @NotNull String getText() {
            return preview;
        }

        @Override
        public @NotNull String getLocationText() {
            return file.getPresentableUrl();
        }

        @Override
        public @Nullable Icon getLocationIcon() {
            return null;
        }

        @Override
        public @NotNull ImplementationViewElement getContainingMemberOrSelf() {
            return this;
        }

        @Override
        public @Nullable PsiElement getElementForShowUsages() {
            return null;
        }

        @Override
        public void navigate(boolean focusEditor) {
            new OpenFileDescriptor(project, file, offset).navigate(focusEditor);
        }
    }
}
