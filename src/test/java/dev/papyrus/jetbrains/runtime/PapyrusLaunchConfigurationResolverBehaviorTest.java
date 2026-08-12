package dev.papyrus.jetbrains.runtime;

import dev.papyrus.jetbrains.config.PapyrusSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PapyrusLaunchConfigurationResolverBehaviorTest {

    @TempDir
    Path temp;

    @Test
    void resolvesTheHostCompilerIniAndRemotesFromACompleteInstallation() throws Exception {
        Path vsix = Files.createDirectory(temp.resolve("vsix"));
        Path host = vsix.resolve("bin/Debug/net472/DarkId.Papyrus.Host.Skyrim/DarkId.Papyrus.Host.Skyrim.exe");
        Files.createDirectories(host.getParent());
        Files.writeString(host, "host", StandardCharsets.UTF_8);
        Files.createDirectories(vsix.resolve("pyro/remote"));

        Path game = Files.createDirectory(temp.resolve("game"));
        Files.createDirectories(game.resolve("CompilerFromIni"));
        Path ini = Files.writeString(temp.resolve("Papyrus.ini"), """
                [Papyrus]
                sScriptSourceFolder=.\\Source\\Scripts\\
                sAdditionalImports=.\\Shared\\
                sCompilerFolder=CompilerFromIni
                """, StandardCharsets.UTF_8);

        PapyrusSettings.SettingsState state = new PapyrusSettings.SettingsState();
        state.creationKitInstallPath = game.toString();
        state.iniPaths = ini.toString();
        state.compilerPathOverride = "";
        state.ambientProjectName = "Test Project";
        state.flagsFileName = "Flags.flg";

        PapyrusLaunchConfiguration config = PapyrusLaunchConfigurationResolver.resolve(
                state,
                (key, valueName) -> null,
                vsix
        );
        assertEquals(host.toAbsolutePath().normalize(), config.hostExecutable().toAbsolutePath().normalize());
        assertEquals(game.resolve("CompilerFromIni").normalize(), config.compilerAssemblyPath());
        assertEquals(List.of(ini.toString()), config.relativeIniPaths());
        assertEquals("Test Project", config.ambientProjectName());
        assertEquals("Flags.flg", config.flagsFileName());
    }

    @Test
    void rejectsIncompleteInstallationsInsteadOfGuessing() {
        PapyrusSettings.SettingsState state = new PapyrusSettings.SettingsState();
        state.creationKitInstallPath = temp.toString();
        assertThrows(
                IllegalStateException.class,
                () -> PapyrusLaunchConfigurationResolver.resolve(
                        state,
                        (key, valueName) -> null,
                        temp.resolve("missing-vsix")
                )
        );
    }
    @Test
    void classifiesDisabledBeforeAnyFilesystemOrRegistryProbe() {
        PapyrusSettings.SettingsState state = new PapyrusSettings.SettingsState();
        state.enabled = false;
        state.creationKitInstallPath = temp.resolve("missing-game-disabled").toString();

        assertEquals(
                PapyrusLaunchReadiness.Kind.DISABLED,
                PapyrusLaunchConfigurationResolver.readiness(
                        state,
                        (key, valueName) -> {
                            throw new AssertionError("Disabled readiness must not access the Windows Registry");
                        },
                        temp.resolve("missing-vsix-disabled")
                ).kind()
        );
    }

    @Test
    void classifiesMissingGameCompilerAndOtherConfigurationFailures() throws Exception {
        PapyrusSettings.SettingsState state = new PapyrusSettings.SettingsState();
        state.creationKitInstallPath = temp.resolve("missing-game").toString();
        state.iniPaths = "";
        state.compilerPathOverride = "";

        assertEquals(
                PapyrusLaunchReadiness.Kind.MISSING_GAME,
                PapyrusLaunchConfigurationResolver.readiness(state, (key, valueName) -> null, temp.resolve("missing-vsix")).kind()
        );

        Path game = Files.createDirectory(temp.resolve("game-readiness"));
        state.creationKitInstallPath = game.toString();
        state.compilerPathOverride = temp.resolve("missing-compiler").toString();
        assertEquals(
                PapyrusLaunchReadiness.Kind.COMPILER_MISSING,
                PapyrusLaunchConfigurationResolver.readiness(state, (key, valueName) -> null, temp.resolve("missing-vsix")).kind()
        );

        Path compiler = Files.createDirectory(temp.resolve("compiler-readiness"));
        state.compilerPathOverride = compiler.toString();
        assertEquals(
                PapyrusLaunchReadiness.Kind.ERROR,
                PapyrusLaunchConfigurationResolver.readiness(state, (key, valueName) -> null, temp.resolve("missing-vsix")).kind()
        );
    }

}
