package dev.papyrus.jetbrains.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class CreationKitIniLoaderBehaviorTest {

    @TempDir
    Path temp;

    @Test
    void appliesPapyrusSettingsInConfiguredIniOrder() throws Exception {
        Files.writeString(temp.resolve("CreationKit.ini"), """
                [General]
                sScriptSourceFolder=ignored

                [Papyrus]
                sScriptSourceFolder=.\\Data\\Source\\Scripts\\
                sAdditionalImports=.\\Data\\Source\\Base;C:\\Shared
                sCompilerFolder=Papyrus Compiler\\
                """, StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("CreationKitCustom.ini"), """
                [Papyrus]
                sScriptSourceFolder=".\\Custom\\Source\\"
                sCompilerFolder="Custom Compiler\\"
                """, StandardCharsets.UTF_8);

        CreationKitPapyrusConfig config = CreationKitIniLoader.load(
                temp,
                List.of("CreationKit.ini", "CreationKitCustom.ini")
        );

        assertEquals(".\\Custom\\Source\\", config.scriptSourceFolder());
        assertEquals(".\\Data\\Source\\Base;C:\\Shared", config.additionalImports());
        assertEquals("Custom Compiler\\", config.compilerFolder());
    }

    @Test
    void missingOptionalIniFilesKeepSkyrimDefaults() {
        CreationKitPapyrusConfig config = CreationKitIniLoader.load(temp, List.of("missing.ini"));
        assertEquals(".\\Data\\Source\\Scripts\\", config.scriptSourceFolder());
        assertNull(config.additionalImports());
        assertEquals("Papyrus Compiler\\", config.compilerFolder());
    }
}
