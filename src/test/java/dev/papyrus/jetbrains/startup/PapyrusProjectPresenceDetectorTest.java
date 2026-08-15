package dev.papyrus.jetbrains.startup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PapyrusProjectPresenceDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsProjectLocalPpjOrPsc() throws Exception {
        Path ppjRoot = tempDir.resolve("ppj-project");
        Files.createDirectories(ppjRoot.resolve("config"));
        Files.writeString(ppjRoot.resolve("config/runtime.PPJ"), "<PapyrusProject />");
        assertTrue(PapyrusProjectPresenceDetector.containsPapyrusSignal(ppjRoot));

        Path pscRoot = tempDir.resolve("psc-project");
        Files.createDirectories(pscRoot.resolve("Source/Scripts"));
        Files.writeString(pscRoot.resolve("Source/Scripts/Probe.PsC"), "Scriptname Probe");
        assertTrue(PapyrusProjectPresenceDetector.containsPapyrusSignal(pscRoot));
    }

    @Test
    void ignoresIdeaAndGeneratedOrDependencyTrees() throws Exception {
        Path root = tempDir.resolve("ordinary-project");
        Files.createDirectories(root.resolve(".idea"));
        Files.createDirectories(root.resolve("build/generated"));
        Files.createDirectories(root.resolve("node_modules/package"));
        Files.createDirectories(root.resolve(".papyrus-jetbrains-compile-old"));
        Files.writeString(root.resolve(".idea/stale.ppj"), "stale");
        Files.writeString(root.resolve("build/generated/Generated.psc"), "generated");
        Files.writeString(root.resolve("node_modules/package/Dependency.psc"), "dependency");
        Files.writeString(root.resolve(".papyrus-jetbrains-compile-old/Snapshot.psc"), "snapshot");

        assertFalse(PapyrusProjectPresenceDetector.containsPapyrusSignal(root));
    }

    @Test
    void ordinaryProjectDoesNotMatch() throws Exception {
        Path root = tempDir.resolve("plain-project");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/main.cpp"), "int main() { return 0; }");
        Files.writeString(root.resolve("README.md"), "plain project");

        assertFalse(PapyrusProjectPresenceDetector.containsPapyrusSignal(root));
    }
}
