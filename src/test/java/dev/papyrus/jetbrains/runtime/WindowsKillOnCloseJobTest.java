package dev.papyrus.jetbrains.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class WindowsKillOnCloseJobTest {

    @Test
    @SuppressWarnings({"BusyWait", "UseOptimizedEelFunctions"}) // Local Windows process/VFS contract uses bounded polling and local java.nio.file APIs.
    void closingJobTerminatesGuardianAndChildProcess(@TempDir Path tempDir) throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("windows"));

        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        assertTrue(Files.isRegularFile(java), "Missing test JDK java.exe: " + java);

        String isolatedClassPath = guardianTestClassPath();
        Path gate = PapyrusHostGuardianMain.newGatePath();
        Path childPidFile = tempDir.resolve("child.pid");
        List<String> hostCommand = List.of(
                java.toString(),
                "-cp",
                isolatedClassPath,
                WindowsJobChildMain.class.getName(),
                childPidFile.toString()
        );
        List<String> guardianCommand = PapyrusHostGuardianMain.javaCommand(
                java,
                isolatedClassPath,
                ProcessHandle.current().pid(),
                gate,
                hostCommand
        );

        PapyrusHostGuardianMain.removeGate(gate);
        ProcessBuilder guardianBuilder = new ProcessBuilder(guardianCommand);
        PapyrusHostGuardianMain.sanitizeJavaLauncherEnvironment(guardianBuilder.environment());
        Process guardian = guardianBuilder.start();
        ProcessHandle childHandle = null;
        try {
            Thread.sleep(200);
            assertFalse(Files.exists(childPidFile), "Guardian started its child before the Job assignment gate was signaled");

            try (WindowsKillOnCloseJob job = WindowsKillOnCloseJob.create()) {
                job.assign(guardian.pid());
                PapyrusHostGuardianMain.signal(gate);

                long childPid = waitForChildPid(childPidFile, Duration.ofSeconds(5));
                assertTrue(childPid > 0, "Guardian did not publish its child PID after Job assignment");
                childHandle = ProcessHandle.of(childPid).orElseThrow();
                assertTrue(guardian.isAlive(), "Guardian exited before kill-on-close could be verified");
                assertTrue(childHandle.isAlive(), "Guardian child exited before kill-on-close could be verified");
            }

            assertTrue(
                    guardian.waitFor(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS),
                    "Guardian survived closing a kill-on-close Windows Job Object"
            );
            assertFalse(guardian.isAlive(), "Guardian is still alive after the Job Object was closed");
            assertTrue(waitForExit(childHandle, Duration.ofSeconds(5)), "Guardian child survived closing the Job Object");
            assertFalse(childHandle.isAlive(), "Guardian child is still alive after the Job Object was closed");
        } finally {
            PapyrusHostGuardianMain.removeGate(gate);
            if (childHandle != null && childHandle.isAlive()) {
                childHandle.destroyForcibly();
            }
            if (guardian.isAlive()) {
                guardian.destroyForcibly();
            }
        }
    }

    private static String guardianTestClassPath() throws Exception {
        StringBuilder classPath = new StringBuilder();
        for (Class<?> type : List.of(PapyrusHostGuardianMain.class, WindowsJobChildMain.class)) {
            Path location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
            if (!classPath.isEmpty()) {
                classPath.append(File.pathSeparatorChar);
            }
            classPath.append(location);
        }
        return classPath.toString();
    }


    @SuppressWarnings("BusyWait") // The child creates and writes the PID file in separate filesystem-visible steps.
    private static long waitForChildPid(Path pidFile, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(pidFile)) {
                String text = Files.readString(pidFile).trim();
                if (!text.isEmpty()) {
                    try {
                        return Long.parseLong(text);
                    } catch (NumberFormatException ignored) {
                        // Keep polling until the child has finished publishing the PID.
                    }
                }
            }
            Thread.sleep(20);
        }
        return -1L;
    }

    @SuppressWarnings("BusyWait") // Bounded test polling avoids adding a synchronization dependency to the child process.
    private static boolean waitForExit(ProcessHandle process, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return !process.isAlive();
    }

}
