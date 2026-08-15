package dev.papyrus.jetbrains.runtime;

import com.intellij.openapi.application.PathManager;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class PapyrusBundledVsix {

    public static final String VERSION = "v3.3.0-prerelease.1";
    public static final String SHA256 = "c4cf68d74471d4646b1c7dcff36f30293b507ebee215cc931cef051a0f8766db";

    private static final String RESOURCE_PATH = "/papyrus/vendor/" + VERSION + "/papyrus-lang-vscode.vsix";
    private static final String READY_MARKER = ".papyrus-vsix-ready";
    private static final String TEXTMATE_COMPATIBILITY_MARKER = ".papyrus-jetbrains-textmate-v1";
    private static volatile Path cachedExtensionRoot;

    private PapyrusBundledVsix() {
    }

    public static @NotNull Path getExtensionRoot() {
        Path cached = cachedExtensionRoot;
        if (cached != null && isReady(cached.getParent())) {
            return cached;
        }

        synchronized (PapyrusBundledVsix.class) {
            cached = cachedExtensionRoot;
            if (cached != null && isReady(cached.getParent())) {
                return cached;
            }

            Path cacheBase = PathManager.getSystemDir().resolve(Path.of("papyrus", "vendor"));
            Path target = cacheBase.resolve(VERSION + "-" + SHA256.substring(0, 16));
            if (!isReady(target)) {
                extractBundledArchive(cacheBase, target);
            }

            Path extensionRoot = target.resolve("extension");
            requireExtensionLayout(extensionRoot);
            cachedExtensionRoot = extensionRoot;
            return extensionRoot;
        }
    }

    static @NotNull Path extractArchiveForTests(
            @NotNull InputStream archive,
            @NotNull Path cacheBase,
            @NotNull String expectedSha256
    ) throws IOException {
        Path target = cacheBase.resolve("test-" + expectedSha256.substring(0, Math.min(16, expectedSha256.length())));
        extractArchive(archive, cacheBase, target, expectedSha256);
        return target.resolve("extension");
    }

    private static void extractBundledArchive(Path cacheBase, Path target) {
        try (InputStream stream = PapyrusBundledVsix.class.getResourceAsStream(RESOURCE_PATH)) {
            if (stream == null) {
                throw new IllegalStateException(
                        "Bundled papyrus-lang VSIX was not found in the plugin. Rebuild after copying the pinned VSIX into vendor/papyrus-lang/"
                                + VERSION + "/papyrus-lang-vscode.vsix."
                );
            }
            extractArchive(stream, cacheBase, target, SHA256);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to extract bundled papyrus-lang VSIX: " + exception.getMessage(), exception);
        }
    }

    private static void extractArchive(
            InputStream archive,
            Path cacheBase,
            Path target,
            String expectedSha256
    ) throws IOException {
        Files.createDirectories(cacheBase);
        Path staging = Files.createTempDirectory(cacheBase, ".papyrus-vsix-");
        Path archiveFile = staging.resolve("papyrus-lang-vscode.vsix");
        Path extracted = staging.resolve("extracted");
        try {
            String actualSha256 = copyAndHash(archive, archiveFile);
            if (!actualSha256.equalsIgnoreCase(expectedSha256)) {
                throw new IOException(
                        "Bundled papyrus-lang VSIX SHA-256 mismatch: " + actualSha256 + " (expected " + expectedSha256 + ")"
                );
            }

            Files.createDirectory(extracted);
            unzipSafely(archiveFile, extracted);
            Files.createDirectories(extracted.resolve("extension/pyro/remote"));
            Path extensionRoot = extracted.resolve("extension");
            requireExtensionLayout(extensionRoot);
            patchPapyrusTextMateGrammar(extensionRoot);
            Files.writeString(extracted.resolve(READY_MARKER), expectedSha256);
            Files.writeString(extracted.resolve(TEXTMATE_COMPATIBILITY_MARKER), "parameter-leading-comma-v1");

            if (isReady(target)) {
                return;
            }
            if (Files.exists(target)) {
                deleteTree(target);
            }
            moveDirectory(extracted, target);
        } finally {
            deleteTree(staging);
        }
    }

    private static String copyAndHash(InputStream source, Path destination) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }

        try (DigestInputStream input = new DigestInputStream(new BufferedInputStream(source), digest);
             BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(destination))) {
            input.transferTo(output);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void unzipSafely(Path archive, Path destination) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String entryName = entry.getName().replace('\\', '/');
                Path target = destination.resolve(entryName).normalize();
                if (!target.startsWith(destination) || Path.of(entryName).isAbsolute()) {
                    throw new IOException("Unsafe VSIX archive entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    private static boolean isReady(Path target) {
        return target != null
                && Files.isRegularFile(target.resolve(READY_MARKER))
                && Files.isRegularFile(target.resolve(TEXTMATE_COMPATIBILITY_MARKER))
                && Files.isRegularFile(target.resolve("extension/package.json"))
                && Files.isRegularFile(target.resolve(
                        "extension/bin/Debug/net472/DarkId.Papyrus.Host.Skyrim/DarkId.Papyrus.Host.Skyrim.exe"
                ))
                && Files.isDirectory(target.resolve("extension/pyro/remote"));
    }

    private static void requireExtensionLayout(Path extensionRoot) {
        if (!Files.isRegularFile(extensionRoot.resolve("package.json"))) {
            throw new IllegalStateException("Bundled papyrus-lang extension package.json is missing: " + extensionRoot);
        }
        if (!Files.isRegularFile(extensionRoot.resolve(
                "bin/Debug/net472/DarkId.Papyrus.Host.Skyrim/DarkId.Papyrus.Host.Skyrim.exe"
        ))) {
            throw new IllegalStateException("Bundled Skyrim Papyrus language server is missing: " + extensionRoot);
        }
        if (!Files.isDirectory(extensionRoot.resolve("pyro/remote"))) {
            throw new IllegalStateException("Bundled Papyrus remotes directory is missing: " + extensionRoot);
        }
        if (!Files.isRegularFile(extensionRoot.resolve("syntaxes/papyrus/papyrus.tmLanguage"))) {
            throw new IllegalStateException("Bundled Papyrus TextMate grammar is missing: " + extensionRoot);
        }
    }

    private static void patchPapyrusTextMateGrammar(Path extensionRoot) throws IOException {
        Path grammar = extensionRoot.resolve("syntaxes/papyrus/papyrus.tmLanguage");
        String source = Files.readString(grammar).replace("\r\n", "\n");
        String matchElement = "<string>\\G\\s*,</string>";
        int first = source.indexOf(matchElement);
        if (first < 0 || source.indexOf(matchElement, first + matchElement.length()) >= 0) {
            throw new IOException("Unexpected pinned Papyrus TextMate parameter-comma rule; JetBrains compatibility patch was not applied");
        }

        int lineStart = source.lastIndexOf('\n', first) + 1;
        String indent = source.substring(lineStart, first);
        String upstream = matchElement + "\n"
                + indent + "<key>name</key>\n"
                + indent + "<string>invalid.illegal.function.papyrus</string>";
        if (!source.startsWith(upstream, first)) {
            throw new IOException("Unexpected pinned Papyrus TextMate parameter-comma scope; JetBrains compatibility patch was not applied");
        }

        String jetBrains = "<string>(?&lt;=\\()\\s*(,)</string>\n"
                + indent + "<key>captures</key>\n"
                + indent + "<dict>\n"
                + indent + "    <key>1</key>\n"
                + indent + "    <dict>\n"
                + indent + "        <key>name</key>\n"
                + indent + "        <string>invalid.illegal.function.papyrus</string>\n"
                + indent + "    </dict>\n"
                + indent + "</dict>";
        Files.writeString(grammar, source.substring(0, first) + jetBrains + source.substring(first + upstream.length()));
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
