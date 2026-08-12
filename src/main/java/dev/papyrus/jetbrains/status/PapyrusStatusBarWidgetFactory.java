package dev.papyrus.jetbrains.status;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ide.HelpTooltipKt;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.platform.lsp.api.LspServerState;
import com.intellij.ui.components.JBLabel;
import dev.papyrus.jetbrains.config.PapyrusSettings;
import dev.papyrus.jetbrains.config.PapyrusSettingsConfigurable;
import dev.papyrus.jetbrains.lsp.PapyrusLspIntegrationProvider;
import dev.papyrus.jetbrains.runtime.PapyrusLaunchConfigurationResolver;
import dev.papyrus.jetbrains.runtime.PapyrusLaunchReadiness;
import org.eclipse.lsp4j.InitializeResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PapyrusStatusBarWidgetFactory implements StatusBarWidgetFactory {

    public static final String ID = "PapyrusStatus";

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Papyrus Language Server";
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new PapyrusStatusBarWidget(project);
    }

    static @Nullable String statusTextForStates(@NotNull Collection<LspServerState> states) {
        StatusPresentation presentation = statusPresentationFor(PapyrusLaunchReadiness.ready(), states);
        return presentation == null ? null : presentation.text();
    }

    static @Nullable StatusPresentation statusPresentationFor(
            @NotNull PapyrusLaunchReadiness readiness,
            @NotNull Collection<LspServerState> states
    ) {
        return statusPresentationFor(readiness, StatusDetails.minimal(states));
    }

    static @Nullable StatusPresentation statusPresentationFor(
            @NotNull PapyrusLaunchReadiness readiness,
            @NotNull StatusDetails details
    ) {
        Collection<LspServerState> states = details.states();
        switch (readiness.kind()) {
            case DISABLED:
                return null;
            case MISSING_GAME:
                return new StatusPresentation(
                        "Papyrus: game missing",
                        readiness.detail(),
                        ClickAction.SETTINGS
                );
            case COMPILER_MISSING:
                return new StatusPresentation(
                        "Papyrus: compiler missing",
                        readiness.detail() + " Check the Creation Kit installation or Settings | Papyrus.",
                        ClickAction.SETTINGS
                );
            case ERROR:
                return new StatusPresentation(
                        "Papyrus: error",
                        "Papyrus runtime configuration error: " + readiness.detail(),
                        states.isEmpty() ? ClickAction.NONE : ClickAction.OUTPUT
                );
            case READY:
                break;
        }

        if (states.isEmpty()) {
            return null;
        }
        if (states.contains(LspServerState.Running)) {
            return new StatusPresentation(
                    "Papyrus: running",
                    enrichTooltip("Papyrus language service is running.", details),
                    ClickAction.OUTPUT
            );
        }
        if (states.contains(LspServerState.Initializing)) {
            return new StatusPresentation(
                    "Papyrus: starting",
                    enrichTooltip("Papyrus language service is starting.", details),
                    ClickAction.OUTPUT
            );
        }
        if (states.contains(LspServerState.ShutdownUnexpectedly)) {
            return new StatusPresentation(
                    "Papyrus: error",
                    enrichTooltip("Papyrus language service stopped unexpectedly.", details),
                    ClickAction.OUTPUT
            );
        }
        return new StatusPresentation(
                "Papyrus: idle",
                enrichTooltip("Papyrus language service is not running.", details),
                ClickAction.OUTPUT
        );
    }

    private static @NotNull String enrichTooltip(@NotNull String base, @NotNull StatusDetails details) {
        StringBuilder tooltip = new StringBuilder(base);
        if (details.serverName() != null && !details.serverName().isBlank()) {
            tooltip.append(" Server: ").append(details.serverName());
            if (details.serverVersion() != null && !details.serverVersion().isBlank()) {
                tooltip.append(' ').append(details.serverVersion());
            }
            tooltip.append('.');
        }
        if (details.clientCount() > 1) {
            tooltip.append(" Clients: ").append(details.clientCount()).append('.');
        }
        if (!details.workspaceRoots().isEmpty()) {
            tooltip.append(details.workspaceRoots().size() == 1 ? " Workspace root: " : " Workspace roots: ");
            tooltip.append(String.join(", ", details.workspaceRoots())).append('.');
        }
        if (details.activeFileName() != null && !details.activeFileName().isBlank()) {
            tooltip.append(" Current file: ").append(details.activeFileName()).append('.');
        }
        switch (details.scriptActivity()) {
            case CHECKING -> tooltip.append(" Script status: checking.");
            case RESOLVED -> tooltip.append(" Script status: resolved.");
            case UNRESOLVED -> tooltip.append(" Script status: unresolved.");
            case OVERRIDDEN -> tooltip.append(" Script status: overridden.");
            case NONE -> {
            }
        }
        return tooltip.toString();
    }

    enum ClickAction {
        NONE,
        OUTPUT,
        SETTINGS
    }

    enum ScriptActivity {
        NONE,
        CHECKING,
        RESOLVED,
        UNRESOLVED,
        OVERRIDDEN
    }

    record StatusDetails(
            @NotNull Collection<LspServerState> states,
            int clientCount,
            @Nullable String serverName,
            @Nullable String serverVersion,
            @NotNull List<String> workspaceRoots,
            @Nullable String activeFileName,
            @NotNull ScriptActivity scriptActivity
    ) {
        StatusDetails {
            states = List.copyOf(states);
            workspaceRoots = List.copyOf(workspaceRoots);
        }

        static @NotNull StatusDetails minimal(@NotNull Collection<LspServerState> states) {
            return new StatusDetails(states, states.size(), null, null, List.of(), null, ScriptActivity.NONE);
        }
    }

    record StatusPresentation(@NotNull String text, @NotNull String tooltip, @NotNull ClickAction clickAction) {
    }

    private static final class PapyrusStatusBarWidget implements CustomStatusBarWidget {
        private final Project project;
        private final AtomicBoolean refreshing = new AtomicBoolean(false);
        private JBLabel label;
        private Timer timer;
        private volatile boolean disposed;
        private volatile boolean activePapyrusFile;
        private volatile VirtualFile activeFile;
        private volatile ClickAction clickAction = ClickAction.NONE;

        private PapyrusStatusBarWidget(@NotNull Project project) {
            this.project = project;
            project.getMessageBus().connect(this).subscribe(
                    FileEditorManagerListener.FILE_EDITOR_MANAGER,
                    new FileEditorManagerListener() {
                        @Override
                        public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                            updateActiveFileFromSelection();
                        }

                        @Override
                        public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
                            updateActiveFileFromSelection();
                        }

                        @Override
                        public void selectionChanged(@NotNull FileEditorManagerEvent event) {
                            setActiveFile(event.getNewFile());
                        }
                    }
            );
        }

        @Override
        public @NotNull String ID() {
            return ID;
        }

        @Override
        public @NotNull JComponent getComponent() {
            if (label == null) {
                label = new JBLabel();
                label.setVisible(false);
                HelpTooltipKt.setToolTipText(label, HtmlChunk.text("Papyrus language server status"));
                label.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent event) {
                        if (event.getButton() != MouseEvent.BUTTON1 || !label.isVisible()) {
                            return;
                        }
                        switch (clickAction) {
                            case OUTPUT -> PapyrusLspOutputOpener.open(project);
                            case SETTINGS -> ApplicationManager.getApplication().invokeLater(() ->
                                    ShowSettingsUtil.getInstance()
                                            .showSettingsDialog(project, PapyrusSettingsConfigurable.class)
                            );
                            case NONE -> {
                            }
                        }
                    }
                });
                updateActiveFileFromSelection();
            }
            return label;
        }

        private void startPolling() {
            if (disposed || project.isDisposed()) {
                return;
            }
            if (!ApplicationManager.getApplication().isDispatchThread()) {
                ApplicationManager.getApplication().invokeLater(this::startPolling);
                return;
            }
            if (timer != null) {
                return;
            }
            timer = new Timer(2000, event -> refreshAsync());
            timer.setInitialDelay(0);
            timer.start();
        }

        private void refreshAsync() {
            VirtualFile selectedFile = activeFile;
            if (disposed || project.isDisposed() || !activePapyrusFile || selectedFile == null
                    || !refreshing.compareAndSet(false, true)) {
                return;
            }
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    PapyrusLaunchReadiness readiness = PapyrusLaunchConfigurationResolver.readiness(
                            PapyrusSettings.getInstance().getState()
                    );
                    Collection<LspClient> clients = getPapyrusClients();
                    StatusPresentation presentation = statusPresentationFor(
                            readiness,
                            statusDetailsFor(clients, selectedFile)
                    );
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (selectedFile.equals(activeFile)) {
                            applyPresentation(presentation);
                        }
                    });
                } finally {
                    refreshing.set(false);
                }
            });
        }

        private @NotNull StatusDetails statusDetailsFor(
                @NotNull Collection<LspClient> clients,
                @NotNull VirtualFile selectedFile
        ) {
            List<LspServerState> states = clients.stream().map(LspClient::getState).toList();
            LspClient primary = clients.stream()
                    .filter(client -> client.getState() == LspServerState.Running)
                    .findFirst()
                    .orElseGet(() -> clients.stream().findFirst().orElse(null));

            String serverName = null;
            String serverVersion = null;
            List<String> workspaceRoots = List.of();
            if (primary != null) {
                serverName = primary.getDescriptor().getPresentableName();
                InitializeResult initializeResult = primary.getInitializeResult();
                if (initializeResult != null && initializeResult.getServerInfo() != null) {
                    if (initializeResult.getServerInfo().getName() != null
                            && !initializeResult.getServerInfo().getName().isBlank()) {
                        serverName = initializeResult.getServerInfo().getName();
                    }
                    serverVersion = initializeResult.getServerInfo().getVersion();
                }
                workspaceRoots = Arrays.stream(primary.getDescriptor().getRoots())
                        .map(VirtualFile::getPresentableUrl)
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
            }

            ScriptActivity scriptActivity = scriptActivityFor(selectedFile, states);
            return new StatusDetails(
                    states,
                    clients.size(),
                    serverName,
                    serverVersion,
                    workspaceRoots,
                    selectedFile.getName(),
                    scriptActivity
            );
        }

        private @NotNull ScriptActivity scriptActivityFor(
                @NotNull VirtualFile selectedFile,
                @NotNull Collection<LspServerState> states
        ) {
            if (!"psc".equalsIgnoreCase(selectedFile.getExtension()) || !states.contains(LspServerState.Running)) {
                return ScriptActivity.NONE;
            }
            PapyrusScriptStatusService service = PapyrusScriptStatusService.getInstance(project);
            PapyrusScriptStatusService.ScriptStatus status = service.getCachedSnapshot(selectedFile);
            if (status == null) {
                service.scheduleRefreshIfMissing(selectedFile);
                return ScriptActivity.CHECKING;
            }
            if (status.unresolved()) {
                return ScriptActivity.UNRESOLVED;
            }
            if (status.overridden()) {
                return ScriptActivity.OVERRIDDEN;
            }
            return ScriptActivity.RESOLVED;
        }

        private void applyPresentation(@Nullable StatusPresentation presentation) {
            if (disposed || label == null) {
                return;
            }
            if (!activePapyrusFile) {
                hideStatus();
                stopPolling();
                return;
            }
            boolean visible = presentation != null;
            clickAction = visible ? presentation.clickAction() : ClickAction.NONE;
            label.setVisible(visible);
            label.setText(visible ? presentation.text() : "");
            label.setCursor(Cursor.getPredefinedCursor(
                    clickAction != ClickAction.NONE ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR
            ));
            String tooltip = visible
                    ? presentation.tooltip() + switch (clickAction) {
                        case OUTPUT -> " Click to open Papyrus language-service output.";
                        case SETTINGS -> " Click to open Settings | Papyrus.";
                        case NONE -> "";
                    }
                    : "Papyrus language server status";
            HelpTooltipKt.setToolTipText(label, HtmlChunk.text(tooltip));
        }

        private @NotNull Collection<LspClient> getPapyrusClients() {
            return LspClientManager.getInstance(project).getClients(PapyrusLspIntegrationProvider.class);
        }

        private void updateActiveFileFromSelection() {
            if (!ApplicationManager.getApplication().isDispatchThread()) {
                ApplicationManager.getApplication().invokeLater(this::updateActiveFileFromSelection);
                return;
            }
            VirtualFile[] files = FileEditorManager.getInstance(project).getSelectedFiles();
            setActiveFile(files.length > 0 ? files[0] : null);
        }

        private void setActiveFile(@Nullable VirtualFile file) {
            if (!ApplicationManager.getApplication().isDispatchThread()) {
                ApplicationManager.getApplication().invokeLater(() -> setActiveFile(file));
                return;
            }
            activePapyrusFile = file != null && isPapyrusFile(file);
            activeFile = activePapyrusFile ? file : null;
            if (activePapyrusFile) {
                if (file != null && "psc".equalsIgnoreCase(file.getExtension())) {
                    PapyrusScriptStatusService.getInstance(project).scheduleRefresh(file);
                }
                startPolling();
                refreshAsync();
            } else {
                hideStatus();
                stopPolling();
            }
        }

        private void hideStatus() {
            if (label != null) {
                label.setVisible(false);
                label.setText("");
                clickAction = ClickAction.NONE;
                label.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                HelpTooltipKt.setToolTipText(label, HtmlChunk.text("Papyrus language server status"));
            }
        }

        private void stopPolling() {
            if (timer != null) {
                timer.stop();
                timer = null;
            }
        }

        private static boolean isPapyrusFile(@NotNull VirtualFile file) {
            String extension = file.getExtension();
            return "psc".equalsIgnoreCase(extension) || "ppj".equalsIgnoreCase(extension);
        }

        @Override
        public void dispose() {
            disposed = true;
            activeFile = null;
            stopPolling();
        }
    }
}
