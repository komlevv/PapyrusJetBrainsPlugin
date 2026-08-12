package dev.papyrus.jetbrains.actions;

import org.jetbrains.annotations.NotNull;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

public final class PapyrusProjectCompileSafety {
    private static final Set<String> FORBIDDEN_ELEMENTS = Set.of(
            "Variables",
            "Packages",
            "ZipFiles",
            "PreBuildEvent",
            "PostBuildEvent",
            "PreImportEvent",
            "PostImportEvent",
            "PreCompileEvent",
            "PostCompileEvent",
            "PreAnonymizeEvent",
            "PostAnonymizeEvent",
            "PrePackageEvent",
            "PostPackageEvent",
            "PreZipEvent",
            "PostZipEvent"
    );

    private PapyrusProjectCompileSafety() {
    }

    public static @NotNull Plan validate(@NotNull Path projectRoot, @NotNull Path projectFile) throws Exception {
        Path root = requireRealProjectDirectory(projectRoot);
        Path ppj = requireProjectFile(root, projectFile);
        String pyroProjectXml = loadPyroCompatibleSnapshot(ppj);
        Document document = parseSecureXml(pyroProjectXml);
        Element rootElement = document.getDocumentElement();
        if (rootElement == null || !"PapyrusProject".equals(localName(rootElement))) {
            throw new IllegalArgumentException("The selected file is not a PapyrusProject .ppj file.");
        }

        String game = rootElement.getAttribute("Game").trim();
        if (!game.isEmpty() && !"sse".equalsIgnoreCase(game)) {
            throw new IllegalArgumentException(
                    "Safe project compilation currently supports Skyrim Special Edition/Anniversary Edition only."
            );
        }

        rejectEnabledBoolean(rootElement, "Anonymize");
        rejectEnabledBoolean(rootElement, "Package");
        rejectEnabledBoolean(rootElement, "Zip");
        rejectUnsafeElementsAndRemotes(rootElement);

        String outputValue = rootElement.getAttribute("Output").trim();
        if (outputValue.isEmpty()) {
            throw new IllegalArgumentException(
                    "The Papyrus project must define an Output folder inside the IDE project."
            );
        }
        if (containsExpansionToken(outputValue)) {
            throw new IllegalArgumentException(
                    "The Papyrus project Output path must not contain variables or environment expansion."
            );
        }

        Path output;
        try {
            Path configured = Path.of(outputValue);
            output = configured.isAbsolute() ? configured.normalize() : ppj.getParent().resolve(configured).normalize();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("The Papyrus project Output path is invalid: " + outputValue, exception);
        }
        output = validateProjectOutput(root, output);
        return new Plan(ppj, ppj.getParent(), output, pyroProjectXml);
    }

    private static @NotNull Path requireRealProjectDirectory(@NotNull Path path) throws Exception {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("IDE project root must be a real directory: " + normalized);
        }
        return normalized.toRealPath();
    }

    private static @NotNull Path requireProjectFile(@NotNull Path root, @NotNull Path projectFile) throws Exception {
        Path normalized = projectFile.toAbsolutePath().normalize();
        if (!normalized.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ppj")) {
            throw new IllegalArgumentException("Select an existing .ppj file inside the IDE project.");
        }
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("Papyrus project file must be a real file: " + normalized);
        }
        Path real = normalized.toRealPath();
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException("Papyrus project file must be inside the IDE project: " + real);
        }
        return real;
    }

    @SuppressWarnings("UseOptimizedEelFunctions") // Safe compile accepts only local project files and intentionally avoids the experimental Eel API.
    private static @NotNull String loadPyroCompatibleSnapshot(@NotNull Path projectFile) throws Exception {
        byte[] bytes = Files.readAllBytes(projectFile);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                    "Papyrus project files must be UTF-8 because the bundled Pyro runtime reads PPJ files as UTF-8.",
                    exception
            );
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text.replaceFirst("\\A\\s*<\\?xml\\s+[^?]*\\?>", "");
    }

    private static @NotNull Document parseSecureXml(@NotNull String projectXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void warning(org.xml.sax.SAXParseException exception) throws org.xml.sax.SAXException {
                throw exception;
            }

            @Override
            public void error(org.xml.sax.SAXParseException exception) throws org.xml.sax.SAXException {
                throw exception;
            }

            @Override
            public void fatalError(org.xml.sax.SAXParseException exception) throws org.xml.sax.SAXException {
                throw exception;
            }
        });
        builder.setEntityResolver((publicId, systemId) -> {
            throw new org.xml.sax.SAXException("External XML entities are not allowed in Papyrus project files.");
        });
        return builder.parse(new ByteArrayInputStream(projectXml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void rejectEnabledBoolean(@NotNull Element root, @NotNull String attribute) {
        if ("true".equalsIgnoreCase(root.getAttribute(attribute).trim())) {
            throw new IllegalArgumentException(
                    "Safe project compilation does not allow " + attribute + " in the .ppj file."
            );
        }
    }

    private static void rejectUnsafeElementsAndRemotes(@NotNull Element root) {
        NodeList nodes = root.getElementsByTagNameNS("*", "*");
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (!(node instanceof Element element)) {
                continue;
            }
            String name = localName(element);
            if (FORBIDDEN_ELEMENTS.contains(name)) {
                throw new IllegalArgumentException(
                        "Safe project compilation does not allow <" + name + "> in the .ppj file."
                );
            }
            if ("Import".equals(name) || "Folder".equals(name)) {
                String value = element.getTextContent() == null ? "" : element.getTextContent().trim();
                String lower = value.toLowerCase(Locale.ROOT);
                if (lower.startsWith("http://") || lower.startsWith("https://")) {
                    throw new IllegalArgumentException(
                            "Safe project compilation does not allow remote Import or Folder paths: " + value
                    );
                }
                if (containsExpansionToken(value)) {
                    throw new IllegalArgumentException(
                            "Safe project compilation does not allow variable or environment expansion in Import or Folder paths: " + value
                    );
                }
            }
        }
    }

    private static boolean containsExpansionToken(@NotNull String value) {
        return value.indexOf('$') >= 0 || value.indexOf('%') >= 0;
    }

    private static @NotNull Path validateProjectOutput(@NotNull Path root, @NotNull Path output) throws Exception {
        Path absolute = output.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Papyrus project Output must stay inside the IDE project: " + absolute
            );
        }

        Path cursor = root;
        Path relative = root.relativize(absolute);
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException(
                        "Papyrus project Output must not pass through a symbolic link: " + cursor
                );
            }
        }

        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Papyrus project Output must be a real directory: " + absolute);
            }
            Path real = absolute.toRealPath();
            if (!real.startsWith(root)) {
                throw new IllegalArgumentException(
                        "Papyrus project Output resolves outside the IDE project: " + real
                );
            }
            if (!Files.isWritable(real)) {
                throw new IllegalArgumentException("Papyrus project Output is read-only: " + real);
            }
            return real;
        }

        Path existing = absolute.getParent();
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null || Files.isSymbolicLink(existing) || !Files.isDirectory(existing, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Papyrus project Output has no writable project parent: " + absolute);
        }
        Path realParent = existing.toRealPath();
        if (!realParent.startsWith(root) || !Files.isWritable(realParent)) {
            throw new IllegalArgumentException("Papyrus project Output parent is outside the writable project: " + realParent);
        }
        return absolute;
    }

    private static @NotNull String localName(@NotNull Element element) {
        String local = element.getLocalName();
        return local == null || local.isBlank() ? element.getTagName() : local;
    }

    public record Plan(
            @NotNull Path projectFile,
            @NotNull Path workingDirectory,
            @NotNull Path outputDirectory,
            @NotNull String pyroProjectXml
    ) {
    }
}
