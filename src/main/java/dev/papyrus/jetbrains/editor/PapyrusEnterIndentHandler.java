package dev.papyrus.jetbrains.editor;

import com.intellij.application.options.CodeStyle;
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

public final class PapyrusEnterIndentHandler implements EnterHandlerDelegate {
    private static final Pattern INCREASE_INDENT = Pattern.compile(
            "^\\s*(if|while|(\\S+\\s+)?(property\\W+\\w+(?!.*(auto)))|struct|group|state|event|(\\S+\\s+)?(function.*\\(.*\\)(?!.*native))|else|elseif)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern DECREASE_INDENT = Pattern.compile(
            "^\\s*(endif|endwhile|endproperty|endstruct|endgroup|endstate|endevent|endfunction|else|elseif)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public @NotNull Result postProcessEnter(
            @NotNull PsiFile file,
            @NotNull Editor editor,
            @NotNull DataContext dataContext
    ) {
        if (!isPapyrusTextMateFile(file)) {
            return Result.Continue;
        }

        Document document = editor.getDocument();
        int caretOffset = editor.getCaretModel().getOffset();
        int currentLine = document.getLineNumber(caretOffset);
        if (currentLine <= 0) {
            return Result.Continue;
        }

        int previousStart = document.getLineStartOffset(currentLine - 1);
        int previousEnd = document.getLineEndOffset(currentLine - 1);
        String previousLine = document.getText().substring(previousStart, previousEnd);
        int change = indentChange(previousLine);
        if (change == 0) {
            return Result.Continue;
        }

        CommonCodeStyleSettings.IndentOptions options = CodeStyle.getIndentOptions(file);
        int tabSize = Math.max(1, options.TAB_SIZE);
        int baseIndent = visualIndent(previousLine, tabSize);
        int targetIndent = Math.max(0, baseIndent + change * tabSize);
        String replacement = makeIndent(targetIndent, tabSize, options.USE_TAB_CHARACTER);

        int currentStart = document.getLineStartOffset(currentLine);
        int currentEnd = document.getLineEndOffset(currentLine);
        CharSequence chars = document.getCharsSequence();
        int existingIndentEnd = currentStart;
        while (existingIndentEnd < currentEnd) {
            char c = chars.charAt(existingIndentEnd);
            if (c != ' ' && c != '\t') {
                break;
            }
            existingIndentEnd++;
        }

        int oldIndentLength = existingIndentEnd - currentStart;
        if (!document.getText().substring(currentStart, existingIndentEnd).equals(replacement)) {
            document.replaceString(currentStart, existingIndentEnd, replacement);
            int delta = replacement.length() - oldIndentLength;
            if (caretOffset <= existingIndentEnd) {
                editor.getCaretModel().moveToOffset(currentStart + replacement.length());
            } else {
                editor.getCaretModel().moveToOffset(caretOffset + delta);
            }
        }

        return Result.Continue;
    }

    static int indentChange(String line) {
        if (INCREASE_INDENT.matcher(line).find()) {
            return 1;
        }
        if (DECREASE_INDENT.matcher(line).find()) {
            return -1;
        }
        return 0;
    }

    static int visualIndent(String line, int tabSize) {
        int columns = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                columns++;
            } else if (c == '\t') {
                columns += tabSize - (columns % tabSize);
            } else {
                break;
            }
        }
        return columns;
    }

    static String makeIndent(int columns, int tabSize, boolean useTabs) {
        if (columns <= 0) {
            return "";
        }
        if (!useTabs) {
            return " ".repeat(columns);
        }
        int safeTabSize = Math.max(1, tabSize);
        return "\t".repeat(columns / safeTabSize) + " ".repeat(columns % safeTabSize);
    }

    private static boolean isPapyrusTextMateFile(PsiFile file) {
        if (!"textmate".equalsIgnoreCase(file.getLanguage().getID())) {
            return false;
        }
        String extension = file.getVirtualFile() != null ? file.getVirtualFile().getExtension() : null;
        return extension != null && extension.equalsIgnoreCase("psc");
    }
}
