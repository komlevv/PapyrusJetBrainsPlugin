package dev.papyrus.jetbrains.projects;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.impl.libraries.LibraryEx;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import dev.papyrus.jetbrains.protocol.ProjectInfo;
import dev.papyrus.jetbrains.protocol.ProjectInfoSourceInclude;
import dev.papyrus.jetbrains.protocol.ProjectInfos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mirrors local Papyrus import directories into a managed IntelliJ module library.
 *
 * <p>Papyrus imports are dependencies, not project source roots. They therefore belong under
 * External Libraries as {@link OrderRootType#SOURCES}. IntelliJ Platform 2026.2 native LSP
 * features only activate for project-content files, so navigation while an import file is the
 * active editor is handled separately by the Papyrus Go To Declaration bridge.</p>
 *
 * <p>This service only manages a library that it created and recorded in its own state. It does
 * not adopt or remove an unrelated user library with the same display name.</p>
 */
@Service(Service.Level.PROJECT)
@State(
        name = "dev.papyrus.intellij.projects.PapyrusImportLibrary",
        storages = @Storage("papyrus.xml")
)
public final class PapyrusImportLibraryService
        implements PersistentStateComponent<PapyrusImportLibraryService.SettingsState> {

    private static final Logger LOG = Logger.getInstance(PapyrusImportLibraryService.class);
    private static final String DEFAULT_LIBRARY_NAME = "Papyrus Imports";
    private static final String FALLBACK_LIBRARY_NAME = "Papyrus Imports (Papyrus Language)";

    public static final class SettingsState {
        public String managedModuleName = "";
        public String managedLibraryName = "";
    }

    private final Project project;
    private final Object generationLock = new Object();
    private final Object syncExecutionLock = new Object();
    private long requestedGeneration;
    private volatile SettingsState state = new SettingsState();
    private volatile List<String> currentImportRoots = List.of();
    private volatile Map<String, String> currentImportLabels = Map.of();

    public PapyrusImportLibraryService(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull PapyrusImportLibraryService getInstance(@NotNull Project project) {
        return project.getService(PapyrusImportLibraryService.class);
    }

    @Override
    public @NotNull SettingsState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull SettingsState state) {
        if (state.managedModuleName == null) {
            state.managedModuleName = "";
        }
        if (state.managedLibraryName == null) {
            state.managedLibraryName = "";
        }
        this.state = state;
    }

    public void clearAsync() {
        syncAsync(new ProjectInfos());
    }

    public void syncAsync(@NotNull ProjectInfos infos) {
        if (project.isDisposed()) {
            return;
        }

        long generation;
        synchronized (generationLock) {
            generation = ++requestedGeneration;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            ImportCollection imports = collectLocalImports(infos);
            synchronized (syncExecutionLock) {
                if (project.isDisposed() || !isCurrentGeneration(generation)) {
                    return;
                }
                syncNow(imports, infos);
            }
        });
    }

    public boolean isImportFile(@NotNull VirtualFile file) {
        if (!file.isValid()) {
            return false;
        }
        String filePath = file.getPath();
        for (String root : currentImportRoots) {
            if (isAncestorOrSame(root, filePath)) {
                return true;
            }
        }
        return false;
    }

    public @Nullable String getImportRootDisplayLabel(@NotNull VirtualFile file) {
        if (!file.isValid()) {
            return null;
        }
        return currentImportLabels.get(pathKey(file.getPath()));
    }

    private boolean isCurrentGeneration(long generation) {
        synchronized (generationLock) {
            return generation == requestedGeneration;
        }
    }

    private void syncNow(@NotNull ImportCollection imports, @NotNull ProjectInfos infos) {
        List<String> desiredRoots = imports.roots();
        currentImportRoots = List.copyOf(desiredRoots);
        currentImportLabels = Map.copyOf(imports.labelsByPathKey());

        ManagedLibrary managed = readManagedLibrary();
        Module desiredModule = desiredRoots.isEmpty()
                ? null
                : ReadAction.computeBlocking(() -> findPreferredModule(infos));

        if (managed != null && (desiredModule == null || !managed.moduleName().equals(desiredModule.getName()))) {
            removeManagedLibrary(managed);
            managed = null;
        }

        if (desiredRoots.isEmpty()) {
            if (managed != null) {
                removeManagedLibrary(managed);
            }
            clearManagedState();
            return;
        }

        if (desiredModule == null || desiredModule.isDisposed()) {
            LOG.warn("Cannot attach Papyrus import library because no owning module was found");
            return;
        }

        if (managed == null) {
            String libraryName = chooseLibraryName(desiredModule);
            managed = new ManagedLibrary(desiredModule.getName(), libraryName);
        }

        if (!syncManagedLibrary(desiredModule, managed.libraryName(), desiredRoots)) {
            return;
        }
        writeManagedState(managed);
    }

    private @NotNull String chooseLibraryName(@NotNull Module module) {
        return ReadAction.computeBlocking(() -> {
            for (OrderEntry entry : ModuleRootManager.getInstance(module).getOrderEntries()) {
                if (entry instanceof LibraryOrderEntry libraryEntry) {
                    Library library = libraryEntry.getLibrary();
                    if (library != null && DEFAULT_LIBRARY_NAME.equals(library.getName())) {
                        return FALLBACK_LIBRARY_NAME;
                    }
                }
            }
            return DEFAULT_LIBRARY_NAME;
        });
    }

    private boolean syncManagedLibrary(
            @NotNull Module module,
            @NotNull String libraryName,
            @NotNull List<String> desiredRoots
    ) {
        if (module.isDisposed()) {
            return false;
        }

        try {
            ModuleRootModificationUtil.updateModel(module, model -> {
                LibraryTable table = model.getModuleLibraryTable();
                Library library = table.getLibraryByName(libraryName);
                if (library == null) {
                    library = table.createLibrary(libraryName);
                }

                Library.ModifiableModel libraryModel = library.getModifiableModel();
                if (libraryModel instanceof LibraryEx.ModifiableModelEx extendedModel) {
                    if (extendedModel.getKind() != PapyrusImportLibraryType.KIND) {
                        extendedModel.setKind(PapyrusImportLibraryType.KIND);
                        extendedModel.setProperties(PapyrusImportLibraryType.KIND.createDefaultProperties());
                    }
                } else {
                    throw new IllegalStateException(
                            "Papyrus import library does not expose the IntelliJ extended library model"
                    );
                }

                for (String url : libraryModel.getUrls(OrderRootType.SOURCES)) {
                    libraryModel.removeRoot(url, OrderRootType.SOURCES);
                }
                for (String url : libraryModel.getUrls(OrderRootType.CLASSES)) {
                    libraryModel.removeRoot(url, OrderRootType.CLASSES);
                }
                for (String root : desiredRoots) {
                    libraryModel.addRoot(VfsUtilCore.pathToUrl(root), OrderRootType.SOURCES);
                }
                ApplicationManager.getApplication().invokeAndWait(
                        () -> WriteAction.run(libraryModel::commit)
                );
            });
            return true;
        } catch (RuntimeException exception) {
            LOG.warn("Failed to synchronize Papyrus import library", exception);
            return false;
        }
    }

    private void removeManagedLibrary(@NotNull ManagedLibrary managed) {
        Module module = ReadAction.computeBlocking(() -> findModuleByName(managed.moduleName()));
        if (module == null || module.isDisposed()) {
            clearManagedState();
            return;
        }

        try {
            ModuleRootModificationUtil.updateModel(module, model -> {
                LibraryTable table = model.getModuleLibraryTable();
                Library library = table.getLibraryByName(managed.libraryName());
                if (library != null) {
                    table.removeLibrary(library);
                }
            });
        } catch (RuntimeException exception) {
            LOG.warn("Failed to remove Papyrus import library", exception);
            return;
        }
        clearManagedState();
    }

    private @Nullable Module findPreferredModule(@NotNull ProjectInfos infos) {
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        String basePath = project.getBasePath();
        if (basePath != null && !basePath.isBlank()) {
            VirtualFile baseDirectory = findDirectory(basePath);
            if (baseDirectory != null) {
                Module module = fileIndex.getModuleForFile(baseDirectory);
                if (module != null && !module.isDisposed()) {
                    return module;
                }
            }
        }

        for (ProjectInfo projectInfo : infos.getProjects()) {
            for (ProjectInfoSourceInclude include : projectInfo.getSourceIncludes()) {
                if (include.isImport() || include.isRemote()) {
                    continue;
                }
                String fullPath = normalizeExistingDirectory(include.getFullPath());
                if (fullPath == null) {
                    continue;
                }
                VirtualFile sourceDirectory = findDirectory(fullPath);
                if (sourceDirectory == null) {
                    continue;
                }
                Module module = fileIndex.getModuleForFile(sourceDirectory);
                if (module != null && !module.isDisposed()) {
                    return module;
                }
            }
        }

        Module[] modules = ModuleManager.getInstance(project).getModules();
        return modules.length == 1 && !modules[0].isDisposed() ? modules[0] : null;
    }

    private @Nullable Module findModuleByName(@NotNull String name) {
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            if (!module.isDisposed() && module.getName().equals(name)) {
                return module;
            }
        }
        return null;
    }

    private record ManagedLibrary(@NotNull String moduleName, @NotNull String libraryName) {
    }

    private @Nullable ManagedLibrary readManagedLibrary() {
        SettingsState snapshot = state;
        if (snapshot.managedModuleName == null || snapshot.managedModuleName.isBlank()
                || snapshot.managedLibraryName == null || snapshot.managedLibraryName.isBlank()) {
            return null;
        }
        return new ManagedLibrary(snapshot.managedModuleName, snapshot.managedLibraryName);
    }

    private void writeManagedState(@NotNull ManagedLibrary managed) {
        SettingsState newState = new SettingsState();
        newState.managedModuleName = managed.moduleName();
        newState.managedLibraryName = managed.libraryName();
        state = newState;
    }

    private void clearManagedState() {
        state = new SettingsState();
    }

    private static @Nullable VirtualFile findDirectory(@NotNull String path) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
        return file != null && file.isDirectory() ? file : null;
    }

    static @NotNull List<String> collectLocalImportRoots(@NotNull ProjectInfos infos) {
        return collectLocalImports(infos).roots();
    }

    static @NotNull Map<String, String> collectLocalImportRootLabels(@NotNull ProjectInfos infos) {
        return collectLocalImports(infos).labelsByPathKey();
    }

    private static @NotNull ImportCollection collectLocalImports(@NotNull ProjectInfos infos) {
        Map<String, String> localSources = new LinkedHashMap<>();
        for (ProjectInfo projectInfo : infos.getProjects()) {
            for (ProjectInfoSourceInclude include : projectInfo.getSourceIncludes()) {
                if (include.isImport() || include.isRemote()) {
                    continue;
                }
                String normalized = normalizeExistingDirectory(include.getFullPath());
                if (normalized != null) {
                    localSources.putIfAbsent(pathKey(normalized), normalized);
                }
            }
        }

        Map<String, ImportRoot> unique = new LinkedHashMap<>();
        for (ProjectInfo projectInfo : infos.getProjects()) {
            for (ProjectInfoSourceInclude include : projectInfo.getSourceIncludes()) {
                if (!include.isImport() || include.isRemote()) {
                    continue;
                }
                String normalized = normalizeExistingDirectory(include.getFullPath());
                if (normalized == null || isCoveredByLocalSource(normalized, localSources.values())) {
                    continue;
                }

                String key = pathKey(normalized);
                unique.putIfAbsent(
                        key,
                        new ImportRoot(normalized, PapyrusProjectsPresentation.formatIncludeLabel(include))
                );
            }
        }

        List<ImportRoot> candidates = new ArrayList<>(unique.values());
        candidates.sort(Comparator
                .comparingInt((ImportRoot root) -> pathDepth(root.path()))
                .thenComparing(root -> pathKey(root.path())));

        List<ImportRoot> collapsed = new ArrayList<>();
        for (ImportRoot candidate : candidates) {
            boolean covered = false;
            for (ImportRoot retained : collapsed) {
                if (isAncestorOrSame(retained.path(), candidate.path())) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                collapsed.add(candidate);
            }
        }

        List<String> roots = collapsed.stream().map(ImportRoot::path).toList();
        Map<String, String> labels = new LinkedHashMap<>();
        for (ImportRoot root : collapsed) {
            labels.put(pathKey(root.path()), root.label());
        }
        return new ImportCollection(List.copyOf(roots), Map.copyOf(labels));
    }

    private static boolean isCoveredByLocalSource(
            @NotNull String importPath,
            @NotNull Iterable<String> localSources
    ) {
        for (String source : localSources) {
            if (isAncestorOrSame(source, importPath)) {
                return true;
            }
        }
        return false;
    }

    private record ImportRoot(@NotNull String path, @NotNull String label) {
    }

    private record ImportCollection(
            @NotNull List<String> roots,
            @NotNull Map<String, String> labelsByPathKey
    ) {
    }

    private static @Nullable String normalizeExistingDirectory(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (!Files.isDirectory(path)) {
                return null;
            }
            try {
                path = path.toRealPath();
            } catch (IOException ignored) {
                // The normalized absolute path is still usable by LocalFileSystem.
            }
            return path.toString();
        } catch (InvalidPathException exception) {
            LOG.debug("Ignoring invalid Papyrus import path: " + value, exception);
            return null;
        }
    }

    private static boolean isAncestorOrSame(@NotNull String ancestor, @NotNull String candidate) {
        try {
            Path ancestorPath = Path.of(ancestor).toAbsolutePath().normalize();
            Path candidatePath = Path.of(candidate).toAbsolutePath().normalize();
            if (!SystemInfo.isWindows) {
                return candidatePath.startsWith(ancestorPath);
            }

            String ancestorKey = pathKey(ancestorPath.toString());
            String candidateKey = pathKey(candidatePath.toString());
            if (candidateKey.equals(ancestorKey)) {
                return true;
            }
            String prefix = ancestorKey.endsWith("/") ? ancestorKey : ancestorKey + "/";
            return candidateKey.startsWith(prefix);
        } catch (InvalidPathException exception) {
            return false;
        }
    }

    private static int pathDepth(@NotNull String path) {
        try {
            return Path.of(path).toAbsolutePath().normalize().getNameCount();
        } catch (InvalidPathException exception) {
            return Integer.MAX_VALUE;
        }
    }

    private static @NotNull String pathKey(@NotNull String path) {
        String normalized = path.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return SystemInfo.isWindows ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }
}
