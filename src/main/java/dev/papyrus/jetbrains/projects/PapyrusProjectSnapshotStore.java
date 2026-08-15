package dev.papyrus.jetbrains.projects;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import dev.papyrus.jetbrains.run.PapyrusProjectTaskDiscovery;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Owns immutable, validated PPJ workspaces consumed by papyrus-lang.
 *
 * <p>The upstream server reloads PPJs from disk after receiving didSave. Keeping the server's
 * workspace on a private immutable snapshot removes the validation/read TOCTOU window: edits to the
 * user's PPJ cannot change what the server reads for the already-approved generation.</p>
 */
final class PapyrusProjectSnapshotStore {

    private static final Logger LOG = Logger.getInstance(PapyrusProjectSnapshotStore.class);
    private static final String ACTIVE_MARKER = "active.txt";
    private static final String GENERATION_PREFIX = "generation-";

    private final Project project;
    private final Object lock = new Object();
    private Snapshot activeSnapshot;

    PapyrusProjectSnapshotStore(@NotNull Project project) {
        this.project = project;
    }

    record Snapshot(
            @NotNull Path workspaceRoot,
            @Nullable Path triggerProjectFile,
            int projectCount
    ) {
    }

    record Preparation(
            @Nullable Snapshot snapshot,
            @Nullable PapyrusProjectReloadValidator.ValidationResult failure,
            @NotNull String failureProject,
            boolean noProjectFiles
    ) {
        static @NotNull Preparation success(@NotNull Snapshot snapshot) {
            return new Preparation(snapshot, null, "", false);
        }

        static @NotNull Preparation failure(
                @Nullable Snapshot fallback,
                @NotNull PapyrusProjectReloadValidator.ValidationResult failure,
                @NotNull String failureProject
        ) {
            return new Preparation(fallback, failure, failureProject, false);
        }

        static @NotNull Preparation noProjects(@NotNull Snapshot empty) {
            return new Preparation(empty, null, "", true);
        }

    }

    @NotNull Preparation prepareCurrentProject() {
        synchronized (lock) {
            Snapshot previous = loadActiveSnapshot();
            Path base;
            try {
                base = projectBasePath();
            } catch (RuntimeException exception) {
                PapyrusProjectReloadValidator.ValidationResult failure =
                        PapyrusProjectReloadValidator.ValidationResult.failure(
                                Path.of("."),
                                "IDE project has no local base directory",
                                readableMessage(exception)
                        );
                return Preparation.failure(previous, failure, project.getName());
            }
            List<PapyrusProjectTaskDiscovery.Task> tasks;
            try {
                tasks = PapyrusProjectTaskDiscovery.discover(base);
            } catch (IOException | RuntimeException exception) {
                PapyrusProjectReloadValidator.ValidationResult failure =
                        PapyrusProjectReloadValidator.ValidationResult.failure(
                                base,
                                "project files cannot be discovered",
                                readableMessage(exception)
                        );
                return Preparation.failure(previous, failure, base.toString());
            }

            if (tasks.isEmpty()) {
                return Preparation.noProjects(ensureEmptyWorkspace());
            }

            PapyrusProjectReloadValidator.ValidationResult[] validations =
                    new PapyrusProjectReloadValidator.ValidationResult[tasks.size()];
            for (int index = 0; index < tasks.size(); index++) {
                PapyrusProjectTaskDiscovery.Task task = tasks.get(index);
                byte[] currentBytes;
                try {
                    currentBytes = readCurrentProjectBytes(task.projectFile());
                } catch (IOException exception) {
                    PapyrusProjectReloadValidator.ValidationResult failure =
                            PapyrusProjectReloadValidator.ValidationResult.failure(
                                    task.projectFile(),
                                    "project file cannot be read",
                                    readableMessage(exception)
                            );
                    return Preparation.failure(previous, failure, task.relativePath());
                }

                PapyrusProjectReloadValidator.ValidationResult validation =
                        PapyrusProjectReloadValidator.validate(task.projectFile(), currentBytes);
                if (!validation.valid()) {
                    return Preparation.failure(previous, validation, task.relativePath());
                }
                validations[index] = validation;
            }

            Path storeRoot = storeRoot();
            String generationName = GENERATION_PREFIX
                    + Instant.now().toEpochMilli()
                    + "-"
                    + UUID.randomUUID();
            Path temporary = storeRoot.resolve("." + generationName + ".tmp");
            Path generation = storeRoot.resolve(generationName);
            try {
                Files.createDirectories(storeRoot);
                deleteRecursively(temporary);
                Files.createDirectories(temporary);

                Path trigger = null;
                for (int index = 0; index < tasks.size(); index++) {
                    PapyrusProjectTaskDiscovery.Task task = tasks.get(index);
                    Path relative = Path.of(task.relativePath()).normalize();
                    if (relative.isAbsolute() || relative.startsWith("..")) {
                        throw new IOException("Unsafe PPJ relative path: " + task.relativePath());
                    }
                    Path target = temporary.resolve(relative).normalize();
                    if (!target.startsWith(temporary)) {
                        throw new IOException("PPJ snapshot escaped its workspace: " + task.relativePath());
                    }
                    Files.createDirectories(target.getParent());
                    Files.write(target, validations[index].snapshotBytes());
                    if (trigger == null) {
                        trigger = generation.resolve(relative);
                    }
                }

                moveDirectory(temporary, generation);
                String previousGeneration = activeGenerationName();
                writeActiveMarker(generationName);
                Snapshot published = new Snapshot(generation, trigger, tasks.size());
                activeSnapshot = published;
                cleanupOldGenerations(generationName, previousGeneration);
                return Preparation.success(published);
            } catch (IOException exception) {
                LOG.warn("Failed to publish validated PPJ snapshot workspace", exception);
                try {
                    deleteRecursively(temporary);
                } catch (IOException cleanupException) {
                    LOG.debug("Failed to clean temporary PPJ snapshot workspace", cleanupException);
                }
                PapyrusProjectReloadValidator.ValidationResult failure =
                        PapyrusProjectReloadValidator.ValidationResult.failure(
                                base,
                                "validated snapshot cannot be published",
                                readableMessage(exception)
                        );
                return Preparation.failure(previous, failure, base.toString());
            }
        }
    }

    /**
     * Reads the exact PPJ state visible to the user. If IntelliJ has an editor document for the
     * project file, its current in-memory text wins even when Ctrl+S has not been pressed. Otherwise
     * the file is read from disk. The returned bytes are then the only bytes validation and snapshot
     * publication use for this refresh generation.
     */
    private @NotNull byte[] readCurrentProjectBytes(@NotNull Path projectFile) throws IOException {
        Path absolute = projectFile.toAbsolutePath().normalize();
        byte[] editorBytes = ReadAction.computeBlocking(() -> {
            VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByNioFile(absolute);
            if (virtualFile == null || !virtualFile.isValid()) {
                return null;
            }
            FileDocumentManager documentManager = FileDocumentManager.getInstance();
            Document document = documentManager.getCachedDocument(virtualFile);
            return document != null && documentManager.isDocumentUnsaved(document)
                    ? document.getText().getBytes(StandardCharsets.UTF_8)
                    : null;
        });
        return editorBytes != null ? editorBytes : Files.readAllBytes(absolute);
    }

    @NotNull Snapshot activeOrEmpty() {
        synchronized (lock) {
            Snapshot active = loadActiveSnapshot();
            return active != null ? active : ensureEmptyWorkspace();
        }
    }

    @Nullable Snapshot active() {
        synchronized (lock) {
            return loadActiveSnapshot();
        }
    }

    private @NotNull Path projectBasePath() {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            throw new IllegalStateException("The IDE project has no local base directory.");
        }
        return Path.of(basePath).toAbsolutePath().normalize();
    }

    private @NotNull Path storeRoot() {
        String identity;
        try {
            identity = projectBasePath().toString();
        } catch (RuntimeException exception) {
            identity = project.getName();
        }
        return PathManager.getSystemDir()
                .resolve("papyrus")
                .resolve("validated-project-workspaces")
                .resolve(sha256(identity).substring(0, 24));
    }

    private @Nullable Snapshot loadActiveSnapshot() {
        if (activeSnapshot != null && Files.isDirectory(activeSnapshot.workspaceRoot())) {
            return activeSnapshot;
        }

        Path root = storeRoot();
        String generationName = activeGenerationName();
        if (generationName == null) {
            return null;
        }
        Path generation = root.resolve(generationName).normalize();
        if (!generation.startsWith(root) || !Files.isDirectory(generation)) {
            return null;
        }

        try (Stream<Path> files = Files.walk(generation)) {
            List<Path> projects = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".ppj"))
                    .sorted(Comparator.comparing(Path::toString, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            if (projects.isEmpty()) {
                return null;
            }
            activeSnapshot = new Snapshot(generation, projects.getFirst(), projects.size());
            return activeSnapshot;
        } catch (IOException exception) {
            LOG.debug("Failed to read active PPJ snapshot workspace", exception);
            return null;
        }
    }

    private @NotNull Snapshot ensureEmptyWorkspace() {
        Path empty = storeRoot().resolve("empty");
        try {
            Files.createDirectories(empty);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create safe empty Papyrus workspace: " + empty, exception);
        }
        return new Snapshot(empty, null, 0);
    }

    private @Nullable String activeGenerationName() {
        Path marker = storeRoot().resolve(ACTIVE_MARKER);
        if (!Files.isRegularFile(marker)) {
            return null;
        }
        try {
            String name = Files.readString(marker, StandardCharsets.UTF_8).trim();
            if (name.startsWith(GENERATION_PREFIX) && !name.contains("/") && !name.contains("\\")) {
                return name;
            }
        } catch (IOException exception) {
            LOG.debug("Failed to read active PPJ snapshot marker", exception);
        }
        return null;
    }

    private void writeActiveMarker(@NotNull String generationName) throws IOException {
        Path root = storeRoot();
        Path marker = root.resolve(ACTIVE_MARKER);
        Path temporary = root.resolve("." + ACTIVE_MARKER + ".tmp");
        Files.writeString(temporary, generationName + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(
                    temporary,
                    marker,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveDirectory(@NotNull Path source, @NotNull Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void cleanupOldGenerations(@NotNull String active, @Nullable String previous) {
        Path root = storeRoot();
        try (Stream<Path> children = Files.list(root)) {
            List<Path> obsolete = children
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(GENERATION_PREFIX))
                    .filter(path -> !path.getFileName().toString().equals(active))
                    .filter(path -> previous == null || !path.getFileName().toString().equals(previous))
                    .toList();
            for (Path path : obsolete) {
                try {
                    deleteRecursively(path);
                } catch (IOException exception) {
                    LOG.debug("Failed to remove obsolete PPJ snapshot generation: " + path, exception);
                }
            }
        } catch (IOException exception) {
            LOG.debug("Failed to enumerate obsolete PPJ snapshot generations", exception);
        }
    }

    private static void deleteRecursively(@NotNull Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static @NotNull String sha256(@NotNull String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static @NotNull String readableMessage(@NotNull Exception exception) {
        String message = exception.getMessage();
        return message != null && !message.isBlank() ? message : exception.getClass().getSimpleName();
    }
}
