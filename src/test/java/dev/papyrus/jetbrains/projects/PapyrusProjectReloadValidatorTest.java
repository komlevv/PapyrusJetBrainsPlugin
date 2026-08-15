package dev.papyrus.jetbrains.projects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PapyrusProjectReloadValidatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesLocalImportsFoldersVariablesAndRemoteImports() throws Exception {
        Path imports = Files.createDirectories(temporaryDirectory.resolve("deps").resolve("scripts"));
        Path source = Files.createDirectories(temporaryDirectory.resolve("src"));
        Path ppj = writeProject("""
                <PapyrusProject xmlns="PapyrusProject.xsd">
                  <Variables>
                    <Variable Name="Deps" Value="deps"/>
                  </Variables>
                  <Imports>
                    <Import>@Deps/scripts</Import>
                    <Import>https://example.invalid/papyrus.zip</Import>
                  </Imports>
                  <Folders>
                    <Folder>src</Folder>
                  </Folders>
                </PapyrusProject>
                """);

        PapyrusProjectReloadValidator.ValidationResult result = PapyrusProjectReloadValidator.validate(ppj);

        assertTrue(result.valid());
        assertEquals(imports.toAbsolutePath().normalize(), result.localImports().getFirst());
        assertTrue(Files.isDirectory(source));
    }

    @Test
    void validatesCapturedEditorBytesWithoutSavingThemToDisk() throws Exception {
        Path diskImport = Files.createDirectories(temporaryDirectory.resolve("disk-import"));
        Path editorImport = Files.createDirectories(temporaryDirectory.resolve("editor-import"));
        Path ppj = writeProject("""
                <PapyrusProject xmlns="PapyrusProject.xsd">
                  <Imports><Import>disk-import</Import></Imports>
                </PapyrusProject>
                """);
        String diskText = Files.readString(ppj);
        byte[] editorBytes = """
                <PapyrusProject xmlns="PapyrusProject.xsd">
                  <Imports><Import>editor-import</Import></Imports>
                </PapyrusProject>
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        PapyrusProjectReloadValidator.ValidationResult result =
                PapyrusProjectReloadValidator.validate(ppj, editorBytes);

        assertTrue(result.valid());
        assertEquals(editorImport.toAbsolutePath().normalize(), result.localImports().getFirst());
        assertEquals(diskText, Files.readString(ppj));
        assertFalse(new String(result.snapshotBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .contains(diskImport.toAbsolutePath().normalize().toString()));
    }

    @Test
    void reportsMissingImportWithOriginalAndResolvedPath() throws Exception {
        Path ppj = writeProject("""
                <PapyrusProject xmlns="PapyrusProject.xsd">
                  <Imports><Import>missing/scripts</Import></Imports>
                </PapyrusProject>
                """);

        PapyrusProjectReloadValidator.ValidationResult result = PapyrusProjectReloadValidator.validate(ppj);

        assertFalse(result.valid());
        assertEquals("PPJ validation failed: import directory does not exist", result.failureSummary());
        assertTrue(result.details().contains("Import: missing/scripts"));
        assertTrue(result.details().contains(temporaryDirectory.resolve("missing").resolve("scripts").toString()));
    }

    @Test
    void reportsMalformedXmlWithoutTouchingServerState() throws Exception {
        Path ppj = writeProject("<PapyrusProject><Imports></PapyrusProject>");

        PapyrusProjectReloadValidator.ValidationResult result = PapyrusProjectReloadValidator.validate(ppj);

        assertFalse(result.valid());
        assertEquals("PPJ validation failed: XML is malformed", result.failureSummary());
    }

    @Test
    void reportsCyclicalVariableSubstitution() throws Exception {
        Path ppj = writeProject("""
                <PapyrusProject xmlns="PapyrusProject.xsd">
                  <Variables>
                    <Variable Name="A" Value="@B"/>
                    <Variable Name="B" Value="@A"/>
                  </Variables>
                  <Imports><Import>@A</Import></Imports>
                </PapyrusProject>
                """);

        PapyrusProjectReloadValidator.ValidationResult result = PapyrusProjectReloadValidator.validate(ppj);

        assertFalse(result.valid());
        assertEquals("PPJ validation failed: project configuration is invalid", result.failureSummary());
        assertTrue(result.details().contains("cyclical variable substitutions"));
    }


    @Test
    void reportsUnresolvedVariableBeforeServerReload() throws Exception {
        Path ppj = writeProject("""
                <PapyrusProject xmlns="PapyrusProject.xsd">
                  <Imports><Import>@Missing/scripts</Import></Imports>
                </PapyrusProject>
                """);

        PapyrusProjectReloadValidator.ValidationResult result = PapyrusProjectReloadValidator.validate(ppj);

        assertFalse(result.valid());
        assertEquals("PPJ validation failed: variable is unresolved", result.failureSummary());
        assertTrue(result.details().contains("Unknown variable: @Missing"));
    }

    @Test
    void reportsMissingSourceFolderWithResolvedPath() throws Exception {
        Path ppj = writeProject("""
                <PapyrusProject xmlns="PapyrusProject.xsd">
                  <Imports/>
                  <Folders><Folder>missing-source</Folder></Folders>
                </PapyrusProject>
                """);

        PapyrusProjectReloadValidator.ValidationResult result = PapyrusProjectReloadValidator.validate(ppj);

        assertFalse(result.valid());
        assertEquals("PPJ validation failed: source folder does not exist", result.failureSummary());
        assertTrue(result.details().contains("Folder: missing-source"));
        assertTrue(result.details().contains(temporaryDirectory.resolve("missing-source").toString()));
    }


    @Test
    void reportsMissingPapyrusProjectNamespace() throws Exception {
        Path ppj = writeProject("""
                <PapyrusProject>
                  <Imports/>
                </PapyrusProject>
                """);

        PapyrusProjectReloadValidator.ValidationResult result = PapyrusProjectReloadValidator.validate(ppj);

        assertFalse(result.valid());
        assertEquals("PPJ validation failed: XML namespace is invalid", result.failureSummary());
        assertTrue(result.details().contains("PapyrusProject.xsd"));
    }

    @Test
    void reportsMissingImportsSectionBeforeUpstreamReload() throws Exception {
        Path ppj = writeProject("""
                <PapyrusProject xmlns="PapyrusProject.xsd">
                  <Folders/>
                </PapyrusProject>
                """);

        PapyrusProjectReloadValidator.ValidationResult result = PapyrusProjectReloadValidator.validate(ppj);

        assertFalse(result.valid());
        assertEquals("PPJ validation failed: Imports section is missing", result.failureSummary());
    }

    @Test
    void reportsDuplicateVariablesBeforeUpstreamToDictionaryFails() throws Exception {
        Path ppj = writeProject("""
                <PapyrusProject xmlns="PapyrusProject.xsd">
                  <Variables>
                    <Variable Name="Root" Value="one"/>
                    <Variable Name="Root" Value="two"/>
                  </Variables>
                  <Imports/>
                </PapyrusProject>
                """);

        PapyrusProjectReloadValidator.ValidationResult result = PapyrusProjectReloadValidator.validate(ppj);

        assertFalse(result.valid());
        assertEquals("PPJ validation failed: project configuration is invalid", result.failureSummary());
        assertTrue(result.details().contains("duplicate variable @Root"));
    }

    @Test
    void materializesRelativePathsIntoImmutableSnapshot() throws Exception {
        Path imports = Files.createDirectories(temporaryDirectory.resolve("deps").resolve("scripts"));
        Path source = Files.createDirectories(temporaryDirectory.resolve("src"));
        Path ppj = writeProject("""
                <PapyrusProject xmlns="PapyrusProject.xsd" Output="out">
                  <Variables>
                    <Variable Name="Deps" Value="deps"/>
                  </Variables>
                  <Imports><Import>@Deps/scripts</Import></Imports>
                  <Folders><Folder>src</Folder></Folders>
                  <Scripts><Script>src/Probe</Script></Scripts>
                </PapyrusProject>
                """);

        PapyrusProjectReloadValidator.ValidationResult result = PapyrusProjectReloadValidator.validate(ppj);

        assertTrue(result.valid());
        String snapshot = new String(result.snapshotBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(snapshot.contains(imports.toAbsolutePath().normalize().toString()));
        assertTrue(snapshot.contains(source.toAbsolutePath().normalize().toString()));
        assertTrue(snapshot.contains(temporaryDirectory.resolve("src").resolve("Probe").toAbsolutePath().normalize().toString()));
        assertTrue(snapshot.contains(temporaryDirectory.resolve("out").toAbsolutePath().normalize().toString()));
        assertFalse(snapshot.contains("@Deps/scripts"));
    }

    @Test
    void validatedSnapshotBytesAreDetachedFromLaterPpjEdits() throws Exception {
        Path imports = Files.createDirectories(temporaryDirectory.resolve("deps"));
        Path ppj = writeProject("""
                <PapyrusProject xmlns="PapyrusProject.xsd">
                  <Imports><Import>deps</Import></Imports>
                </PapyrusProject>
                """);

        PapyrusProjectReloadValidator.ValidationResult result = PapyrusProjectReloadValidator.validate(ppj);
        assertTrue(result.valid());
        byte[] firstCopy = result.snapshotBytes();
        String approved = new String(firstCopy, java.nio.charset.StandardCharsets.UTF_8);

        Files.writeString(ppj, """
                <PapyrusProject xmlns="PapyrusProject.xsd">
                  <Imports><Import>mods1111</Import></Imports>
                </PapyrusProject>
                """);
        firstCopy[0] = (byte) (firstCopy[0] ^ 0x7f);

        String stillApproved = new String(result.snapshotBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(approved, stillApproved);
        assertTrue(stillApproved.contains(imports.toAbsolutePath().normalize().toString()));
        assertFalse(stillApproved.contains("mods1111"));
    }

    private Path writeProject(String xml) throws Exception {
        Path ppj = temporaryDirectory.resolve("test.ppj");
        Files.writeString(ppj, xml);
        return ppj;
    }
}
