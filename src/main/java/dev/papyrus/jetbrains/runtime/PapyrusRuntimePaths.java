package dev.papyrus.jetbrains.runtime;

import dev.papyrus.jetbrains.config.PapyrusSettings;

import java.nio.file.Files;
import java.nio.file.Path;

public final class PapyrusRuntimePaths {

    private PapyrusRuntimePaths() {
    }

    public static Path getVsixRoot() {
        return PapyrusBundledVsix.getExtensionRoot();
    }

    public static Path getPyroExecutable() {
        return requireFile(getVsixRoot().resolve(Path.of("pyro", "pyro.exe")), "Pyro executable");
    }

    public static Path getResourcesDirectory() {
        return requireDirectory(getVsixRoot().resolve("resources"), "Papyrus VSIX resources directory");
    }

    public static Path getRemotesDirectory() {
        return requireDirectory(getVsixRoot().resolve(Path.of("pyro", "remote")), "Papyrus remotes directory");
    }

    public static Path getDebugAdapterExecutable() {
        return requireFile(
                getVsixRoot().resolve(Path.of(
                        "debug-bin", "Debug", "net472", "DarkId.Papyrus.DebugAdapterProxy.Skyrim",
                        "DarkId.Papyrus.DebugAdapterProxy.Skyrim.exe"
                )),
                "Papyrus Skyrim debug adapter"
        );
    }

    public static Path getBundledDebugPlugin() {
        return requireFile(
                getVsixRoot().resolve(Path.of("debug-plugin", "DarkId.Papyrus.DebugServer.Skyrim.dll")),
                "Papyrus Skyrim debug server plugin"
        );
    }

    /**
     * Returns the debugger plugin destination inside an explicitly configured mod directory.
     *
     * <p>Direct writes into the Skyrim installation are intentionally not supported. This keeps the
     * plugin from modifying the game installation itself; users who want debugger support should
     * point this setting at an MO2/Vortex-style mod directory they control.
     */
    public static Path getDebugModDirectory() {
        PapyrusSettings.SettingsState state = PapyrusSettings.getInstance().getState();
        if (state.debugModDirectoryPath == null || state.debugModDirectoryPath.isBlank()) {
            throw new IllegalStateException(
                    "Debugger mod directory is not configured. Direct installation into the Skyrim directory is disabled for safety."
            );
        }

        Path modDirectory = Path.of(state.debugModDirectoryPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(modDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Debugger mod directory does not exist or is a filesystem link: " + modDirectory);
        }
        if (Files.isSymbolicLink(modDirectory)) {
            throw new IllegalStateException("Debugger mod directory must be a real directory, not a symbolic link: " + modDirectory);
        }

        if (state.creationKitInstallPath != null && !state.creationKitInstallPath.isBlank()) {
            Path gameDirectory = Path.of(state.creationKitInstallPath).toAbsolutePath().normalize();
            if (Files.isDirectory(gameDirectory)) {
                try {
                    if (modDirectory.toRealPath().startsWith(gameDirectory.toRealPath())) {
                        throw new IllegalStateException(
                                "Debugger mod directory must not be inside the Skyrim installation: " + modDirectory
                        );
                    }
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException("Failed to validate debugger mod directory: " + modDirectory, exception);
                }
            }
        }
        return modDirectory;
    }

    public static Path getDebugPluginInstallPath() {
        return getDebugModDirectory().resolve(Path.of(
                "Papyrus Debug Extension", "SKSE", "Plugins", "DarkId.Papyrus.DebugServer.Skyrim.dll"
        )).normalize();
    }

    private static Path requireFile(Path path, String label) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalStateException(label + " was not found: " + normalized);
        }
        return normalized;
    }

    private static Path requireDirectory(Path path, String label) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalStateException(label + " was not found: " + normalized);
        }
        return normalized;
    }
}
