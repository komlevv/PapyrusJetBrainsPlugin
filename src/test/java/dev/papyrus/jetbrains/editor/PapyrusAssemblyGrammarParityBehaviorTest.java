package dev.papyrus.jetbrains.editor;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("UseOptimizedEelFunctions") // Parity tests read local vendored test fixtures only.
class PapyrusAssemblyGrammarParityBehaviorTest {
    private static final String UPSTREAM_GRAMMAR_BLOB_SHA1 = "f1197c6d644cb1fb816a10e79f152183f62d097d";
    private static final String VSIX_ROOT_PROPERTY = "papyrus.test.vsixRoot";

    @Test
    void pinnedVsixDeclaresPapyrusAssemblyLanguageAndGrammar() throws Exception {
        Path vsixRoot = Path.of(requiredVsixRootProperty());
        Path packageJson = vsixRoot.resolve("package.json");
        Path grammar = vsixRoot.resolve("syntaxes/papyrus-assembly/papyrus-assembly.tmLanguage");

        assertTrue(Files.isRegularFile(packageJson), "Missing pinned upstream package.json: " + packageJson);
        assertTrue(Files.isRegularFile(grammar), "Missing pinned upstream Papyrus Assembly grammar: " + grammar);

        String grammarText = normalizeEol(Files.readString(grammar, StandardCharsets.UTF_8));
        assertEquals(
                UPSTREAM_GRAMMAR_BLOB_SHA1,
                gitBlobSha1(grammarText),
                "Unexpected papyrus-assembly.tmLanguage snapshot under papyrus.test.vsixRoot"
        );

        String packageText = Files.readString(packageJson, StandardCharsets.UTF_8);
        assertTrue(packageText.contains("\"id\": \"papyrus-assembly\""), "VSIX must declare papyrus-assembly language");
        assertTrue(packageText.contains("\".disassemble.pas\""), "VSIX must associate .disassemble.pas with papyrus-assembly");
        assertTrue(
                packageText.contains("./syntaxes/papyrus-assembly/papyrus-assembly.tmLanguage"),
                "VSIX must register the Papyrus Assembly TextMate grammar"
        );

        assertTrue(grammarText.contains("<string>pas</string>"), "Assembly grammar must recognize .pas files");
        assertTrue(
                grammarText.contains("<string>source.papyrus assembly</string>"),
                "Assembly grammar root scope must match tagged upstream"
        );
        assertTrue(
                grammarText.contains("<string>entity.name.function.papyrus assembly</string>"),
                "Assembly grammar function-name scope must match tagged upstream"
        );
    }

    private static String requiredVsixRootProperty() {
        String value = System.getProperty(VSIX_ROOT_PROPERTY);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing system property: " + VSIX_ROOT_PROPERTY);
        }
        return value;
    }

    private static String gitBlobSha1(String normalizedText) throws Exception {
        byte[] content = normalizedText.getBytes(StandardCharsets.UTF_8);
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + content.length + "\0").getBytes(StandardCharsets.UTF_8));
        digest.update(content);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String normalizeEol(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }
}
