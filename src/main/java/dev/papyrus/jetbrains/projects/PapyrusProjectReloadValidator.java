package dev.papyrus.jetbrains.projects;

import org.jetbrains.annotations.NotNull;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs a bounded, read-only validation pass before a PPJ is allowed to affect the language server.
 *
 * <p>The successful result also contains a materialized PPJ snapshot. Local path-bearing values are
 * expanded and converted to absolute paths while preserving the original project structure and
 * attributes. The language server can therefore read the immutable snapshot from a private workspace
 * instead of re-reading the user-editable PPJ after validation.</p>
 */
final class PapyrusProjectReloadValidator {

    private static final Pattern VARIABLE_REFERENCE = Pattern.compile("@[A-Za-z_][A-Za-z0-9_]*");

    private PapyrusProjectReloadValidator() {
    }

    static @NotNull ValidationResult validate(@NotNull Path projectFile) {
        Path absolute = projectFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            return ValidationResult.failure(absolute, "project file does not exist", "Project: " + absolute);
        }

        try {
            byte[] projectBytes = Files.readAllBytes(absolute);
            String fingerprint = fingerprint(projectBytes);
            Document document = parse(projectBytes);
            Element root = document.getDocumentElement();
            String rootName = root != null && root.getLocalName() != null
                    ? root.getLocalName()
                    : root != null ? root.getNodeName() : "";
            if (!"PapyrusProject".equals(rootName)) {
                return ValidationResult.failure(
                        absolute,
                        "root element is invalid",
                        "Expected <PapyrusProject>.\nProject: " + absolute
                );
            }
            if (!"PapyrusProject.xsd".equals(root.getNamespaceURI())) {
                return ValidationResult.failure(
                        absolute,
                        "XML namespace is invalid",
                        "Expected xmlns=\"PapyrusProject.xsd\" on <PapyrusProject>.\nProject: " + absolute
                );
            }
            if (document.getElementsByTagNameNS("PapyrusProject.xsd", "Imports").getLength() == 0) {
                return ValidationResult.failure(
                        absolute,
                        "Imports section is missing",
                        "papyrus-lang requires an <Imports> element before it can construct project source includes.\nProject: " + absolute
                );
            }

            Map<String, String> variables = readVariables(document);
            Path baseDirectory = absolute.getParent();
            List<Path> localImports = new ArrayList<>();

            for (Element importElement : elements(document, "Import")) {
                String importValue = requiredText(importElement, "Import");
                String expanded = expand(importValue, variables, new ArrayDeque<>());
                ValidationResult unresolved = validateUnresolvedVariable(
                        absolute,
                        "Import",
                        importValue,
                        expanded,
                        variables
                );
                if (unresolved != null) {
                    return unresolved;
                }
                if (isRemoteImport(expanded)) {
                    importElement.setTextContent(expanded);
                    continue;
                }

                Path resolved = resolveLocalPath(baseDirectory, expanded);
                if (!Files.isDirectory(resolved)) {
                    return ValidationResult.failure(
                            absolute,
                            "import directory does not exist",
                            "Import: " + importValue + "\nResolved path (does not exist): " + resolved
                    );
                }
                localImports.add(resolved);
                importElement.setTextContent(resolved.toString());
            }

            for (Element folderElement : elements(document, "Folder")) {
                String folderValue = requiredText(folderElement, "Folder");
                String expanded = expand(folderValue, variables, new ArrayDeque<>());
                ValidationResult unresolved = validateUnresolvedVariable(
                        absolute,
                        "Folder",
                        folderValue,
                        expanded,
                        variables
                );
                if (unresolved != null) {
                    return unresolved;
                }

                Path resolved = resolveLocalPath(baseDirectory, expanded);
                if (!Files.isDirectory(resolved)) {
                    return ValidationResult.failure(
                            absolute,
                            "source folder does not exist",
                            "Folder: " + folderValue + "\nResolved path (does not exist): " + resolved
                    );
                }
                folderElement.setTextContent(resolved.toString());
            }

            for (Element scriptElement : elements(document, "Script")) {
                String scriptValue = requiredText(scriptElement, "Script");
                String expanded = expand(scriptValue, variables, new ArrayDeque<>());
                ValidationResult unresolved = validateUnresolvedVariable(
                        absolute,
                        "Script",
                        scriptValue,
                        expanded,
                        variables
                );
                if (unresolved != null) {
                    return unresolved;
                }
                scriptElement.setTextContent(resolveLocalPath(baseDirectory, expanded).toString());
            }

            if (root.hasAttribute("Output")) {
                String outputValue = root.getAttribute("Output");
                if (!outputValue.isBlank()) {
                    String expanded = expand(outputValue, variables, new ArrayDeque<>());
                    ValidationResult unresolved = validateUnresolvedVariable(
                            absolute,
                            "Output",
                            outputValue,
                            expanded,
                            variables
                    );
                    if (unresolved != null) {
                        return unresolved;
                    }
                    root.setAttribute("Output", resolveLocalPath(baseDirectory, expanded).toString());
                }
            }

            byte[] snapshotBytes = serialize(document);
            return ValidationResult.success(
                    absolute,
                    List.copyOf(localImports),
                    fingerprint,
                    snapshotBytes
            );
        } catch (ParserConfigurationException | SAXException exception) {
            return ValidationResult.failure(
                    absolute,
                    "XML is malformed",
                    readableMessage(exception)
            );
        } catch (IOException exception) {
            return ValidationResult.failure(
                    absolute,
                    "project file cannot be read",
                    readableMessage(exception)
            );
        } catch (InvalidPathException exception) {
            return ValidationResult.failure(
                    absolute,
                    "path is invalid",
                    readableMessage(exception)
            );
        } catch (IllegalArgumentException exception) {
            return ValidationResult.failure(
                    absolute,
                    "project configuration is invalid",
                    readableMessage(exception)
            );
        } catch (TransformerException exception) {
            return ValidationResult.failure(
                    absolute,
                    "validated snapshot cannot be created",
                    readableMessage(exception)
            );
        }
    }

    static @NotNull String fingerprint(@NotNull Path projectFile) throws IOException {
        return fingerprint(Files.readAllBytes(projectFile.toAbsolutePath().normalize()));
    }

    private static @NotNull String fingerprint(@NotNull byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static @NotNull Document parse(@NotNull byte[] projectBytes)
            throws ParserConfigurationException, IOException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(projectBytes));
    }

    private static byte @NotNull [] serialize(@NotNull Document document) throws TransformerException {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }

    private static @NotNull Map<String, String> readVariables(@NotNull Document document) {
        Map<String, String> variables = new LinkedHashMap<>();
        NodeList nodes = document.getElementsByTagNameNS("*", "Variable");
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            if (!(node instanceof Element element)) {
                continue;
            }
            String name = element.getAttribute("Name");
            String value = element.getAttribute("Value");
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("PPJ <Variable> is missing the Name attribute.");
            }
            String key = "@" + name;
            if (variables.containsKey(key)) {
                throw new IllegalArgumentException("PPJ contains duplicate variable " + key + ".");
            }
            variables.put(key, value != null ? value : "");
        }
        return variables;
    }

    private static @NotNull List<Element> elements(@NotNull Document document, @NotNull String localName) {
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        List<Element> elements = new ArrayList<>(nodes.getLength());
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private static @NotNull String requiredText(@NotNull Element element, @NotNull String localName) {
        String value = element.getTextContent();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PPJ <" + localName + "> path is empty.");
        }
        return value.trim();
    }

    private static @NotNull String expand(
            @NotNull String value,
            @NotNull Map<String, String> variables,
            @NotNull Deque<String> expansionStack
    ) {
        String expanded = value;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String variable = entry.getKey();
            if (!expanded.contains(variable)) {
                continue;
            }
            if (expansionStack.contains(variable)) {
                throw new IllegalArgumentException("Project has cyclical variable substitutions involving " + variable + ".");
            }

            expansionStack.push(variable);
            String replacement = expand(entry.getValue(), variables, expansionStack);
            expansionStack.pop();
            expanded = expanded.replace(variable, replacement);
        }
        return expanded;
    }

    private static ValidationResult validateUnresolvedVariable(
            @NotNull Path projectFile,
            @NotNull String elementName,
            @NotNull String original,
            @NotNull String expanded,
            @NotNull Map<String, String> variables
    ) {
        Matcher matcher = VARIABLE_REFERENCE.matcher(expanded);
        while (matcher.find()) {
            String variable = matcher.group();
            if (!variables.containsKey(variable)) {
                return ValidationResult.failure(
                        projectFile,
                        "variable is unresolved",
                        elementName + ": " + original + "\nUnknown variable: " + variable
                );
            }
        }
        return null;
    }

    private static @NotNull Path resolveLocalPath(Path baseDirectory, @NotNull String value) {
        String normalizedSeparators = value.replace('\\', java.io.File.separatorChar)
                .replace('/', java.io.File.separatorChar);
        Path configured = Path.of(normalizedSeparators);
        Path resolved = configured.isAbsolute()
                ? configured
                : baseDirectory.resolve(configured);
        return resolved.toAbsolutePath().normalize();
    }

    private static boolean isRemoteImport(@NotNull String value) {
        return value.startsWith("http");
    }

    private static @NotNull String readableMessage(@NotNull Exception exception) {
        String message = exception.getMessage();
        return message != null && !message.isBlank() ? message : exception.getClass().getSimpleName();
    }

    record ValidationResult(
            boolean valid,
            @NotNull Path projectFile,
            @NotNull List<Path> localImports,
            @NotNull String fingerprint,
            byte[] snapshotBytes,
            @NotNull String reason,
            @NotNull String details
    ) {
        static @NotNull ValidationResult success(
                @NotNull Path projectFile,
                @NotNull List<Path> localImports,
                @NotNull String fingerprint,
                byte[] snapshotBytes
        ) {
            return new ValidationResult(
                    true,
                    projectFile,
                    localImports,
                    fingerprint,
                    snapshotBytes.clone(),
                    "",
                    ""
            );
        }

        static @NotNull ValidationResult failure(
                @NotNull Path projectFile,
                @NotNull String reason,
                @NotNull String details
        ) {
            return new ValidationResult(false, projectFile, List.of(), "", new byte[0], reason, details);
        }

        public byte[] snapshotBytes() {
            return snapshotBytes.clone();
        }

        @NotNull String failureSummary() {
            return "PPJ validation failed: " + reason;
        }
    }
}
