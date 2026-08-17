package dev.papyrus.jetbrains.projects;

import com.intellij.openapi.project.Project;
import dev.papyrus.jetbrains.PapyrusPluginVersion;
import dev.papyrus.jetbrains.protocol.ProjectInfo;
import dev.papyrus.jetbrains.protocol.ProjectInfoSourceInclude;
import dev.papyrus.jetbrains.protocol.ProjectInfos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PapyrusImportLibraryServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void syntheticLibraryUsesPapyrusImportsPresentation() {
        PapyrusImportSyntheticLibrary library = new PapyrusImportSyntheticLibrary(List.of(), Map.of());

        assertEquals("Papyrus Imports", library.getPresentableText());
        assertEquals(List.of(), List.copyOf(library.getSourceRoots()));
    }

    @Test
    void collectsEveryLocalImportAndCollapsesNestedRoots() throws Exception {
        Path imports = Files.createDirectories(temporaryDirectory.resolve("Imports"));
        Path nested = Files.createDirectories(imports.resolve("Nested"));
        Path second = Files.createDirectories(temporaryDirectory.resolve("Second"));
        Path source = Files.createDirectories(temporaryDirectory.resolve("Source"));

        ProjectInfo project = new ProjectInfo();
        project.setName("Test");
        project.setSourceIncludes(List.of(
                include("imports", imports, true, false),
                include("nested", nested, true, false),
                include("duplicate", imports, true, false),
                include("second", second, true, false),
                include("source", source, false, false),
                include("source", source, true, false),
                include("remote", temporaryDirectory.resolve("Remote"), true, true)
        ));

        ProjectInfos infos = new ProjectInfos();
        infos.setProjects(List.of(project));

        List<String> roots = PapyrusImportLibraryService.collectLocalImportRoots(infos);

        assertEquals(3, roots.size());
        assertEquals(imports.toRealPath().toString(), roots.get(0));
        assertEquals(second.toRealPath().toString(), roots.get(1));
        assertEquals(source.toRealPath().toString(), roots.get(2));

    }

    @Test
    void usesPapyrusProjectLabelsForExternalLibraryRoots() throws Exception {
        Path dataScripts = Files.createDirectories(temporaryDirectory.resolve("Data").resolve("Scripts"));
        Path raceMenuScripts = Files.createDirectories(temporaryDirectory.resolve("racemenu").resolve("scripts"));

        ProjectInfo project = new ProjectInfo();
        project.setName("Test");
        project.setSourceIncludes(List.of(
                include("Data", dataScripts, true, false),
                include("racemenu", raceMenuScripts, true, false)
        ));

        ProjectInfos infos = new ProjectInfos();
        infos.setProjects(List.of(project));

        Map<String, String> labels = PapyrusImportLibraryService.collectLocalImportRootLabels(infos);
        assertEquals(2, labels.size());
        assertEquals(
                List.of("Data: Scripts", "racemenu: scripts"),
                labels.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList()
        );
    }

    @Test
    void staleManagedLibraryStateIsNotReused() {
        Project project = (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> null
        );
        PapyrusImportLibraryService service = new PapyrusImportLibraryService(project);
        PapyrusImportLibraryService.SettingsState stale = new PapyrusImportLibraryService.SettingsState();
        stale.pluginVersion = "0.0.0-stale";
        stale.managedModuleName = "OldModule";
        stale.managedLibraryName = "Papyrus Imports";

        service.loadState(stale);

        assertEquals(PapyrusPluginVersion.CURRENT, service.getState().pluginVersion);
        assertEquals("", service.getState().managedModuleName);
        assertEquals("", service.getState().managedLibraryName);

        PapyrusImportLibraryService.SettingsState current = new PapyrusImportLibraryService.SettingsState();
        current.pluginVersion = PapyrusPluginVersion.CURRENT;
        current.managedModuleName = "CurrentModule";
        current.managedLibraryName = "Papyrus Imports";
        service.loadState(current);

        assertEquals(PapyrusPluginVersion.CURRENT, service.getState().pluginVersion);
        assertEquals("", service.getState().managedModuleName);
        assertEquals("", service.getState().managedLibraryName);
    }

    private static ProjectInfoSourceInclude include(
            String name,
            Path path,
            boolean isImport,
            boolean isRemote
    ) {
        ProjectInfoSourceInclude include = new ProjectInfoSourceInclude();
        include.setName(name);
        include.setFullPath(path.toString());
        include.setImport(isImport);
        include.setRemote(isRemote);
        return include;
    }
}
