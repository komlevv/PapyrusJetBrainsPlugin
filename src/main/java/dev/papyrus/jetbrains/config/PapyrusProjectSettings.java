package dev.papyrus.jetbrains.config;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

@Service(Service.Level.PROJECT)
@State(
        name = "dev.papyrus.intellij.config.PapyrusProjectSettings",
        storages = @Storage("papyrus.xml")
)
public final class PapyrusProjectSettings implements PersistentStateComponent<PapyrusProjectSettings.SettingsState> {

    public static final String SKYRIM_SE_GAME_ID = "skyrimSpecialEdition";
    public static final String DEFAULT_PROJECT_FILE = "skyrimse.ppj";
    public static final String BUILD_SYSTEM_IDE = "ide";
    public static final String BUILD_SYSTEM_PAPYRUS = "papyrus";

    public static final class SettingsState {
        public String gameId = SKYRIM_SE_GAME_ID;
        public String projectFile = DEFAULT_PROJECT_FILE;
        public String buildSystem = BUILD_SYSTEM_IDE;
    }

    private SettingsState state = new SettingsState();

    public static @NotNull PapyrusProjectSettings getInstance(@NotNull Project project) {
        return project.getService(PapyrusProjectSettings.class);
    }

    public static boolean usesPapyrusBuild(@NotNull Project project) {
        return BUILD_SYSTEM_PAPYRUS.equals(getInstance(project).getState().buildSystem);
    }

    public static @NotNull Path resolveProjectFile(@NotNull Project project) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            throw new IllegalStateException("Papyrus build requires an IDE project root.");
        }

        String configured = getInstance(project).getState().projectFile;
        if (configured == null || configured.isBlank()) {
            configured = DEFAULT_PROJECT_FILE;
        }
        Path path = Path.of(configured);
        return path.isAbsolute()
                ? path.toAbsolutePath().normalize()
                : Path.of(basePath).resolve(path).toAbsolutePath().normalize();
    }

    @Override
    public @NotNull SettingsState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull SettingsState state) {
        if (state.gameId == null || state.gameId.isBlank()) {
            state.gameId = SKYRIM_SE_GAME_ID;
        }
        if (state.projectFile == null || state.projectFile.isBlank()) {
            state.projectFile = DEFAULT_PROJECT_FILE;
        }
        if (!BUILD_SYSTEM_PAPYRUS.equals(state.buildSystem)) {
            // Normalize the pre-0.2.125 internal value "intellij" and any unknown value
            // to the product-neutral IDE default without changing user-visible behavior.
            state.buildSystem = BUILD_SYSTEM_IDE;
        }
        this.state = state;
    }
}
