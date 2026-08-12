package dev.papyrus.jetbrains.runtime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

public final class PapyrusHostRuntimeStager {
    private static final String REQUIRED_COMPILER_DEPENDENCY = "antlr.runtime.dll";

    private PapyrusHostRuntimeStager() {
    }

    public static StagedHostRuntime stage(
            Path sourceDirectory,
            Path hostExecutable,
            Path compilerAssemblyPath,
            Path stagingRoot
    ) {
        try {
            Path sourceReal = sourceDirectory.toRealPath();
            Path hostReal = hostExecutable.toRealPath();
            if (!hostReal.startsWith(sourceReal) || !Files.isRegularFile(hostReal, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Papyrus host executable is outside its runtime directory: " + hostReal);
            }

            Path dependency = compilerAssemblyPath.resolve(REQUIRED_COMPILER_DEPENDENCY).normalize();
            if (!Files.isRegularFile(dependency, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Papyrus Compiler dependency is missing: " + dependency);
            }

            Path root = stagingRoot.toAbsolutePath().normalize();
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) {
                throw new IllegalStateException("Papyrus host staging root must not be a symbolic link: " + root);
            }
            Path rootReal = root.toRealPath();

            String fingerprint = fingerprint(sourceReal, dependency);
            Path runtimeDirectory = rootReal.resolve(fingerprint).normalize();
            requireDirectChild(rootReal, runtimeDirectory);
            Path relativeHost = sourceReal.relativize(hostReal);
            Path stagedHost = runtimeDirectory.resolve(relativeHost).normalize();
            Path stagedDependency = runtimeDirectory.resolve(REQUIRED_COMPILER_DEPENDENCY).normalize();

            if (Files.isRegularFile(stagedHost, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(stagedDependency, LinkOption.NOFOLLOW_LINKS)) {
                return new StagedHostRuntime(stagedHost, runtimeDirectory);
            }

            Path temporary = rootReal.resolve("." + fingerprint + "." + UUID.randomUUID()).normalize();
            requireDirectChild(rootReal, temporary);
            Files.createDirectory(temporary);
            boolean committed = false;
            try {
                copyRuntimeTree(sourceReal, temporary);
                Files.copy(dependency, temporary.resolve(REQUIRED_COMPILER_DEPENDENCY));
                committed = commitRuntimeDirectory(temporary, runtimeDirectory);
            } finally {
                if (!committed && Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                    deleteOwnedTree(temporary, rootReal);
                }
            }

            if (!Files.isRegularFile(stagedHost, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(stagedDependency, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Papyrus host runtime staging did not produce a complete runtime: " + runtimeDirectory);
            }
            return new StagedHostRuntime(stagedHost, runtimeDirectory);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to stage Papyrus language-server runtime", exception);
        }
    }

    private static boolean commitRuntimeDirectory(Path temporary, Path runtimeDirectory) throws IOException {
        try {
            Files.move(temporary, runtimeDirectory, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (AtomicMoveNotSupportedException ignored) {
            return moveRuntimeDirectoryIfAbsent(temporary, runtimeDirectory);
        } catch (FileAlreadyExistsException raceWonElsewhere) {
            // Another project/process completed the same immutable fingerprint first.
            return false;
        }
    }

    private static boolean moveRuntimeDirectoryIfAbsent(Path temporary, Path runtimeDirectory) throws IOException {
        try {
            Files.move(temporary, runtimeDirectory);
            return true;
        } catch (FileAlreadyExistsException raceWonElsewhere) {
            // Another project/process completed the same immutable fingerprint first.
            return false;
        }
    }

    private static void copyRuntimeTree(Path sourceDirectory, Path targetDirectory) throws IOException {
        try (var stream = Files.walk(sourceDirectory)) {
            for (Path source : stream.toList()) {
                if (Files.isSymbolicLink(source)) {
                    throw new IOException("Refusing to stage a symbolic link from the Papyrus host runtime: " + source);
                }
                Path relative = sourceDirectory.relativize(source);
                Path target = targetDirectory.resolve(relative).normalize();
                if (!target.startsWith(targetDirectory)) {
                    throw new IOException("Papyrus host staging path escaped its owned directory: " + target);
                }
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(source, target);
                }
            }
        }
    }

    private static String fingerprint(Path sourceDirectory, Path compilerDependency) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }

        try (var stream = Files.walk(sourceDirectory)) {
            List<Path> files = stream
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> sourceDirectory.relativize(path).toString()))
                    .toList();
            for (Path file : files) {
                updateFingerprint(digest, sourceDirectory.relativize(file).toString(), file);
            }
        }
        updateFingerprint(digest, REQUIRED_COMPILER_DEPENDENCY, compilerDependency);
        return HexFormat.of().formatHex(digest.digest()).substring(0, 24);
    }

    private static void updateFingerprint(MessageDigest digest, String label, Path file) throws IOException {
        digest.update(label.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        digest.update((byte) 0);
    }

    private static void deleteOwnedTree(Path directory, Path root) throws IOException {
        requireDirectChild(root, directory);
        try (var stream = Files.walk(directory)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void requireDirectChild(Path root, Path candidate) {
        Path parent = candidate.getParent();
        if (parent == null || !parent.equals(root)) {
            throw new IllegalStateException("Refusing Papyrus runtime staging outside the owned root: " + candidate);
        }
    }

    public record StagedHostRuntime(Path executable, Path workingDirectory) {
    }
}
