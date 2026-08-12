package dev.papyrus.jetbrains.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("UseOptimizedEelFunctions") // Runtime-stager tests inspect local temporary files only.
final class PapyrusHostRuntimeStagerBehaviorTest {

    @TempDir
    Path temp;

    @Test
    void stagesAnImmutableRuntimeAndReusesTheSameFingerprint() throws Exception {
        Path hostDir = Files.createDirectories(temp.resolve("host/bin"));
        Path host = Files.writeString(hostDir.resolve("DarkId.Papyrus.Host.Skyrim.exe"), "host-v1", StandardCharsets.UTF_8);
        Files.writeString(hostDir.resolve("dependency.dll"), "runtime", StandardCharsets.UTF_8);
        Path compiler = Files.createDirectory(temp.resolve("compiler"));
        Files.writeString(compiler.resolve("antlr.runtime.dll"), "antlr-v1", StandardCharsets.UTF_8);
        Path staging = temp.resolve("staging");

        PapyrusHostRuntimeStager.StagedHostRuntime first = PapyrusHostRuntimeStager.stage(hostDir, host, compiler, staging);
        PapyrusHostRuntimeStager.StagedHostRuntime second = PapyrusHostRuntimeStager.stage(hostDir, host, compiler, staging);

        assertEquals(first.workingDirectory(), second.workingDirectory());
        assertTrue(Files.isRegularFile(first.executable()));
        assertEquals("antlr-v1", Files.readString(first.workingDirectory().resolve("antlr.runtime.dll")));

        Files.writeString(compiler.resolve("antlr.runtime.dll"), "antlr-v2", StandardCharsets.UTF_8);
        PapyrusHostRuntimeStager.StagedHostRuntime changed = PapyrusHostRuntimeStager.stage(hostDir, host, compiler, staging);
        assertNotEquals(first.workingDirectory(), changed.workingDirectory());
    }

    @Test
    void refusesHostExecutablesOutsideTheDeclaredRuntimeTree() throws Exception {
        Path hostDir = Files.createDirectory(temp.resolve("runtime"));
        Path outside = Files.writeString(temp.resolve("outside.exe"), "host", StandardCharsets.UTF_8);
        Path compiler = Files.createDirectory(temp.resolve("compiler"));
        Files.writeString(compiler.resolve("antlr.runtime.dll"), "antlr", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> PapyrusHostRuntimeStager.stage(
                hostDir,
                outside,
                compiler,
                temp.resolve("staging")
        ));
    }
}
