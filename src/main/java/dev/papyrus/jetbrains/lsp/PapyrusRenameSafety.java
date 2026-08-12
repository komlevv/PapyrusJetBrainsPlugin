package dev.papyrus.jetbrains.lsp;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import dev.papyrus.jetbrains.config.PapyrusSettings;
import dev.papyrus.jetbrains.protocol.ProjectInfo;
import dev.papyrus.jetbrains.protocol.ProjectInfoSourceInclude;
import dev.papyrus.jetbrains.protocol.ProjectInfos;
import dev.papyrus.jetbrains.runtime.PapyrusGameInstallPathResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

public final class PapyrusRenameSafety {

    private static final Set<String> RESERVED_IDENTIFIERS = Set.of(
            "as", "auto", "autoreadonly", "bool", "conditional", "const", "default",
            "else", "elseif", "endevent", "endfunction", "endgroup", "endif", "endproperty",
            "endstate", "endstruct", "endwhile", "event", "extends", "false", "float",
            "function", "global", "group", "hidden", "if", "import", "int", "mandatory",
            "native", "new", "none", "parent", "property", "return", "scriptname", "self",
            "state", "string", "struct", "true", "var", "while"
    );

    public record Decision(boolean allowed, @NotNull String reason, @Nullable Path path) {
        public static @NotNull Decision allow(@NotNull Path path) {
            return new Decision(true, "", path);
        }

        public static @NotNull Decision block(@NotNull String reason, @Nullable Path path) {
            return new Decision(false, reason, path);
        }
    }

    private PapyrusRenameSafety() {
    }

    static boolean isValidRenameIdentifier(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        if (!isIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            if (!isIdentifierPart(value.charAt(index))) {
                return false;
            }
        }
        return !RESERVED_IDENTIFIERS.contains(value.toLowerCase(Locale.ROOT));
    }

    static boolean isExpectedRenameReplacement(
            @NotNull String currentText,
            @NotNull String currentName,
            @NotNull String replacementText,
            @NotNull String requestedNewName
    ) {
        return !currentText.isEmpty()
                && currentText.equalsIgnoreCase(currentName)
                && replacementText.equals(requestedNewName);
    }

    static boolean isScriptNameDeclarationPrefix(@NotNull String linePrefix) {
        return "scriptname".equalsIgnoreCase(linePrefix.trim());
    }

    static boolean isIdentifierStart(char value) {
        return value == '_' || Character.isLetter(value);
    }

    static boolean isIdentifierPart(char value) {
        return value == '_' || Character.isLetterOrDigit(value);
    }

    public static @NotNull Decision validateExistingScript(
            @NotNull Project project,
            @NotNull VirtualFile file,
            @Nullable ProjectInfos projectInfos
    ) {
        Path candidate;
        try {
            candidate = canonicalExisting(file.toNioPath());
        } catch (IOException | RuntimeException exception) {
            return Decision.block("The target path cannot be resolved safely.", safePath(file));
        }

        String extension = file.getExtension();
        if (extension == null || !"psc".equals(extension.toLowerCase(Locale.ROOT))) {
            return Decision.block("Only existing Papyrus .psc files can be changed by Rename.", candidate);
        }

        Path projectRoot = canonicalProjectRoot(project);
        if (projectRoot == null) {
            return Decision.block("The IDE project does not have a writable project root.", candidate);
        }

        Path creationKitRoot = PapyrusGameInstallPathResolver
                .resolveSkyrimSpecialEdition(PapyrusSettings.getInstance().getState().creationKitInstallPath)
                .map(path -> canonicalOptionalDirectory(path.toString()))
                .orElse(null);
        if (creationKitRoot != null && candidate.startsWith(creationKitRoot)) {
            return Decision.block("Creation Kit / game files are always read-only for Papyrus Rename.", candidate);
        }

        Path vendorCacheRoot = PathManager.getSystemDir().resolve(Path.of("papyrus", "vendor")).toAbsolutePath().normalize();
        if (candidate.startsWith(vendorCacheRoot)) {
            return Decision.block("Bundled papyrus-lang vendor/cache files are always read-only.", candidate);
        }

        SourceKind sourceKind = sourceKind(projectInfos, candidate);
        if (!candidate.startsWith(projectRoot)) {
            if (sourceKind.remote()) {
                return Decision.block("The symbol belongs to a remote Papyrus source. Remote sources are read-only.", candidate);
            }
            if (sourceKind.importOnly()) {
                return Decision.block("The symbol belongs to an imported Papyrus source. Imported sources are read-only for Rename.", candidate);
            }
            return Decision.block("The symbol belongs to a file outside the writable IDE project.", candidate);
        }

        if (sourceKind.remote()) {
            return Decision.block("The symbol belongs to a remote Papyrus source. Remote sources are read-only.", candidate);
        }
        if (sourceKind.importOnly()) {
            return Decision.block("The symbol belongs to an imported Papyrus source. Imported sources are read-only for Rename.", candidate);
        }

        if (!ProjectFileIndex.getInstance(project).isInContent(file)) {
            return Decision.block("The file is not part of IDE project content.", candidate);
        }

        if (!file.isWritable() || !Files.isWritable(candidate)) {
            return Decision.block("The file is read-only on disk.", candidate);
        }

        return Decision.allow(candidate);
    }

    static @NotNull Decision validatePathForTests(
            @NotNull Path candidate,
            @NotNull Path projectRoot,
            @Nullable Path creationKitRoot,
            @Nullable Path vendorCacheRoot,
            boolean inProjectContent,
            boolean writable,
            boolean remoteSource,
            boolean importSource
    ) {
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();

        if (!normalizedCandidate.toString().toLowerCase(Locale.ROOT).endsWith(".psc")) {
            return Decision.block("Only existing Papyrus .psc files can be changed by Rename.", normalizedCandidate);
        }
        if (creationKitRoot != null && normalizedCandidate.startsWith(creationKitRoot.toAbsolutePath().normalize())) {
            return Decision.block("Creation Kit / game files are always read-only for Papyrus Rename.", normalizedCandidate);
        }
        if (vendorCacheRoot != null && normalizedCandidate.startsWith(vendorCacheRoot.toAbsolutePath().normalize())) {
            return Decision.block("Bundled papyrus-lang vendor/cache files are always read-only.", normalizedCandidate);
        }
        if (!normalizedCandidate.startsWith(normalizedProjectRoot)) {
            if (remoteSource) {
                return Decision.block("The symbol belongs to a remote Papyrus source. Remote sources are read-only.", normalizedCandidate);
            }
            if (importSource) {
                return Decision.block("The symbol belongs to an imported Papyrus source. Imported sources are read-only for Rename.", normalizedCandidate);
            }
            return Decision.block("The symbol belongs to a file outside the writable IDE project.", normalizedCandidate);
        }
        if (remoteSource) {
            return Decision.block("The symbol belongs to a remote Papyrus source. Remote sources are read-only.", normalizedCandidate);
        }
        if (importSource) {
            return Decision.block("The symbol belongs to an imported Papyrus source. Imported sources are read-only for Rename.", normalizedCandidate);
        }
        if (!inProjectContent) {
            return Decision.block("The file is not part of IDE project content.", normalizedCandidate);
        }
        if (!writable) {
            return Decision.block("The file is read-only on disk.", normalizedCandidate);
        }
        return Decision.allow(normalizedCandidate);
    }

    private static @NotNull SourceKind sourceKind(
            @Nullable ProjectInfos infos,
            @NotNull Path candidate
    ) {
        if (infos == null) {
            return SourceKind.NONE;
        }

        int bestDepth = -1;
        boolean remoteAtBestDepth = false;
        boolean sourceAtBestDepth = false;
        boolean importAtBestDepth = false;
        for (ProjectInfo projectInfo : infos.getProjects()) {
            for (ProjectInfoSourceInclude include : projectInfo.getSourceIncludes()) {
                String fullPath = include.getFullPath();
                if (fullPath == null || fullPath.isBlank()) {
                    continue;
                }
                Path includePath;
                try {
                    includePath = Path.of(fullPath).toAbsolutePath().normalize();
                    if (Files.exists(includePath)) {
                        includePath = includePath.toRealPath();
                    }
                } catch (IOException | RuntimeException ignored) {
                    continue;
                }
                if (!candidate.startsWith(includePath)) {
                    continue;
                }

                int depth = includePath.getNameCount();
                if (depth > bestDepth) {
                    bestDepth = depth;
                    remoteAtBestDepth = include.isRemote();
                    sourceAtBestDepth = !include.isImport();
                    importAtBestDepth = include.isImport();
                } else if (depth == bestDepth) {
                    remoteAtBestDepth |= include.isRemote();
                    sourceAtBestDepth |= !include.isImport();
                    importAtBestDepth |= include.isImport();
                }
            }
        }

        // The upstream Skyrim template lists .\Source\Scripts both as <Import> and <Folder>.
        // At the same most-specific path, an explicit Source/Folder include therefore proves
        // project ownership and wins over the duplicate Import marker. Import-only paths stay read-only.
        boolean importOnly = importAtBestDepth && !sourceAtBestDepth;
        return new SourceKind(remoteAtBestDepth, importOnly);
    }

    private static @Nullable Path canonicalProjectRoot(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return null;
        }
        try {
            return canonicalExisting(Path.of(basePath));
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static @Nullable Path canonicalOptionalDirectory(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            return Files.exists(path) ? path.toRealPath() : path;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static @NotNull Path canonicalExisting(@NotNull Path path) throws IOException {
        return path.toAbsolutePath().normalize().toRealPath();
    }

    private static @Nullable Path safePath(@NotNull VirtualFile file) {
        try {
            return file.toNioPath().toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private record SourceKind(boolean remote, boolean importOnly) {
        private static final SourceKind NONE = new SourceKind(false, false);
    }
}
