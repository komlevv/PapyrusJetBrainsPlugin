package dev.papyrus.jetbrains.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import dev.papyrus.jetbrains.runtime.PapyrusRuntimePaths;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class InstallPapyrusDebugSupportAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Path source = PapyrusRuntimePaths.getBundledDebugPlugin();
                Path modDirectory = PapyrusRuntimePaths.getDebugModDirectory();
                Path destination = PapyrusRuntimePaths.getDebugPluginInstallPath();

                ensureWriteStaysInside(modDirectory, destination.getParent());

                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
                            && Files.mismatch(source, destination) == -1L) {
                        ApplicationManager.getApplication().invokeLater(
                                () -> PapyrusActionUtil.showInfo(project, "Papyrus debugging support is already installed.\n" + destination)
                        );
                        return;
                    }
                    throw new FileAlreadyExistsException(
                            destination.toString(),
                            null,
                            "Refusing to overwrite an existing debugger file. Remove or rename it manually first."
                    );
                }

                Files.createDirectories(destination.getParent());
                ensureWriteStaysInside(modDirectory, destination.getParent());
                Files.copy(source, destination);

                ApplicationManager.getApplication().invokeLater(
                        () -> PapyrusActionUtil.showInfo(project, "Papyrus debugging support installed to:\n" + destination)
                );
            } catch (IOException | RuntimeException throwable) {
                ApplicationManager.getApplication().invokeLater(
                        () -> PapyrusActionUtil.showError(project, "Failed to install Papyrus debugging support: " + throwable.getMessage())
                );
            }
        });
    }

    private static void ensureWriteStaysInside(@NotNull Path baseDirectory, @NotNull Path targetDirectory) throws IOException {
        Path baseReal = baseDirectory.toRealPath();
        Path normalizedTarget = targetDirectory.toAbsolutePath().normalize();

        Path existing = normalizedTarget;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("Could not resolve an existing parent for debugger installation.");
        }

        Path existingReal = existing.toRealPath();
        if (!existingReal.startsWith(baseReal)) {
            throw new IOException("Refusing to write through a link outside the configured debugger mod directory.");
        }

        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            Path targetReal = normalizedTarget.toRealPath();
            if (!targetReal.startsWith(baseReal)) {
                throw new IOException("Refusing to write outside the configured debugger mod directory.");
            }
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
