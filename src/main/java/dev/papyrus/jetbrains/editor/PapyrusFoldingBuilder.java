package dev.papyrus.jetbrains.editor;

import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilder;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Papyrus block folding equivalent to the block structure implied by the VSIX indentation rules.
 */
public final class PapyrusFoldingBuilder implements FoldingBuilder, DumbAware {

    @Override
    public FoldingDescriptor @NotNull [] buildFoldRegions(
            @NotNull ASTNode root,
            @NotNull Document document
    ) {
        VirtualFile file = root.getPsi().getContainingFile().getVirtualFile();
        if (file == null || !"psc".equalsIgnoreCase(file.getExtension())) {
            return FoldingDescriptor.EMPTY_ARRAY;
        }

        List<FoldingDescriptor> descriptors = new ArrayList<>();
        Deque<BlockStart> blocks = new ArrayDeque<>();
        boolean inBlockComment = false;

        for (int line = 0; line < document.getLineCount(); line++) {
            String lineText = getLineText(document, line);
            String trimmed = lineText.trim();

            if (inBlockComment) {
                if (trimmed.contains("/;")) {
                    inBlockComment = false;
                }
                continue;
            }
            if (trimmed.startsWith(";/")) {
                if (!trimmed.contains("/;") || trimmed.indexOf("/;") <= trimmed.indexOf(";/")) {
                    inBlockComment = true;
                }
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith(";")) {
                continue;
            }

            String code = stripLineComment(trimmed).trim();
            if (code.isEmpty()) {
                continue;
            }

            String lower = code.toLowerCase(Locale.ROOT);
            String closeKind = getCloseKind(lower);
            if (closeKind != null) {
                BlockStart start = popMatching(blocks, closeKind);
                if (start != null && line > start.line()) {
                    int startOffset = document.getLineEndOffset(start.line());
                    int endOffset = document.getLineEndOffset(line);
                    if (endOffset > startOffset) {
                        descriptors.add(new FoldingDescriptor(
                                root,
                                new TextRange(startOffset, endOffset),
                                null,
                                " ..."
                        ));
                    }
                }
                continue;
            }

            String openKind = getOpenKind(lower);
            if (openKind != null) {
                blocks.push(new BlockStart(openKind, line));
            }
        }

        return descriptors.toArray(FoldingDescriptor.EMPTY_ARRAY);
    }

    @Override
    public @NotNull String getPlaceholderText(@NotNull ASTNode node) {
        return " ...";
    }

    @Override
    public boolean isCollapsedByDefault(@NotNull ASTNode node) {
        return false;
    }

    private static @NotNull String getLineText(@NotNull Document document, int line) {
        int start = document.getLineStartOffset(line);
        int end = document.getLineEndOffset(line);
        return document.getText(new TextRange(start, end));
    }

    private static @NotNull String stripLineComment(@NotNull String text) {
        int commentIndex = text.indexOf(';');
        return commentIndex >= 0 ? text.substring(0, commentIndex) : text;
    }

    private static @Nullable String getOpenKind(@NotNull String lower) {
        if (startsWithKeyword(lower, "if")) return "if";
        if (startsWithKeyword(lower, "while")) return "while";
        if (startsWithKeyword(lower, "struct")) return "struct";
        if (startsWithKeyword(lower, "group")) return "group";
        if (startsWithKeyword(lower, "state")) return "state";
        if (startsWithKeyword(lower, "event")) return "event";

        if (containsDefinitionKeyword(lower, "property") && lacksKeyword(lower, "auto")) {
            return "property";
        }
        if (containsDefinitionKeyword(lower, "function") && lacksKeyword(lower, "native")) {
            return "function";
        }
        return null;
    }

    private static @Nullable String getCloseKind(@NotNull String lower) {
        if (startsWithKeyword(lower, "endif")) return "if";
        if (startsWithKeyword(lower, "endwhile")) return "while";
        if (startsWithKeyword(lower, "endproperty")) return "property";
        if (startsWithKeyword(lower, "endstruct")) return "struct";
        if (startsWithKeyword(lower, "endgroup")) return "group";
        if (startsWithKeyword(lower, "endstate")) return "state";
        if (startsWithKeyword(lower, "endevent")) return "event";
        if (startsWithKeyword(lower, "endfunction")) return "function";
        return null;
    }

    private static @Nullable BlockStart popMatching(
            @NotNull Deque<BlockStart> blocks,
            @NotNull String kind
    ) {
        while (!blocks.isEmpty()) {
            BlockStart candidate = blocks.pop();
            if (candidate.kind().equals(kind)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean startsWithKeyword(@NotNull String text, @NotNull String keyword) {
        if (!text.startsWith(keyword)) {
            return false;
        }
        return text.length() == keyword.length() || isKeywordBoundary(text.charAt(keyword.length()));
    }

    private static boolean containsDefinitionKeyword(@NotNull String text, @NotNull String keyword) {
        int index = indexOfKeyword(text, keyword);
        if (index < 0) {
            return false;
        }
        return index == 0 || isKeywordBoundary(text.charAt(index - 1));
    }

    private static boolean lacksKeyword(@NotNull String text, @NotNull String keyword) {
        return indexOfKeyword(text, keyword) < 0;
    }

    private static int indexOfKeyword(@NotNull String text, @NotNull String keyword) {
        int from = 0;
        while (from < text.length()) {
            int index = text.indexOf(keyword, from);
            if (index < 0) {
                return -1;
            }

            boolean leftBoundary = index == 0 || isKeywordBoundary(text.charAt(index - 1));
            int after = index + keyword.length();
            boolean rightBoundary = after == text.length() || isKeywordBoundary(text.charAt(after));
            if (leftBoundary && rightBoundary) {
                return index;
            }
            from = index + 1;
        }
        return -1;
    }

    private static boolean isKeywordBoundary(char value) {
        return !Character.isLetterOrDigit(value) && value != '_';
    }

    private record BlockStart(String kind, int line) {
    }
}
