package dev.papyrus.jetbrains.runtime;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class PapyrusGameInstallPathResolver {
    static final String SKYRIM_SE_REGISTRY_VALUE = "installed path";
    private static final String SKYRIM_SE_REGISTRY_BASE =
            "SOFTWARE\\Bethesda Softworks\\Skyrim Special Edition";
    private static final String SKYRIM_SE_REGISTRY_WOW6432 =
            "SOFTWARE\\WOW6432Node\\Bethesda Softworks\\Skyrim Special Edition";

    private PapyrusGameInstallPathResolver() {
    }

    public static @NotNull Optional<Path> resolveSkyrimSpecialEdition(@Nullable String configuredPath) {
        return resolveSkyrimSpecialEdition(configuredPath, WindowsPapyrusRegistryReader.INSTANCE);
    }

    static @NotNull Optional<Path> resolveSkyrimSpecialEdition(
            @Nullable String configuredPath,
            @NotNull PapyrusRegistryReader registryReader
    ) {
        Optional<Path> configured = existingDirectory(configuredPath);
        if (configured.isPresent()) {
            return configured;
        }

        String registryPath = registryReader.readLocalMachineString(
                registrySubKeyForArchitecture(System.getProperty("os.arch", "")),
                SKYRIM_SE_REGISTRY_VALUE
        );
        return existingDirectory(registryPath);
    }

    static @NotNull String registrySubKeyForArchitecture(@Nullable String architecture) {
        String normalized = architecture == null ? "" : architecture.toLowerCase();
        boolean x64 = normalized.equals("amd64") || normalized.equals("x86_64") || normalized.equals("x64");
        return x64 ? SKYRIM_SE_REGISTRY_WOW6432 : SKYRIM_SE_REGISTRY_BASE;
    }

    private static @NotNull Optional<Path> existingDirectory(@Nullable String rawPath) {
        String cleaned = cleanPath(rawPath);
        if (cleaned == null) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(cleaned).toAbsolutePath().normalize();
            return Files.isDirectory(path) ? Optional.of(path) : Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static @Nullable String cleanPath(@Nullable String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String value = rawPath.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1).trim();
        }
        return value.isEmpty() ? null : value;
    }
}
