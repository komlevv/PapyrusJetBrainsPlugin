package dev.papyrus.jetbrains.projects;

import dev.papyrus.jetbrains.protocol.ProjectInfo;
import dev.papyrus.jetbrains.protocol.ProjectInfoScript;
import dev.papyrus.jetbrains.protocol.ProjectInfoSourceInclude;
import dev.papyrus.jetbrains.protocol.ProjectInfos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PapyrusScriptNavigatorModelBehaviorTest {

    @Test
    void navigatorUsesLspReportedTargetsAndRanksExactIdentifiersBeforeProvenanceMatches() {
        ProjectInfoScript localFeature = script("FeatureTarget", "C:/project/Scripts/FeatureTarget.psc");
        ProjectInfoScript importedFeature = script("FeatureTarget", "C:/game/Data/Scripts/FeatureTarget.psc");
        ProjectInfoScript importedHelper = script("DataHelper", "C:/game/Data/Scripts/DataHelper.psc");

        ProjectInfoSourceInclude local = include("project", "C:/project/Scripts", false, List.of(localFeature));
        ProjectInfoSourceInclude imported = include("Data", "C:/game/Data/Scripts", true, List.of(importedFeature, importedHelper));

        ProjectInfo runtime = new ProjectInfo();
        runtime.setName("runtime");
        runtime.setSourceIncludes(List.of(imported, local));

        ProjectInfos infos = new ProjectInfos();
        infos.setProjects(List.of(runtime));

        List<PapyrusScriptNavigatorModel.ScriptTarget> targets = PapyrusScriptNavigatorModel.targets(infos);
        assertTrue(PapyrusScriptNavigatorModel.hasScripts(infos));
        assertEquals(3, targets.size());

        List<PapyrusScriptNavigatorModel.ScriptTarget> exact = PapyrusScriptNavigatorModel.search(targets, "featuretarget", 10);
        assertEquals(2, exact.size());
        assertEquals("C:/project/Scripts/FeatureTarget.psc", exact.getFirst().filePath(), "Local exact match should sort before import duplicate");
        assertEquals("C:/game/Data/Scripts/FeatureTarget.psc", exact.get(1).filePath());

        List<PapyrusScriptNavigatorModel.ScriptTarget> provenance = PapyrusScriptNavigatorModel.search(targets, "data", 10);
        assertEquals(List.of("DataHelper", "FeatureTarget"), provenance.stream().map(PapyrusScriptNavigatorModel.ScriptTarget::identifier).toList());
        assertTrue(provenance.stream().allMatch(PapyrusScriptNavigatorModel.ScriptTarget::importSource));
        assertTrue(provenance.getFirst().toString().contains("runtime / Data: Scripts [import]"));

        assertEquals(1, PapyrusScriptNavigatorModel.search(targets, "", 1).size(), "Navigator must respect its bounded result limit");
    }

    private static ProjectInfoScript script(String identifier, String path) {
        ProjectInfoScript script = new ProjectInfoScript();
        script.setIdentifier(identifier);
        script.setFilePath(path);
        return script;
    }

    private static ProjectInfoSourceInclude include(
            String name,
            String path,
            boolean isImport,
            List<ProjectInfoScript> scripts
    ) {
        ProjectInfoSourceInclude include = new ProjectInfoSourceInclude();
        include.setName(name);
        include.setFullPath(path);
        include.setImport(isImport);
        include.setRemote(false);
        include.setScripts(scripts);
        return include;
    }
}
