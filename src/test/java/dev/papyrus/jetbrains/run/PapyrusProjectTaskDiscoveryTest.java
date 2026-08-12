package dev.papyrus.jetbrains.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class PapyrusProjectTaskDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversProjectLocalPpjFilesWithUpstreamStyleLabelsInStableOrder() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(root.resolve("zeta.ppj"), "<PapyrusProject />");
        Files.createDirectories(root.resolve("Nested"));
        Files.writeString(root.resolve("Nested/Alpha.ppj"), "<PapyrusProject />");
        Files.writeString(root.resolve("Nested/not-a-project.txt"), "ignored");

        List<PapyrusProjectTaskDiscovery.Task> tasks = PapyrusProjectTaskDiscovery.discover(root);

        assertEquals(List.of("Nested/Alpha.ppj", "zeta.ppj"), tasks.stream().map(PapyrusProjectTaskDiscovery.Task::relativePath).toList());
        assertEquals("Compile Project (Nested/Alpha.ppj)", tasks.getFirst().label());
        assertEquals(root.resolve("Nested/Alpha.ppj").toRealPath(), tasks.getFirst().projectFile());
    }

    @Test
    void ignoresValidatedCompileSnapshotsAndFilesystemLinks() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("project"));
        Files.writeString(root.resolve("real.ppj"), "<PapyrusProject />");
        Files.writeString(root.resolve(".papyrus-jetbrains-compile-123.ppj"), "<PapyrusProject />");
        Path outside = Files.writeString(tempDir.resolve("outside.ppj"), "<PapyrusProject />");
        try {
            Files.createSymbolicLink(root.resolve("outside-link.ppj"), outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException ignored) {
            // Symlink creation is not guaranteed on every Windows test host; snapshot exclusion is still asserted.
        }

        List<PapyrusProjectTaskDiscovery.Task> tasks = PapyrusProjectTaskDiscovery.discover(root);

        assertEquals(List.of("real.ppj"), tasks.stream().map(PapyrusProjectTaskDiscovery.Task::relativePath).toList());
        assertFalse(tasks.stream().anyMatch(task -> task.relativePath().startsWith(".papyrus-jetbrains-compile-")));
    }
}
