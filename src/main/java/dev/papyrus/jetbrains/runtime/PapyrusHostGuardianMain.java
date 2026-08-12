package dev.papyrus.jetbrains.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JDK-only gate process used to ensure the real Papyrus host is never started before its parent
 * has been placed in a Windows kill-on-close Job Object.
 *
 * <p>The guardian must not write to stdout because the real host inherits the guardian's standard
 * handles and stdout is the LSP transport. Startup errors are written to stderr only.</p>
 */
public final class PapyrusHostGuardianMain {
    private static final String PARENT_PID = "--parent-pid";
    private static final String GATE = "--gate";
    private static final String COMMAND_SEPARATOR = "--";
    private static final Duration GATE_TIMEOUT = Duration.ofSeconds(30);
    private static final long POLL_MILLIS = 20L;

    private PapyrusHostGuardianMain() {
    }

    public static Path newGatePath() {
        return Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("papyrus-host-" + UUID.randomUUID() + ".gate")
                .toAbsolutePath()
                .normalize();
    }

    public static List<String> javaCommand(
            Path javaExecutable,
            String classPath,
            long parentProcessId,
            Path gatePath,
            List<String> hostCommand
    ) {
        if (parentProcessId <= 0) {
            throw new IllegalArgumentException("Invalid guardian parent process id: " + parentProcessId);
        }
        if (hostCommand.isEmpty()) {
            throw new IllegalArgumentException("Papyrus host command is empty");
        }

        List<String> command = new ArrayList<>(hostCommand.size() + 13);
        command.add(javaExecutable.toString());
        command.add("-Xms4m");
        command.add("-Xmx24m");
        command.add("-XX:+UseSerialGC");
        command.add("-Djava.awt.headless=true");
        command.add("-cp");
        command.add(classPath);
        command.add(PapyrusHostGuardianMain.class.getName());
        command.add(PARENT_PID);
        command.add(Long.toString(parentProcessId));
        command.add(GATE);
        command.add(gatePath.toString());
        command.add(COMMAND_SEPARATOR);
        command.addAll(hostCommand);
        return command;
    }

    public static void sanitizeJavaLauncherEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(PapyrusHostGuardianMain::isJavaLauncherOptionVariable);
    }

    private static boolean isJavaLauncherOptionVariable(String name) {
        return "JAVA_TOOL_OPTIONS".equalsIgnoreCase(name)
                || "_JAVA_OPTIONS".equalsIgnoreCase(name)
                || "JDK_JAVA_OPTIONS".equalsIgnoreCase(name);
    }

    public static void signal(Path gatePath) throws IOException {
        Path parent = gatePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.createFile(gatePath);
    }

    public static void removeGate(Path gatePath) {
        try {
            Files.deleteIfExists(gatePath);
        } catch (IOException ignored) {
            // The gate contains no data and is safe to leave for later temp cleanup.
        }
    }

    static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) {
        try {
            Arguments parsed = parseArguments(args);
            if (!awaitGate(parsed.parentProcessId(), parsed.gatePath())) {
                return 72;
            }
            removeGate(parsed.gatePath());

            Process host = new ProcessBuilder(parsed.hostCommand())
                    .inheritIO()
                    .start();
            return host.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("Papyrus host guardian was interrupted before the host exited.");
            return 73;
        } catch (Exception failure) {
            System.err.println("Papyrus host guardian failed: " + failure.getMessage());
            return 74;
        }
    }

    @SuppressWarnings("BusyWait") // Bounded 20 ms startup polling also watches parent death; no blocking primitive covers both conditions.
    private static boolean awaitGate(long parentProcessId, Path gatePath) throws InterruptedException {
        long deadline = System.nanoTime() + GATE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(gatePath)) {
                return true;
            }
            if (!isAlive(parentProcessId)) {
                return false;
            }
            Thread.sleep(POLL_MILLIS);
        }
        return false;
    }

    private static boolean isAlive(long processId) {
        return ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false);
    }

    private static Arguments parseArguments(String[] args) {
        if (args.length < 6 || !PARENT_PID.equals(args[0]) || !GATE.equals(args[2])) {
            throw new IllegalArgumentException("Invalid Papyrus host guardian arguments");
        }
        long parentProcessId = Long.parseLong(args[1]);
        if (parentProcessId <= 0) {
            throw new IllegalArgumentException("Invalid guardian parent process id: " + parentProcessId);
        }
        Path gatePath = Path.of(args[3]).toAbsolutePath().normalize();
        if (!COMMAND_SEPARATOR.equals(args[4])) {
            throw new IllegalArgumentException("Missing Papyrus host command separator");
        }
        List<String> hostCommand = List.of(args).subList(5, args.length);
        if (hostCommand.isEmpty()) {
            throw new IllegalArgumentException("Papyrus host command is empty");
        }
        return new Arguments(parentProcessId, gatePath, List.copyOf(hostCommand));
    }

    private record Arguments(long parentProcessId, Path gatePath, List<String> hostCommand) {
    }
}
