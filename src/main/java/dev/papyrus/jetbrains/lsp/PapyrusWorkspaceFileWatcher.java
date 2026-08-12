package dev.papyrus.jetbrains.lsp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListenerBackgroundable;
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspServerState;
import kotlin.Unit;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service(Service.Level.PROJECT)
public final class PapyrusWorkspaceFileWatcher implements Disposable {

    private static final Set<String> WATCHED_EXTENSIONS = Set.of("psc", "ppj", "flg");

    private final Project project;
    private boolean started;

    public PapyrusWorkspaceFileWatcher(@NotNull Project project) {
        this.project = project;
    }

    public synchronized void ensureStarted() {
        if (started || project.isDisposed()) {
            return;
        }

        started = true;
        ApplicationManager.getApplication()
                .getMessageBus()
                .connect(this)
                .subscribe(VirtualFileManager.VFS_CHANGES_BG, new BulkFileListenerBackgroundable() {
                    @Override
                    public void after(@NotNull List<? extends VFileEvent> events) {
                        collectAndForwardEvents(events);
                    }
                });
    }

    private void collectAndForwardEvents(List<? extends VFileEvent> events) {
        if (project.isDisposed()) {
            return;
        }

        List<FileEvent> changedFiles = new ArrayList<>();
        for (VFileEvent event : events) {
            appendFileEvents(changedFiles, event);
        }

        if (changedFiles.isEmpty()) {
            return;
        }

        // VFS callbacks run under a write action. Keep LSP traffic outside that callback.
        ApplicationManager.getApplication().executeOnPooledThread(() -> forwardEvents(changedFiles));
    }

    private void appendFileEvents(List<FileEvent> changedFiles, VFileEvent event) {
        if (event instanceof VFilePropertyChangeEvent propertyChange && propertyChange.isRename()) {
            appendPathTransition(changedFiles, propertyChange.getOldPath(), propertyChange.getNewPath());
            return;
        }

        if (event instanceof VFileMoveEvent moveEvent) {
            appendPathTransition(changedFiles, moveEvent.getOldPath(), moveEvent.getNewPath());
            return;
        }

        FileChangeType changeType = getChangeType(event);
        if (changeType == null) {
            return;
        }

        String path = event.getPath();
        if (!isWatchedProjectPath(path)) {
            return;
        }

        changedFiles.add(new FileEvent(toFileUri(path), changeType));
    }

    private void appendPathTransition(List<FileEvent> changedFiles, String oldPath, String newPath) {
        if (isWatchedProjectPath(oldPath)) {
            changedFiles.add(new FileEvent(toFileUri(oldPath), FileChangeType.Deleted));
        }
        if (isWatchedProjectPath(newPath)) {
            changedFiles.add(new FileEvent(toFileUri(newPath), FileChangeType.Created));
        }
    }

    private void forwardEvents(List<FileEvent> changedFiles) {
        if (project.isDisposed()) {
            return;
        }

        DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(changedFiles);
        for (LspClient client : LspClientManager.getInstance(project)
                .getClients(PapyrusLspIntegrationProvider.class)) {
            if (client.getState() != LspServerState.Running) {
                continue;
            }
            client.sendNotification(server -> {
                server.getWorkspaceService().didChangeWatchedFiles(params);
                return Unit.INSTANCE;
            });
        }
    }

    private boolean isWatchedProjectPath(String path) {
        String extension = getExtension(path);
        if (!WATCHED_EXTENSIONS.contains(extension)) {
            return false;
        }

        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return false;
        }

        try {
            Path projectPath = Path.of(basePath).toAbsolutePath().normalize();
            Path eventPath = Path.of(path).toAbsolutePath().normalize();
            return eventPath.startsWith(projectPath);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String getExtension(String path) {
        int separatorIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex <= separatorIndex || dotIndex == path.length() - 1) {
            return "";
        }
        return path.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private static FileChangeType getChangeType(VFileEvent event) {
        if (event instanceof VFileCreateEvent) {
            return FileChangeType.Created;
        }
        if (event instanceof VFileDeleteEvent) {
            return FileChangeType.Deleted;
        }
        if (event instanceof VFileContentChangeEvent) {
            return FileChangeType.Changed;
        }
        return null;
    }

    private static String toFileUri(String path) {
        String uri = Path.of(path).toAbsolutePath().normalize().toUri().toASCIIString();
        String filePrefix = "file:///";
        if (uri.startsWith(filePrefix)
                && uri.length() > filePrefix.length() + 1
                && uri.charAt(filePrefix.length() + 1) == ':') {
            char drive = Character.toLowerCase(uri.charAt(filePrefix.length()));
            return filePrefix + drive + "%3A" + uri.substring(filePrefix.length() + 2);
        }
        return uri;
    }

    @Override
    public void dispose() {
    }
}
