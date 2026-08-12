package dev.papyrus.jetbrains.lsp;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.openapi.util.TextRange;
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport;
import com.intellij.psi.PsiFile;
import org.eclipse.lsp4j.Diagnostic;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Makes zero-length Papyrus/Creation Kit diagnostics visible in the IDE editor.
 */
public final class PapyrusDiagnosticsSupport extends LspDiagnosticsSupport {

    @Override
    public void createAnnotation(
            @NotNull AnnotationHolder holder,
            @NotNull Diagnostic diagnostic,
            @NotNull TextRange textRange,
            @NotNull List<? extends IntentionAction> quickFixes
    ) {
        super.createAnnotation(holder, diagnostic, expandZeroLengthRange(holder, textRange), quickFixes);
    }

    private static @NotNull TextRange expandZeroLengthRange(
            @NotNull AnnotationHolder holder,
            @NotNull TextRange range
    ) {
        if (!range.isEmpty()) {
            return range;
        }

        PsiFile file = holder.getCurrentAnnotationSession().getFile();
        String text = file.getText();
        int length = text.length();
        int start = Math.clamp(range.getStartOffset(), 0, length);

        if (length == 0) {
            return range;
        }

        if (start == length) {
            return new TextRange(length - 1, length);
        }

        int tokenStart = start;
        while (tokenStart < length && isHorizontalWhitespace(text.charAt(tokenStart))) {
            tokenStart++;
        }

        if (tokenStart >= length || text.charAt(tokenStart) == '\r' || text.charAt(tokenStart) == '\n') {
            return new TextRange(start, Math.min(start + 1, length));
        }

        int tokenEnd = tokenStart;
        if (isIdentifierCharacter(text.charAt(tokenStart))) {
            while (tokenEnd < length && isIdentifierCharacter(text.charAt(tokenEnd))) {
                tokenEnd++;
            }
        } else {
            tokenEnd++;
        }

        return new TextRange(tokenStart, tokenEnd);
    }

    private static boolean isHorizontalWhitespace(char value) {
        return value == ' ' || value == '\t';
    }

    private static boolean isIdentifierCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }
}
