package dev.papyrus.jetbrains.actions;

import dev.papyrus.jetbrains.PapyrusPluginVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("UseOptimizedEelFunctions") // Generator behavior tests inspect local temporary project files only.
final class PapyrusProjectGeneratorBehaviorTest {

    @TempDir
    Path temp;

    @Test
    void generatesACompleteNewProjectWithoutTouchingExistingFiles() throws Exception {
        Path templates = Files.createDirectory(temp.resolve("templates"));
        Path ppj = write(templates.resolve("skyrimse.ppj"), "<Import>${SKYRIMSE_PATH}</Import>");
        Path parent = Files.createDirectory(temp.resolve("projects"));
        Path forbidden = Files.createDirectory(temp.resolve("game"));
        Path sourcePath = Path.of("X:/Game & Tools/Data/Source/Scripts");

        Path generated = PapyrusProjectGenerator.generateNewProject(
                parent,
                "MyPapyrusMod",
                ppj,
                sourcePath,
                forbidden
        );

        assertEquals(parent.resolve("MyPapyrusMod").toAbsolutePath().normalize(), generated);
        assertTrue(Files.readString(generated.resolve("skyrimse.ppj")).contains("Game &amp; Tools"));
        String projectSettings = Files.readString(generated.resolve(".idea/papyrus.xml"));
        assertTrue(projectSettings.contains("dev.papyrus.intellij.config.PapyrusProjectSettings"));
        assertTrue(projectSettings.contains("pluginVersion\" value=\"" + PapyrusPluginVersion.CURRENT));
        assertTrue(projectSettings.contains("gameId\" value=\"skyrimSpecialEdition"));
        assertTrue(projectSettings.contains("projectFile\" value=\"skyrimse.ppj"));
        assertTrue(projectSettings.contains("buildSystem\" value=\"papyrus"));
        String runConfiguration = Files.readString(generated.resolve(".run/Papyrus_Skyrim_SE_AE.run.xml"));
        assertTrue(runConfiguration.contains("type=\"PapyrusAttach\""));
        assertTrue(runConfiguration.contains("request\" value=\"attach"));
        assertTrue(runConfiguration.contains("name=\"Papyrus: Skyrim SE/AE\""));
        assertTrue(runConfiguration.contains("projectFile\" value=\"$PROJECT_DIR$/skyrimse.ppj"));
        String compileRunConfiguration = Files.readString(generated.resolve(".run/Papyrus_Compile.run.xml"));
        assertTrue(compileRunConfiguration.contains("type=\"PapyrusProject\""));
        assertTrue(compileRunConfiguration.contains("factoryName=\"Papyrus Project\""));
        assertTrue(compileRunConfiguration.contains("name=\"Papyrus: Compile Project\""));
        assertTrue(compileRunConfiguration.contains("projectFile\" value=\"$PROJECT_DIR$/skyrimse.ppj"));
        assertTrue(Files.isDirectory(generated.resolve("Source/Scripts")));
        assertTrue(Files.notExists(generated.resolve(".vscode")));
        assertTrue(Files.notExists(generated.resolve("SkyrimSE.code-workspace")));

        assertThrows(IOException.class, () -> PapyrusProjectGenerator.generateNewProject(
                parent,
                "MyPapyrusMod",
                ppj,
                sourcePath,
                forbidden
        ));
    }

    @Test
    void populatesAnExistingWizardProjectWithoutReplacingIdeaState() throws Exception {
        Path templates = Files.createDirectory(temp.resolve("wizard-templates"));
        Path ppj = write(templates.resolve("skyrimse.ppj"), "<Import>${SKYRIMSE_PATH}</Import>");
        Path project = Files.createDirectory(temp.resolve("wizard-project"));
        Path idea = Files.createDirectory(project.resolve(".idea"));
        Path sentinel = write(idea.resolve("workspace.xml"), "keep");
        Path game = Files.createDirectory(temp.resolve("wizard-game"));

        Path populated = PapyrusProjectGenerator.populateExistingProject(
                project,
                ppj,
                Path.of("X:/Skyrim/Data/Source/Scripts"),
                game
        );

        assertEquals(project.toRealPath(), populated);
        assertTrue(Files.isRegularFile(project.resolve("skyrimse.ppj")));
        assertTrue(Files.isDirectory(project.resolve("Source/Scripts")));
        assertTrue(Files.isRegularFile(project.resolve(".run/Papyrus_Compile.run.xml")));
        assertTrue(Files.isRegularFile(project.resolve(".run/Papyrus_Skyrim_SE_AE.run.xml")));
        assertEquals("keep", Files.readString(sentinel));
        assertTrue(Files.notExists(project.resolve(".idea/papyrus.xml")));
    }

    @Test
    void rejectsUnsafeWindowsFolderNamesAndGameDirectoryTargets() throws Exception {
        assertNotNull(PapyrusProjectGenerator.validateFolderName("CON"));
        assertNotNull(PapyrusProjectGenerator.validateFolderName("bad:name"));
        assertNotNull(PapyrusProjectGenerator.validateFolderName("trailing."));

        Path game = Files.createDirectory(temp.resolve("Skyrim"));
        Path ppj = write(temp.resolve("p.ppj"), "${SKYRIMSE_PATH}");
        IOException error = assertThrows(IOException.class, () -> PapyrusProjectGenerator.generateNewProject(
                game,
                "Generated",
                ppj,
                Path.of("X:/Data/Source/Scripts"),
                game
        ));
        assertTrue(error.getMessage().contains("inside the configured Skyrim installation"));
    }

    private static Path write(Path path, String text) throws IOException {
        return Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
