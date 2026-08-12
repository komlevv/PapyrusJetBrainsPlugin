package dev.papyrus.jetbrains.projects;

import dev.papyrus.jetbrains.protocol.ProjectInfo;
import dev.papyrus.jetbrains.protocol.ProjectInfoScript;
import dev.papyrus.jetbrains.protocol.ProjectInfoSourceInclude;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PapyrusProjectsPresentationBehaviorTest {

    @Test
    void separatesSourcesFromImportsAndKeepsPresentationDeterministic() {
        ProjectInfoSourceInclude sourceB = include("Source B", "C:\\Game\\Data\\SourceB", false, false);
        ProjectInfoSourceInclude sourceA = include("Source A", "C:\\Game\\Data\\SourceA", false, false);
        ProjectInfoSourceInclude remoteImport = include("Shared", "C:\\Mods\\SharedScripts", true, true);

        ProjectInfo project = new ProjectInfo();
        project.setSourceIncludes(List.of(sourceB, remoteImport, sourceA));

        assertEquals(List.of(sourceA, sourceB), PapyrusProjectsPresentation.localIncludes(project));
        assertEquals(List.of(remoteImport), PapyrusProjectsPresentation.importIncludes(project));
        assertEquals("Shared: SharedScripts [remote]", PapyrusProjectsPresentation.formatIncludeLabel(remoteImport));

        ProjectInfoScript zebra = script("Zebra");
        ProjectInfoScript alphaLower = script("alpha");
        ProjectInfoScript alphaUpper = script("Alpha");
        assertEquals(
                List.of(alphaUpper, alphaLower, zebra),
                PapyrusProjectsPresentation.sortedScripts(List.of(zebra, alphaLower, alphaUpper))
        );
    }


    @Test
    void groupsScriptsOnlyAboveTheBoundedTreeThreshold() {
        List<ProjectInfoScript> scripts = new ArrayList<>();
        for (int index = 399; index >= 0; index--) {
            scripts.add(script(String.format("Bulk%03d", index)));
        }

        assertFalse(PapyrusProjectsPresentation.requiresScriptGrouping(scripts));

        scripts.add(script("Bulk400"));
        assertTrue(PapyrusProjectsPresentation.requiresScriptGrouping(scripts));

        Map<String, List<ProjectInfoScript>> groups = PapyrusProjectsPresentation.groupScripts(scripts);
        assertEquals(List.of("B"), List.copyOf(groups.keySet()));
        assertEquals(401, groups.get("B").size());
        assertEquals("Bulk000", groups.get("B").getFirst().getIdentifier());
        assertEquals("Bulk400", groups.get("B").get(400).getIdentifier());
    }

    private static ProjectInfoSourceInclude include(
            String name,
            String fullPath,
            boolean isImport,
            boolean isRemote
    ) {
        ProjectInfoSourceInclude include = new ProjectInfoSourceInclude();
        include.setName(name);
        include.setFullPath(fullPath);
        include.setImport(isImport);
        include.setRemote(isRemote);
        return include;
    }

    private static ProjectInfoScript script(String identifier) {
        ProjectInfoScript script = new ProjectInfoScript();
        script.setIdentifier(identifier);
        return script;
    }
}
