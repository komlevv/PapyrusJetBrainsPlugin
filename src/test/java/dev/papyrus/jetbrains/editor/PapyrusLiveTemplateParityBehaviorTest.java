package dev.papyrus.jetbrains.editor;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("UseOptimizedEelFunctions") // Template parity reads local project/vendor fixtures only.
class PapyrusLiveTemplateParityBehaviorTest {
    private static final String UPSTREAM_BLOB_SHA1 = "e941f6a31cded7cd3e4e61be4f68765104ce98f9";

    @Test
    void commonTemplatesMatchPinnedUpstreamSnippetSnapshot() throws Exception {
        Path projectDir = Path.of(requiredProperty("papyrus.test.projectDir"));
        Path vsixRoot = Path.of(requiredProperty("papyrus.test.vsixRoot"));
        Path upstream = vsixRoot.resolve("snippets/papyrus/papyrus.json");
        Path templates = projectDir.resolve("src/main/resources/liveTemplates/Papyrus.xml");

        assertTrue(Files.isRegularFile(upstream), "Missing pinned upstream common snippet file: " + upstream);
        assertTrue(Files.isRegularFile(templates), "Missing JetBrains IDE Papyrus live-template file: " + templates);
        String upstreamText = normalizeEol(Files.readString(upstream, StandardCharsets.UTF_8));
        assertEquals(UPSTREAM_BLOB_SHA1, gitBlobSha1(upstreamText), "Unexpected papyrus.json snapshot under papyrus.test.vsixRoot");
        assertUpstreamNamesAndPrefixes(upstreamText);

        Map<String, TemplateSpec> expected = expectedTemplates();
        Map<String, Element> actual = readTemplates(templates);
        Properties bundle = readBundle();
        assertEquals(expected.keySet(), actual.keySet(), "JetBrains IDE template abbreviations must match all 20 upstream common prefixes");
        assertEquals(20, actual.size(), "Pinned upstream common snippet inventory");

        for (Map.Entry<String, TemplateSpec> entry : expected.entrySet()) {
            String prefix = entry.getKey();
            TemplateSpec spec = entry.getValue();
            Element template = actual.get(prefix);
            assertNotNull(template, "Missing JetBrains IDE template for upstream prefix " + prefix);
            assertEquals(spec.value(), normalizeEol(template.getAttribute("value")), prefix + " body");
            assertEquals(spec.variables(), readVariables(template), prefix + " variable order/defaults");
            assertEquals("false", template.getAttribute("toReformat"), prefix + " must preserve upstream whitespace");
            assertEquals("false", template.getAttribute("toShortenFQNames"), prefix + " must not rewrite Papyrus names");
            String descriptionKey = template.getAttribute("key");
            assertTrue(descriptionKey.startsWith("live.template.papyrus."),
                    prefix + " description must be localized through the plugin resource bundle");
            assertEquals("messages.PapyrusBundle", template.getAttribute("resource-bundle"),
                    prefix + " resource bundle");
            assertTrue(bundle.containsKey(descriptionKey), prefix + " localized description key must exist");
            assertPapyrusOnlyContext(template, prefix);
        }
    }

    private static Properties readBundle() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = PapyrusLiveTemplateParityBehaviorTest.class.getClassLoader()
                .getResourceAsStream("messages/PapyrusBundle.properties")) {
            assertNotNull(input, "PapyrusBundle.properties must be packaged");
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return properties;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing system property: " + name);
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

    private static void assertUpstreamNamesAndPrefixes(String source) {
        Map<String, String> names = upstreamNamesAndPrefixes();
        int previous = -1;
        for (Map.Entry<String, String> entry : names.entrySet()) {
            String nameToken = "\"" + entry.getKey() + "\"";
            int nameOffset = source.indexOf(nameToken, previous + 1);
            assertTrue(nameOffset > previous, "Missing or reordered upstream snippet name: " + entry.getKey());
            String prefixToken = "\"prefix\": \"" + entry.getValue() + "\"";
            int prefixOffset = source.indexOf(prefixToken, nameOffset);
            assertTrue(prefixOffset > nameOffset, "Unexpected upstream prefix for " + entry.getKey());
            previous = prefixOffset;
        }
    }

    private static Map<String, String> upstreamNamesAndPrefixes() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("Script", "script");
        result.put("Import", "import");
        result.put("Comment", "comment");
        result.put("Comment Inline", "comment inline");
        result.put("Comment Block", "comment block");
        result.put("Comment Region", "comment region");
        result.put("Property", "property auto");
        result.put("Property AutoReadOnly", "property auto readonly");
        result.put("Property Full", "property full");
        result.put("If", "if");
        result.put("Else If", "elseif");
        result.put("Else", "else");
        result.put("While", "while");
        result.put("For", "while for");
        result.put("For Each", "while for each");
        result.put("Function", "function");
        result.put("Function Full", "function full");
        result.put("Function Getter", "function getter");
        result.put("Function Setter", "function setter");
        result.put("State (Skyrim)", "state (Skyrim)");
        return result;
    }

    private static Map<String, Element> readTemplates(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element root = factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
        Map<String, Element> result = new LinkedHashMap<>();
        NodeList nodes = root.getElementsByTagName("template");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            String prefix = element.getAttribute("name");
            assertNull(result.put(prefix, element), "Duplicate template abbreviation: " + prefix);
        }
        return result;
    }

    private static List<VariableSpec> readVariables(Element template) {
        List<VariableSpec> result = new ArrayList<>();
        NodeList children = template.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && element.getTagName().equals("variable")) {
                result.add(new VariableSpec(
                        element.getAttribute("name"),
                        element.getAttribute("expression"),
                        element.getAttribute("defaultValue"),
                        element.getAttribute("alwaysStopAt")
                ));
            }
        }
        return result;
    }

    private static void assertPapyrusOnlyContext(Element template, String prefix) {
        NodeList contexts = template.getElementsByTagName("context");
        assertEquals(1, contexts.getLength(), prefix + " context count");
        Element context = (Element) contexts.item(0);
        Map<String, String> options = new LinkedHashMap<>();
        NodeList nodes = context.getElementsByTagName("option");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element option = (Element) nodes.item(i);
            options.put(option.getAttribute("name"), option.getAttribute("value"));
        }
        assertEquals(Map.of("PAPYRUS", "true", "OTHER", "false"), options, prefix + " context");
    }

    private static String normalizeEol(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static Map<String, TemplateSpec> expectedTemplates() {
        Map<String, TemplateSpec> result = new LinkedHashMap<>();
        result.put("script", spec(
                "Scriptname $NAME$ extends $BASE$\n{$DOC$}\n\n$END$",
                variable("NAME", "fileNameWithoutExtension()", "\"ScriptName\""),
                variable("BASE", "", "\"ScriptObject\""),
                variable("DOC", "", "\"The documentation string.\"")
        ));
        result.put("import", spec("import $SCRIPT$\n$END$", variable("SCRIPT", "", "\"scriptname\"")));
        result.put("comment", spec("; $FINAL$$END$", variable("FINAL", "", "\"comment\"")));
        result.put("comment inline", spec(";/ $FINAL$$END$ /;", variable("FINAL", "", "\"comment\"")));
        result.put("comment block", spec(";/\n\t$FINAL$$END$\n/;", variable("FINAL", "", "\"comment\"")));
        result.put("comment region", spec(
                "; $NAME$\n;---------------------------------------------\n\n$END$",
                variable("NAME", "", "\"Region\"")
        ));
        result.put("property auto", spec(
                "$TYPE$ Property $NAME$$END$ Auto",
                variable("TYPE", "", "\"type\""),
                variable("NAME", "", "\"propertyname\"")
        ));
        result.put("property auto readonly", spec(
                "$TYPE$ Property $NAME$$END$ AutoReadOnly$HIDDEN$",
                variable("TYPE", "", "\"type\""),
                variable("NAME", "", "\"propertyname\""),
                variable("HIDDEN", "", "\" Hidden\"")
        ));
        result.put("property full", spec(
                """
                $TYPE$ $NAME$Field$END$
                $TYPE$ Property $NAME$$HIDDEN$
                	$TYPE$ Function Get()
                		return $NAME$Field
                	EndFunction
                	Function Set($TYPE$ value)
                		$NAME$Field = value
                	EndFunction
                EndProperty""",
                variable("TYPE", "", "\"type\""),
                variable("NAME", "", "\"propertyname\""),
                variable("HIDDEN", "", "\" Hidden\"")
        ));
        result.put("if", spec(
                "If ($CONDITION$)\n\t$FINAL$$END$\nEndIf",
                variable("CONDITION", "", "\"true\""),
                variable("FINAL", "", "\"; code\"")
        ));
        result.put("elseif", spec(
                "ElseIf ($CONDITION$)\n\t$FINAL$$END$",
                variable("CONDITION", "", "\"true\""),
                variable("FINAL", "", "\"; code\"")
        ));
        result.put("else", spec("Else\n\t$FINAL$$END$", variable("FINAL", "", "\"; code\"")));
        result.put("while", spec(
                "While ($CONDITION$)\n\t$FINAL$$END$\nEndWhile",
                variable("CONDITION", "", "\"true\""),
                variable("FINAL", "", "\"; code\"")
        ));
        result.put("while for", spec(
                "int $INDEX$ = 0\nWhile ($INDEX$ < $SIZE$)\n\t$BODY$\t$END$\n\t$INDEX$ += 1\nEndWhile",
                variable("INDEX", "", "\"index\""),
                variable("SIZE", "", "\"size\""),
                variable("BODY", "", "\"; code\"")
        ));
        result.put("while for each", spec(
                "int $INDEX$ = 0\nWhile ($INDEX$ < $ARRAY$.Length)\n\t$TYPE$ item = $ARRAY$[$INDEX$]\n\t$BODY$$END$\n\t$INDEX$ += 1\nEndWhile",
                variable("INDEX", "", "\"index\""),
                variable("ARRAY", "", "\"array\""),
                variable("TYPE", "", "\"type\""),
                variable("BODY", "", "\"; code\"")
        ));
        result.put("function", spec(
                "Function $NAME$()\n\t$FINAL$$END$\nEndFunction",
                variable("NAME", "", "\"Foo\""),
                variable("FINAL", "", "\"; code\"")
        ));
        result.put("function full", spec(
                "$TYPE$ Function $NAME$($ARGTYPE$ $ARG$)\n\t$FINAL$$END$\n\treturn $RETURN$\nEndFunction",
                variable("TYPE", "", "\"var\""),
                variable("NAME", "", "\"Foobar\""),
                variable("ARGTYPE", "", "\"var\""),
                variable("ARG", "", "\"argument\""),
                variable("RETURN", "", "\"none\""),
                variable("FINAL", "", "\"; code\"")
        ));
        result.put("function getter", spec(
                "$TYPE$ Function $NAME$()\n\t$TYPE$ $VALUE$ = $INITIAL$\n\t$FINAL$$END$\n\treturn $VALUE$\nEndFunction",
                variable("TYPE", "", "\"var\""),
                variable("NAME", "", "\"GetFoo\""),
                variable("VALUE", "", "\"value\""),
                variable("INITIAL", "", "\"\\\"type\\\"\""),
                variable("FINAL", "", "\"; code\"")
        ));
        result.put("function setter", spec(
                "Function $NAME$($TYPE$ $ARG$)\n\t$FINAL$$END$\nEndFunction",
                variable("NAME", "", "\"SetFoo\""),
                variable("TYPE", "", "\"var\""),
                variable("ARG", "", "\"argument\""),
                variable("FINAL", "", "\"; code\"")
        ));
        result.put("state (Skyrim)", spec(
                """
                $AUTO$State $NAME$$END$
                		; Note: Parameterless state events are only supported in Skyrim.
                	Event OnBeginState()
                		$BEGIN_CODE$
                	EndEvent
                	Event OnEndState()
                		$END_CODE$
                	EndEvent
                EndState""",
                variable("AUTO", "", "\"Auto \""),
                variable("NAME", "", "\"statename\""),
                variable("BEGIN_CODE", "", "\"; code\""),
                variable("END_CODE", "", "\"; code\"")
        ));
        return result;
    }

    private static TemplateSpec spec(String value, VariableSpec... variables) {
        return new TemplateSpec(value, List.of(variables));
    }

    private static VariableSpec variable(String name, String expression, String defaultValue) {
        return new VariableSpec(name, expression, defaultValue, "true");
    }

    private record TemplateSpec(String value, List<VariableSpec> variables) {
    }

    private record VariableSpec(String name, String expression, String defaultValue, String alwaysStopAt) {
    }
}
