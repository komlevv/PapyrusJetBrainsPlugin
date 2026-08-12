package dev.papyrus.jetbrains.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** JDK-only child process used by the Windows Job Object lifecycle contract. */
public final class WindowsJobChildMain {
    private WindowsJobChildMain() {
    }

    static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected PID output path");
        }
        Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
        Thread.sleep(Duration.ofMinutes(2).toMillis());
    }
}
