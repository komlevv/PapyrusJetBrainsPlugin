package dev.papyrus.jetbrains.testing;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.ide.IdeEventQueue;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.AnActionResult;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.Shortcut;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.ConfigurationTypeUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.roots.LibraryOrderEntry;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.impl.libraries.LibraryEx;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryType;
import com.intellij.task.ProjectTaskRunner;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.platform.lsp.api.LspClient;
import com.intellij.platform.lsp.api.LspClientManager;
import dev.papyrus.jetbrains.lsp.PapyrusLspIntegrationProvider;
import dev.papyrus.jetbrains.lsp.PapyrusLanguageService;
import dev.papyrus.jetbrains.lsp.PapyrusWorkspaceFileWatcher;
import dev.papyrus.jetbrains.actions.PapyrusActionTestBridge;
import dev.papyrus.jetbrains.actions.PapyrusExternalUrlOpener;
import dev.papyrus.jetbrains.config.PapyrusProjectSettings;
import dev.papyrus.jetbrains.config.PapyrusSettings;
import dev.papyrus.jetbrains.run.PapyrusAttachConfigurationType;
import dev.papyrus.jetbrains.run.PapyrusCompilerDiagnostic;
import dev.papyrus.jetbrains.run.PapyrusCompilerFilter;
import dev.papyrus.jetbrains.run.PapyrusProjectCompileService;
import dev.papyrus.jetbrains.run.PapyrusProjectConfigurationType;
import dev.papyrus.jetbrains.run.PapyrusProjectRunConfiguration;
import dev.papyrus.jetbrains.run.PapyrusProjectTaskRunner;
import dev.papyrus.jetbrains.status.PapyrusLspOutputOpener;
import dev.papyrus.jetbrains.status.PapyrusLspOutputService;
import dev.papyrus.jetbrains.protocol.DocumentSyntaxTree;
import dev.papyrus.jetbrains.protocol.DocumentSyntaxTreeNode;
import dev.papyrus.jetbrains.protocol.ProjectInfo;
import dev.papyrus.jetbrains.protocol.ProjectInfoScript;
import dev.papyrus.jetbrains.protocol.ProjectInfoSourceInclude;
import dev.papyrus.jetbrains.protocol.ProjectInfos;
import dev.papyrus.jetbrains.projects.PapyrusProjectsService;
import org.jetbrains.annotations.TestOnly;

import javax.accessibility.AccessibleContext;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.JTextField;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.AWTEvent;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class PapyrusUiTestSupport {
    private static final String ENABLE_PROPERTY = "papyrus.ui.integration.test";
    private static final Object SHORTCUT_TRACE_LOCK = new Object();
    private static final StringBuilder SHORTCUT_TRACE = new StringBuilder();
    private static MessageBusConnection shortcutTraceConnection;
    private static AWTEventListener shortcutTraceAwtListener;

    private PapyrusUiTestSupport() {
    }

    public static void replaceDocument(Project project, Editor editor, String text) {
        requireEnabled();
        WriteCommandAction.writeCommandAction(project)
                .withName("Papyrus UI Test Document Replacement")
                .run(() -> {
                    Document document = editor.getDocument();
                    document.replaceString(0, document.getTextLength(), text);
                    editor.getCaretModel().moveToOffset(0);
                    editor.getSelectionModel().removeSelection();
                });
    }

    public static void replaceDocumentRange(
            Project project,
            Editor editor,
            int startOffset,
            int endOffset,
            String text
    ) {
        requireEnabled();
        WriteCommandAction.writeCommandAction(project)
                .withName("Papyrus UI Test Document Range Replacement")
                .run(() -> {
                    Document document = editor.getDocument();
                    if (startOffset < 0 || endOffset < startOffset || endOffset > document.getTextLength()) {
                        throw new IllegalArgumentException(
                                "Invalid document range: " + startOffset + ".." + endOffset
                                        + " for length " + document.getTextLength()
                        );
                    }
                    document.replaceString(startOffset, endOffset, text);
                    editor.getCaretModel().moveToOffset(Math.min(startOffset + text.length(), document.getTextLength()));
                    editor.getSelectionModel().removeSelection();
                });
    }

    public static String papyrusSyntaxTreeSnapshot(Project project, Editor editor) {
        requireEnabled();
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file == null) {
            return "";
        }
        DocumentSyntaxTree tree = PapyrusLanguageService.getInstance(project).requestSyntaxTree(file);
        if (tree == null || tree.getRoot() == null) {
            return "";
        }
        StringBuilder snapshot = new StringBuilder();
        appendSyntaxTreeNode(snapshot, tree.getRoot(), 0);
        return snapshot.toString();
    }

    public static int caretOffset(Editor editor) {
        requireEnabled();
        return ReadAction.computeBlocking(() -> editor.getCaretModel().getOffset());
    }

    public static void saveAllDocuments() {
        requireEnabled();
        onEdt(() -> {
            FileDocumentManager.getInstance().saveAllDocuments();
            return null;
        });
    }

    public static void clearCapturedExternalUrl() {
        requireEnabled();
        PapyrusExternalUrlOpener.clearCapturedUrlForTests();
    }

    public static String capturedExternalUrl() {
        requireEnabled();
        return PapyrusExternalUrlOpener.capturedUrlForTests();
    }


    public static void preparePapyrusCompileSelection(String projectFile) {
        requireEnabled();
        PapyrusActionTestBridge.preparePapyrusCompileSelection(projectFile);
    }

    public static String papyrusLspOutputSnapshot(Project project) {
        requireEnabled();
        return PapyrusLspOutputService.getInstance(project).snapshot();
    }

    public static String firstPapyrusCompilerDiagnostic(Project project) {
        requireEnabled();
        String[] lines = PapyrusLspOutputService.getInstance(project).snapshot().split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            if (PapyrusCompilerDiagnostic.parse(lines[index]) != null) {
                return lines[index];
            }
        }
        return "";
    }

    public static String navigateFirstPapyrusCompilerDiagnostic(Project project) {
        requireEnabled();
        String line = firstPapyrusCompilerDiagnostic(project);
        if (line.isBlank()) {
            return "";
        }
        PapyrusCompilerDiagnostic diagnostic = PapyrusCompilerDiagnostic.parse(line);
        if (diagnostic == null) {
            return "";
        }
        var result = new PapyrusCompilerFilter(project).applyFilter(line, line.length());
        if (result == null || result.getFirstHyperlinkInfo() == null) {
            return "";
        }
        onEdt(() -> {
            result.getFirstHyperlinkInfo().navigate(project);
            return null;
        });
        return diagnostic.filePath();
    }

    public static String selectedEditorFilePath(Project project) {
        requireEnabled();
        return onEdt(() -> {
            VirtualFile[] selected = FileEditorManager.getInstance(project).getSelectedFiles();
            return selected.length == 0 ? "" : selected[0].getPath();
        });
    }

    public static void prepareProjectGeneration(String parentDirectory, String folderName) {
        requireEnabled();
        PapyrusActionTestBridge.prepareProjectGeneration(parentDirectory, folderName);
    }

    public static void cancelProjectGeneration() {
        requireEnabled();
        PapyrusActionTestBridge.cancelProjectGeneration();
    }

    public static void clearCapturedActionMessage() {
        requireEnabled();
        PapyrusActionTestBridge.clearCapturedMessage();
    }

    public static String capturedActionMessageKind() {
        requireEnabled();
        return PapyrusActionTestBridge.capturedMessageKind();
    }

    public static String capturedActionMessageText() {
        requireEnabled();
        return PapyrusActionTestBridge.capturedMessageText();
    }

    public static String creationKitInstallPath() {
        requireEnabled();
        return PapyrusSettings.getInstance().getState().creationKitInstallPath;
    }

    public static void setCreationKitInstallPath(String path) {
        requireEnabled();
        PapyrusSettings.getInstance().getState().creationKitInstallPath = path;
    }

    public static String compilerPathOverride() {
        requireEnabled();
        return PapyrusSettings.getInstance().getState().compilerPathOverride;
    }

    public static void setCompilerPathOverride(String path) {
        requireEnabled();
        PapyrusSettings.getInstance().getState().compilerPathOverride = path;
    }

    public static boolean papyrusEnabled() {
        requireEnabled();
        return PapyrusSettings.getInstance().getState().enabled;
    }

    public static void setPapyrusEnabled(boolean enabled) {
        requireEnabled();
        PapyrusSettings.getInstance().getState().enabled = enabled;
    }

    public static void refreshPapyrusEnablement(Project project) {
        requireEnabled();
        LspClientManager clientManager = LspClientManager.getInstance(project);
        if (PapyrusSettings.getInstance().getState().enabled) {
            clientManager.startClientsIfNeeded(PapyrusLspIntegrationProvider.class);
        } else {
            clientManager.stopClients(PapyrusLspIntegrationProvider.class);
        }
    }

    public static boolean papyrusAttachConfigurationTypeRegistered() {
        requireEnabled();
        return ConfigurationTypeUtil.findConfigurationType(PapyrusAttachConfigurationType.ID) instanceof PapyrusAttachConfigurationType;
    }

    public static boolean papyrusProjectConfigurationTypeRegistered() {
        requireEnabled();
        return ConfigurationTypeUtil.findConfigurationType(PapyrusProjectConfigurationType.ID) instanceof PapyrusProjectConfigurationType;
    }

    public static boolean papyrusProjectTaskRunnerRegistered() {
        requireEnabled();
        return ProjectTaskRunner.EP_NAME.getExtensionList().stream()
                .anyMatch(PapyrusProjectTaskRunner.class::isInstance);
    }

    public static String papyrusBuildSystem(Project project) {
        requireEnabled();
        return PapyrusProjectSettings.getInstance(project).getState().buildSystem;
    }

    public static void setPapyrusBuildSettings(Project project, String buildSystem, String projectFile) {
        requireEnabled();
        PapyrusProjectSettings.SettingsState state = PapyrusProjectSettings.getInstance(project).getState();
        state.buildSystem = buildSystem;
        state.projectFile = projectFile;
    }

    public static String runPapyrusProjectConfiguration(Project project, String projectFile) {
        requireEnabled();
        return onEdt(() -> {
            PapyrusProjectConfigurationType type = ConfigurationTypeUtil.findConfigurationType(
                    PapyrusProjectConfigurationType.class
            );
            RunManager runManager = RunManager.getInstance(project);
            String name = runManager.suggestUniqueName("Papyrus Project UI Test", type);
            var settings = runManager.createConfiguration(name, type.getConfigurationFactories()[0]);
            if (!(settings.getConfiguration() instanceof PapyrusProjectRunConfiguration configuration)) {
                throw new IllegalStateException("Papyrus Project run configuration factory returned an unexpected type.");
            }
            configuration.setProjectFile(projectFile);
            settings.storeInLocalWorkspace();
            runManager.addConfiguration(settings);
            runManager.setSelectedConfiguration(settings);
            ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance());
            return name;
        });
    }

    public static boolean papyrusProjectCompileRunning(Project project) {
        requireEnabled();
        return PapyrusProjectCompileService.isRunning(project);
    }

    public static String selectedRunConfigurationTypeId(Project project) {
        requireEnabled();
        return onEdt(() -> {
            var settings = RunManager.getInstance(project).getSelectedConfiguration();
            return settings == null ? "" : settings.getType().getId();
        });
    }

    public static int papyrusLspClientCount(Project project) {
        requireEnabled();
        return LspClientManager.getInstance(project).getClients(PapyrusLspIntegrationProvider.class).size();
    }

    public static String papyrusLspClientStates(Project project) {
        requireEnabled();
        return String.join(", ", LspClientManager.getInstance(project)
                .getClients(PapyrusLspIntegrationProvider.class)
                .stream()
                .map(client -> client.getState().name())
                .toList());
    }

    public static String papyrusDefinitionProviderStates(Project project) {
        requireEnabled();
        return String.join(", ", LspClientManager.getInstance(project)
                .getClients(PapyrusLspIntegrationProvider.class)
                .stream()
                .map(client -> {
                    var initializeResult = client.getInitializeResult();
                    if (initializeResult == null || initializeResult.getCapabilities() == null) {
                        return "uninitialized";
                    }
                    var provider = initializeResult.getCapabilities().getDefinitionProvider();
                    if (provider == null) {
                        return "null";
                    }
                    if (provider.isLeft()) {
                        return "boolean:" + provider.getLeft();
                    }
                    return "options";
                })
                .toList());
    }

    public static String activeShortcutBindings(String actionId) {
        requireEnabled();
        Keymap keymap = KeymapManager.getInstance().getActiveKeymap();
        List<String> parts = new ArrayList<>();
        for (Shortcut shortcut : keymap.getShortcuts(actionId)) {
            List<String> exactActionIds = keymap.getActionIdList(shortcut);
            parts.add(shortcut + " -> " + String.join(",", exactActionIds));
        }
        return "keymap=" + keymap.getName() + "; " + String.join("; ", parts);
    }

    public static void startShortcutDispatchTrace() {
        requireEnabled();
        onEdt(() -> {
            stopShortcutDispatchTraceInternal();
            synchronized (SHORTCUT_TRACE_LOCK) {
                SHORTCUT_TRACE.setLength(0);
            }

            shortcutTraceAwtListener = event -> {
                if (!(event instanceof KeyEvent keyEvent)) {
                    return;
                }
                String kind = switch (keyEvent.getID()) {
                    case KeyEvent.KEY_PRESSED -> "KEY_PRESSED";
                    case KeyEvent.KEY_RELEASED -> "KEY_RELEASED";
                    case KeyEvent.KEY_TYPED -> "KEY_TYPED";
                    default -> "KEY_" + keyEvent.getID();
                };
                Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                appendShortcutTrace(
                        kind
                                + " code=" + keyEvent.getKeyCode()
                                + " char=" + (int) keyEvent.getKeyChar()
                                + " modifiersEx=" + keyEvent.getModifiersEx()
                                + " consumed=" + keyEvent.isConsumed()
                                + " source=" + keyEvent.getSource().getClass().getName()
                                + " focusOwner=" + (focusOwner == null ? "null" : focusOwner.getClass().getName())
                );
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(shortcutTraceAwtListener, AWTEvent.KEY_EVENT_MASK);

            shortcutTraceConnection = ApplicationManager.getApplication().getMessageBus().connect();
            shortcutTraceConnection.subscribe(AnActionListener.TOPIC, new AnActionListener() {
                @Override
                public void beforeShortcutTriggered(
                        Shortcut shortcut,
                        List<AnAction> actions,
                        DataContext dataContext
                ) {
                    List<String> actionIds = actions.stream()
                            .map(action -> {
                                String id = ActionManager.getInstance().getId(action);
                                return id == null ? action.getClass().getName() : id;
                            })
                            .toList();
                    appendShortcutTrace("SHORTCUT " + shortcut + " actions=" + String.join(",", actionIds));
                }

                @Override
                public void beforeActionPerformed(AnAction action, AnActionEvent event) {
                    String id = ActionManager.getInstance().getId(action);
                    appendShortcutTrace(
                            "BEFORE_ACTION id=" + (id == null ? action.getClass().getName() : id)
                                    + " place=" + event.getPlace()
                                    + " enabled=" + event.getPresentation().isEnabled()
                                    + " input=" + describeInputEvent(event.getInputEvent())
                    );
                }

                @Override
                public void afterActionPerformed(AnAction action, AnActionEvent event, AnActionResult result) {
                    String id = ActionManager.getInstance().getId(action);
                    appendShortcutTrace(
                            "AFTER_ACTION id=" + (id == null ? action.getClass().getName() : id)
                                    + " place=" + event.getPlace()
                                    + " result=" + result
                    );
                }
            });
            return null;
        });
    }

    public static String shortcutDispatchTraceSnapshot() {
        requireEnabled();
        synchronized (SHORTCUT_TRACE_LOCK) {
            return SHORTCUT_TRACE.toString();
        }
    }

    public static String stopShortcutDispatchTrace() {
        requireEnabled();
        return onEdt(() -> {
            stopShortcutDispatchTraceInternal();
            synchronized (SHORTCUT_TRACE_LOCK) {
                return SHORTCUT_TRACE.toString();
            }
        });
    }

    private static void stopShortcutDispatchTraceInternal() {
        if (shortcutTraceAwtListener != null) {
            Toolkit.getDefaultToolkit().removeAWTEventListener(shortcutTraceAwtListener);
            shortcutTraceAwtListener = null;
        }
        if (shortcutTraceConnection != null) {
            shortcutTraceConnection.disconnect();
            shortcutTraceConnection = null;
        }
    }

    private static void appendShortcutTrace(String line) {
        synchronized (SHORTCUT_TRACE_LOCK) {
            if (!SHORTCUT_TRACE.isEmpty()) {
                SHORTCUT_TRACE.append(" | ");
            }
            SHORTCUT_TRACE.append(line);
        }
    }

    private static String describeInputEvent(InputEvent event) {
        if (event == null) {
            return "null";
        }
        if (event instanceof KeyEvent keyEvent) {
            return "KeyEvent[id=" + keyEvent.getID()
                    + ",code=" + keyEvent.getKeyCode()
                    + ",modifiersEx=" + keyEvent.getModifiersEx()
                    + ",consumed=" + keyEvent.isConsumed() + "]";
        }
        return event.getClass().getName();
    }

    public static boolean isProjectContentFile(Project project, String filePath) {
        requireEnabled();
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(filePath.replace('\\', '/'));
        if (file == null) {
            return false;
        }
        return ReadAction.computeBlocking(() -> ProjectFileIndex.getInstance(project).isInContent(file));
    }

    public static boolean isProjectLibrarySourceFile(Project project, String filePath) {
        requireEnabled();
        if (filePath == null || filePath.isBlank()) {
            return false;
        }
        VirtualFile file = LocalFileSystem.getInstance().findFileByPath(filePath.replace('\\', '/'));
        if (file == null) {
            return false;
        }
        return ReadAction.computeBlocking(() -> ProjectFileIndex.getInstance(project).isInLibrarySource(file));
    }

    public static String papyrusImportLibraryExternalRootTypes(Project project) {
        requireEnabled();
        return ReadAction.computeBlocking(() -> {
            for (Module module : ModuleManager.getInstance(project).getModules()) {
                for (OrderEntry entry : ModuleRootManager.getInstance(module).getOrderEntries()) {
                    if (!(entry instanceof LibraryOrderEntry libraryEntry)) {
                        continue;
                    }
                    Library library = libraryEntry.getLibrary();
                    if (library == null || library.getName() == null
                            || !library.getName().startsWith("Papyrus Imports")) {
                        continue;
                    }
                    if (!(library instanceof LibraryEx extendedLibrary) || extendedLibrary.getKind() == null) {
                        return "kind=<none>;sourcesExternal=false;classesExternal=false";
                    }

                    LibraryType<?> type;
                    try {
                        type = LibraryType.findByKind(extendedLibrary.getKind());
                    } catch (RuntimeException exception) {
                        return "kind=" + extendedLibrary.getKind().getKindId()
                                + ";sourcesExternal=false;classesExternal=false";
                    }

                    boolean sourcesExternal = false;
                    boolean classesExternal = false;
                    for (OrderRootType rootType : type.getExternalRootTypes()) {
                        sourcesExternal |= rootType == OrderRootType.SOURCES;
                        classesExternal |= rootType == OrderRootType.CLASSES;
                    }
                    return "kind=" + extendedLibrary.getKind().getKindId()
                            + ";sourcesExternal=" + sourcesExternal
                            + ";classesExternal=" + classesExternal;
                }
            }
            return "<missing>";
        });
    }

    @TestOnly
    public static void clearPapyrusLspOutputDiagnostic() {
        requireEnabled();
        PapyrusLspOutputOpener.clearDiagnosticForTests();
    }

    @TestOnly
    public static String papyrusLspOutputDiagnostic(Project project) {
        requireEnabled();
        return onEdt(() -> {
            StringBuilder diagnostic = new StringBuilder();
            ToolWindow outputWindow = ToolWindowManager.getInstance(project).getToolWindow(PapyrusLspOutputOpener.TOOL_WINDOW_ID);
            diagnostic.append("toolWindowExists=").append(outputWindow != null);
            diagnostic.append("; toolWindowVisible=").append(outputWindow != null && outputWindow.isVisible());
            diagnostic.append("; opener={").append(PapyrusLspOutputOpener.diagnosticForTests()).append('}');
            if (outputWindow != null && outputWindow.getContentManagerIfCreated() != null) {
                com.intellij.ui.content.Content selected = outputWindow.getContentManager().getSelectedContent();
                diagnostic.append("; selectedContent=").append(selected == null ? "<none>" : selected.getDisplayName());
            } else {
                diagnostic.append("; selectedContent=<uncreated>");
            }

            String output = PapyrusLspOutputService.getInstance(project).snapshot();
            diagnostic.append("; outputChars=").append(output.length());
            diagnostic.append("; outputContainsStarted=").append(output.contains("Language service started."));
            diagnostic.append("; outputContainsCompileCompleted=")
                    .append(output.contains("[compile] Completed successfully."));

            List<LspClient> clients = new ArrayList<>(
                    LspClientManager.getInstance(project).getClients(PapyrusLspIntegrationProvider.class)
            );
            diagnostic.append("; clientCount=").append(clients.size());
            if (!clients.isEmpty()) {
                LspClient client = clients.stream()
                        .filter(candidate -> candidate.getState() == com.intellij.platform.lsp.api.LspServerState.Running)
                        .findFirst()
                        .orElse(clients.getFirst());
                diagnostic.append("; clientState=").append(client.getState());
                diagnostic.append("; clientClass=").append(client.getClass().getName());
            }
            return diagnostic.toString();
        });
    }

    public static String selectedToolWindowTreePath(Project project, String toolWindowId) {
        requireEnabled();
        return onEdt(() -> {
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(toolWindowId);
            if (toolWindow == null) {
                return "";
            }
            return selectedSwingTreePath(toolWindow.getComponent());
        });
    }

    public static void disposeVisibleDialog(String dialogTitle) {
        requireEnabled();
        onEdt(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof Dialog dialog
                        && dialog.isShowing()
                        && dialogTitle.equals(dialog.getTitle())) {
                    dialog.dispose();
                }
            }
            return null;
        });
    }

    public static void cleanupTransientUi(Project project, String baselinePath) {
        requireEnabled();
        onEdt(() -> {
            IdeEventQueue.getInstance().getPopupManager().closeAllPopups();
            HintManager.getInstance().hideAllHints();
            LookupManager.getInstance(project).hideActiveLookup();

            ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(project);
            hideToolWindow(toolWindowManager, "Find");
            hideToolWindow(toolWindowManager, "Papyrus Projects");
            hideToolWindow(toolWindowManager, "Services");

            FileEditorManager editorManager = FileEditorManager.getInstance(project);
            VirtualFile baseline = LocalFileSystem.getInstance().findFileByPath(baselinePath);
            if (baseline == null) {
                throw new IllegalStateException("Papyrus UI test baseline file is missing: " + baselinePath);
            }
            for (VirtualFile file : editorManager.getOpenFiles()) {
                if (!baseline.equals(file)) {
                    editorManager.closeFile(file);
                }
            }
            editorManager.openFile(baseline, true);
            return null;
        });
        PapyrusExternalUrlOpener.clearCapturedUrlForTests();
        PapyrusActionTestBridge.clearCapturedMessage();
        PapyrusActionTestBridge.clearPapyrusCompileSelection();
    }

    private static void appendSyntaxTreeNode(StringBuilder target, DocumentSyntaxTreeNode node, int depth) {
        target.repeat("  ", Math.max(0, depth));
        if (node.getName() != null) {
            target.append(node.getName());
        }
        if (node.getText() != null && !node.getText().isEmpty()) {
            target.append(" | ").append(node.getText());
        }
        target.append('\n');
        for (DocumentSyntaxTreeNode child : node.getChildren()) {
            appendSyntaxTreeNode(target, child, depth + 1);
        }
    }

    private static void hideToolWindow(ToolWindowManager manager, String id) {
        ToolWindow toolWindow = manager.getToolWindow(id);
        if (toolWindow != null && toolWindow.isVisible()) {
            toolWindow.hide();
        }
    }

    public static String tokenScopeAt(Editor editor, int offset) {
        requireEnabled();
        return ReadAction.computeBlocking(() -> {
            if (!(editor instanceof EditorEx editorEx)) {
                return "";
            }
            HighlighterIterator iterator = editorEx.getHighlighter().createIterator(offset);
            return iterator.getTokenType() == null ? "" : iterator.getTokenType().toString();
        });
    }

    public static String createProjectTextFile(Project project, String relativePath, String text) {
        requireEnabled();
        String normalized = normalizeProjectRelativePath(relativePath);
        return onEdt(() -> {
            VirtualFile root = requireProjectRoot(project);

            AtomicReference<String> createdPath = new AtomicReference<>();
            ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    String[] segments = normalized.split("/");
                    VirtualFile parent = root;
                    for (int i = 0; i < segments.length - 1; i++) {
                        VirtualFile child = parent.findChild(segments[i]);
                        if (child == null) {
                            child = parent.createChildDirectory(PapyrusUiTestSupport.class, segments[i]);
                        }
                        if (!child.isDirectory()) {
                            throw new IllegalStateException("Expected project directory: " + child.getPath());
                        }
                        parent = child;
                    }

                    String fileName = segments[segments.length - 1];
                    VirtualFile file = parent.findChild(fileName);
                    if (file == null) {
                        file = parent.createChildData(PapyrusUiTestSupport.class, fileName);
                    } else if (file.isDirectory()) {
                        throw new IllegalStateException("Expected project file but found directory: " + file.getPath());
                    }
                    file.setBinaryContent(text.getBytes(StandardCharsets.UTF_8));
                    createdPath.set(file.getPath());
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to create project test file " + normalized, exception);
                }
            });
            return createdPath.get();
        });
    }

    public static boolean deleteProjectFile(Project project, String relativePath) {
        requireEnabled();
        String normalized = normalizeProjectRelativePath(relativePath);
        return onEdt(() -> {
            VirtualFile root = requireProjectRoot(project);
            VirtualFile file = root.findFileByRelativePath(normalized);
            if (file == null) {
                return false;
            }
            ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    file.delete(PapyrusUiTestSupport.class);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to delete project test file " + normalized, exception);
                }
            });
            return true;
        });
    }

    public static boolean papyrusProjectInfosReady(Project project) {
        requireEnabled();
        return PapyrusProjectsService.getInstance(project).getCurrentSnapshot() != null;
    }

    public static boolean papyrusProjectInfosContainsFile(Project project, String filePath) {
        requireEnabled();
        return projectInfosContainsFile(
                PapyrusProjectsService.getInstance(project).getCurrentSnapshot(),
                filePath
        );
    }

    public static boolean livePapyrusProjectInfosContainsFile(Project project, String filePath) {
        requireEnabled();
        return projectInfosContainsFile(
                PapyrusLanguageService.getInstance(project).requestProjectInfos(),
                filePath
        );
    }

    public static boolean papyrusWorkspaceFileWatcherStarted(Project project) {
        requireEnabled();
        return project.getService(PapyrusWorkspaceFileWatcher.class).isStarted();
    }

    public static long papyrusWorkspaceFileWatcherRelevantEventCount(Project project) {
        requireEnabled();
        return project.getService(PapyrusWorkspaceFileWatcher.class).getRelevantEventCount();
    }

    public static String papyrusWorkspaceFileWatcherLastRelevantEvent(Project project) {
        requireEnabled();
        return project.getService(PapyrusWorkspaceFileWatcher.class).getLastRelevantEvent();
    }

    public static String papyrusProjectsStatusPhase(Project project) {
        requireEnabled();
        return PapyrusProjectsService.getInstance(project).getStatus().phase().name();
    }

    public static String papyrusProjectsStatusSummary(Project project) {
        requireEnabled();
        return PapyrusProjectsService.getInstance(project).getStatus().summary();
    }

    public static String papyrusProjectsStatusDetails(Project project) {
        requireEnabled();
        return PapyrusProjectsService.getInstance(project).getStatus().details();
    }

    public static boolean papyrusProjectsShowingLastKnownGood(Project project) {
        requireEnabled();
        return PapyrusProjectsService.getInstance(project).getStatus().showingLastKnownGood();
    }

    public static void reloadPapyrusProjects(Project project) {
        requireEnabled();
        PapyrusProjectsService.getInstance(project).reloadFromProjectFilesAsync();
    }

    public static void restartPapyrusLanguageServer(Project project) {
        requireEnabled();
        PapyrusLanguageService.getInstance(project).restartClients();
    }

    public static String papyrusLanguageServerWorkspaceRoot(Project project) {
        requireEnabled();
        return PapyrusProjectsService.getInstance(project).getLanguageServerWorkspaceRoot().toString();
    }

    private static boolean projectInfosContainsFile(ProjectInfos infos, String filePath) {
        if (infos == null) {
            return false;
        }
        String expected = normalizeComparablePath(filePath);
        for (ProjectInfo projectInfo : infos.getProjects()) {
            for (ProjectInfoSourceInclude include : projectInfo.getSourceIncludes()) {
                for (ProjectInfoScript script : include.getScripts()) {
                    String candidate = script.getFilePath();
                    if (candidate != null && normalizeComparablePath(candidate).equalsIgnoreCase(expected)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean selectPapyrusProjectsContent(Project project) {
        requireEnabled();
        return onEdt(() -> {
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Papyrus Projects");
            if (toolWindow == null) {
                return false;
            }
            ContentManager contentManager = toolWindow.getContentManager();
            Content projectsContent = contentManager.findContent("Projects");
            if (projectsContent == null) {
                return false;
            }
            if (contentManager.getSelectedContent() != projectsContent) {
                contentManager.setSelectedContent(projectsContent, true);
            }
            return contentManager.getSelectedContent() == projectsContent;
        });
    }

    public static String projectsTreeSnapshot(Project project) {
        requireEnabled();
        return onEdt(() -> {
            Tree tree = projectsTree(project);
            List<String> rows = new ArrayList<>();
            TreeModel model = tree.getModel();
            Object root = model.getRoot();
            TreePath rootPath = new TreePath(root);
            appendTreeRows(tree, root, rootPath, "", rows);
            return String.join("\n", rows);
        });
    }

    public static boolean expandProjectsTreePath(Project project, String encodedPath) {
        requireEnabled();
        return onEdt(() -> {
            Tree tree = projectsTree(project);
            TreePath path = findTreePath(tree, encodedPath);
            if (path == null) {
                return false;
            }
            tree.expandPath(path);
            return tree.isExpanded(path);
        });
    }

    public static boolean doubleClickProjectsTreePath(Project project, String encodedPath) {
        requireEnabled();
        return onEdt(() -> {
            Tree tree = projectsTree(project);
            TreePath path = findTreePath(tree, encodedPath);
            if (path == null) {
                return false;
            }
            tree.scrollPathToVisible(path);
            tree.setSelectionPath(path);
            Rectangle bounds = tree.getPathBounds(path);
            if (bounds == null) {
                return false;
            }
            int x = bounds.x + Math.clamp(bounds.width / 2, 1, 12);
            int y = bounds.y + Math.max(1, bounds.height / 2);
            MouseEvent event = new MouseEvent(
                    tree,
                    MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(),
                    InputEvent.BUTTON1_DOWN_MASK,
                    x,
                    y,
                    2,
                    false,
                    MouseEvent.BUTTON1
            );
            tree.dispatchEvent(event);
            return true;
        });
    }


    public static String visibleTextTooltip(Project project, String text) {
        requireEnabled();
        return onEdt(() -> {
            var frame = WindowManager.getInstance().getIdeFrame(project);
            if (frame == null) {
                return null;
            }
            Component target = findVisibleTextComponent(frame.getComponent(), text);
            return target instanceof JComponent swingComponent ? swingComponent.getToolTipText() : null;
        });
    }


    public static String actionIdByText(String text) {
        requireEnabled();
        return onEdt(() -> {
            List<String> candidates = actionIdsByNormalizedText(text);
            if (candidates.isEmpty()) {
                candidates = actionIdsByFuzzyText(text);
            }
            if (candidates.isEmpty()) {
                return "";
            }
            candidates.sort(Comparator
                    .comparingInt((String id) -> actionPreferenceScore(text, id))
                    .thenComparing(String::length)
                    .thenComparing(String.CASE_INSENSITIVE_ORDER));
            return candidates.getFirst();
        });
    }

    public static String actionDiagnostics(String text) {
        requireEnabled();
        return onEdt(() -> {
            List<String> candidates = actionIdsByNormalizedText(text);
            if (!candidates.isEmpty()) {
                return String.join(", ", candidates);
            }
            String wanted = normalizeActionText(text);
            List<String> fuzzy = new ArrayList<>();
            ActionManager manager = ActionManager.getInstance();
            for (String id : manager.getActionIdList("")) {
                String actionText = actionTemplateText(manager, id);
                if (actionText.isEmpty()) {
                    continue;
                }
                String normalized = normalizeActionText(actionText);
                if (!normalized.isEmpty() && (normalized.contains(wanted) || wanted.contains(normalized))) {
                    fuzzy.add(id + "=" + actionText);
                }
            }
            fuzzy.sort(String.CASE_INSENSITIVE_ORDER);
            return fuzzy.isEmpty() ? "none" : String.join(", ", fuzzy);
        });
    }

    private static List<String> actionIdsByNormalizedText(String text) {
        String wanted = normalizeActionText(text);
        List<String> result = new ArrayList<>();
        ActionManager manager = ActionManager.getInstance();
        for (String id : manager.getActionIdList("")) {
            String actionText = actionTemplateText(manager, id);
            if (wanted.equals(normalizeActionText(actionText))) {
                result.add(id);
            }
        }
        return result;
    }

    private static List<String> actionIdsByFuzzyText(String text) {
        String wanted = normalizeActionText(text);
        List<String> result = new ArrayList<>();
        ActionManager manager = ActionManager.getInstance();
        for (String id : manager.getActionIdList("")) {
            String actionText = actionTemplateText(manager, id);
            String normalized = normalizeActionText(actionText);
            if (normalized.isEmpty()) {
                continue;
            }
            if (normalized.contains(wanted) || wanted.contains(normalized)) {
                result.add(id);
            }
        }
        return result;
    }

    private static String actionTemplateText(ActionManager manager, String id) {
        try {
            AnAction action = manager.getAction(id);
            if (action == null || action.getTemplatePresentation().getText() == null) {
                return "";
            }
            return action.getTemplatePresentation().getText();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static int actionPreferenceScore(String text, String id) {
        String normalizedText = normalizeActionText(text);
        String normalizedId = id.toLowerCase(java.util.Locale.ROOT).replace(".", "").replace("_", "");
        if ("new project".equals(normalizedText)) {
            if (normalizedId.contains("newproject")) return 0;
            if (normalizedId.contains("createproject")) return 1;
        }
        if ("build project".equals(normalizedText)) {
            if (normalizedId.contains("buildproject")) return 0;
            if (normalizedId.contains("build")) return 1;
            if (normalizedId.contains("compile")) return 2;
        }
        return 10;
    }

    private static String normalizeActionText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("_", "").replace("&", "").trim();
        while (normalized.endsWith("...") || normalized.endsWith("…")) {
            normalized = normalized.endsWith("...")
                    ? normalized.substring(0, normalized.length() - 3).trim()
                    : normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean setVisibleTextFieldAndSubmit(Project project, String accessibleName, String text) {
        requireEnabled();
        return onEdt(() -> {
            JTextField textField = findVisibleTextField(project, accessibleName);
            if (textField == null) {
                return false;
            }
            textField.setText(text);
            textField.postActionEvent();
            return true;
        });
    }

    private static JTextField findVisibleTextField(Project project, String accessibleName) {
        var frame = WindowManager.getInstance().getIdeFrame(project);
        if (frame == null) {
            return null;
        }
        Component target = findVisibleTextField(frame.getComponent(), accessibleName);
        if (target == null) {
            target = findVisibleWindowComponent(accessibleName, PapyrusUiTestSupport::findVisibleTextField);
        }
        return target instanceof JTextField textField ? textField : null;
    }

    public static boolean clickVisibleText(Project project, String text) {
        requireEnabled();
        return onEdt(() -> {
            var frame = WindowManager.getInstance().getIdeFrame(project);
            if (frame == null) {
                return false;
            }
            Component target = findVisibleButtonComponent(frame.getComponent(), text);
            if (target == null) {
                target = findVisibleWindowComponent(text, PapyrusUiTestSupport::findVisibleButtonComponent);
            }
            if (target instanceof AbstractButton button) {
                button.doClick(0);
                return true;
            }
            target = findVisibleTextComponent(frame.getComponent(), text);
            if (target == null) {
                return false;
            }
            int x = Math.max(1, target.getWidth() / 2);
            int y = Math.max(1, target.getHeight() / 2);
            long when = System.currentTimeMillis();
            target.dispatchEvent(new MouseEvent(
                    target,
                    MouseEvent.MOUSE_PRESSED,
                    when,
                    InputEvent.BUTTON1_DOWN_MASK,
                    x,
                    y,
                    1,
                    false,
                    MouseEvent.BUTTON1
            ));
            target.dispatchEvent(new MouseEvent(
                    target,
                    MouseEvent.MOUSE_RELEASED,
                    when,
                    0,
                    x,
                    y,
                    1,
                    false,
                    MouseEvent.BUTTON1
            ));
            target.dispatchEvent(new MouseEvent(
                    target,
                    MouseEvent.MOUSE_CLICKED,
                    when,
                    0,
                    x,
                    y,
                    1,
                    false,
                    MouseEvent.BUTTON1
            ));
            return true;
        });
    }

    private static Component findVisibleTextComponent(Component frameComponent, String text) {
        Component target = findVisibleLabelComponent(frameComponent, text);
        if (target == null) {
            target = findVisibleWindowComponent(text, PapyrusUiTestSupport::findVisibleLabelComponent);
        }
        if (target == null) {
            target = findVisibleAccessibleTextComponent(frameComponent, text);
        }
        if (target == null) {
            target = findVisibleWindowComponent(text, PapyrusUiTestSupport::findVisibleAccessibleTextComponent);
        }
        return target;
    }

    private static Component findVisibleWindowComponent(
            String text,
            java.util.function.BiFunction<Component, String, Component> finder
    ) {
        for (Window window : Window.getWindows()) {
            if (!window.isShowing()) {
                continue;
            }
            Component match = finder.apply(window, text);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    private static Component findVisibleTextField(Component component, String accessibleName) {
        if (!component.isShowing()) {
            return null;
        }
        if (component instanceof JTextField
                && component.getAccessibleContext() != null
                && accessibleName.equals(component.getAccessibleContext().getAccessibleName())) {
            return component;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component match = findVisibleTextField(child, accessibleName);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static Component findVisibleButtonComponent(Component component, String text) {
        if (!component.isShowing()) {
            return null;
        }
        if (component instanceof AbstractButton button && text.equals(button.getText())) {
            return component;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component match = findVisibleButtonComponent(child, text);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static Component findVisibleLabelComponent(Component component, String text) {
        if (!component.isShowing()) {
            return null;
        }
        if (component instanceof JLabel label && text.equals(label.getText())) {
            return component;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component match = findVisibleLabelComponent(child, text);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static Component findVisibleAccessibleTextComponent(Component component, String text) {
        if (!component.isShowing()) {
            return null;
        }
        AccessibleContext accessibleContext = component.getAccessibleContext();
        if (accessibleContext != null && text.equals(accessibleContext.getAccessibleName())) {
            return component;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Component match = findVisibleAccessibleTextComponent(child, text);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static VirtualFile requireProjectRoot(Project project) {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            throw new IllegalStateException("Project has no base path");
        }
        VirtualFile root = LocalFileSystem.getInstance().findFileByPath(basePath);
        if (root == null || !root.isDirectory()) {
            throw new IllegalStateException("Project root is not available in VFS: " + basePath);
        }
        return root;
    }

    private static String normalizeProjectRelativePath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Project relative path must not be blank");
        }
        String normalized = value.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Project path must be relative: " + value);
        }
        List<String> safeSegments = safeProjectPathSegments(normalized, value);
        if (safeSegments.isEmpty()) {
            throw new IllegalArgumentException("Project relative path has no file name: " + value);
        }
        return String.join("/", safeSegments);
    }

    private static List<String> safeProjectPathSegments(String normalized, String originalValue) {
        List<String> safeSegments = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isBlank() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("Project path must stay inside the project: " + originalValue);
            }
            safeSegments.add(segment);
        }
        return safeSegments;
    }

    private static String normalizeComparablePath(String value) {
        try {
            return Path.of(value).toAbsolutePath().normalize().toString();
        } catch (RuntimeException ignored) {
            return value.replace('/', '\\');
        }
    }

    private static Tree projectsTree(Project project) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Papyrus Projects");
        if (toolWindow == null) {
            throw new IllegalStateException("Papyrus Projects tool window is not registered");
        }

        if (toolWindow.getContentManagerIfCreated() != null) {
            ContentManager contentManager = toolWindow.getContentManager();
            Content projectsContent = contentManager.findContent("Projects");
            if (projectsContent != null && projectsContent != contentManager.getSelectedContent()) {
                contentManager.setSelectedContent(projectsContent, true);
            }
        }

        Tree tree = findTree(toolWindow.getComponent());
        if (tree == null) {
            throw new IllegalStateException("Papyrus Projects tree is not created");
        }
        return tree;
    }

    private static String selectedSwingTreePath(Component component) {
        if (component instanceof JTree tree && tree.getSelectionPath() != null) {
            Object[] path = tree.getSelectionPath().getPath();
            List<String> labels = new ArrayList<>(path.length);
            for (Object value : path) {
                String label = String.valueOf(value).trim();
                if (!label.isEmpty()) {
                    labels.add(label);
                }
            }
            if (!labels.isEmpty()) {
                return String.join(" > ", labels);
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                String path = selectedSwingTreePath(child);
                if (!path.isEmpty()) {
                    return path;
                }
            }
        }
        return "";
    }

    private static Tree findTree(Component component) {
        if (component instanceof Tree tree) {
            return tree;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                Tree tree = findTree(child);
                if (tree != null) {
                    return tree;
                }
            }
        }
        return null;
    }

    private static TreePath findTreePath(Tree tree, String encodedPath) {
        String[] labels = encodedPath.split("\u001F", -1);
        Object root = tree.getModel().getRoot();
        if (!(root instanceof DefaultMutableTreeNode current)) {
            return null;
        }
        TreePath path = new TreePath(current);
        for (String label : labels) {
            tree.expandPath(path);
            DefaultMutableTreeNode next = null;
            for (int i = 0; i < current.getChildCount(); i++) {
                Object child = current.getChildAt(i);
                if (child instanceof DefaultMutableTreeNode node && label.equals(node.toString())) {
                    next = node;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            current = next;
            path = path.pathByAddingChild(current);
        }
        return path;
    }

    private static void appendTreeRows(
            Tree tree,
            Object value,
            TreePath path,
            String parentPath,
            List<String> rows
    ) {
        if (!(value instanceof DefaultMutableTreeNode node)) {
            return;
        }
        String currentPath = parentPath;
        if (path.getPathCount() > 1) {
            String label = node.toString();
            currentPath = parentPath.isEmpty() ? label : parentPath + '\u001F' + label;
            rows.add(currentPath + '\u001E' + node.getChildCount() + '\u001E' + tree.isExpanded(path));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            Object child = node.getChildAt(i);
            appendTreeRows(tree, child, path.pathByAddingChild(child), currentPath, rows);
        }
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> action) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            try {
                return action.call();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        ApplicationManager.getApplication().invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable throwable) {
                error.set(throwable);
            }
        });
        if (error.get() != null) {
            if (error.get() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(error.get());
        }
        return result.get();
    }

    private static void requireEnabled() {
        if (!Boolean.getBoolean(ENABLE_PROPERTY)) {
            throw new IllegalStateException("Papyrus UI test support is disabled outside integration tests");
        }
    }
}
