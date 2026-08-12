package dev.papyrus.jetbrains.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("UseOptimizedEelFunctions") // Archive behavior tests read only local temporary files.
final class PapyrusBundledVsixBehaviorTest {

    @TempDir
    Path temp;

    @Test
    void extractsPinnedVsixLayoutIntoAnIsolatedCache() throws Exception {
        byte[] archive = zip(
                entry("extension/package.json", "{}"),
                entry(
                        "extension/bin/Debug/net472/DarkId.Papyrus.Host.Skyrim/DarkId.Papyrus.Host.Skyrim.exe",
                        "host"
                )
        );
        String sha256 = sha256(archive);

        Path extensionRoot = PapyrusBundledVsix.extractArchiveForTests(
                new ByteArrayInputStream(archive),
                temp,
                sha256
        );

        assertTrue(Files.isRegularFile(extensionRoot.resolve("package.json")));
        assertTrue(Files.isRegularFile(extensionRoot.resolve(
                "bin/Debug/net472/DarkId.Papyrus.Host.Skyrim/DarkId.Papyrus.Host.Skyrim.exe"
        )));
        assertTrue(Files.isDirectory(extensionRoot.resolve("pyro/remote")));
        assertEquals("host", Files.readString(extensionRoot.resolve(
                "bin/Debug/net472/DarkId.Papyrus.Host.Skyrim/DarkId.Papyrus.Host.Skyrim.exe"
        )));
    }

    @Test
    void rejectsArchiveEntriesThatEscapeTheVendorCache() throws Exception {
        byte[] archive = zip(entry("../outside.txt", "unsafe"));

        assertThrows(
                IOException.class,
                () -> PapyrusBundledVsix.extractArchiveForTests(
                        new ByteArrayInputStream(archive),
                        temp,
                        sha256(archive)
                )
        );
        assertTrue(Files.notExists(temp.getParent().resolve("outside.txt")));
    }

    private static Entry entry(String name, String content) {
        return new Entry(name, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] zip(Entry... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name()));
                zip.write(entry.content());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Entry(String name, byte[] content) {
    }
}
