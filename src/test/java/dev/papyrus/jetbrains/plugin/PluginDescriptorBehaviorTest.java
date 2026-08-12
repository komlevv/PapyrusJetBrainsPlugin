package dev.papyrus.jetbrains.plugin;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PluginDescriptorBehaviorTest {

    @Test
    void packagedDescriptorRegistersTheSafePapyrusSurfaceOnly() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("META-INF/plugin.xml")) {
            assertNotNull(input, "META-INF/plugin.xml must be packaged");
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input);
            String xml = document.getDocumentElement().getTextContent();

            assertTrue(hasAction(document, "Papyrus.SearchCreationKitWiki"));
            assertTrue(hasAction(document, "Papyrus.ViewAssembly"));
            assertTrue(hasAction(document, "Papyrus.GenerateSkyrimProject"));
            assertTrue(hasAction(document, "Papyrus.CompileProject"));
            assertTrue(hasAction(document, "Papyrus.ShowWelcome"));
            assertTrue(hasConfigurationType(document, "dev.papyrus.jetbrains.run.PapyrusProjectConfigurationType"));
            assertTrue(hasConfigurationType(document, "dev.papyrus.jetbrains.run.PapyrusAttachConfigurationType"));
            assertTrue(hasPapyrusProjectTaskRunner(document));
            assertTrue(hasPapyrusBuildConfigurable(document));
            assertTrue(hasPapyrusLanguageConfigurable(document));
            assertTrue(hasPapyrusDirectoryProjectGenerator(document));
            assertTrue(hasDependency(document, "com.intellij.modules.lsp"));
            assertTrue(hasDependency(document, "com.intellij.modules.ultimate"));
            assertFalse(hasLanguageProjectGenerator(document));
            assertFalse(hasAction(document, "Papyrus.AttachDebugger"));
            assertFalse(hasAction(document, "Papyrus.InstallDebugSupport"));
            assertTrue(hasPapyrusResourceBundle(document));
            assertTrue(configurablesUseResourceBundle(document));
            assertTrue(actionsUseResourceBundle(document));
            assertTrue(bundleContainsDescriptorKeys());
            assertFalse(xml.isBlank());
        }
    }


    private static boolean hasPapyrusResourceBundle(Document document) {
        var bundles = document.getElementsByTagName("resource-bundle");
        for (int index = 0; index < bundles.getLength(); index++) {
            if ("messages.PapyrusBundle".equals(bundles.item(index).getTextContent().trim())) return true;
        }
        return false;
    }

    private static boolean configurablesUseResourceBundle(Document document) {
        for (String tag : new String[]{"projectConfigurable", "applicationConfigurable"}) {
            var configurables = document.getElementsByTagName(tag);
            for (int index = 0; index < configurables.getLength(); index++) {
                var element = (org.w3c.dom.Element) configurables.item(index);
                if (!element.getAttribute("id").startsWith("dev.papyrus.intellij")) continue;
                if (!"settings.papyrus.display.name".equals(element.getAttribute("key"))) return false;
                if (!"messages.PapyrusBundle".equals(element.getAttribute("bundle"))) return false;
                if (element.hasAttribute("displayName")) return false;
            }
        }
        return true;
    }

    private static boolean actionsUseResourceBundle(Document document) {
        var actions = document.getElementsByTagName("action");
        int papyrusActions = 0;
        for (int index = 0; index < actions.getLength(); index++) {
            var element = (org.w3c.dom.Element) actions.item(index);
            if (!element.getAttribute("id").startsWith("Papyrus.")) continue;
            papyrusActions++;
            if (element.hasAttribute("text") || element.hasAttribute("description")) return false;
        }
        return papyrusActions == 5;
    }

    private static boolean bundleContainsDescriptorKeys() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = PluginDescriptorBehaviorTest.class.getClassLoader()
                .getResourceAsStream("messages/PapyrusBundle.properties")) {
            if (input == null) return false;
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        if (!properties.containsKey("settings.papyrus.display.name")) return false;
        for (String id : new String[]{
                "Papyrus.SearchCreationKitWiki",
                "Papyrus.ViewAssembly",
                "Papyrus.GenerateSkyrimProject",
                "Papyrus.CompileProject",
                "Papyrus.ShowWelcome"
        }) {
            if (!properties.containsKey("action." + id + ".text")
                    || !properties.containsKey("action." + id + ".description")) return false;
        }
        return true;
    }

    private static boolean hasPapyrusProjectTaskRunner(Document document) {
        String implementation = "dev.papyrus.jetbrains.run.PapyrusProjectTaskRunner";
        var runners = document.getElementsByTagName("projectTaskRunner");
        for (int index = 0; index < runners.getLength(); index++) {
            var element = (org.w3c.dom.Element) runners.item(index);
            if (implementation.equals(element.getAttribute("implementation"))) return true;
        }
        return false;
    }

    private static boolean hasPapyrusBuildConfigurable(Document document) {
        String id = "dev.papyrus.intellij.build.settings";
        var configurables = document.getElementsByTagName("projectConfigurable");
        for (int index = 0; index < configurables.getLength(); index++) {
            var element = (org.w3c.dom.Element) configurables.item(index);
            if (id.equals(element.getAttribute("id")) && "build.tools".equals(element.getAttribute("groupId"))) return true;
        }
        return false;
    }

    private static boolean hasPapyrusLanguageConfigurable(Document document) {
        String id = "dev.papyrus.intellij.settings";
        var configurables = document.getElementsByTagName("applicationConfigurable");
        for (int index = 0; index < configurables.getLength(); index++) {
            var element = (org.w3c.dom.Element) configurables.item(index);
            if (id.equals(element.getAttribute("id")) && "language".equals(element.getAttribute("groupId"))) return true;
        }
        return false;
    }

    private static boolean hasDependency(Document document, String id) {
        var dependencies = document.getElementsByTagName("depends");
        for (int index = 0; index < dependencies.getLength(); index++) {
            if (id.equals(dependencies.item(index).getTextContent().trim())) return true;
        }
        return false;
    }

    private static boolean hasLanguageProjectGenerator(Document document) {
        return document.getElementsByTagName("newProjectWizard.languageGenerator").getLength() > 0;
    }

    private static boolean hasPapyrusDirectoryProjectGenerator(Document document) {
        String implementation = "dev.papyrus.jetbrains.actions.PapyrusDirectoryProjectGenerator";
        var generators = document.getElementsByTagName("directoryProjectGenerator");
        for (int index = 0; index < generators.getLength(); index++) {
            var element = (org.w3c.dom.Element) generators.item(index);
            if (implementation.equals(element.getAttribute("implementation"))) return true;
        }
        return false;
    }

    private static boolean hasConfigurationType(Document document, String implementation) {
        var types = document.getElementsByTagName("configurationType");
        for (int index = 0; index < types.getLength(); index++) {
            var element = (org.w3c.dom.Element) types.item(index);
            if (implementation.equals(element.getAttribute("implementation"))) return true;
        }
        return false;
    }

    private static boolean hasAction(Document document, String id) {
        var actions = document.getElementsByTagName("action");
        for (int index = 0; index < actions.getLength(); index++) {
            var element = (org.w3c.dom.Element) actions.item(index);
            if (id.equals(element.getAttribute("id"))) return true;
        }
        return false;
    }
}
