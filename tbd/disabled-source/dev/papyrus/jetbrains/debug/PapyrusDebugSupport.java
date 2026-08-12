package dev.papyrus.jetbrains.debug;

import dev.papyrus.jetbrains.runtime.PapyrusRuntimePaths;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PapyrusDebugSupport {

    public enum InstallState {
        INSTALLED,
        NOT_INSTALLED,
        OUTDATED
    }

    private PapyrusDebugSupport() {
    }

    public static @NotNull InstallState getInstallState() throws IOException {
        Path bundled = PapyrusRuntimePaths.getBundledDebugPlugin();
        Path installed = PapyrusRuntimePaths.getDebugPluginInstallPath();
        if (!Files.isRegularFile(bundled)) {
            throw new IOException("Bundled Papyrus debug server was not found: " + bundled);
        }
        if (!Files.isRegularFile(installed)) {
            return InstallState.NOT_INSTALLED;
        }
        return Files.mismatch(bundled, installed) == -1L ? InstallState.INSTALLED : InstallState.OUTDATED;
    }

    public static boolean isSkyrimRunning() {
        return ProcessHandle.allProcesses().anyMatch(handle -> handle.info().command()
                .map(PapyrusDebugSupport::isSkyrimExecutable)
                .orElse(false));
    }

    private static boolean isSkyrimExecutable(String command) {
        try {
            Path fileName = Path.of(command).getFileName();
            return fileName != null && "SkyrimSE.exe".equalsIgnoreCase(fileName.toString());
        } catch (RuntimeException ignored) {
            return command.toLowerCase(java.util.Locale.ROOT).endsWith("skyrimse.exe");
        }
    }
}
