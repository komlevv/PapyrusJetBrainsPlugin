package dev.papyrus.jetbrains.status;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotifications;
import dev.papyrus.jetbrains.lsp.PapyrusLanguageService;
import dev.papyrus.jetbrains.protocol.DocumentScriptInfo;
import com.intellij.platform.lsp.api.LspClient;
import dev.papyrus.jetbrains.protocol.IdentifierFiles;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service(Service.Level.PROJECT)
public final class PapyrusScriptStatusService {

    private static final Logger LOG = Logger.getInstance(PapyrusScriptStatusService.class);
    private static final long SNAPSHOT_TTL_MS = 2_000L;

    public record ScriptStatus(boolean unresolved, boolean overridden, String overridingFile) {
    }

    private record StatusSnapshot(
            ScriptStatus status,
            long documentStamp,
            long validUntilMillis,
            @NotNull LspClient client
    ) {
    }

    private final Project project;
    private final Map<String, StatusSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicLong invalidationGeneration = new AtomicLong();
    private volatile boolean serverWasAvailable;

    public PapyrusScriptStatusService(@NotNull Project project) {
        this.project = project;
    }

    public static @NotNull PapyrusScriptStatusService getInstance(@NotNull Project project) {
        return project.getService(PapyrusScriptStatusService.class);
    }

    public @Nullable ScriptStatus getSnapshot(@NotNull VirtualFile file) {
        StatusSnapshot snapshot = validSnapshot(file);
        if (snapshot == null) {
            return null;
        }
        if (snapshot.validUntilMillis() < System.currentTimeMillis()) {
            snapshots.remove(key(file), snapshot);
            return null;
        }
        return snapshot.status();
    }

    /**
     * Returns the last still-document-valid status without applying the short refresh TTL.
     * This is intended for passive UI summaries such as the status-bar tooltip: they may
     * display the most recently verified state, but must not turn a UI repaint timer into
     * a repeated custom LSP request loop. Project/client/document invalidation still clears
     * the cached value through the same generation/client checks as the notification UI.
     */
    public @Nullable ScriptStatus getCachedSnapshot(@NotNull VirtualFile file) {
        StatusSnapshot snapshot = validSnapshot(file);
        return snapshot != null ? snapshot.status() : null;
    }

    private @Nullable StatusSnapshot validSnapshot(@NotNull VirtualFile file) {
        if (isRunningClientUnavailable()) {
            return null;
        }

        String key = key(file);
        StatusSnapshot snapshot = snapshots.get(key);
        if (snapshot == null) {
            return null;
        }

        PapyrusLanguageService languageService = PapyrusLanguageService.getInstance(project);
        long currentStamp = currentModificationStamp(file);
        if (snapshot.documentStamp() != currentStamp
                || !languageService.isCurrentRunningClient(snapshot.client())) {
            snapshots.remove(key, snapshot);
            return null;
        }
        return snapshot;
    }

    /**
     * Ensures that passive UI has at least one status for the current document/client,
     * without treating the short notification TTL as a reason to poll again.
     */
    public void scheduleRefreshIfMissing(@NotNull VirtualFile file) {
        if (getCachedSnapshot(file) != null) {
            return;
        }
        scheduleRefresh(file);
    }

    public void scheduleRefresh(@NotNull VirtualFile file) {
        if (project.isDisposed() || !file.isValid() || !"psc".equalsIgnoreCase(file.getExtension())) {
            return;
        }
        if (isRunningClientUnavailable()) {
            return;
        }
        if (getSnapshot(file) != null) {
            return;
        }

        String key = key(file);
        if (!inFlight.add(key)) {
            return;
        }
        long requestedStamp = currentModificationStamp(file);
        long requestedGeneration = invalidationGeneration.get();

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean updated = false;
            try {
                PapyrusLanguageService languageService = PapyrusLanguageService.getInstance(project);
                PapyrusLanguageService.ClientBoundResult<DocumentScriptInfo> response =
                        languageService.requestScriptInfoBound(file);
                if (response != null) {
                    DocumentScriptInfo info = response.value();
                    LspClient responseClient = response.client();
                    if (languageService.isCurrentRunningClient(responseClient)
                            && file.isValid()
                            && currentModificationStamp(file) == requestedStamp
                            && invalidationGeneration.get() == requestedGeneration) {
                        snapshots.put(
                                key,
                                new StatusSnapshot(
                                        toStatus(file, info),
                                        requestedStamp,
                                        System.currentTimeMillis() + SNAPSHOT_TTL_MS,
                                        responseClient
                                )
                        );
                        updated = true;
                    }
                }
            } catch (RuntimeException exception) {
                LOG.debug("Failed to refresh Papyrus script status for " + file.getPath(), exception);
            } finally {
                inFlight.remove(key);
                boolean invalidatedDuringRequest = invalidationGeneration.get() != requestedGeneration;
                if (invalidatedDuringRequest && !project.isDisposed() && file.isValid()) {
                    scheduleRefresh(file);
                } else if (updated) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!project.isDisposed() && file.isValid()) {
                            EditorNotifications.getInstance(project).updateNotifications(file);
                        }
                    });
                }
            }
        });
    }

    private boolean isRunningClientUnavailable() {
        boolean running = PapyrusLanguageService.getInstance(project).hasRunningClient();
        if (running) {
            serverWasAvailable = true;
            return false;
        }

        if (serverWasAvailable || !inFlight.isEmpty() || !snapshots.isEmpty()) {
            serverWasAvailable = false;
            invalidationGeneration.incrementAndGet();
            snapshots.clear();
        }
        return true;
    }

    public void invalidateAll() {
        invalidationGeneration.incrementAndGet();
        serverWasAvailable = false;
        snapshots.clear();
        if (!project.isDisposed()) {
            ApplicationManager.getApplication().invokeLater(
                    () -> EditorNotifications.getInstance(project).updateAllNotifications()
            );
        }
    }

    private static long currentModificationStamp(@NotNull VirtualFile file) {
        // This method is called from pooled threads. getDocument() requires a read lock,
        // while the cached document is sufficient to observe any unsaved editor changes.
        Document document = FileDocumentManager.getInstance().getCachedDocument(file);
        return document != null ? document.getModificationStamp() : file.getModificationStamp();
    }

    private static @NotNull ScriptStatus toStatus(@NotNull VirtualFile file, @NotNull DocumentScriptInfo info) {
        boolean unresolved = info.getIdentifiers().isEmpty();
        if (unresolved) {
            return new ScriptStatus(true, false, null);
        }

        String documentPath = normalizePath(file.getPath());
        boolean containsDocument = false;
        String overridingFile = null;
        for (IdentifierFiles identifierFiles : info.getIdentifierFiles()) {
            for (String candidate : identifierFiles.getFiles()) {
                if (candidate == null || candidate.isBlank()) {
                    continue;
                }
                if (overridingFile == null) {
                    overridingFile = candidate;
                }
                if (normalizePath(candidate).equalsIgnoreCase(documentPath)) {
                    containsDocument = true;
                }
            }
        }

        return new ScriptStatus(false, !containsDocument, overridingFile);
    }

    private static String key(VirtualFile file) {
        return normalizePath(file.getPath()).toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizePath(String value) {
        try {
            return Path.of(value).toAbsolutePath().normalize().toString();
        } catch (RuntimeException ignored) {
            return value.replace('/', '\\');
        }
    }
}
