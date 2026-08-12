package dev.papyrus.jetbrains.lsp;

import com.intellij.codeInsight.FileModificationService;
import com.intellij.ide.TitledHandler;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.refactoring.rename.RenameHandler;
import dev.papyrus.jetbrains.protocol.PapyrusLsp4jServer;
import dev.papyrus.jetbrains.protocol.ProjectInfos;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public final class PapyrusRenameHandler implements RenameHandler, TitledHandler {

    private static final Logger LOG = Logger.getInstance(PapyrusRenameHandler.class);
    private static final int REQUEST_TIMEOUT_MS = 15_000;

    @Override
    public @NotNull String getActionTitle() {
        return "Rename Papyrus Symbol";
    }

    @Override
    public boolean isAvailableOnDataContext(@NotNull DataContext dataContext) {
        VirtualFile file = CommonDataKeys.VIRTUAL_FILE.getData(dataContext);
        Editor editor = CommonDataKeys.EDITOR.getData(dataContext);
        return editor != null && isPapyrusScript(file);
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file, DataContext dataContext) {
        VirtualFile sourceFile = file.getVirtualFile();
        if (!isPapyrusScript(sourceFile)) {
            return;
        }

        LspClient client = findRunningClient(project);
        if (client == null) {
            showFailure(project,
                    "Papyrus Rename is unavailable because the Papyrus language service is not running.");
            return;
        }

        Document document = editor.getDocument();
        int caretOffset = editor.getCaretModel().getOffset();
        TextRange identifierRange = identifierRange(document, caretOffset);
        if (identifierRange == null) {
            showFailure(project, "Place the caret on a Papyrus identifier before invoking Rename.");
            return;
        }

        String currentName = document.getText(identifierRange);
        Position position = toPosition(document, caretOffset);
        long initialModificationStamp = document.getModificationStamp();

        runInBackground(project, () -> {
            ProjectInfos projectInfos = requestProjectInfos(client);
            if (projectInfos == null) {
                return new PrepareResult(null, null);
            }
            return new PrepareResult(projectInfos, requestDefinition(client, sourceFile, position));
        }, prepareResult -> {
            if (document.getModificationStamp() != initialModificationStamp) {
                showFailure(project,
                        "The source file changed while Rename was being prepared. Run Rename again. No files were changed.");
                return;
            }
            if (prepareResult == null || prepareResult.projectInfos() == null) {
                showFailure(project,
                        "Papyrus Rename could not verify project source/import provenance. No files were changed.");
                return;
            }

            ProjectInfos projectInfos = prepareResult.projectInfos();
            PapyrusRenameSafety.Decision sourceDecision =
                    PapyrusRenameSafety.validateExistingScript(project, sourceFile, projectInfos);
            if (!sourceDecision.allowed()) {
                showBlocked(project, sourceDecision, currentName);
                return;
            }

            DefinitionCheck definitionCheck = checkDefinitionTargets(
                    project, client, prepareResult.definitionResponse(), projectInfos
            );
            if (!definitionCheck.allowed()) {
                showBlocked(project, requireDecision(definitionCheck.decision()), currentName);
                return;
            }

            PapyrusRenameDialog dialog = new PapyrusRenameDialog(project, currentName);
            if (!dialog.showAndGet()) {
                return;
            }
            String newName = dialog.newName();
            if (newName.equals(currentName)) {
                return;
            }

            long renameRequestStamp = document.getModificationStamp();
            runInBackground(project, () -> requestRename(client, sourceFile, position, newName), workspaceEdit -> {
                if (document.getModificationStamp() != renameRequestStamp) {
                    showFailure(project,
                            "The source file changed while Rename was being calculated. Run Rename again. No files were changed.");
                    return;
                }
                if (workspaceEdit == null) {
                    showFailure(project,
                            "The Papyrus language server did not return any rename edits. No files were changed.");
                    return;
                }

                EditPlan plan = buildEditPlan(
                        project, client, workspaceEdit, projectInfos, currentName, newName
                );
                if (!plan.allowed()) {
                    showBlocked(project, requireDecision(plan.decision()), currentName);
                    return;
                }

                applyPlan(project, plan, currentName, newName);
            });
        });
    }

    @Override
    public void invoke(@NotNull Project project, PsiElement @NotNull [] elements, DataContext dataContext) {
        showFailure(project, "Papyrus Rename is available from the editor only.");
    }

    private static @Nullable LspClient findRunningClient(@NotNull Project project) {
        return LspClientManager.getInstance(project)
                .getClients(PapyrusLspIntegrationProvider.class)
                .stream()
                .filter(client -> client.getState() == LspServerState.Running)
                .findFirst()
                .orElse(null);
    }

    private static @Nullable ProjectInfos requestProjectInfos(@NotNull LspClient client) {
        return client.sendRequestSync(
                REQUEST_TIMEOUT_MS,
                server -> ((PapyrusLsp4jServer) server).projectInfos(
                        new dev.papyrus.jetbrains.protocol.ProjectInfosParams()
                )
        );
    }

    private static @Nullable Either<List<? extends Location>, List<? extends LocationLink>> requestDefinition(
            @NotNull LspClient client,
            @NotNull VirtualFile sourceFile,
            @NotNull Position position
    ) {
        DefinitionParams params = new DefinitionParams();
        params.setTextDocument(client.getDocumentIdentifier(sourceFile));
        params.setPosition(position);
        return client.sendRequestSync(
                REQUEST_TIMEOUT_MS,
                server -> server.getTextDocumentService().definition(params)
        );
    }

    private static @NotNull DefinitionCheck checkDefinitionTargets(
            @NotNull Project project,
            @NotNull LspClient client,
            @Nullable Either<List<? extends Location>, List<? extends LocationLink>> response,
            @Nullable ProjectInfos projectInfos
    ) {
        if (response == null) {
            return DefinitionCheck.block(PapyrusRenameSafety.Decision.block(
                    "The language server could not verify where this symbol is declared. Rename was not started.",
                    null
            ));
        }

        List<DefinitionTarget> targets = definitionTargets(response);

        if (targets.isEmpty()) {
            return DefinitionCheck.block(PapyrusRenameSafety.Decision.block(
                    "The language server did not return a declaration for this symbol. Rename was not started.",
                    null
            ));
        }

        Set<String> checkedUris = new LinkedHashSet<>();
        for (DefinitionTarget target : targets) {
            VirtualFile definitionFile = client.getDescriptor().findFileByUri(target.uri());
            if (definitionFile == null) {
                return DefinitionCheck.block(PapyrusRenameSafety.Decision.block(
                        "The language server returned a definition path that the IDE cannot resolve safely.",
                        null
                ));
            }

            if (checkedUris.add(target.uri())) {
                PapyrusRenameSafety.Decision decision =
                        PapyrusRenameSafety.validateExistingScript(project, definitionFile, projectInfos);
                if (!decision.allowed()) {
                    return DefinitionCheck.block(decision);
                }
            }

            Document definitionDocument = FileDocumentManager.getInstance().getDocument(definitionFile);
            if (definitionDocument == null || target.range() == null) {
                return DefinitionCheck.block(PapyrusRenameSafety.Decision.block(
                        "The IDE could not inspect the symbol declaration safely. Rename was not started.",
                        safePath(definitionFile)
                ));
            }
            int definitionStart = toOffset(definitionDocument, target.range().getStart());
            int definitionEnd = toOffset(definitionDocument, target.range().getEnd());
            if (definitionStart < 0 || definitionEnd <= definitionStart) {
                return DefinitionCheck.block(PapyrusRenameSafety.Decision.block(
                        "The language server returned an invalid declaration range. Rename was not started.",
                        safePath(definitionFile)
                ));
            }
            if (isScriptNameDeclaration(definitionDocument, definitionStart)) {
                return DefinitionCheck.block(PapyrusRenameSafety.Decision.block(
                        "Renaming a ScriptName would also require renaming its .psc file. Script renames are not supported yet.",
                        safePath(definitionFile)
                ));
            }
        }
        return DefinitionCheck.allow();
    }

    private static @NotNull List<DefinitionTarget> definitionTargets(
            @NotNull Either<List<? extends Location>, List<? extends LocationLink>> response
    ) {
        List<DefinitionTarget> targets = new ArrayList<>();
        if (response.isLeft()) {
            List<? extends Location> locations = response.getLeft();
            if (locations != null) {
                for (Location location : locations) {
                    if (location != null && location.getUri() != null) {
                        targets.add(new DefinitionTarget(location.getUri(), location.getRange()));
                    }
                }
            }
            return targets;
        }

        List<? extends LocationLink> links = response.getRight();
        if (links != null) {
            for (LocationLink link : links) {
                if (link != null && link.getTargetUri() != null) {
                    Range range = link.getTargetSelectionRange() != null
                            ? link.getTargetSelectionRange()
                            : link.getTargetRange();
                    targets.add(new DefinitionTarget(link.getTargetUri(), range));
                }
            }
        }
        return targets;
    }

    private static @Nullable WorkspaceEdit requestRename(
            @NotNull LspClient client,
            @NotNull VirtualFile sourceFile,
            @NotNull Position position,
            @NotNull String newName
    ) {
        RenameParams params = new RenameParams();
        params.setTextDocument(client.getDocumentIdentifier(sourceFile));
        params.setPosition(position);
        params.setNewName(newName);
        return client.sendRequestSync(
                REQUEST_TIMEOUT_MS,
                server -> server.getTextDocumentService().rename(params)
        );
    }

    private static @NotNull EditPlan buildEditPlan(
            @NotNull Project project,
            @NotNull LspClient client,
            @NotNull WorkspaceEdit workspaceEdit,
            @Nullable ProjectInfos projectInfos,
            @NotNull String currentName,
            @NotNull String newName
    ) {
        if (workspaceEdit.getDocumentChanges() != null) {
            return EditPlan.block(PapyrusRenameSafety.Decision.block(
                    "The language server requested document/resource operations. Papyrus Rename only allows text edits to existing project .psc files.",
                    null
            ));
        }

        Map<String, List<TextEdit>> changes = workspaceEdit.getChanges();
        if (changes == null || changes.isEmpty()) {
            return EditPlan.block(PapyrusRenameSafety.Decision.block(
                    "The language server returned an empty rename edit. No files were changed.",
                    null
            ));
        }

        Map<Document, DocumentPlan> documents = new LinkedHashMap<>();
        for (Map.Entry<String, List<TextEdit>> entry : changes.entrySet()) {
            DocumentEditPlan documentEditPlan = buildDocumentEditPlan(
                    project,
                    client,
                    entry,
                    projectInfos,
                    currentName,
                    newName
            );
            if (!documentEditPlan.allowed()) {
                return EditPlan.block(requireDecision(documentEditPlan.decision()));
            }
            if (documentEditPlan.document() != null && documentEditPlan.plan() != null) {
                documents.put(documentEditPlan.document(), documentEditPlan.plan());
            }
        }

        if (documents.isEmpty()) {
            return EditPlan.block(PapyrusRenameSafety.Decision.block(
                    "The language server returned no usable rename edits. No files were changed.",
                    null
            ));
        }

        return EditPlan.allow(documents);
    }

    private static @NotNull DocumentEditPlan buildDocumentEditPlan(
            @NotNull Project project,
            @NotNull LspClient client,
            @NotNull Map.Entry<String, List<TextEdit>> entry,
            @Nullable ProjectInfos projectInfos,
            @NotNull String currentName,
            @NotNull String newName
    ) {
        VirtualFile file = client.getDescriptor().findFileByUri(entry.getKey());
        if (file == null) {
            return DocumentEditPlan.block(PapyrusRenameSafety.Decision.block(
                    "The language server requested a change to an unresolved or non-file URI. No files were changed.",
                    null
            ));
        }

        PapyrusRenameSafety.Decision decision = PapyrusRenameSafety.validateExistingScript(project, file, projectInfos);
        if (!decision.allowed()) {
            return DocumentEditPlan.block(decision);
        }

        Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            return DocumentEditPlan.block(PapyrusRenameSafety.Decision.block(
                    "The IDE cannot open the target as a writable text document.",
                    decision.path()
            ));
        }

        List<TextEdit> textEdits = entry.getValue();
        if (textEdits == null || textEdits.isEmpty()) {
            return DocumentEditPlan.skip();
        }

        EditOperations operations = buildEditOperations(
                document,
                textEdits,
                decision.path(),
                currentName,
                newName
        );
        if (!operations.allowed()) {
            return DocumentEditPlan.block(requireDecision(operations.decision()));
        }

        return DocumentEditPlan.allow(
                document,
                new DocumentPlan(file, document.getModificationStamp(), operations.operations())
        );
    }

    private static @NotNull EditOperations buildEditOperations(
            @NotNull Document document,
            @NotNull List<TextEdit> textEdits,
            @Nullable Path validatedPath,
            @NotNull String currentName,
            @NotNull String newName
    ) {
        List<EditOperation> operations = new ArrayList<>();
        for (TextEdit edit : textEdits) {
            PapyrusRenameSafety.Decision invalidEdit = validateTextEdit(
                    document,
                    edit,
                    validatedPath,
                    currentName,
                    newName,
                    operations
            );
            if (invalidEdit != null) {
                return EditOperations.block(invalidEdit);
            }
        }

        operations.sort(Comparator.comparingInt(EditOperation::start).thenComparingInt(EditOperation::end));
        if (hasOverlappingEdits(operations)) {
            return EditOperations.block(PapyrusRenameSafety.Decision.block(
                    "The language server returned overlapping rename edits. No files were changed.",
                    validatedPath
            ));
        }
        return EditOperations.allow(operations);
    }

    private static @Nullable PapyrusRenameSafety.Decision validateTextEdit(
            @NotNull Document document,
            @Nullable TextEdit edit,
            @Nullable Path validatedPath,
            @NotNull String currentName,
            @NotNull String newName,
            @NotNull List<EditOperation> operations
    ) {
        if (edit == null || edit.getRange() == null || edit.getNewText() == null) {
            return PapyrusRenameSafety.Decision.block(
                    "The language server returned a malformed text edit. No files were changed.",
                    validatedPath
            );
        }

        int start = toOffset(document, edit.getRange().getStart());
        int end = toOffset(document, edit.getRange().getEnd());
        if (start < 0 || end <= start || end > document.getTextLength()) {
            return PapyrusRenameSafety.Decision.block(
                    "The language server returned an invalid Rename range. Insert/delete edits are not allowed.",
                    validatedPath
            );
        }

        String currentText = document.getText(new TextRange(start, end));
        if (!PapyrusRenameSafety.isExpectedRenameReplacement(
                currentText,
                currentName,
                edit.getNewText(),
                newName
        )) {
            return PapyrusRenameSafety.Decision.block(
                    "The language server returned an edit that is not a direct rename of the selected symbol. No files were changed.",
                    validatedPath
            );
        }

        operations.add(new EditOperation(start, end, edit.getNewText()));
        return null;
    }

    private static boolean hasOverlappingEdits(@NotNull List<EditOperation> operations) {
        for (int index = 1; index < operations.size(); index++) {
            EditOperation previous = operations.get(index - 1);
            EditOperation current = operations.get(index);
            if (previous.end() > current.start()
                    || (previous.start() == previous.end()
                    && current.start() == current.end()
                    && previous.start() == current.start())) {
                return true;
            }
        }
        return false;
    }

    private static void applyPlan(
            @NotNull Project project,
            @NotNull EditPlan plan,
            @NotNull String currentName,
            @NotNull String newName
    ) {
        List<VirtualFile> files = plan.documents().values().stream().map(DocumentPlan::file).toList();
        if (!FileModificationService.getInstance().prepareVirtualFilesForWrite(project, files)) {
            showFailure(project, "The IDE refused write access for one or more rename targets. No files were changed.");
            return;
        }

        for (Map.Entry<Document, DocumentPlan> entry : plan.documents().entrySet()) {
            if (entry.getKey().getModificationStamp() != entry.getValue().modificationStamp()) {
                showFailure(project,
                        "A target file changed while Rename was being calculated. Run Rename again. No files were changed.");
                return;
            }
        }

        WriteCommandAction.writeCommandAction(project)
                .withName("Rename " + currentName + " to " + newName)
                .run(() -> {
                    for (Map.Entry<Document, DocumentPlan> entry : plan.documents().entrySet()) {
                        List<EditOperation> operations = new ArrayList<>(entry.getValue().operations());
                        operations.sort(Comparator.comparingInt(EditOperation::start).reversed()
                                .thenComparing(Comparator.comparingInt(EditOperation::end).reversed()));
                        for (EditOperation operation : operations) {
                            entry.getKey().replaceString(operation.start(), operation.end(), operation.newText());
                        }
                    }
                });
    }

    private static @Nullable TextRange identifierRange(@NotNull Document document, int offset) {
        CharSequence text = document.getCharsSequence();
        if (offset < 0 || offset > text.length()) {
            return null;
        }
        int start = offset;
        int end = offset;
        while (start > 0 && PapyrusRenameSafety.isIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && PapyrusRenameSafety.isIdentifierPart(text.charAt(end))) {
            end++;
        }
        return start == end ? null : new TextRange(start, end);
    }

    private static @NotNull Position toPosition(@NotNull Document document, int offset) {
        int safeOffset = Math.clamp(offset, 0, document.getTextLength());
        int line = document.getLineNumber(safeOffset);
        int character = safeOffset - document.getLineStartOffset(line);
        return new Position(line, character);
    }

    private static int toOffset(@NotNull Document document, @Nullable Position position) {
        if (position == null || position.getLine() < 0 || position.getCharacter() < 0) {
            return -1;
        }
        int line = position.getLine();
        if (line >= document.getLineCount()) {
            return -1;
        }
        int lineStart = document.getLineStartOffset(line);
        int lineEnd = document.getLineEndOffset(line);
        int offset = lineStart + position.getCharacter();
        return offset <= lineEnd ? offset : -1;
    }

    private static boolean isPapyrusScript(@Nullable VirtualFile file) {
        return file != null && "psc".equalsIgnoreCase(file.getExtension());
    }

    private static @NotNull PapyrusRenameSafety.Decision requireDecision(
            @Nullable PapyrusRenameSafety.Decision decision
    ) {
        if (decision == null) {
            throw new IllegalStateException("Blocked Papyrus Rename result is missing its safety decision.");
        }
        return decision;
    }

    private static void showBlocked(
            @NotNull Project project,
            @NotNull PapyrusRenameSafety.Decision decision,
            @Nullable String symbol
    ) {
        StringBuilder message = new StringBuilder("Papyrus Rename was blocked before making any changes.\n\n");
        if (symbol != null && !symbol.isBlank()) {
            message.append("Symbol: ").append(symbol).append('\n');
        }
        message.append("Reason: ").append(decision.reason());
        if (decision.path() != null) {
            message.append("\nPath: ").append(decision.path());
        }
        message.append("\n\nNo files were changed. Papyrus Rename only applies validated identifier edits to writable project .psc files.");
        Messages.showErrorDialog(project, message.toString(), "Papyrus Rename Blocked");
    }

    private static void showFailure(@NotNull Project project, @NotNull String message) {
        Messages.showErrorDialog(project, message, "Papyrus Rename Failed");
    }

    private static <T> void runInBackground(
            @NotNull Project project,
            @NotNull Callable<T> request,
            @NotNull Consumer<T> onComplete
    ) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            T result = null;
            try {
                result = request.call();
            } catch (Exception exception) {
                LOG.warn("Papyrus Rename background request failed", exception);
            }

            T completedResult = result;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed()) {
                    onComplete.accept(completedResult);
                }
            });
        });
    }

    private static boolean isScriptNameDeclaration(@NotNull Document document, int identifierStart) {
        if (identifierStart < 0 || identifierStart > document.getTextLength()) {
            return false;
        }
        int line = document.getLineNumber(identifierStart);
        int lineStart = document.getLineStartOffset(line);
        String prefix = document.getText(new TextRange(lineStart, identifierStart));
        return PapyrusRenameSafety.isScriptNameDeclarationPrefix(prefix);
    }

    private static @Nullable Path safePath(@NotNull VirtualFile file) {
        try {
            return file.toNioPath().toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record PrepareResult(
            @Nullable ProjectInfos projectInfos,
            @Nullable Either<List<? extends Location>, List<? extends LocationLink>> definitionResponse
    ) {
    }

    private record DefinitionTarget(@NotNull String uri, @Nullable Range range) {
    }

    private record DefinitionCheck(boolean allowed, @Nullable PapyrusRenameSafety.Decision decision) {
        static @NotNull DefinitionCheck allow() {
            return new DefinitionCheck(true, null);
        }

        static @NotNull DefinitionCheck block(@NotNull PapyrusRenameSafety.Decision decision) {
            return new DefinitionCheck(false, decision);
        }
    }

    private record DocumentEditPlan(
            @Nullable Document document,
            @Nullable DocumentPlan plan,
            @Nullable PapyrusRenameSafety.Decision decision
    ) {
        boolean allowed() {
            return decision == null;
        }

        static @NotNull DocumentEditPlan allow(@NotNull Document document, @NotNull DocumentPlan plan) {
            return new DocumentEditPlan(document, plan, null);
        }

        static @NotNull DocumentEditPlan skip() {
            return new DocumentEditPlan(null, null, null);
        }

        static @NotNull DocumentEditPlan block(@NotNull PapyrusRenameSafety.Decision decision) {
            return new DocumentEditPlan(null, null, decision);
        }
    }

    private record EditOperations(
            @NotNull List<EditOperation> operations,
            @Nullable PapyrusRenameSafety.Decision decision
    ) {
        boolean allowed() {
            return decision == null;
        }

        static @NotNull EditOperations allow(@NotNull List<EditOperation> operations) {
            return new EditOperations(List.copyOf(operations), null);
        }

        static @NotNull EditOperations block(@NotNull PapyrusRenameSafety.Decision decision) {
            return new EditOperations(List.of(), decision);
        }
    }

    private record EditOperation(int start, int end, @NotNull String newText) {
    }

    private record DocumentPlan(
            @NotNull VirtualFile file,
            long modificationStamp,
            @NotNull List<EditOperation> operations
    ) {
    }

    private record EditPlan(
            boolean allowed,
            @Nullable PapyrusRenameSafety.Decision decision,
            @NotNull Map<Document, DocumentPlan> documents
    ) {
        static @NotNull EditPlan allow(@NotNull Map<Document, DocumentPlan> documents) {
            return new EditPlan(true, null, Map.copyOf(documents));
        }

        static @NotNull EditPlan block(@NotNull PapyrusRenameSafety.Decision decision) {
            return new EditPlan(false, decision, Map.of());
        }
    }
}
