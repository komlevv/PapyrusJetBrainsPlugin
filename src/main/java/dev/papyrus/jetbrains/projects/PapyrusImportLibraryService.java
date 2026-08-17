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
import com.intellij.openapi.roots.AdditionalLibraryRootsListener;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.SyntheticLibrary;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import dev.papyrus.jetbrains.PapyrusPluginVersion;
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
 * Maintains the immutable synthetic-library snapshot for local Papyrus imports.
 *
 * <p>PPJ imports are dependencies, so they are exposed through IntelliJ's official
 * {@link com.intellij.openapi.roots.AdditionalLibraryRootsProvider}/{@link SyntheticLibrary}
 * API instead of creating a persistent module library. A project-local directory may therefore
 * remain normal project content and simultaneously appear under External Libraries when it is
 * explicitly listed in PPJ {@code <Imports>}.</p>
 *
 * <p>The persisted module/library names are retained only as a one-version migration hook so
 * 0.2.173 can remove the managed module library created by 0.2.172 and earlier.</p>
 */
@Service(Service.Level.PROJECT)
@State(
        name = "dev.papyrus.intellij.projects.PapyrusImportLibrary",
        storages = @Storage("papyrus.xml")
)
public final class PapyrusImportLibraryService
        implements PersistentStateComponent<PapyrusImportLibraryService.SettingsState> {

    private static final Logger LOG = Logger.getInstance(PapyrusImportLibraryService.class);
    private static final String LIBRARY_DEBUG_NAME = "Papyrus PPJ imports";

    public static final class SettingsState {
        // Empty is intentional so legacy .idea/papyrus.xml state is rejected on load.
        public String pluginVersion = "";
        public String managedModuleName = "";
        public String managedLibraryName = "";
    }

    private final Project project;
    private final Object generationLock = new Object();
    private final Object syncExecutionLock = new Object();
    private long requestedGeneration;
    private volatile SettingsState state = currentDefaults();
    private volatile ManagedLibrary staleManagedLibrary;
    private volatile PapyrusImportSyntheticLibrary currentLibrary;

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
    public void loadState(@NotNull SettingsState loadedState) {
        if (loadedState.managedModuleName != null && !loadedState.managedModuleName.isBlank()
                && loadedState.managedLibraryName != null && !loadedState.managedLibraryName.isBlank()) {
            staleManagedLibrary = new ManagedLibrary(
                    loadedState.managedModuleName,
                    loadedState.managedLibraryName
            );
        }

        // Synthetic libraries do not need persistent ownership state. Always normalize the old
        // module-library fields away, including state written by 0.2.172.
        state = currentDefaults();
        currentLibrary = null;
    }

    public void discardStaleManagedLibraryAsync() {
        ManagedLibrary stale = staleManagedLibrary;
        if (stale == null || project.isDisposed()) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            synchronized (syncExecutionLock) {
                if (project.isDisposed()) {
                    return;
                }
                removeLegacyManagedLibrary(stale);
                if (stale.equals(staleManagedLibrary)) {
                    staleManagedLibrary = null;
                }
            }
        });
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
            PapyrusImportSyntheticLibrary nextLibrary = createLibrary(imports);

            synchronized (syncExecutionLock) {
                if (project.isDisposed() || !isCurrentGeneration(generation)) {
                    return;
                }

                PapyrusImportSyntheticLibrary previousLibrary = currentLibrary;
                if (librariesEqual(previousLibrary, nextLibrary)) {
                    return;
                }

                currentLibrary = nextLibrary;
                publishLibraryChange(previousLibrary, nextLibrary);
            }
        });
    }

    public boolean isImportFile(@NotNull VirtualFile file) {
        PapyrusImportSyntheticLibrary library = currentLibrary;
        return file.isValid() && library != null && library.contains(file);
    }

    @Nullable PapyrusImportSyntheticLibrary getCurrentLibrary() {
        return currentLibrary;
    }

    private boolean isCurrentGeneration(long generation) {
        synchronized (generationLock) {
            return generation == requestedGeneration;
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    private void publishLibraryChange(
            @Nullable PapyrusImportSyntheticLibrary previousLibrary,
            @Nullable PapyrusImportSyntheticLibrary nextLibrary
    ) {
        List<VirtualFile> oldRoots = sourceRoots(previousLibrary);
        List<VirtualFile> newRoots = sourceRoots(nextLibrary);

        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            WriteAction.run(() -> AdditionalLibraryRootsListener.fireAdditionalLibraryChanged(
                    project,
                    PapyrusImportSyntheticLibrary.PRESENTABLE_NAME,
                    oldRoots,
                    newRoots,
                    LIBRARY_DEBUG_NAME
            ));
        });
    }

    private static @NotNull List<VirtualFile> sourceRoots(@Nullable PapyrusImportSyntheticLibrary library) {
        return library == null ? List.of() : List.copyOf(library.getSourceRoots());
    }

    private static boolean librariesEqual(
            @Nullable PapyrusImportSyntheticLibrary first,
            @Nullable PapyrusImportSyntheticLibrary second
    ) {
        return first == null ? second == null : first.equals(second);
    }

    private static @Nullable PapyrusImportSyntheticLibrary createLibrary(@NotNull ImportCollection imports) {
        if (imports.roots().isEmpty()) {
            return null;
        }

        List<VirtualFile> roots = new ArrayList<>();
        Map<String, String> labelsByRootUrl = new LinkedHashMap<>();
        for (String rootPath : imports.roots()) {
            VirtualFile root = findOrRefreshDirectory(rootPath);
            if (root == null) {
                continue;
            }
            roots.add(root);
            String label = imports.labelsByPathKey().get(pathKey(rootPath));
            if (label != null && !label.isBlank()) {
                labelsByRootUrl.put(root.getUrl(), label);
            }
        }

        return roots.isEmpty() ? null : new PapyrusImportSyntheticLibrary(roots, labelsByRootUrl);
    }

    private void removeLegacyManagedLibrary(@NotNull ManagedLibrary managed) {
        Module module = ReadAction.computeBlocking(() -> findModuleByName(managed.moduleName()));
        if (module == null || module.isDisposed()) {
            state = currentDefaults();
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
            LOG.warn("Failed to remove legacy Papyrus import module library", exception);
            return;
        }
        state = currentDefaults();
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

    private static @NotNull SettingsState currentDefaults() {
        SettingsState defaults = new SettingsState();
        defaults.pluginVersion = PapyrusPluginVersion.CURRENT;
        return defaults;
    }

    static @NotNull List<String> collectLocalImportRoots(@NotNull ProjectInfos infos) {
        return collectLocalImports(infos).roots();
    }

    static @NotNull Map<String, String> collectLocalImportRootLabels(@NotNull ProjectInfos infos) {
        return collectLocalImports(infos).labelsByPathKey();
    }

    private static @NotNull ImportCollection collectLocalImports(@NotNull ProjectInfos infos) {
        Map<String, ImportRoot> unique = new LinkedHashMap<>();
        for (ProjectInfo projectInfo : infos.getProjects()) {
            for (ProjectInfoSourceInclude include : projectInfo.getSourceIncludes()) {
                if (!include.isImport() || include.isRemote()) {
                    continue;
                }
                String normalized = normalizeExistingDirectory(include.getFullPath());
                if (normalized == null) {
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

    private static @Nullable VirtualFile findOrRefreshDirectory(@NotNull String path) {
        LocalFileSystem fileSystem = LocalFileSystem.getInstance();
        VirtualFile file = fileSystem.findFileByPath(path);
        if (file == null) {
            file = fileSystem.refreshAndFindFileByPath(path);
        }
        return file != null && file.isDirectory() ? file : null;
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
