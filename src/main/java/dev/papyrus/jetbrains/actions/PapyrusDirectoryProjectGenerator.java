package dev.papyrus.jetbrains.actions;

import com.intellij.facet.ui.ValidationResult;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.DirectoryProjectGenerator;
import dev.papyrus.jetbrains.config.PapyrusProjectSettings;
import dev.papyrus.jetbrains.config.PapyrusSettings;
import dev.papyrus.jetbrains.runtime.PapyrusGameInstallPathResolver;
import dev.papyrus.jetbrains.runtime.PapyrusRuntimePaths;
import dev.papyrus.jetbrains.ui.PapyrusIcons;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.nio.file.Path;

public final class PapyrusDirectoryProjectGenerator implements DirectoryProjectGenerator<Object> {

    @Override
    public @NotNull String getName() {
        return "Papyrus";
    }

    @Override
    public Icon getLogo() {
        return PapyrusIcons.SCRIPT;
    }

    @Override
    public @NotNull ValidationResult validate(@NotNull String baseDirPath) {
        return ValidationResult.OK;
    }

    @Override
    public void generateProject(
            @NotNull Project project,
            @NotNull VirtualFile baseDir,
            @NotNull Object settings,
            @NotNull Module module
    ) {
        Path gameDirectory = PapyrusGameInstallPathResolver.resolveSkyrimSpecialEdition(
                PapyrusSettings.getInstance().getState().creationKitInstallPath
        ).orElseThrow(() -> new IllegalStateException(
                "Skyrim Special Edition path was not found. Configure it in Settings | Languages & Frameworks | Papyrus before creating a Papyrus project."
        ));

        Path sourcePath = gameDirectory.resolve(Path.of("Data", "Source", "Scripts"));
        Path ppjTemplate = PapyrusRuntimePaths.getResourcesDirectory().resolve(Path.of("sse", "skyrimse.ppj"));
        Path projectRoot = baseDir.toNioPath().toAbsolutePath().normalize();

        try {
            PapyrusProjectGenerator.populateExistingProject(
                    projectRoot,
                    ppjTemplate,
                    sourcePath,
                    gameDirectory
            );
        } catch (Exception exception) {
            String message = exception.getMessage();
            throw new IllegalStateException(
                    "Failed to create Papyrus project files: "
                            + (message == null || message.isBlank() ? exception.getClass().getSimpleName() : message),
                    exception
            );
        }

        PapyrusProjectSettings.SettingsState state = PapyrusProjectSettings.getInstance(project).getState();
        state.gameId = PapyrusProjectSettings.SKYRIM_SE_GAME_ID;
        state.projectFile = PapyrusProjectSettings.DEFAULT_PROJECT_FILE;
        state.buildSystem = PapyrusProjectSettings.BUILD_SYSTEM_PAPYRUS;

        VfsUtil.markDirtyAndRefresh(false, true, true, baseDir);
    }
}
