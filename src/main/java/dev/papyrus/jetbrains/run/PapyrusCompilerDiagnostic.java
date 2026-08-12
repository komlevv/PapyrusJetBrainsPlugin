package dev.papyrus.jetbrains.run;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the pinned upstream VS Code {@code $PapyrusCompiler} problem matcher.
 *
 * <p>Pyro may wrap the compiler line in its own {@code COMPILATION FAILED:} log envelope. The
 * upstream regexp still matches that line, but its greedy file group then includes the envelope;
 * this parser removes only that observed envelope from the captured file group.</p>
 */
public record PapyrusCompilerDiagnostic(
        @NotNull String filePath,
        int line,
        int column,
        @NotNull String message,
        int fileStartOffset,
        int fileEndOffset
) {
    private static final String STDERR_PREFIX = "[stderr] ";
    private static final String PYRO_COMPILATION_FAILED_PREFIX = "COMPILATION FAILED:";
    private static final Pattern UPSTREAM_PATTERN = Pattern.compile("^(.*)\\((\\d+),(\\d+)\\):(.*)$");

    public static @Nullable PapyrusCompilerDiagnostic parse(@NotNull String rawLine) {
        String lineText = stripLineEnding(rawLine);
        int prefixLength = lineText.startsWith(STDERR_PREFIX) ? STDERR_PREFIX.length() : 0;
        String candidate = prefixLength == 0 ? lineText : lineText.substring(prefixLength);
        Matcher matcher = UPSTREAM_PATTERN.matcher(candidate);
        if (!matcher.matches()) {
            return null;
        }

        String filePath = matcher.group(1);
        if (filePath == null || filePath.isBlank()) {
            return null;
        }

        int fileStartInGroup = 0;
        int pyroPrefix = filePath.lastIndexOf(PYRO_COMPILATION_FAILED_PREFIX);
        if (pyroPrefix >= 0) {
            fileStartInGroup = pyroPrefix + PYRO_COMPILATION_FAILED_PREFIX.length();
            while (fileStartInGroup < filePath.length() && Character.isWhitespace(filePath.charAt(fileStartInGroup))) {
                fileStartInGroup++;
            }
            filePath = filePath.substring(fileStartInGroup);
            if (filePath.isBlank()) {
                return null;
            }
        }

        try {
            int line = Integer.parseInt(matcher.group(2));
            int column = Integer.parseInt(matcher.group(3));
            String message = matcher.group(4) == null ? "" : matcher.group(4).stripLeading();
            return new PapyrusCompilerDiagnostic(
                    filePath,
                    line,
                    column,
                    message,
                    prefixLength + matcher.start(1) + fileStartInGroup,
                    prefixLength + matcher.end(1)
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static @NotNull String stripLineEnding(@NotNull String value) {
        int end = value.length();
        while (end > 0) {
            char character = value.charAt(end - 1);
            if (character != '\r' && character != '\n') {
                break;
            }
            end--;
        }
        return end == value.length() ? value : value.substring(0, end);
    }
}
