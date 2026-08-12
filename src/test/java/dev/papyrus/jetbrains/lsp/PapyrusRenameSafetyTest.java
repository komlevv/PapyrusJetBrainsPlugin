package dev.papyrus.jetbrains.lsp;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PapyrusRenameSafetyTest {

    @Test
    void writableProjectScriptIsAllowed() {
        Path root = Path.of("C:/work/mod");
        PapyrusRenameSafety.Decision decision = PapyrusRenameSafety.validatePathForTests(
                root.resolve("Source/Scripts/MyScript.psc"),
                root,
                Path.of("C:/games/skyrim"),
                Path.of("C:/idea/system/papyrus/vendor"),
                true,
                true,
                false,
                false
        );
        assertTrue(decision.allowed(), decision.reason());
    }

    @Test
    void importedAndRemoteSourcesAreBlockedEvenInsideProjectRoot() {
        Path root = Path.of("C:/work/mod");
        PapyrusRenameSafety.Decision outside = PapyrusRenameSafety.validatePathForTests(
                Path.of("D:/mods/dependency/Source/Scripts/Library.psc"),
                root,
                null,
                null,
                false,
                true,
                false,
                true
        );
        assertFalse(outside.allowed());
        assertTrue(outside.reason().contains("imported Papyrus source"));

        PapyrusRenameSafety.Decision localImport = PapyrusRenameSafety.validatePathForTests(
                root.resolve("Imports/Dependency.psc"),
                root,
                null,
                null,
                true,
                true,
                false,
                true
        );
        assertFalse(localImport.allowed());
        assertTrue(localImport.reason().contains("imported Papyrus source"));

        PapyrusRenameSafety.Decision remote = PapyrusRenameSafety.validatePathForTests(
                root.resolve("remotes/cache/Library.psc"),
                root,
                null,
                null,
                true,
                true,
                true,
                false
        );
        assertFalse(remote.allowed());
        assertTrue(remote.reason().contains("remote Papyrus source"));
    }

    @Test
    void creationKitAndVendorCacheAreBlockedEvenInsideProjectRoot() {
        Path root = Path.of("C:/workspace");
        Path creationKit = root.resolve("Skyrim Special Edition");
        PapyrusRenameSafety.Decision game = PapyrusRenameSafety.validatePathForTests(
                creationKit.resolve("Data/Source/Scripts/Quest.psc"),
                root,
                creationKit,
                null,
                true,
                true,
                false,
                false
        );
        assertFalse(game.allowed());
        assertTrue(game.reason().contains("Creation Kit / game"));

        Path vendor = root.resolve("system/papyrus/vendor");
        PapyrusRenameSafety.Decision cached = PapyrusRenameSafety.validatePathForTests(
                vendor.resolve("extension/Test.psc"),
                root,
                null,
                vendor,
                true,
                true,
                false,
                false
        );
        assertFalse(cached.allowed());
        assertTrue(cached.reason().contains("vendor/cache"));
    }

    @Test
    void nonProjectReadOnlyAndNonPscTargetsAreBlocked() {
        Path root = Path.of("C:/work/mod");
        PapyrusRenameSafety.Decision nonProject = PapyrusRenameSafety.validatePathForTests(
                root.resolve("Source/Scripts/Detached.psc"),
                root,
                null,
                null,
                false,
                true,
                false,
                false
        );
        assertFalse(nonProject.allowed());
        assertTrue(nonProject.reason().contains("project content"));

        PapyrusRenameSafety.Decision readOnly = PapyrusRenameSafety.validatePathForTests(
                root.resolve("Source/Scripts/ReadOnly.psc"),
                root,
                null,
                null,
                true,
                false,
                false,
                false
        );
        assertFalse(readOnly.allowed());
        assertTrue(readOnly.reason().contains("read-only"));

        PapyrusRenameSafety.Decision wrongType = PapyrusRenameSafety.validatePathForTests(
                root.resolve("runtime.ppj"),
                root,
                null,
                null,
                true,
                true,
                false,
                false
        );
        assertFalse(wrongType.allowed());
        assertTrue(wrongType.reason().contains(".psc"));
    }
    @Test
    void renameIdentifiersAreValidatedBeforeServerRequest() {
        assertTrue(PapyrusRenameSafety.isValidRenameIdentifier("SharedProbe"));
        assertTrue(PapyrusRenameSafety.isValidRenameIdentifier("_probe2"));

        assertFalse(PapyrusRenameSafety.isValidRenameIdentifier(""));
        assertFalse(PapyrusRenameSafety.isValidRenameIdentifier("2Probe"));
        assertFalse(PapyrusRenameSafety.isValidRenameIdentifier("Bad-Name"));
        assertFalse(PapyrusRenameSafety.isValidRenameIdentifier("if"));
        assertFalse(PapyrusRenameSafety.isValidRenameIdentifier("ScriptName"));
    }

    @Test
    void renameTextEditsMustBeExactIdentifierReplacement() {
        assertTrue(PapyrusRenameSafety.isExpectedRenameReplacement(
                "sharedprobe", "SharedProbe", "SharedProbeRenamed", "SharedProbeRenamed"
        ));
        assertFalse(PapyrusRenameSafety.isExpectedRenameReplacement(
                "other", "SharedProbe", "SharedProbeRenamed", "SharedProbeRenamed"
        ));
        assertFalse(PapyrusRenameSafety.isExpectedRenameReplacement(
                "SharedProbe", "SharedProbe", "arbitrary code", "SharedProbeRenamed"
        ));
        assertFalse(PapyrusRenameSafety.isExpectedRenameReplacement(
                "", "SharedProbe", "SharedProbeRenamed", "SharedProbeRenamed"
        ));
    }

    @Test
    void scriptNameDeclarationPrefixIsRecognized() {
        assertTrue(PapyrusRenameSafety.isScriptNameDeclarationPrefix("ScriptName "));
        assertTrue(PapyrusRenameSafety.isScriptNameDeclarationPrefix("    scriptname\t"));
        assertFalse(PapyrusRenameSafety.isScriptNameDeclarationPrefix("extends "));
        assertFalse(PapyrusRenameSafety.isScriptNameDeclarationPrefix("; ScriptName "));
    }

}
