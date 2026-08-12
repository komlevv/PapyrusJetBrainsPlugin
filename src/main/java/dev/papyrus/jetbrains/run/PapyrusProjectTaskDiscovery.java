package dev.papyrus.jetbrains.run;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Discovers the bounded JetBrains equivalent of upstream Pyro workspace tasks.
 *
 * <p>Only real .ppj files physically contained by the IDE project are returned. Filesystem links
 * are never followed and the temporary validated compile snapshots are never exposed as tasks.</p>
 */
public final class PapyrusProjectTaskDiscovery {
    private static final String SNAPSHOT_PREFIX = ".papyrus-jetbrains-compile-";

    private PapyrusProjectTaskDiscovery() {
    }

    public static @NotNull List<Task> discover(@NotNull Path projectRoot) throws IOException {
        Path normalized = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("IDE project root must be a real directory: " + normalized);
        }
        Path root = normalized.toRealPath();

        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(path -> isProjectFile(root, path))
                    .map(path -> toTask(root, path))
                    .sorted(Comparator
                            .comparing((Task task) -> task.relativePath().toLowerCase(Locale.ROOT))
                            .thenComparing(Task::relativePath))
                    .toList();
        }
    }

    private static boolean isProjectFile(@NotNull Path root, @NotNull Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return false;
        }
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        if (name.startsWith(SNAPSHOT_PREFIX) || !name.toLowerCase(Locale.ROOT).endsWith(".ppj")) {
            return false;
        }
        try {
            return path.toRealPath().startsWith(root);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static @NotNull Task toTask(@NotNull Path root, @NotNull Path projectFile) {
        Path real;
        try {
            real = projectFile.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Papyrus project disappeared during task discovery: " + projectFile, exception);
        }
        String relative = root.relativize(real).toString().replace('\\', '/');
        return new Task(real, relative, "Compile Project (" + relative + ")");
    }

    public record Task(@NotNull Path projectFile, @NotNull String relativePath, @NotNull String label) {
        @Override
        public @NotNull String toString() {
            return label;
        }
    }
}
