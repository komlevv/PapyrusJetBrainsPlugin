package dev.papyrus.jetbrains.projects;

import com.intellij.openapi.roots.OrderRootType;
import dev.papyrus.jetbrains.protocol.ProjectInfo;
import dev.papyrus.jetbrains.protocol.ProjectInfoSourceInclude;
import dev.papyrus.jetbrains.protocol.ProjectInfos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class PapyrusImportLibraryServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void libraryTypeExposesSourceRootsAsExternalDependencies() {
        assertArrayEquals(
                new OrderRootType[]{OrderRootType.SOURCES},
                new PapyrusImportLibraryType().getExternalRootTypes()
        );
    }

    @Test
    void collectsOnlyLocalImportsAndCollapsesNestedRoots() throws Exception {
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

        assertEquals(2, roots.size());
        assertEquals(imports.toRealPath().toString(), roots.get(0));
        assertEquals(second.toRealPath().toString(), roots.get(1));

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
