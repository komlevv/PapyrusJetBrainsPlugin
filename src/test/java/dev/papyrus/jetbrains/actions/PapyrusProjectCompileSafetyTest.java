package dev.papyrus.jetbrains.actions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PapyrusProjectCompileSafetyTest {

    @TempDir
    Path tempDir;

    @Test
    void simpleSseProjectWithProjectLocalOutputIsAllowed() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("project"));
        Path ppj = writeProject(root, "Scripts", "sse", "");

        PapyrusProjectCompileSafety.Plan plan = PapyrusProjectCompileSafety.validate(root, ppj);

        assertEquals(ppj.toRealPath(), plan.projectFile());
        assertEquals(root.toRealPath(), plan.workingDirectory());
        assertEquals(root.resolve("Scripts").toAbsolutePath().normalize(), plan.outputDirectory());
        assertFalse(plan.pyroProjectXml().contains("<?xml"));
    }

    @Test
    void outputOutsideProjectIsBlocked() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("project"));
        Path ppj = writeProject(root, "../outside", "sse", "");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> PapyrusProjectCompileSafety.validate(root, ppj)
        );
        assertTrue(error.getMessage().contains("Output must stay inside"));

        Path expandable = root.resolve("expandable-output.ppj");
        Files.writeString(expandable, xml("${BUILD_OUTPUT}", "sse", "", ""));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> PapyrusProjectCompileSafety.validate(root, expandable)
        ).getMessage().contains("Output path must not contain variables"));
    }

    @Test
    void packageZipAnonymizeAndEventsAreBlocked() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("project"));

        Path packageProject = writeProject(root, "Scripts", "sse", " Package=\"true\"");
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> PapyrusProjectCompileSafety.validate(root, packageProject)
        ).getMessage().contains("Package"));

        Path anonymizeProject = root.resolve("anonymize.ppj");
        Files.writeString(anonymizeProject, xml("Scripts", "sse", " Anonymize=\"True\"", ""));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> PapyrusProjectCompileSafety.validate(root, anonymizeProject)
        ).getMessage().contains("Anonymize"));

        Path zipProject = root.resolve("zip.ppj");
        Files.writeString(zipProject, xml("Scripts", "sse", " Zip=\"True\"", ""));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> PapyrusProjectCompileSafety.validate(root, zipProject)
        ).getMessage().contains("Zip"));

        Path eventProject = root.resolve("event.ppj");
        Files.writeString(eventProject, xml("Scripts", "sse", "", "<PreBuildEvent UseInBuild=\"False\" />"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> PapyrusProjectCompileSafety.validate(root, eventProject)
        ).getMessage().contains("PreBuildEvent"));
    }

    @Test
    void remoteAndExpandableImportPathsAreBlocked() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("project"));
        Path remote = root.resolve("remote.ppj");
        Files.writeString(remote, xml("Scripts", "sse", "", "<Imports><Import>https://example.invalid/scripts</Import></Imports>"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> PapyrusProjectCompileSafety.validate(root, remote)
        ).getMessage().contains("remote Import"));

        Path variable = root.resolve("variable.ppj");
        Files.writeString(variable, xml("Scripts", "sse", "", "<Imports><Import>${DEPENDENCY}</Import></Imports>"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> PapyrusProjectCompileSafety.validate(root, variable)
        ).getMessage().contains("variable or environment expansion"));
    }

    @Test
    void otherGamesAndVariablesAreBlocked() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("project"));
        Path fallout = writeProject(root, "Scripts", "fo4", "");
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> PapyrusProjectCompileSafety.validate(root, fallout)
        ).getMessage().contains("Skyrim Special Edition"));

        Path variables = root.resolve("variables.ppj");
        Files.writeString(variables, xml("Scripts", "sse", "", "<Variables><Variable Name=\"Out\" Value=\"Scripts\" /></Variables>"));
        assertTrue(assertThrows(
                IllegalArgumentException.class,
                () -> PapyrusProjectCompileSafety.validate(root, variables)
        ).getMessage().contains("<Variables>"));
    }


    @Test
    void doctypeAndExternalEntitiesAreRejected() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("project"));
        Path external = tempDir.resolve("external.txt");
        Files.writeString(external, "outside");
        Path ppj = root.resolve("doctype.ppj");
        String externalUri = external.toUri().toASCIIString();
        Files.writeString(
                ppj,
                """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE PapyrusProject [<!ENTITY external SYSTEM="%s">]>
                <PapyrusProject xmlns="PapyrusProject.xsd" Game="sse" Output="Scripts" Flags="TESV_Papyrus_Flags.flg">
                  <Imports><Import>&external;</Import></Imports>
                </PapyrusProject>
                """.formatted(externalUri)
        );

        assertThrows(Exception.class, () -> PapyrusProjectCompileSafety.validate(root, ppj));
    }

    private static Path writeProject(Path root, String output, String game, String rootAttributes) throws Exception {
        Path ppj = root.resolve("project.ppj");
        Files.writeString(ppj, xml(output, game, rootAttributes, ""));
        return ppj;
    }

    private static String xml(String output, String game, String rootAttributes, String body) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <PapyrusProject xmlns="PapyrusProject.xsd" Game="%s" Output="%s" Flags="TESV_Papyrus_Flags.flg"%s>
                  %s
                </PapyrusProject>
                """.formatted(game, output, rootAttributes, body);
    }
}
