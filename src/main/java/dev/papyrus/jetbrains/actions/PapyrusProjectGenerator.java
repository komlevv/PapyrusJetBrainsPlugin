package dev.papyrus.jetbrains.actions;

import dev.papyrus.jetbrains.PapyrusPluginVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;

final class PapyrusProjectGenerator {

    private static final String STAGING_PREFIX = ".papyrus-project-staging-";

    private static final String IDE_PROJECT_SETTINGS_XML = """
            <project version="4">
              <component name="dev.papyrus.intellij.config.PapyrusProjectSettings">
                <option name="pluginVersion" value="%s" />
                <option name="gameId" value="skyrimSpecialEdition" />
                <option name="projectFile" value="skyrimse.ppj" />
                <option name="buildSystem" value="papyrus" />
              </component>
            </project>
            """.formatted(PapyrusPluginVersion.CURRENT);

    private static final String ATTACH_RUN_CONFIGURATION_XML = """
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="Papyrus: Skyrim SE/AE" type="PapyrusAttach" factoryName="Papyrus Attach">
                <option name="request" value="attach" />
                <option name="game" value="skyrimSpecialEdition" />
                <option name="projectFile" value="$PROJECT_DIR$/skyrimse.ppj" />
                <method v="2" />
              </configuration>
            </component>
            """;

    private static final String COMPILE_RUN_CONFIGURATION_XML = """
            <component name="ProjectRunConfigurationManager">
              <configuration default="false" name="Papyrus: Compile Project" type="PapyrusProject" factoryName="Papyrus Project">
                <option name="projectFile" value="$PROJECT_DIR$/skyrimse.ppj" />
                <method v="2" />
              </configuration>
            </component>
            """;

    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private PapyrusProjectGenerator() {
    }

    static Path generateNewProject(
            Path parentDirectory,
            String folderName,
            Path ppjTemplate,
            Path sourcePath,
            Path forbiddenRoot
    ) throws IOException {
        String validationError = validateFolderName(folderName);
        if (validationError != null) {
            throw new IOException(validationError);
        }

        Path parentReal = ensureParentDirectoryIsStable(parentDirectory);
        Path targetDirectory = parentReal.resolve(folderName);
        ensureNewTargetIsSafe(targetDirectory);
        ensureTargetIsOutsideRoot(targetDirectory, forbiddenRoot);

        String ppjContent = renderProjectFile(ppjTemplate, sourcePath);
        Path stagingDirectory = Files.createTempDirectory(parentReal, STAGING_PREFIX);
        try {
            writeProjectFiles(stagingDirectory, ppjContent, true);

            ensureNewTargetIsSafe(targetDirectory);
            ensureTargetIsOutsideRoot(targetDirectory, forbiddenRoot);
            Files.move(stagingDirectory, targetDirectory);
            return targetDirectory.toAbsolutePath().normalize();
        } catch (IOException | RuntimeException | Error failure) {
            try {
                deleteOwnedTree(stagingDirectory);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    static Path populateExistingProject(
            Path targetDirectory,
            Path ppjTemplate,
            Path sourcePath,
            Path forbiddenRoot
    ) throws IOException {
        if (!Files.isDirectory(targetDirectory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(targetDirectory)) {
            throw new IOException("the IDE project root must be a stable real directory.");
        }

        Path targetReal = targetDirectory.toRealPath();
        ensureTargetIsOutsideRoot(targetReal, forbiddenRoot);
        String ppjContent = renderProjectFile(ppjTemplate, sourcePath);
        writeProjectFiles(targetReal, ppjContent, false);
        return targetReal;
    }

    @SuppressWarnings("UseOptimizedEelFunctions") // Project generation reads a local user-selected template; remote Eel paths are out of scope.
    private static String renderProjectFile(Path ppjTemplate, Path sourcePath) throws IOException {
        Path checkedPpjTemplate = requireRegularFile(ppjTemplate);
        return Files.readString(checkedPpjTemplate, StandardCharsets.UTF_8)
                .replace("${SKYRIMSE_PATH}", escapeXmlText(sourcePath.toString()));
    }

    private static void writeProjectFiles(Path root, String ppjContent, boolean writeProjectSettings) throws IOException {
        Path runDirectory = root.resolve(".run");
        Path sourceDirectory = root.resolve(Path.of("Source", "Scripts"));
        Files.createDirectories(runDirectory);
        Files.createDirectories(sourceDirectory);

        Files.writeString(
                root.resolve("skyrimse.ppj"),
                ppjContent,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
        Files.writeString(
                runDirectory.resolve("Papyrus_Skyrim_SE_AE.run.xml"),
                ATTACH_RUN_CONFIGURATION_XML,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
        Files.writeString(
                runDirectory.resolve("Papyrus_Compile.run.xml"),
                COMPILE_RUN_CONFIGURATION_XML,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );

        if (writeProjectSettings) {
            Path ideaDirectory = root.resolve(".idea");
            Files.createDirectories(ideaDirectory);
            Files.writeString(
                    ideaDirectory.resolve("papyrus.xml"),
                    IDE_PROJECT_SETTINGS_XML,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
        }
    }


    static String validateFolderName(String folderName) {
        if (folderName.isEmpty()) {
            return "Project folder name cannot be empty.";
        }
        if (folderName.equals(".") || folderName.equals("..")) {
            return "Project folder name cannot be '.' or '..'.";
        }
        if (folderName.endsWith(".") || folderName.endsWith(" ")) {
            return "Project folder name cannot end with a dot or space.";
        }
        for (int i = 0; i < folderName.length(); i++) {
            char c = folderName.charAt(i);
            if (c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0) {
                return "Project folder name contains characters that are not valid on Windows.";
            }
        }
        String stem = folderName;
        int dotIndex = stem.indexOf('.');
        if (dotIndex >= 0) {
            stem = stem.substring(0, dotIndex);
        }
        if (WINDOWS_RESERVED_NAMES.contains(stem.toUpperCase(Locale.ROOT))) {
            return "Project folder name is reserved by Windows.";
        }
        return null;
    }

    static Path ensureParentDirectoryIsStable(Path parentDirectory) throws IOException {
        if (!Files.isDirectory(parentDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("the selected parent is not a real directory.");
        }
        if (Files.isSymbolicLink(parentDirectory)) {
            throw new IOException("symbolic-link parent folders are not accepted for project generation.");
        }
        return parentDirectory.toRealPath();
    }

    static void ensureNewTargetIsSafe(Path targetDirectory) throws IOException {
        if (Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("the target folder already exists. Choose a new folder name.");
        }
        Path parent = targetDirectory.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw new IOException("the target parent is not a stable real directory.");
        }
    }

    static void ensureTargetIsOutsideRoot(Path targetDirectory, Path forbiddenRoot) throws IOException {
        if (forbiddenRoot == null || !Files.isDirectory(forbiddenRoot)) {
            return;
        }

        Path rootReal = forbiddenRoot.toRealPath();
        Path targetAbsolute = targetDirectory.toAbsolutePath().normalize();
        if (targetAbsolute.startsWith(rootReal)) {
            throw new IOException(
                    "project generation inside the configured Skyrim installation is disabled for safety. "
                            + "Choose a separate parent folder."
            );
        }
    }

    static Path requireRegularFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("required project template was not found: " + path);
        }
        return path;
    }

    private static void deleteOwnedTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        IOException failure = null;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String escapeXmlText(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
