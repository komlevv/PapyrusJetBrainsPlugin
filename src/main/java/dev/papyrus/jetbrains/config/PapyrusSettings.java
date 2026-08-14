package dev.papyrus.jetbrains.config;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.RoamingType;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.APP)
@State(
        name = "dev.papyrus.intellij.config.PapyrusSettings",
        storages = @Storage(value = "Papyrus.xml", roamingType = RoamingType.DISABLED)
)
public final class PapyrusSettings implements PersistentStateComponent<PapyrusSettings.SettingsState> {

    public static final String DEFAULT_CREATION_KIT_INSTALL_PATH =
            "X:\\SteamLibrary\\steamapps\\common\\Skyrim Special Edition";
    public static final String DEFAULT_INI_PATHS = "";

    /** Public mutable bean fields are required by PersistentStateComponent XML serialization. */
    public static final class SettingsState {
        public boolean enabled = true;
        public String creationKitInstallPath = DEFAULT_CREATION_KIT_INSTALL_PATH;
        public String compilerPathOverride = "";
        public String iniPaths = DEFAULT_INI_PATHS;
        public String ambientProjectName = "Creation Kit";
        public String flagsFileName = "TESV_Papyrus_Flags.flg";
        public String debugModDirectoryPath = "";
        public int debugPort = 43201;
    }

    private SettingsState state = new SettingsState();

    public static PapyrusSettings getInstance() {
        return ApplicationManager.getApplication().getService(PapyrusSettings.class);
    }

    @Override
    public @NotNull SettingsState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull SettingsState state) {
        this.state = state;
    }
}
