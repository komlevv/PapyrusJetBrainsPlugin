package dev.papyrus.jetbrains.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtil;
import dev.papyrus.jetbrains.config.PapyrusSettings;
import dev.papyrus.jetbrains.runtime.PapyrusGameInstallPathResolver;
import dev.papyrus.jetbrains.runtime.PapyrusRuntimePaths;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

public final class GenerateSkyrimProjectAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        GenerationTarget requestedTarget = chooseGenerationTarget(project);
        if (requestedTarget == null) {
            return;
        }

        String folderName = requestedTarget.folderName().trim();
        String validationError = PapyrusProjectGenerator.validateFolderName(folderName);
        if (validationError != null) {
            PapyrusActionUtil.showError(project, validationError);
            return;
        }

        Path parentDirectory = requestedTarget.parentDirectory();
        Path gameDirectory = PapyrusGameInstallPathResolver.resolveSkyrimSpecialEdition(
                PapyrusSettings.getInstance().getState().creationKitInstallPath
        ).orElse(null);
        if (gameDirectory == null) {
            PapyrusActionUtil.showError(
                    project,
                    "Skyrim Special Edition path was not found. Configure it in Settings | Papyrus or install the game so the Bethesda registry entry is available."
            );
            return;
        }
        Path sourcePath = gameDirectory.resolve(Path.of("Data", "Source", "Scripts"));
        Path resources = PapyrusRuntimePaths.getResourcesDirectory().resolve("sse");
        Path ppjTemplate = resources.resolve("skyrimse.ppj");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                Path targetReal = PapyrusProjectGenerator.generateNewProject(
                        parentDirectory,
                        folderName,
                        ppjTemplate,
                        sourcePath,
                        gameDirectory
                );

                VirtualFile target = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetReal);
                if (target != null) {
                    VfsUtil.markDirtyAndRefresh(false, true, true, target);
                }
                ApplicationManager.getApplication().invokeLater(
                        () -> PapyrusActionUtil.showInfo(
                                project,
                                "Papyrus project generated in new folder:\n" + targetReal
                        )
                );
            } catch (Exception exception) {
                ApplicationManager.getApplication().invokeLater(
                        () -> PapyrusActionUtil.showError(
                                project,
                                "Failed to generate Papyrus project files: " + exception.getMessage()
                        )
                );
            }
        });
    }


    private static GenerationTarget chooseGenerationTarget(@NotNull Project project) {
        if (PapyrusActionTestBridge.isUiIntegrationTest()) {
            PapyrusActionTestBridge.ProjectGenerationRequest request =
                    PapyrusActionTestBridge.consumeProjectGenerationRequest();
            if (request == null || request.cancelled() || request.parentDirectory() == null || request.folderName() == null) {
                return null;
            }
            return new GenerationTarget(request.parentDirectory(), request.folderName());
        }

        VirtualFile initial = null;
        String basePath = project.getBasePath();
        if (basePath != null) {
            initial = LocalFileSystem.getInstance().findFileByPath(basePath);
        }

        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.singleDir()
                .withTitle("Generate Papyrus Project")
                .withDescription("Choose a parent folder. A new Papyrus project folder will be created inside it.");
        VirtualFile selected = FileChooser.chooseFile(descriptor, project, initial);
        if (selected == null) {
            return null;
        }

        String folderName = Messages.showInputDialog(
                project,
                "Enter a name for the new project folder. The folder must not already exist.",
                "Generate Papyrus Project",
                null
        );
        return folderName == null ? null : new GenerationTarget(selected.toNioPath(), folderName);
    }

    private record GenerationTarget(@NotNull Path parentDirectory, @NotNull String folderName) {
    }

    static boolean isEnabledFor(@Nullable Project project) {
        return project != null;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(isEnabledFor(event.getProject()));
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
