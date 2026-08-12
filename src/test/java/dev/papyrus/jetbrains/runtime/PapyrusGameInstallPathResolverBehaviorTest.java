package dev.papyrus.jetbrains.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PapyrusGameInstallPathResolverBehaviorTest {

    @TempDir
    Path temp;

    @Test
    void configuredExistingPathWinsWithoutReadingRegistry() throws Exception {
        Path configured = Files.createDirectory(temp.resolve("configured"));
        AtomicInteger registryReads = new AtomicInteger();

        Path resolved = PapyrusGameInstallPathResolver.resolveSkyrimSpecialEdition(
                configured.toString(),
                (key, valueName) -> {
                    registryReads.incrementAndGet();
                    return temp.resolve("registry").toString();
                }
        ).orElseThrow();

        assertEquals(configured.toAbsolutePath().normalize(), resolved);
        assertEquals(0, registryReads.get());
    }

    @Test
    void missingConfiguredPathFallsBackToValidBethesdaRegistryPath() throws Exception {
        Path registryGame = Files.createDirectory(temp.resolve("registry-game"));
        AtomicReference<String> requestedKey = new AtomicReference<>();
        AtomicReference<String> requestedValue = new AtomicReference<>();

        Path resolved = PapyrusGameInstallPathResolver.resolveSkyrimSpecialEdition(
                temp.resolve("missing-configured").toString(),
                (key, valueName) -> {
                    requestedKey.set(key);
                    requestedValue.set(valueName);
                    return "\"" + registryGame.resolve(".") + "\"";
                }
        ).orElseThrow();

        assertEquals(registryGame.toAbsolutePath().normalize(), resolved);
        assertTrue(requestedKey.get().endsWith("Bethesda Softworks\\Skyrim Special Edition"));
        assertEquals("installed path", requestedValue.get());
        assertEquals(
                "SOFTWARE\\WOW6432Node\\Bethesda Softworks\\Skyrim Special Edition",
                PapyrusGameInstallPathResolver.registrySubKeyForArchitecture("amd64")
        );
        assertEquals(
                "SOFTWARE\\Bethesda Softworks\\Skyrim Special Edition",
                PapyrusGameInstallPathResolver.registrySubKeyForArchitecture("x86")
        );
    }

    @Test
    void missingOrInvalidRegistryEntryReturnsNotFound() {
        assertTrue(PapyrusGameInstallPathResolver.resolveSkyrimSpecialEdition(
                temp.resolve("missing-configured").toString(),
                (key, valueName) -> null
        ).isEmpty());

        assertTrue(PapyrusGameInstallPathResolver.resolveSkyrimSpecialEdition(
                "\u0000invalid",
                (key, valueName) -> temp.resolve("missing-registry").toString()
        ).isEmpty());
    }

    @Test
    void registryBoundaryExposesReadOnlyContractOnly() {
        String[] interfaceMethods = Arrays.stream(PapyrusRegistryReader.class.getDeclaredMethods())
                .map(Method::getName)
                .sorted()
                .toArray(String[]::new);
        String[] implementationMethods = Arrays.stream(WindowsPapyrusRegistryReader.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(Method::getName)
                .filter(name -> !name.equals("isWindows"))
                .sorted()
                .toArray(String[]::new);

        assertEquals(List.of("readLocalMachineString"), Arrays.asList(interfaceMethods));
        assertEquals(List.of("readLocalMachineString"), Arrays.asList(implementationMethods));
    }
}
