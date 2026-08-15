package dev.papyrus.jetbrains.startup;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;

/**
 * Cheap project-startup probe used to avoid activating Papyrus support for unrelated projects.
 *
 * <p>The probe intentionally does not inspect .idea or common generated/dependency directories.
 * A project is considered Papyrus-capable when a real project-local .ppj or .psc file is present.
 * Symlinks are not followed.</p>
 */
public final class PapyrusProjectPresenceDetector {

    private static final Set<String> SKIPPED_DIRECTORY_NAMES = Set.of(
            ".git",
            ".gradle",
            ".idea",
            ".venv",
            "build",
            "node_modules",
            "out",
            "target"
    );

    private static final String SNAPSHOT_PREFIX = ".papyrus-jetbrains-";

    private PapyrusProjectPresenceDetector() {
    }

    public static boolean hasPapyrusProjectSignal(@NotNull Project project) {
        if (project.isDisposed() || project.isDefault()) {
            return false;
        }

        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return false;
        }
        try {
            return containsPapyrusSignal(Path.of(basePath));
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    static boolean containsPapyrusSignal(@NotNull Path candidateRoot) throws IOException {
        Path root = candidateRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            return false;
        }

        final boolean[] found = {false};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
                if (!dir.equals(root) && shouldSkipDirectory(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && isPapyrusSignal(file)) {
                    found[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(@NotNull Path file, @NotNull IOException exception) {
                return FileVisitResult.CONTINUE;
            }
        });
        return found[0];
    }

    private static boolean shouldSkipDirectory(@NotNull Path directory) {
        Path fileName = directory.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        return SKIPPED_DIRECTORY_NAMES.contains(name) || name.startsWith(SNAPSHOT_PREFIX);
    }

    private static boolean isPapyrusSignal(@NotNull Path file) {
        Path fileName = file.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".ppj") || name.endsWith(".psc");
    }
}
