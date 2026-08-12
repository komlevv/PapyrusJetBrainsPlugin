package dev.papyrus.jetbrains.run;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Project-bounded JetBrains console filter for the upstream Papyrus compiler problem format.
 */
public final class PapyrusCompilerFilter implements Filter, DumbAware {
    private final Project project;

    public PapyrusCompilerFilter(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @Nullable Result applyFilter(@NotNull String line, int entireLength) {
        PapyrusCompilerDiagnostic diagnostic = PapyrusCompilerDiagnostic.parse(line);
        if (diagnostic == null) {
            return null;
        }

        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return null;
        }
        Path root = realProjectRoot(Path.of(basePath));
        if (root == null) {
            return null;
        }
        Path target = resolveProjectPath(root, diagnostic.filePath());
        if (target == null) {
            return null;
        }

        int lineStart = entireLength - line.length();
        int start = lineStart + diagnostic.fileStartOffset();
        int end = lineStart + diagnostic.fileEndOffset();
        HyperlinkInfo hyperlink = new ProjectFileHyperlinkInfo(
                project,
                root,
                target,
                Math.max(0, diagnostic.line() - 1),
                Math.max(0, diagnostic.column() - 1)
        );
        return new Result(start, end, hyperlink);
    }

    static @Nullable Path resolveProjectPath(@NotNull Path projectRoot, @NotNull String rawPath) {
        try {
            Path root = realProjectRoot(projectRoot);
            if (root == null) {
                return null;
            }

            Path candidate = Path.of(rawPath);
            candidate = candidate.isAbsolute()
                    ? candidate.toAbsolutePath().normalize()
                    : root.resolve(candidate).normalize();
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(candidate)) {
                return null;
            }
            Path real = candidate.toRealPath();
            return real.startsWith(root) ? real : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static @Nullable Path realProjectRoot(@NotNull Path projectRoot) {
        try {
            Path root = projectRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                return null;
            }
            return root.toRealPath();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Resolves the already physically validated target into IntelliJ's VFS only when navigation is requested.
     * Compiler output can be produced for files created outside the IDE, so the VFS snapshot may not know the
     * target yet. Platform 262 documents markDirtyAndRefresh as the reliable refresh path after external file IO.
     */
    private static @Nullable VirtualFile refreshProjectFile(@NotNull Path projectRoot, @NotNull Path target) {
        LocalFileSystem fileSystem = LocalFileSystem.getInstance();
        VirtualFile current = fileSystem.findFileByNioFile(projectRoot);
        if (current == null) {
            current = fileSystem.refreshAndFindFileByNioFile(projectRoot);
        }
        if (current == null || !current.isDirectory()) {
            return null;
        }

        Path relative;
        try {
            relative = projectRoot.relativize(target);
        } catch (IllegalArgumentException ignored) {
            return null;
        }

        for (Path element : relative) {
            if (!current.isDirectory()) {
                return null;
            }
            VfsUtil.markDirtyAndRefresh(false, false, true, current);
            current = current.findChild(element.toString());
            if (current == null) {
                return null;
            }
        }
        return current.isDirectory() ? null : current;
    }

    private record ProjectFileHyperlinkInfo(
            @NotNull Project project,
            @NotNull Path projectRoot,
            @NotNull Path target,
            int line,
            int column
    ) implements HyperlinkInfo {
        @Override
        public void navigate(@NotNull Project ignored) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) {
                    return;
                }

                // Revalidate at navigation time so a path cannot be swapped to a symlink or moved outside the
                // project between console parsing and the user's click.
                Path validated = resolveProjectPath(projectRoot, target.toString());
                if (validated == null) {
                    return;
                }
                VirtualFile file = refreshProjectFile(projectRoot, validated);
                if (file == null) {
                    return;
                }
                new OpenFileDescriptor(project, file, line, column).navigate(true);
            });
        }
    }
}
