package dev.papyrus.jetbrains.projects;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.platform.lsp.api.LspClient;
import dev.papyrus.jetbrains.lsp.PapyrusLanguageService;
import dev.papyrus.jetbrains.lsp.PapyrusWorkspaceFileWatcher;
import dev.papyrus.jetbrains.protocol.ProjectInfos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service(Service.Level.PROJECT)
public final class PapyrusProjectsService {

    private static final Logger LOG = Logger.getInstance(PapyrusProjectsService.class);

    public enum Phase {
        SERVER_NOT_READY,
        DIRTY,
        VALIDATING,
        RELOADING,
        SYNCHRONIZING,
        READY,
        VALIDATION_ERROR,
        SERVER_ERROR
    }

    public record Status(
            @NotNull Phase phase,
            @NotNull String summary,
            @NotNull String details,
            boolean showingLastKnownGood
    ) {
    }

    private final Project project;
    private final PapyrusProjectSnapshotStore snapshotStore;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Object refreshLock = new Object();

    private boolean refreshing;
    private boolean refreshRequested;
    private boolean sourceReloadQueued;
    private long reloadGeneration;
    private long editableProjectRevision;
    private long activeServerProjectRevision = -1;
    private Long pendingReloadGeneration;
    private PreparedServerSnapshot preparedServerSnapshot;
    private volatile Path lastChangedProjectFile;
    private volatile PapyrusProjectSnapshotStore.Snapshot activeServerSnapshot;
    private volatile Status configurationValidationError;
    private volatile ProjectSnapshot currentSnapshot;
    private volatile Status status = new Status(
            Phase.SERVER_NOT_READY,
            "Papyrus language server is not ready",
            "Open a Papyrus script or project file to start the language service.",
            false
    );

    private record ProjectSnapshot(@NotNull ProjectInfos infos) {
    }

    private record PreparedServerSnapshot(
            @NotNull PapyrusProjectSnapshotStore.Snapshot snapshot,
            long projectRevision
    ) {
    }

    public PapyrusProjectsService(@NotNull Project project) {
        this.project = project;
        this.snapshotStore = new PapyrusProjectSnapshotStore(project);
    }

    public static PapyrusProjectsService getInstance(@NotNull Project project) {
        PapyrusProjectsService service = project.getService(PapyrusProjectsService.class);
        project.getService(PapyrusWorkspaceFileWatcher.class).ensureStarted();
        return service;
    }

    /**
     * Returns the last successfully loaded project graph, even while a newer PPJ reload is pending
     * or has failed validation.
     */
    public @Nullable ProjectInfos getCurrentSnapshot() {
        ProjectSnapshot snapshot = currentSnapshot;
        return snapshot != null ? snapshot.infos() : null;
    }

    public @NotNull Status getStatus() {
        return status;
    }

    /**
     * True while a user-triggered or automatic project reload is actively validating, restarting,
     * waiting for papyrus/projectsUpdated, or synchronizing projectInfos. The Projects Refresh
     * control uses this to prevent duplicate reload requests.
     */
    public boolean isReloadInProgress() {
        synchronized (refreshLock) {
            return isReloadInProgressLocked();
        }
    }

    public void addListener(@NotNull Runnable listener, @NotNull Disposable parentDisposable) {
        listeners.add(listener);
        Disposer.register(parentDisposable, () -> listeners.remove(listener));
    }

    public void invalidateSnapshot() {
        synchronized (refreshLock) {
            reloadGeneration++;
            pendingReloadGeneration = null;
            preparedServerSnapshot = null;
            activeServerSnapshot = null;
            activeServerProjectRevision = -1;
            lastChangedProjectFile = null;
            configurationValidationError = null;
            sourceReloadQueued = false;
            refreshRequested = false;
            currentSnapshot = null;
            status = new Status(
                    Phase.SERVER_NOT_READY,
                    "Papyrus project information was invalidated",
                    "The language-service configuration changed. Waiting for a running Papyrus language server.",
                    false
            );
        }
        notifyListeners();
    }

    /**
     * The editable PPJ is never exposed directly to papyrus-lang. An editor-buffer or disk change
     * only marks the real configuration dirty; Refresh captures, validates, and publishes a new
     * immutable server snapshot.
     */
    public void projectFileChanged(@NotNull Path projectFile) {
        boolean notify;
        Path normalized = projectFile.toAbsolutePath().normalize();
        synchronized (refreshLock) {
            // The editable PPJ revision is independent from the immutable server generation.
            // An in-flight source reload remains safe because it reads activeServerSnapshot.
            // A validation pass is different: it is reading the editable PPJ, so a newer edit must
            // invalidate that pass immediately instead of leaving the service stuck in VALIDATING.
            Phase previousPhase = status.phase();
            if (previousPhase == Phase.VALIDATING) {
                reloadGeneration++;
            }
            editableProjectRevision++;
            notify = previousPhase != Phase.DIRTY || !normalized.equals(lastChangedProjectFile);
            lastChangedProjectFile = normalized;
            configurationValidationError = null;
            status = withLastGood(dirtyStatus());
        }
        if (notify) {
            notifyListeners();
        }
    }

    /**
     * Event from papyrus-lang for source-tree reloads performed against the immutable PPJ snapshot.
     */
    public void projectsUpdated() {
        synchronized (refreshLock) {
            Long confirmedGeneration = pendingReloadGeneration;
            pendingReloadGeneration = null;
            if (confirmedGeneration != null && confirmedGeneration != reloadGeneration) {
                LOG.debug("Ignoring stale Papyrus project reload confirmation for generation " + confirmedGeneration);
                return;
            }

            if (confirmedGeneration == null
                    && (status.phase() == Phase.DIRTY || status.phase() == Phase.VALIDATION_ERROR)) {
                LOG.debug("Ignoring unsolicited Papyrus project update while the editable PPJ is dirty or invalid");
                return;
            }

            status = withLastGood(new Status(
                    Phase.SYNCHRONIZING,
                    "Papyrus language server reloaded projects; synchronizing...",
                    "Reading papyrus/projectInfos from the confirmed immutable project snapshot.",
                    false
            ));
        }
        notifyListeners();
        refreshAsync();
    }

    /**
     * Called after a newly initialized LSP client has accepted the private validated workspace.
     */
    public void languageServerStarted() {
        synchronized (refreshLock) {
            reloadGeneration++;
            pendingReloadGeneration = null;
            if (configurationValidationError != null) {
                status = configurationValidationError;
            } else if (hasUnappliedProjectChangesLocked()) {
                status = withLastGood(dirtyStatus());
            } else {
                status = withLastGood(new Status(
                        Phase.SYNCHRONIZING,
                        "Papyrus language server started; loading projects...",
                        "Reading papyrus/projectInfos from the validated server workspace.",
                        false
                ));
            }
        }
        notifyListeners();
        refreshAsync();
    }

    /**
     * Called from the LSP descriptor on its background startup path. On a normal start this creates a
     * fresh immutable snapshot from the current real PPJs. If the current PPJ is invalid, the last
     * validated snapshot is reused; if none exists yet, a safe empty PPJ workspace is used.
     */
    public @NotNull Path prepareLanguageServerWorkspaceForStart() {
        PreparedServerSnapshot prepared;
        synchronized (refreshLock) {
            prepared = preparedServerSnapshot;
            preparedServerSnapshot = null;
            if (prepared != null) {
                activeServerSnapshot = prepared.snapshot();
                activeServerProjectRevision = prepared.projectRevision();
                configurationValidationError = null;
                return prepared.snapshot().workspaceRoot();
            }
        }

        long preparationRevision;
        synchronized (refreshLock) {
            preparationRevision = editableProjectRevision;
        }
        PapyrusProjectSnapshotStore.Preparation preparation = snapshotStore.prepareCurrentProject();
        PapyrusProjectSnapshotStore.Snapshot selected = preparation.snapshot();
        if (selected == null) {
            selected = snapshotStore.activeOrEmpty();
        }

        Status validationError = preparation.failure() != null
                ? validationErrorStatus(preparation, selected)
                : null;
        synchronized (refreshLock) {
            activeServerSnapshot = selected;
            activeServerProjectRevision = preparationRevision;
            configurationValidationError = validationError;
            if (validationError != null) {
                status = validationError;
            } else if (hasUnappliedProjectChangesLocked()) {
                status = withLastGood(dirtyStatus());
            }
        }
        if (validationError != null) {
            notifyListeners();
        }
        return selected.workspaceRoot();
    }

    /**
     * Workspace root reported back if papyrus-lang requests workspace/workspaceFolders after startup.
     */
    public @NotNull Path getLanguageServerWorkspaceRoot() {
        PapyrusProjectSnapshotStore.Snapshot snapshot = activeServerSnapshot;
        if (snapshot != null) {
            return snapshot.workspaceRoot();
        }
        return snapshotStore.activeOrEmpty().workspaceRoot();
    }

    /**
     * User-facing Refresh. Validation and snapshot materialization happen before the current server is
     * touched. A successful PPJ change restarts only the Papyrus LSP on the immutable generation.
     */
    public void reloadFromProjectFilesAsync() {
        if (project.isDisposed()) {
            return;
        }

        long generation;
        long projectRevision;
        synchronized (refreshLock) {
            if (isReloadInProgressLocked()) {
                LOG.debug("Ignoring duplicate Papyrus Projects Refresh while reload is already in progress: " + status.phase());
                return;
            }
            generation = ++reloadGeneration;
            projectRevision = editableProjectRevision;
            status = withLastGood(new Status(
                    Phase.VALIDATING,
                    "Validating Papyrus project files...",
                    "The running language server continues using the last validated snapshot until this validation succeeds.",
                    false
            ));
        }
        notifyListeners();
        ApplicationManager.getApplication().executeOnPooledThread(() -> validateSnapshotAndRestart(generation, projectRevision));
    }

    private void validateSnapshotAndRestart(long generation, long projectRevision) {
        PapyrusProjectSnapshotStore.Preparation preparation = snapshotStore.prepareCurrentProject();
        if (preparation.noProjectFiles()) {
            setReloadFailure(
                    generation,
                    Phase.VALIDATION_ERROR,
                    "PPJ validation failed: no project-local .ppj files were found",
                    "Refresh only reloads validated PPJ files physically contained by the IDE project."
            );
            return;
        }
        if (preparation.failure() != null) {
            Status error = validationErrorStatus(
                    preparation,
                    preparation.snapshot() != null ? preparation.snapshot() : snapshotStore.activeOrEmpty()
            );
            synchronized (refreshLock) {
                if (generation != reloadGeneration || project.isDisposed()) {
                    return;
                }
                configurationValidationError = error;
                status = error;
            }
            notifyListeners();
            return;
        }

        PapyrusProjectSnapshotStore.Snapshot snapshot = preparation.snapshot();
        if (snapshot == null) {
            setReloadFailure(
                    generation,
                    Phase.SERVER_ERROR,
                    "Failed to prepare validated Papyrus project snapshot",
                    "Validation succeeded but no immutable server workspace was published."
            );
            return;
        }

        synchronized (refreshLock) {
            if (generation != reloadGeneration
                    || projectRevision != editableProjectRevision
                    || project.isDisposed()) {
                return;
            }
            pendingReloadGeneration = null;
            preparedServerSnapshot = new PreparedServerSnapshot(snapshot, projectRevision);
            configurationValidationError = null;
            status = withLastGood(new Status(
                    Phase.RELOADING,
                    "Restarting Papyrus language server with validated projects...",
                    "Validated " + snapshot.projectCount() + " PPJ file(s). The server will read only the immutable validated snapshot; edits to the original PPJ cannot race this reload.",
                    false
            ));
        }
        notifyListeners();

        try {
            PapyrusLanguageService.getInstance(project).restartClients();
        } catch (RuntimeException exception) {
            LOG.warn("Failed to restart Papyrus language server with validated PPJ snapshot", exception);
            setReloadFailure(
                    generation,
                    Phase.SERVER_ERROR,
                    "Failed to restart Papyrus language server",
                    readableMessage(exception)
            );
        }
    }

    /**
     * Automatic source-tree changes reload against the already-published immutable PPJ. A dirty or
     * invalid editable PPJ does not block this path: the server keeps using the last-known-good
     * immutable generation, so source discovery can stay current without applying unvalidated
     * configuration. Reloads are queued only while another validation/reload/synchronization is active.
     */
    public void reloadFromWorkspaceChangeAsync() {
        if (project.isDisposed()) {
            return;
        }

        Path trigger = null;
        boolean restartForAmbient = false;
        long generation;
        synchronized (refreshLock) {
            // DIRTY and VALIDATION_ERROR describe only the editable PPJ. They must not freeze
            // source discovery: the running server is isolated on activeServerSnapshot, so a
            // .psc/.flg change can safely rebuild that last-known-good configuration without ever
            // exposing the unvalidated PPJ. Only an in-progress validation/reload/synchronization
            // needs serialization; queue one source reload behind it.
            if (status.phase() == Phase.VALIDATING
                    || pendingReloadGeneration != null
                    || status.phase() == Phase.RELOADING
                    || status.phase() == Phase.SYNCHRONIZING) {
                sourceReloadQueued = true;
                return;
            }

            PapyrusProjectSnapshotStore.Snapshot snapshot = activeServerSnapshot;
            if (snapshot == null) {
                snapshot = snapshotStore.active();
            }
            if (snapshot == null) {
                return;
            }

            generation = ++reloadGeneration;
            trigger = snapshot.triggerProjectFile();
            if (trigger == null) {
                preparedServerSnapshot = new PreparedServerSnapshot(snapshot, editableProjectRevision);
                restartForAmbient = true;
                status = withLastGood(new Status(
                        Phase.RELOADING,
                        "Restarting Papyrus language server after source-tree change...",
                        "No PPJ reload trigger exists in the safe workspace, so only the Papyrus language server will be restarted.",
                        false
                ));
            } else {
                pendingReloadGeneration = generation;
                status = withLastGood(new Status(
                        Phase.RELOADING,
                        "Reloading Papyrus projects after source-tree change...",
                        "The reload trigger is an immutable validated PPJ snapshot. Waiting for papyrus/projectsUpdated.",
                        false
                ));
            }
        }
        notifyListeners();

        PapyrusLanguageService languageService = PapyrusLanguageService.getInstance(project);
        if (restartForAmbient) {
            try {
                languageService.restartClients();
            } catch (RuntimeException exception) {
                LOG.warn("Failed to restart Papyrus language server after source-tree change", exception);
                setReloadFailure(generation, Phase.SERVER_ERROR, "Failed to restart Papyrus language server", readableMessage(exception));
            }
            return;
        }

        boolean sent;
        try {
            sent = languageService.notifyProjectSaved(trigger);
        } catch (RuntimeException exception) {
            LOG.warn("Failed to send immutable Papyrus project reload trigger", exception);
            sent = false;
        }
        if (!sent) {
            synchronized (refreshLock) {
                if (pendingReloadGeneration != null && pendingReloadGeneration == generation) {
                    pendingReloadGeneration = null;
                }
            }
            setReloadFailure(
                    generation,
                    Phase.SERVER_ERROR,
                    "Failed to request Papyrus source-tree reload",
                    "The immutable PPJ snapshot is valid, but its reload notification could not be sent to a running language server."
            );
        }
    }

    /**
     * Refreshes the current server snapshot only. This is used after server lifecycle events and
     * papyrus/projectsUpdated; it does not expose the editable PPJ to the server.
     */
    public void refreshAsync() {
        if (project.isDisposed()) {
            return;
        }

        boolean startWorker = false;
        synchronized (refreshLock) {
            refreshRequested = true;
            if (!refreshing) {
                refreshing = true;
                startWorker = true;
            }
        }

        if (startWorker) {
            ApplicationManager.getApplication().executeOnPooledThread(this::runRefreshLoop);
        }
    }

    private void runRefreshLoop() {
        while (!project.isDisposed()) {
            long requestGeneration;
            synchronized (refreshLock) {
                refreshRequested = false;
                requestGeneration = reloadGeneration;
            }

            ProjectInfos refreshed = null;
            LspClient responseClient = null;
            try {
                PapyrusLanguageService languageService = PapyrusLanguageService.getInstance(project);
                PapyrusLanguageService.ClientBoundResult<ProjectInfos> response = languageService.requestProjectInfosBound();
                if (response != null) {
                    refreshed = response.value();
                    responseClient = response.client();
                }
            } catch (RuntimeException throwable) {
                LOG.warn("Failed to refresh Papyrus project information", throwable);
            }

            boolean acceptedSnapshot = false;
            synchronized (refreshLock) {
                if (refreshRequested) {
                    continue;
                }

                PapyrusLanguageService languageService = PapyrusLanguageService.getInstance(project);
                if (requestGeneration != reloadGeneration) {
                    refreshing = false;
                    return;
                }

                if (responseClient != null
                        && refreshed != null
                        && languageService.isCurrentRunningClient(responseClient)) {
                    currentSnapshot = new ProjectSnapshot(refreshed);
                    acceptedSnapshot = true;
                    if (configurationValidationError != null) {
                        status = configurationValidationError;
                    } else if (hasUnappliedProjectChangesLocked()) {
                        status = withLastGood(dirtyStatus());
                    } else {
                        status = new Status(
                                Phase.READY,
                                "Papyrus projects loaded",
                                "The displayed project graph is synchronized with the running language server and its validated PPJ snapshot.",
                                false
                        );
                    }
                } else {
                    boolean running = languageService.hasRunningClient();
                    status = withLastGood(new Status(
                            running ? Phase.SERVER_ERROR : Phase.SERVER_NOT_READY,
                            running
                                    ? "Failed to read Papyrus project information"
                                    : "Papyrus language server is not running",
                            running
                                    ? "papyrus/projectInfos returned no usable response. Check the Papyrus Projects Output tab for server errors."
                                    : "The last successfully loaded project graph is preserved. Start or restart the Papyrus language server to synchronize it.",
                            false
                    ));
                }
                refreshing = false;
            }

            if (acceptedSnapshot) {
                PapyrusImportLibraryService.getInstance(project).syncAsync(refreshed);
            }
            notifyListeners();
            if (acceptedSnapshot) {
                drainQueuedSourceReload();
            }
            return;
        }

        synchronized (refreshLock) {
            refreshing = false;
            refreshRequested = false;
        }
    }

    private void drainQueuedSourceReload() {
        boolean run;
        synchronized (refreshLock) {
            Phase phase = status.phase();
            run = sourceReloadQueued
                    && (phase == Phase.READY
                    || phase == Phase.DIRTY
                    || phase == Phase.VALIDATION_ERROR);
            sourceReloadQueued = false;
        }
        if (run) {
            reloadFromWorkspaceChangeAsync();
        }
    }


    private boolean isReloadInProgressLocked() {
        Phase phase = status.phase();
        return phase == Phase.VALIDATING
                || phase == Phase.RELOADING
                || phase == Phase.SYNCHRONIZING;
    }

    private boolean hasUnappliedProjectChangesLocked() {
        return activeServerProjectRevision != editableProjectRevision;
    }

    private @NotNull Status dirtyStatus() {
        Path changed = lastChangedProjectFile;
        String details = changed != null
                ? changed + "\nRefresh uses the current unsaved editor contents when present; Ctrl+S is not required."
                : "The editable PPJ changed after the language server snapshot was prepared.\nRefresh uses the current unsaved editor contents when present; Ctrl+S is not required.";
        return new Status(
                Phase.DIRTY,
                "Papyrus project file changed — click Refresh to validate and reload",
                details,
                false
        );
    }

    private @NotNull Status validationErrorStatus(
            @NotNull PapyrusProjectSnapshotStore.Preparation preparation,
            @NotNull PapyrusProjectSnapshotStore.Snapshot selectedSnapshot
    ) {
        PapyrusProjectReloadValidator.ValidationResult failure = preparation.failure();
        if (failure == null) {
            throw new IllegalArgumentException("Preparation has no validation failure");
        }
        boolean fallback = selectedSnapshot.projectCount() > 0;
        String details = "Project: " + preparation.failureProject();
        if (!failure.details().isBlank()) {
            details += "\n" + failure.details();
        }
        details += fallback
                ? "\nThe language server will keep using the last validated PPJ snapshot until this error is fixed."
                : "\nNo validated PPJ snapshot exists yet. The language server will use a safe empty PPJ workspace until this error is fixed.";
        return new Status(
                Phase.VALIDATION_ERROR,
                failure.failureSummary(),
                details,
                fallback
        );
    }

    private void setReloadFailure(
            long generation,
            @NotNull Phase phase,
            @NotNull String summary,
            @NotNull String details
    ) {
        synchronized (refreshLock) {
            if (generation != reloadGeneration || project.isDisposed()) {
                return;
            }
            status = withLastGood(new Status(phase, summary, details, false));
        }
        notifyListeners();
    }

    private @NotNull Status withLastGood(@NotNull Status candidate) {
        return new Status(
                candidate.phase(),
                candidate.summary(),
                candidate.details(),
                candidate.showingLastKnownGood() || currentSnapshot != null
        );
    }

    private static @NotNull String readableMessage(@NotNull Exception exception) {
        String message = exception.getMessage();
        return message != null && !message.isBlank() ? message : exception.getClass().getSimpleName();
    }

    private void notifyListeners() {
        if (project.isDisposed()) {
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            for (Runnable listener : listeners) {
                listener.run();
            }
        });
    }
}
