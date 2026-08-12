package dev.papyrus.jetbrains.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

final class PapyrusCompilerDiagnosticTest {

    @Test
    void parsesExactUpstreamProblemMatcherFormatIncludingSpaces() {
        String line = "C:\\Mods Folder\\Source\\Compile Probe.psc(17,9): unknown type BrokenType";

        PapyrusCompilerDiagnostic diagnostic = PapyrusCompilerDiagnostic.parse(line);
        assertNotNull(diagnostic);

        assertEquals("C:\\Mods Folder\\Source\\Compile Probe.psc", diagnostic.filePath());
        assertEquals(17, diagnostic.line());
        assertEquals(9, diagnostic.column());
        assertEquals("unknown type BrokenType", diagnostic.message());
        assertEquals(0, diagnostic.fileStartOffset());
        assertEquals(diagnostic.filePath().length(), diagnostic.fileEndOffset());
    }

    @Test
    void acceptsCapturedStderrPrefixAndRejectsNonCompilerText() {
        String line = "[stderr] D:\\Project\\Broken.psc(3,2): syntax error";

        PapyrusCompilerDiagnostic diagnostic = PapyrusCompilerDiagnostic.parse(line + "\r\n");
        assertNotNull(diagnostic);

        assertEquals("D:\\Project\\Broken.psc", diagnostic.filePath());
        assertEquals("syntax error", diagnostic.message());
        assertEquals("[stderr] ".length(), diagnostic.fileStartOffset());
        assertNull(PapyrusCompilerDiagnostic.parse("Compiling 1 scripts..."));
        assertNull(PapyrusCompilerDiagnostic.parse("Broken.psc: syntax error"));
    }

    @Test
    void parsesObservedPyroLoggerEnvelopeWithoutTreatingItAsPartOfTheFilePath() {
        String line = "2026-08-11 17:11:31,696 [ERRO] COMPILATION FAILED: BrokenCompile\\BrokenProbe.psc(4,15): no viable alternative at input '\\n'";

        PapyrusCompilerDiagnostic diagnostic = PapyrusCompilerDiagnostic.parse(line);
        assertNotNull(diagnostic);

        assertEquals("BrokenCompile\\BrokenProbe.psc", diagnostic.filePath());
        assertEquals(4, diagnostic.line());
        assertEquals(15, diagnostic.column());
        assertEquals("no viable alternative at input '\\n'", diagnostic.message());
        assertEquals(line.indexOf("BrokenCompile\\BrokenProbe.psc"), diagnostic.fileStartOffset());
        assertEquals(
                line.indexOf("BrokenCompile\\BrokenProbe.psc") + "BrokenCompile\\BrokenProbe.psc".length(),
                diagnostic.fileEndOffset()
        );

        String stderrLine = "[stderr] " + line;
        PapyrusCompilerDiagnostic stderrDiagnostic = PapyrusCompilerDiagnostic.parse(stderrLine);
        assertNotNull(stderrDiagnostic);
        assertEquals("BrokenCompile\\BrokenProbe.psc", stderrDiagnostic.filePath());
        assertEquals(stderrLine.indexOf("BrokenCompile\\BrokenProbe.psc"), stderrDiagnostic.fileStartOffset());
        assertEquals(
                stderrLine.indexOf("BrokenCompile\\BrokenProbe.psc") + "BrokenCompile\\BrokenProbe.psc".length(),
                stderrDiagnostic.fileEndOffset()
        );

        PapyrusCompilerDiagnostic markerInMessage = PapyrusCompilerDiagnostic.parse(
                "C:\\Project\\Probe.psc(1,1): COMPILATION FAILED: still a compiler message"
        );
        assertNotNull(markerInMessage);
        assertEquals("C:\\Project\\Probe.psc", markerInMessage.filePath());
    }

    @Test
    void resolvesProjectRelativeCompilerPathWithoutEscapingProject(@TempDir Path root) throws Exception {
        Path source = root.resolve("Source/Broken.psc");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "Scriptname Broken extends Quest\n");

        assertEquals(source.toRealPath(), PapyrusCompilerFilter.resolveProjectPath(root, "Source/Broken.psc"));
        assertEquals(source.toRealPath(), PapyrusCompilerFilter.resolveProjectPath(root, source.toString()));
    }

    @Test
    void rejectsCompilerPathOutsideProject(@TempDir Path root) throws Exception {
        Path project = root.resolve("project");
        Path outside = root.resolve("outside.psc");
        Files.createDirectories(project);
        Files.writeString(outside, "Scriptname Outside extends Quest\n");

        assertNull(PapyrusCompilerFilter.resolveProjectPath(project, "../outside.psc"));
        assertNull(PapyrusCompilerFilter.resolveProjectPath(project, outside.toString()));
    }

}
