package dev.papyrus.jetbrains.lsp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import dev.papyrus.jetbrains.projects.PapyrusProjectsService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.TestOnly;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Guards workspace changes that could make papyrus-lang rebuild its project graph.
 *
 * <p>Upstream reloads all PPJ projects for created/deleted PSC files. Sending that notification
 * directly would bypass local PPJ validation, so this bridge converts source-tree changes into a
 * guarded PPJ reload. Automatic source-tree updates never restart a still-busy LSP process; only a
 * second explicit user Refresh may request that validated recovery. PPJ edits themselves are marked
 * dirty and are never sent automatically to the server. The watcher uses the standard IntelliJ
 * {@link VirtualFileManager#VFS_CHANGES} topic; the listener only classifies lightweight VFS events
 * and schedules heavier validation/reload work outside the VFS callback.</p>
 */
@Service(Service.Level.PROJECT)
public final class PapyrusWorkspaceFileWatcher implements Disposable {

    private final Project project;
    private boolean started;
    private long relevantEventCount;
    private volatile String lastRelevantEvent = "<none>";

    public PapyrusWorkspaceFileWatcher(@NotNull Project project) {
        this.project = project;
    }

    public synchronized void ensureStarted() {
        if (started || project.isDisposed()) {
            return;
        }

        project.getMessageBus()
                .connect(this)
                .subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
                    @Override
                    public void after(@NotNull List<? extends VFileEvent> events) {
                        handleEvents(events);
                    }
                });
        started = true;
    }

    private void handleEvents(@NotNull List<? extends VFileEvent> events) {
        if (project.isDisposed()) {
            return;
        }

        boolean guardedReloadRequired = false;
        for (VFileEvent event : events) {
            guardedReloadRequired |= handleEvent(event);
        }

        if (guardedReloadRequired) {
            PapyrusProjectsService.getInstance(project).reloadFromWorkspaceChangeAsync();
        }
    }

    @TestOnly
    public synchronized boolean isStarted() {
        return started;
    }

    @TestOnly
    public synchronized long getRelevantEventCount() {
        return relevantEventCount;
    }

    @TestOnly
    public @NotNull String getLastRelevantEvent() {
        return lastRelevantEvent;
    }

    private boolean handleEvent(@NotNull VFileEvent event) {
        if (event instanceof VFilePropertyChangeEvent propertyChange && propertyChange.isRename()) {
            boolean reload = handlePathTransition(propertyChange.getOldPath(), propertyChange.getNewPath());
            return reload;
        }
        if (event instanceof VFileMoveEvent moveEvent) {
            return handlePathTransition(moveEvent.getOldPath(), moveEvent.getNewPath());
        }

        String path = event.getPath();
        if (!isProjectLocalEvent(event)) {
            return false;
        }

        String extension = getExtension(path);
        if ("ppj".equals(extension) || "psc".equals(extension) || "flg".equals(extension)) {
            recordRelevantEvent(event, path);
        }
        if ("ppj".equals(extension)) {
            // Editor Document changes already mark PPJs dirty before Ctrl+S. The subsequent VFS
            // save event represents the same edit and must not advance the editable revision again;
            // otherwise saving an already-applied in-memory Refresh would incorrectly make it DIRTY.
            if (!(event instanceof VFileContentChangeEvent) || !event.isFromSave()) {
                notifyPpjDirty(path);
            }
            return false;
        }

        if ("psc".equals(extension)) {
            return event instanceof VFileCreateEvent || event instanceof VFileDeleteEvent;
        }

        return "flg".equals(extension) && event instanceof VFileContentChangeEvent;
    }

    private boolean handlePathTransition(@NotNull String oldPath, @NotNull String newPath) {
        boolean oldLocal = isProjectLocalPath(oldPath);
        boolean newLocal = isProjectLocalPath(newPath);
        String oldExtension = getExtension(oldPath);
        String newExtension = getExtension(newPath);

        if (oldLocal && "ppj".equals(oldExtension)) {
            notifyPpjDirty(oldPath);
        }
        if (newLocal && "ppj".equals(newExtension)) {
            notifyPpjDirty(newPath);
        }

        return (oldLocal && "psc".equals(oldExtension))
                || (newLocal && "psc".equals(newExtension))
                || (oldLocal && "flg".equals(oldExtension))
                || (newLocal && "flg".equals(newExtension));
    }

    private synchronized void recordRelevantEvent(@NotNull VFileEvent event, @NotNull String path) {
        relevantEventCount++;
        lastRelevantEvent = event.getClass().getSimpleName()
                + ":" + path
                + ";fromSave=" + event.isFromSave()
                + ";fromRefresh=" + event.isFromRefresh();
    }

    private void notifyPpjDirty(@NotNull String path) {
        try {
            PapyrusProjectsService.getInstance(project).projectFileChanged(Path.of(path));
        } catch (RuntimeException ignored) {
            // A malformed VFS path must not make the background file watcher fail.
        }
    }


    private boolean isProjectLocalEvent(@NotNull VFileEvent event) {
        VirtualFile file = event.getFile();
        if (file != null && file.isValid()) {
            try {
                if (ProjectFileIndex.getInstance(project).isInContent(file)) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Fall back to the event path for transient VFS/index states.
            }
        }
        return isProjectLocalPath(event.getPath());
    }

    private boolean isProjectLocalPath(@NotNull String path) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return false;
        }

        try {
            return FileUtil.isAncestor(basePath, path, false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static @NotNull String getExtension(@NotNull String path) {
        int separatorIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex <= separatorIndex || dotIndex == path.length() - 1) {
            return "";
        }
        return path.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    @Override
    public void dispose() {
    }
}
