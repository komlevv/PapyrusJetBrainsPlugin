package dev.papyrus.jetbrains.config;

import dev.papyrus.jetbrains.PapyrusPluginVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
        papyrus.pluginVersion = PapyrusPluginVersion.CURRENT;
        papyrus.buildSystem = PapyrusProjectSettings.BUILD_SYSTEM_PAPYRUS;
        settings.loadState(papyrus);
        assertEquals(PapyrusProjectSettings.BUILD_SYSTEM_PAPYRUS, settings.getState().buildSystem);

        PapyrusProjectSettings.SettingsState legacy = new PapyrusProjectSettings.SettingsState();
        legacy.pluginVersion = PapyrusPluginVersion.CURRENT;
        legacy.buildSystem = "intellij";
        settings.loadState(legacy);
        assertEquals(PapyrusProjectSettings.BUILD_SYSTEM_IDE, settings.getState().buildSystem);

        PapyrusProjectSettings.SettingsState unknown = new PapyrusProjectSettings.SettingsState();
        unknown.pluginVersion = PapyrusPluginVersion.CURRENT;
        unknown.buildSystem = "unexpected";
        settings.loadState(unknown);
        assertEquals(PapyrusProjectSettings.BUILD_SYSTEM_IDE, settings.getState().buildSystem);
    }
    @Test
    void staleOrUnversionedIdeaStateIsRejected() {
        PapyrusProjectSettings settings = new PapyrusProjectSettings();

        PapyrusProjectSettings.SettingsState stale = new PapyrusProjectSettings.SettingsState();
        stale.pluginVersion = "0.0.0-stale";
        stale.gameId = "staleGame";
        stale.projectFile = "stale.ppj";
        stale.buildSystem = PapyrusProjectSettings.BUILD_SYSTEM_PAPYRUS;
        settings.loadState(stale);

        assertEquals(PapyrusPluginVersion.CURRENT, settings.getState().pluginVersion);
        assertEquals(PapyrusProjectSettings.SKYRIM_SE_GAME_ID, settings.getState().gameId);
        assertEquals(PapyrusProjectSettings.DEFAULT_PROJECT_FILE, settings.getState().projectFile);
        assertEquals(PapyrusProjectSettings.BUILD_SYSTEM_IDE, settings.getState().buildSystem);

        PapyrusProjectSettings.SettingsState unversioned = new PapyrusProjectSettings.SettingsState();
        assertNotEquals(PapyrusPluginVersion.CURRENT, unversioned.pluginVersion);
        unversioned.projectFile = "legacy.ppj";
        unversioned.buildSystem = PapyrusProjectSettings.BUILD_SYSTEM_PAPYRUS;
        settings.loadState(unversioned);

        assertEquals(PapyrusPluginVersion.CURRENT, settings.getState().pluginVersion);
        assertEquals(PapyrusProjectSettings.DEFAULT_PROJECT_FILE, settings.getState().projectFile);
        assertEquals(PapyrusProjectSettings.BUILD_SYSTEM_IDE, settings.getState().buildSystem);
    }

}
