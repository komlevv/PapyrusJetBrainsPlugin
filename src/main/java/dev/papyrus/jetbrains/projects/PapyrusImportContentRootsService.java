package dev.papyrus.jetbrains.projects;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ContentEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ProjectFileIndex;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mirrors local Papyrus import directories into the IntelliJ project content model.
 *
 * <p>IntelliJ Platform 2026.2 native LSP support rejects files outside
 * {@link ProjectFileIndex#isInContent(VirtualFile)} before calling the plugin descriptor's
 * {@code isSupportedFile}. papyrus-lang can resolve an import target outside the IDE project,
 * but native LSP features stop working after that imported file becomes the active editor.
 * Making local Papyrus import directories content roots keeps native Definition, Hover,
 * Completion, References, diagnostics, and document synchronization available while editing
 * imported scripts.</p>
 *
 * <p>Only roots added by this service are recorded as managed. Existing user/IDE content roots
 * are never adopted and therefore never removed by Papyrus synchronization.</p>
 */
@Service(Service.Level.PROJECT)
@State(
        name = "dev.papyrus.intellij.projects.PapyrusImportContentRoots",
        storages = @Storage("papyrus.xml")
)
public final class PapyrusImportContentRootsService
        implements PersistentStateComponent<PapyrusImportContentRootsService.SettingsState> {

    private static final Logger LOG = Logger.getInstance(PapyrusImportContentRootsService.class);

    public static final class ManagedRootState {
        public String moduleName = "";
        public String path = "";
    }

    public static final class SettingsState {
        public List<ManagedRootState> managedRoots = new ArrayList<>();
    }

    private record ManagedRoot(@NotNull String moduleName, @NotNull String path) {
    }

    private final Project project;
    private final Object generationLock = new Object();
    private final Object syncExecutionLock = new Object();
    private long requestedGeneration;
    private volatile SettingsState state = new SettingsState();

    public PapyrusImportContentRootsService(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull PapyrusImportContentRootsService getInstance(@NotNull Project project) {
        return project.getService(PapyrusImportContentRootsService.class);
    }

    @Override
    public @NotNull SettingsState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull SettingsState state) {
        if (state.managedRoots == null) {
            state.managedRoots = new ArrayList<>();
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
            List<String> desiredRoots = collectLocalImportRoots(infos);
            synchronized (syncExecutionLock) {
                if (project.isDisposed() || !isCurrentGeneration(generation)) {
                    return;
                }
                syncNow(desiredRoots, infos);
            }
        });
    }

    private boolean isCurrentGeneration(long generation) {
        synchronized (generationLock) {
            return generation == requestedGeneration;
        }
    }

    private void syncNow(@NotNull List<String> desiredRoots, @NotNull ProjectInfos infos) {
        ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
        Map<String, Module> modulesByName = ReadAction.computeBlocking(this::modulesByName);
        Module preferredModule = ReadAction.computeBlocking(() -> findPreferredModule(infos, fileIndex));

        Map<String, ManagedRoot> managedByPath = new LinkedHashMap<>();
        for (ManagedRoot managed : readManagedRoots()) {
            managedByPath.put(pathKey(managed.path()), managed);
        }

        Set<String> desiredKeys = new LinkedHashSet<>();
        for (String root : desiredRoots) {
            desiredKeys.add(pathKey(root));
        }

        Map<Module, Set<String>> removals = new LinkedHashMap<>();
        List<ManagedRoot> retainedManaged = new ArrayList<>();
        Set<String> retainedKeys = new HashSet<>();

        for (ManagedRoot managed : managedByPath.values()) {
            String key = pathKey(managed.path());
            Module module = modulesByName.get(managed.moduleName());
            if (!desiredKeys.contains(key)) {
                if (module != null && ReadAction.computeBlocking(() -> hasExactContentEntry(module, managed.path()))) {
                    removals.computeIfAbsent(module, ignored -> new LinkedHashSet<>()).add(managed.path());
                }
                continue;
            }

            if (module != null && ReadAction.computeBlocking(() -> hasExactContentEntry(module, managed.path()))) {
                retainedManaged.add(managed);
                retainedKeys.add(key);
            }
        }

        applyRemovals(removals);

        Map<Module, Set<String>> additions = new LinkedHashMap<>();
        for (String root : desiredRoots) {
            String key = pathKey(root);
            if (retainedKeys.contains(key)) {
                continue;
            }

            VirtualFile directory = findOrRefreshDirectory(root);
            if (directory == null) {
                continue;
            }

            // If a user/IDE content root already covers this import, native LSP is already enabled.
            // Do not record it as Papyrus-managed ownership.
            if (ReadAction.computeBlocking(() -> fileIndex.isInContent(directory))) {
                continue;
            }

            Module module = preferredModule != null
                    ? preferredModule
                    : ReadAction.computeBlocking(() -> findFallbackModule(directory, fileIndex));
            if (module == null || module.isDisposed()) {
                LOG.warn("Cannot attach Papyrus import content root because no owning module was found: " + root);
                continue;
            }

            additions.computeIfAbsent(module, ignored -> new LinkedHashSet<>()).add(root);
        }

        List<ManagedRoot> addedManaged = applyAdditions(additions);
        retainedManaged.addAll(addedManaged);
        writeManagedRoots(retainedManaged);
    }

    private void applyRemovals(@NotNull Map<Module, Set<String>> removals) {
        for (Map.Entry<Module, Set<String>> entry : removals.entrySet()) {
            Module module = entry.getKey();
            Set<String> paths = entry.getValue();
            if (module.isDisposed() || paths.isEmpty()) {
                continue;
            }
            Set<String> keys = new HashSet<>();
            for (String path : paths) {
                keys.add(pathKey(path));
            }
            ModuleRootModificationUtil.updateModel(module, model -> {
                for (ContentEntry contentEntry : model.getContentEntries()) {
                    String entryPath = contentEntryPath(contentEntry);
                    if (entryPath != null && keys.contains(pathKey(entryPath))) {
                        model.removeContentEntry(contentEntry);
                    }
                }
            });
        }
    }

    private @NotNull List<ManagedRoot> applyAdditions(@NotNull Map<Module, Set<String>> additions) {
        List<ManagedRoot> added = new ArrayList<>();
        for (Map.Entry<Module, Set<String>> entry : additions.entrySet()) {
            Module module = entry.getKey();
            Set<String> paths = entry.getValue();
            if (module.isDisposed() || paths.isEmpty()) {
                continue;
            }
            List<String> addedPaths = new ArrayList<>();
            ModuleRootModificationUtil.updateModel(module, model -> {
                Set<String> existing = new HashSet<>();
                for (ContentEntry contentEntry : model.getContentEntries()) {
                    String entryPath = contentEntryPath(contentEntry);
                    if (entryPath != null) {
                        existing.add(pathKey(entryPath));
                    }
                }
                for (String path : paths) {
                    String key = pathKey(path);
                    if (!existing.contains(key)) {
                        model.addContentEntry(VfsUtilCore.pathToUrl(path));
                        existing.add(key);
                        addedPaths.add(path);
                    }
                }
            });
            for (String path : addedPaths) {
                added.add(new ManagedRoot(module.getName(), path));
            }
        }
        return added;
    }

    private @Nullable Module findPreferredModule(
            @NotNull ProjectInfos infos,
            @NotNull ProjectFileIndex fileIndex
    ) {
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
        if (modules.length == 1 && !modules[0].isDisposed()) {
            return modules[0];
        }
        return null;
    }

    private @Nullable Module findFallbackModule(
            @NotNull VirtualFile directory,
            @NotNull ProjectFileIndex fileIndex
    ) {
        Module direct = fileIndex.getModuleForFile(directory);
        if (direct != null && !direct.isDisposed()) {
            return direct;
        }

        String basePath = project.getBasePath();
        VirtualFile baseDirectory = basePath == null ? null : findDirectory(basePath);
        if (baseDirectory != null) {
            Module baseModule = fileIndex.getModuleForFile(baseDirectory);
            if (baseModule != null && !baseModule.isDisposed()) {
                return baseModule;
            }
        }

        Module[] modules = ModuleManager.getInstance(project).getModules();
        return modules.length == 1 && !modules[0].isDisposed() ? modules[0] : null;
    }

    private @NotNull Map<String, Module> modulesByName() {
        Map<String, Module> result = new HashMap<>();
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            if (!module.isDisposed()) {
                result.put(module.getName(), module);
            }
        }
        return result;
    }

    private static boolean hasExactContentEntry(@NotNull Module module, @NotNull String path) {
        String expected = pathKey(path);
        for (ContentEntry contentEntry : ModuleRootManager.getInstance(module).getContentEntries()) {
            String entryPath = contentEntryPath(contentEntry);
            if (entryPath != null && expected.equals(pathKey(entryPath))) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable String contentEntryPath(@NotNull ContentEntry contentEntry) {
        VirtualFile file = contentEntry.getFile();
        if (file != null) {
            return file.getPath();
        }
        String url = contentEntry.getUrl();
        return VfsUtilCore.urlToPath(url);
    }

    private static @Nullable VirtualFile findDirectory(@NotNull String path) {
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(path);
        return file != null && file.isDirectory() ? file : null;
    }

    private static @Nullable VirtualFile findOrRefreshDirectory(@NotNull String path) {
        LocalFileSystem fileSystem = LocalFileSystem.getInstance();
        VirtualFile file = fileSystem.findFileByPath(path);
        if (file == null) {
            file = fileSystem.refreshAndFindFileByPath(path);
        }
        return file != null && file.isDirectory() ? file : null;
    }

    static @NotNull List<String> collectLocalImportRoots(@NotNull ProjectInfos infos) {
        Map<String, String> unique = new LinkedHashMap<>();
        for (ProjectInfo projectInfo : infos.getProjects()) {
            for (ProjectInfoSourceInclude include : projectInfo.getSourceIncludes()) {
                if (!include.isImport() || include.isRemote()) {
                    continue;
                }
                String normalized = normalizeExistingDirectory(include.getFullPath());
                if (normalized != null) {
                    unique.putIfAbsent(pathKey(normalized), normalized);
                }
            }
        }

        List<String> candidates = new ArrayList<>(unique.values());
        candidates.sort(Comparator
                .comparingInt(PapyrusImportContentRootsService::pathDepth)
                .thenComparing(PapyrusImportContentRootsService::pathKey));

        List<String> collapsed = new ArrayList<>();
        for (String candidate : candidates) {
            boolean covered = false;
            for (String retained : collapsed) {
                if (isAncestorOrSame(retained, candidate)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                collapsed.add(candidate);
            }
        }
        return collapsed;
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

    private @NotNull List<ManagedRoot> readManagedRoots() {
        SettingsState snapshot = state;
        List<ManagedRoot> result = new ArrayList<>();
        if (snapshot.managedRoots == null) {
            return result;
        }
        for (ManagedRootState item : snapshot.managedRoots) {
            if (item == null || item.moduleName == null || item.moduleName.isBlank()
                    || item.path == null || item.path.isBlank()) {
                continue;
            }
            result.add(new ManagedRoot(item.moduleName, item.path));
        }
        return result;
    }

    private void writeManagedRoots(@NotNull List<ManagedRoot> managedRoots) {
        Map<String, ManagedRoot> unique = new LinkedHashMap<>();
        for (ManagedRoot root : managedRoots) {
            unique.putIfAbsent(pathKey(root.path()), root);
        }

        List<ManagedRootState> serialized = new ArrayList<>();
        for (ManagedRoot root : unique.values()) {
            ManagedRootState item = new ManagedRootState();
            item.moduleName = root.moduleName();
            item.path = root.path();
            serialized.add(item);
        }
        SettingsState newState = new SettingsState();
        newState.managedRoots = serialized;
        state = newState;
    }
}
