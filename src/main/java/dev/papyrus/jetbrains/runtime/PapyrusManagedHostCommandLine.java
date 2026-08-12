package dev.papyrus.jetbrains.runtime;

import com.intellij.execution.Platform;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.PathUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Papyrus host command line that starts the real Windows host only after a guardian process has
 * entered a kill-on-close Job Object.
 */
public final class PapyrusManagedHostCommandLine extends GeneralCommandLine {
    private static final Logger LOG = Logger.getInstance(PapyrusManagedHostCommandLine.class);
    private final Path gatePath = PapyrusHostGuardianMain.newGatePath();

    public PapyrusManagedHostCommandLine(@NotNull String executable) {
        super(executable);
    }

    @Override
    protected @NotNull List<String> prepareCommandLine(
            @NotNull String command,
            @NotNull List<String> parameters,
            @NotNull Platform platform
    ) {
        if (!SystemInfo.isWindows) {
            return super.prepareCommandLine(command, parameters, platform);
        }

        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe")
                .toAbsolutePath()
                .normalize();
        if (!Files.isRegularFile(javaExecutable)) {
            throw new IllegalStateException("Missing IDE Java executable for Papyrus host guardian: " + javaExecutable);
        }

        String guardianClassPath = PathUtil.getJarPathForClass(PapyrusHostGuardianMain.class);
        List<String> hostCommand = new java.util.ArrayList<>(parameters.size() + 1);
        hostCommand.add(command);
        hostCommand.addAll(parameters);
        List<String> guardianCommand = PapyrusHostGuardianMain.javaCommand(
                javaExecutable,
                guardianClassPath,
                ProcessHandle.current().pid(),
                gatePath,
                hostCommand
        );
        return super.prepareCommandLine(
                guardianCommand.getFirst(),
                guardianCommand.subList(1, guardianCommand.size()),
                platform
        );
    }

    @Override
    protected @NotNull Process createProcess(@NotNull ProcessBuilder processBuilder) throws IOException {
        if (!SystemInfo.isWindows) {
            return super.createProcess(processBuilder);
        }

        PapyrusHostGuardianMain.removeGate(gatePath);
        PapyrusHostGuardianMain.sanitizeJavaLauncherEnvironment(processBuilder.environment());
        WindowsKillOnCloseJob job = WindowsKillOnCloseJob.create();
        Process guardian = null;
        try {
            guardian = super.createProcess(processBuilder);
            job.assign(guardian.pid());
            jobCloseOnExit(guardian, job, gatePath);
            PapyrusHostGuardianMain.signal(gatePath);
            return guardian;
        } catch (IOException | RuntimeException | Error failure) {
            if (guardian != null) {
                destroyProcessTree(guardian, failure);
            }
            closeJobAfterFailure(job, failure);
            PapyrusHostGuardianMain.removeGate(gatePath);
            if (failure instanceof IOException ioException) {
                throw ioException;
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw (Error) failure;
        }
    }

    private static void jobCloseOnExit(
            @NotNull Process guardian,
            @NotNull WindowsKillOnCloseJob job,
            @NotNull Path gatePath
    ) {
        guardian.onExit().whenComplete((ignored, failure) -> {
            PapyrusHostGuardianMain.removeGate(gatePath);
            try {
                job.close();
            } catch (RuntimeException | Error cleanupFailure) {
                LOG.warn("Failed to close the Papyrus language-host Job Object after guardian exit", cleanupFailure);
            }
        });
    }

    private static void closeJobAfterFailure(
            @NotNull WindowsKillOnCloseJob job,
            @NotNull Throwable originalFailure
    ) {
        try {
            job.close();
        } catch (RuntimeException | Error cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
            LOG.debug("Failed to close the Papyrus language-host Job Object during startup cleanup", cleanupFailure);
        }
    }

    private static void destroyProcessTree(
            @NotNull Process process,
            @NotNull Throwable originalFailure
    ) {
        process.descendants().forEach(child -> {
            try {
                child.destroyForcibly();
            } catch (RuntimeException cleanupFailure) {
                originalFailure.addSuppressed(cleanupFailure);
                LOG.debug("Failed to destroy a Papyrus guardian child during startup cleanup", cleanupFailure);
            }
        });
        try {
            process.destroyForcibly();
        } catch (RuntimeException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
            LOG.debug("Failed to destroy the Papyrus guardian during startup cleanup", cleanupFailure);
        }
    }
}
