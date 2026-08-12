package dev.papyrus.jetbrains.projects;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.platform.lsp.api.LspClient;
import dev.papyrus.jetbrains.lsp.PapyrusLanguageService;
import dev.papyrus.jetbrains.protocol.ProjectInfos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service(Service.Level.PROJECT)
public final class PapyrusProjectsService {

    private static final Logger LOG = Logger.getInstance(PapyrusProjectsService.class);

    private final Project project;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Object refreshLock = new Object();

    private boolean refreshing;
    private boolean refreshRequested;
    private long invalidationGeneration;
    private volatile ProjectSnapshot currentSnapshot;

    private record ProjectSnapshot(@NotNull ProjectInfos infos, @NotNull LspClient client) {
    }

    public PapyrusProjectsService(@NotNull Project project) {
        this.project = project;
    }

    public static PapyrusProjectsService getInstance(@NotNull Project project) {
        return project.getService(PapyrusProjectsService.class);
    }

    public @Nullable ProjectInfos getCurrentSnapshot() {
        ProjectSnapshot snapshot = currentSnapshot;
        if (snapshot == null) {
            return null;
        }
        if (!PapyrusLanguageService.getInstance(project).isCurrentRunningClient(snapshot.client())) {
            return null;
        }
        return snapshot.infos();
    }

    public void addListener(@NotNull Runnable listener, @NotNull Disposable parentDisposable) {
        listeners.add(listener);
        Disposer.register(parentDisposable, () -> listeners.remove(listener));
    }

    public void invalidateSnapshot() {
        synchronized (refreshLock) {
            invalidationGeneration++;
            currentSnapshot = null;
        }
        notifyListeners();
    }

    public void projectsUpdated() {
        invalidateSnapshot();
        if (!listeners.isEmpty()) {
            refreshAsync();
        }
    }

    public void refreshAsync() {
        if (project.isDisposed()) {
            return;
        }

        boolean startWorker = false;
        synchronized (refreshLock) {
            refreshRequested = true;
            currentSnapshot = null;
            if (!refreshing) {
                refreshing = true;
                startWorker = true;
            }
        }
        notifyListeners();

        if (startWorker) {
            ApplicationManager.getApplication().executeOnPooledThread(this::runRefreshLoop);
        }
    }

    private void runRefreshLoop() {
        while (!project.isDisposed()) {
            long requestGeneration;
            synchronized (refreshLock) {
                refreshRequested = false;
                requestGeneration = invalidationGeneration;
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

            synchronized (refreshLock) {
                if (refreshRequested) {
                    continue;
                }
                PapyrusLanguageService languageService = PapyrusLanguageService.getInstance(project);
                if (requestGeneration == invalidationGeneration
                        && responseClient != null
                        && languageService.isCurrentRunningClient(responseClient)) {
                    currentSnapshot = new ProjectSnapshot(refreshed, responseClient);
                } else {
                    currentSnapshot = null;
                }
                refreshing = false;
            }
            notifyListeners();
            return;
        }

        synchronized (refreshLock) {
            refreshing = false;
            refreshRequested = false;
            currentSnapshot = null;
        }
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
