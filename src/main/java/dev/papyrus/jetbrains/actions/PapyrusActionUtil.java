package dev.papyrus.jetbrains.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class PapyrusActionUtil {

    private PapyrusActionUtil() {
    }

    static @Nullable VirtualFile getFile(@NotNull AnActionEvent event) {
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file != null) {
            return file;
        }
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        return editor != null ? editor.getVirtualFile() : null;
    }

    static boolean isExtension(@Nullable VirtualFile file, @NotNull String extension) {
        return file != null && extension.equalsIgnoreCase(file.getExtension());
    }

    static @Nullable String getWikiSearchText(@NotNull Editor editor) {
        boolean hasSelection = editor.getSelectionModel().hasSelection();
        String selectedText = hasSelection ? editor.getSelectionModel().getSelectedText() : null;
        if (hasSelection) {
            return chooseSearchText(true, selectedText, null);
        }
        return chooseSearchText(false, null, wordAtCaret(editor));
    }

    static @Nullable String chooseSearchText(
            boolean hasSelection,
            @Nullable String selectedText,
            @Nullable String caretWord
    ) {
        if (hasSelection) {
            if (selectedText == null
                    || selectedText.isBlank()
                    || selectedText.contains("\n")
                    || selectedText.contains("\r")) {
                return null;
            }
            return selectedText;
        }
        return caretWord == null || caretWord.isBlank() ? null : caretWord;
    }

    static void showError(@Nullable Project project, @NotNull String message) {
        if (PapyrusActionTestBridge.captureMessageIfEnabled("ERROR", message)) {
            return;
        }
        Messages.showErrorDialog(project, message, "Papyrus");
    }

    static void showInfo(@Nullable Project project, @NotNull String message) {
        if (PapyrusActionTestBridge.captureMessageIfEnabled("INFO", message)) {
            return;
        }
        Messages.showInfoMessage(project, message, "Papyrus");
    }

    private static @Nullable String wordAtCaret(@NotNull Editor editor) {
        Document document = editor.getDocument();
        CharSequence text = document.getCharsSequence();
        int length = text.length();
        int offset = Math.clamp(editor.getCaretModel().getOffset(), 0, length);
        int start = offset;
        int end = offset;

        while (start > 0 && isIdentifierCharacter(text.charAt(start - 1))) {
            start--;
        }
        while (end < length && isIdentifierCharacter(text.charAt(end))) {
            end++;
        }
        return start < end ? text.subSequence(start, end).toString() : null;
    }

    private static boolean isIdentifierCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }
}
