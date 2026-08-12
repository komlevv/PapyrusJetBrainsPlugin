package dev.papyrus.jetbrains.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PapyrusProjectSettingsBehaviorTest {

    @Test
    void ordinaryProjectsKeepTheIdeBuildSystemByDefault() {
        PapyrusProjectSettings.SettingsState state = new PapyrusProjectSettings.SettingsState();
        assertEquals(PapyrusProjectSettings.BUILD_SYSTEM_IDE, state.buildSystem);
    }

    @Test
    void onlyTheExplicitPapyrusBuildValueSurvivesStateLoading() {
        PapyrusProjectSettings settings = new PapyrusProjectSettings();

        PapyrusProjectSettings.SettingsState papyrus = new PapyrusProjectSettings.SettingsState();
        papyrus.buildSystem = PapyrusProjectSettings.BUILD_SYSTEM_PAPYRUS;
        settings.loadState(papyrus);
        assertEquals(PapyrusProjectSettings.BUILD_SYSTEM_PAPYRUS, settings.getState().buildSystem);

        PapyrusProjectSettings.SettingsState legacy = new PapyrusProjectSettings.SettingsState();
        legacy.buildSystem = "intellij";
        settings.loadState(legacy);
        assertEquals(PapyrusProjectSettings.BUILD_SYSTEM_IDE, settings.getState().buildSystem);

        PapyrusProjectSettings.SettingsState unknown = new PapyrusProjectSettings.SettingsState();
        unknown.buildSystem = "unexpected";
        settings.loadState(unknown);
        assertEquals(PapyrusProjectSettings.BUILD_SYSTEM_IDE, settings.getState().buildSystem);
    }
}
