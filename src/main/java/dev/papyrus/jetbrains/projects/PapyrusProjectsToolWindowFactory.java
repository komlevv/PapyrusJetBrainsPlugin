package dev.papyrus.jetbrains.projects;

import com.intellij.openapi.Disposable;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.ide.HelpTooltipKt;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.treeStructure.Tree;
import dev.papyrus.jetbrains.protocol.ProjectInfo;
import dev.papyrus.jetbrains.protocol.ProjectInfoScript;
import dev.papyrus.jetbrains.protocol.ProjectInfoSourceInclude;
import dev.papyrus.jetbrains.protocol.ProjectInfos;
import dev.papyrus.jetbrains.run.PapyrusCompilerFilter;
import dev.papyrus.jetbrains.status.PapyrusLspOutputService;
import dev.papyrus.jetbrains.ui.PapyrusIcons;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;

public final class PapyrusProjectsToolWindowFactory implements ToolWindowFactory, DumbAware {

    private static final int SCRIPT_PAGE_SIZE = 250;
    private static final String REFRESH_TEXT = "Refresh";
    private static final String REFRESHING_TEXT = "Refreshing...";

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return false;
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        PapyrusProjectsPanel panel = new PapyrusProjectsPanel(project);
        Content projectsContent = ContentFactory.getInstance().createContent(panel, "Projects", false);
        projectsContent.setDisposer(panel);
        toolWindow.getContentManager().addContent(projectsContent);

        PapyrusOutputPanel outputPanel = new PapyrusOutputPanel(project);
        Content outputContent = ContentFactory.getInstance().createContent(outputPanel, "Output", false);
        outputContent.setDisposer(outputPanel);
        toolWindow.getContentManager().addContent(outputContent);

        PapyrusProjectsService.getInstance(project).refreshAsync();
    }

    private static final class PapyrusProjectsPanel extends JPanel implements Disposable {
        private final Project project;
        private final PapyrusProjectsService projectsService;
        private final Tree tree;
        private final JBLabel statusLabel;
        private final JBTextArea detailArea;
        private final JButton refreshButton;
        private final JButton navigateButton;

        private PapyrusProjectsPanel(@NotNull Project project) {
            super(new BorderLayout());
            this.project = project;
            this.projectsService = PapyrusProjectsService.getInstance(project);
            this.tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode("Papyrus")));
            this.statusLabel = new JBLabel("Loading Papyrus projects...");
            this.detailArea = new JBTextArea();
            detailArea.setEditable(false);
            detailArea.setOpaque(false);
            detailArea.setLineWrap(true);
            detailArea.setWrapStyleWord(true);
            detailArea.setRows(0);
            detailArea.setColumns(48);
            detailArea.setMinimumSize(new Dimension(0, 0));
            detailArea.setFont(statusLabel.getFont());
            detailArea.setVisible(false);
            this.navigateButton = new JButton("Navigate...");
            navigateButton.setEnabled(false);
            HelpTooltipKt.setToolTipText(navigateButton, HtmlChunk.text("Search LSP-reported Papyrus scripts without expanding the project tree"));
            navigateButton.addActionListener(event -> showScriptNavigator());

            tree.setRootVisible(false);
            tree.setShowsRootHandles(true);
            tree.setCellRenderer(new PapyrusTreeCellRenderer());
            tree.addTreeWillExpandListener(new TreeWillExpandListener() {
                @Override
                public void treeWillExpand(TreeExpansionEvent event) {
                    Object node = event.getPath().getLastPathComponent();
                    if (node instanceof LazyNode lazyNode) {
                        lazyNode.ensureLoaded((DefaultTreeModel) tree.getModel());
                    }
                }

                @Override
                public void treeWillCollapse(TreeExpansionEvent event) {
                }
            });
            tree.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                        openSelectedScript();
                    }
                }
            });

            this.refreshButton = new JButton(REFRESH_TEXT);
            Dimension idleRefreshSize = refreshButton.getPreferredSize();
            refreshButton.setText(REFRESHING_TEXT);
            Dimension busyRefreshSize = refreshButton.getPreferredSize();
            refreshButton.setText(REFRESH_TEXT);
            refreshButton.setPreferredSize(new Dimension(
                    Math.max(idleRefreshSize.width, busyRefreshSize.width),
                    Math.max(idleRefreshSize.height, busyRefreshSize.height)
            ));
            refreshButton.addActionListener(event -> refresh());

            JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            toolbar.add(refreshButton);
            toolbar.add(navigateButton);
            toolbar.add(statusLabel);

            JPanel header = new JPanel(new BorderLayout());
            header.add(toolbar, BorderLayout.NORTH);
            header.add(detailArea, BorderLayout.SOUTH);

            add(header, BorderLayout.NORTH);
            add(new JBScrollPane(tree), BorderLayout.CENTER);

            projectsService.addListener(this::rebuildTree, this);
            rebuildTree();
        }

        private void refresh() {
            if (projectsService.isReloadInProgress()) {
                updateRefreshButton();
                return;
            }

            // Disable immediately on the EDT instead of waiting for the asynchronous service listener.
            // PapyrusProjectsService also rejects duplicate reloads under its own lock, so this is both
            // user feedback and a second line of defense against repeated requests.
            refreshButton.setText(REFRESHING_TEXT);
            refreshButton.setEnabled(false);
            refreshButton.setToolTipText("Papyrus project refresh is in progress");

            projectsService.reloadFromProjectFilesAsync();
            updateRefreshButton();
        }

        private void updateRefreshButton() {
            boolean busy = projectsService.isReloadInProgress();
            refreshButton.setText(busy ? REFRESHING_TEXT : REFRESH_TEXT);
            refreshButton.setEnabled(!busy);
            refreshButton.setToolTipText(busy ? "Papyrus project refresh is in progress" : null);
        }

        private void rebuildTree() {
            ProjectInfos infos = projectsService.getCurrentSnapshot();
            PapyrusProjectsService.Status serviceStatus = projectsService.getStatus();
            DefaultMutableTreeNode root = new DefaultMutableTreeNode("Papyrus");

            if (infos == null) {
                navigateButton.setEnabled(false);
                tree.setModel(new DefaultTreeModel(root));
                applyStatus(serviceStatus, null);
                return;
            }

            int projectCount = 0;
            int scriptCount = 0;
            List<ProjectInfo> projects = infos.getProjects();
            for (ProjectInfo projectInfo : projects) {
                projectCount++;
                for (ProjectInfoSourceInclude include : projectInfo.getSourceIncludes()) {
                    scriptCount += include.getScripts().size();
                }
            }

            DefaultTreeModel model = new DefaultTreeModel(root);
            if (!projects.isEmpty()) {
                GameNode gameNode = new GameNode(projects);
                root.add(gameNode);
                tree.setModel(model);
                tree.expandPath(new TreePath(new Object[]{root, gameNode}));
            } else {
                tree.setModel(model);
            }
            navigateButton.setEnabled(scriptCount > 0);
            applyStatus(serviceStatus, projectCount + " project(s), " + scriptCount + " script(s)");
        }

        private void applyStatus(
                @NotNull PapyrusProjectsService.Status serviceStatus,
                String readySummary
        ) {
            String summary = serviceStatus.phase() == PapyrusProjectsService.Phase.READY && readySummary != null
                    ? readySummary
                    : serviceStatus.summary();
            statusLabel.setText(summary);
            updateRefreshButton();

            String details = serviceStatus.details();
            if (serviceStatus.showingLastKnownGood()) {
                details = details.isBlank()
                        ? "Showing the last successfully loaded project configuration."
                        : details + "\nShowing the last successfully loaded project configuration.";
            }

            boolean error = serviceStatus.phase() == PapyrusProjectsService.Phase.VALIDATION_ERROR
                    || serviceStatus.phase() == PapyrusProjectsService.Phase.SERVER_ERROR;
            if (error) {
                details = details.isBlank()
                        ? "ERROR: " + summary
                        : "ERROR: " + summary + "\n" + details;
            }

            boolean showDetails = serviceStatus.phase() != PapyrusProjectsService.Phase.READY
                    && !details.isBlank();
            statusLabel.setToolTipText(showDetails ? null : serviceStatus.details());
            detailArea.setVisible(showDetails);
            if (showDetails) {
                detailArea.setText(details);
                detailArea.setCaretPosition(0);
                detailArea.setToolTipText(null);
            } else {
                detailArea.setText("");
                detailArea.setToolTipText(null);
            }
        }

        private void showScriptNavigator() {
            ProjectInfos infos = projectsService.getCurrentSnapshot();
            if (infos == null || !PapyrusScriptNavigatorModel.hasScripts(infos)) {
                navigateButton.setEnabled(false);
                return;
            }

            List<PapyrusScriptNavigatorModel.ScriptTarget> targets = PapyrusScriptNavigatorModel.targets(infos);
            PapyrusScriptNavigatorDialog dialog = new PapyrusScriptNavigatorDialog(
                    project,
                    targets,
                    target -> openScriptPath(target.filePath())
            );
            dialog.show();
        }

        private void openSelectedScript() {
            TreePath selection = tree.getSelectionPath();
            if (selection == null) {
                return;
            }

            Object component = selection.getLastPathComponent();
            if (!(component instanceof DefaultMutableTreeNode node)) {
                return;
            }
            Object userObject = node.getUserObject();
            if (!(userObject instanceof NodeValue value) || value.filePath() == null) {
                return;
            }

            openScriptPath(value.filePath());
        }

        private void openScriptPath(@NotNull String filePath) {
            VirtualFile file = LocalFileSystem.getInstance().findFileByPath(filePath);
            if (file == null) {
                file = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath);
            }
            if (file != null) {
                FileEditorManager.getInstance(project).openFile(file, true);
            }
        }

        @Override
        public void dispose() {
        }
    }

    private static final class PapyrusOutputPanel extends JPanel implements Disposable {
        private final PapyrusLspOutputService outputService;
        private final ConsoleView outputConsole;

        private PapyrusOutputPanel(@NotNull Project project) {
            super(new BorderLayout());
            this.outputService = PapyrusLspOutputService.getInstance(project);
            var consoleBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(project);
            consoleBuilder.addFilter(new PapyrusCompilerFilter(project));
            this.outputConsole = consoleBuilder.getConsole();
            Disposer.register(this, outputConsole);
            add(outputConsole.getComponent(), BorderLayout.CENTER);
            outputService.addListener(this::refresh, this);
            refresh();
        }

        private void refresh() {
            String snapshot = outputService.snapshot();
            outputConsole.clear();
            outputConsole.print(snapshot, ConsoleViewContentType.NORMAL_OUTPUT);
        }

        @Override
        public void dispose() {
        }
    }

    private abstract static class LazyNode extends DefaultMutableTreeNode {
        private boolean loaded;

        private LazyNode(Object userObject, boolean hasChildren) {
            super(userObject);
            if (hasChildren) {
                add(new DefaultMutableTreeNode("Loading..."));
            } else {
                loaded = true;
            }
        }

        final void ensureLoaded(@NotNull DefaultTreeModel model) {
            if (loaded) {
                return;
            }
            loaded = true;
            removeAllChildren();
            loadChildren();
            model.nodeStructureChanged(this);
        }

        abstract void loadChildren();
    }

    private static final class GameNode extends LazyNode {
        private final List<ProjectInfo> projects;

        private GameNode(@NotNull List<ProjectInfo> projects) {
            super(new NodeValue("Skyrim SE/AE", null), !projects.isEmpty());
            this.projects = List.copyOf(projects);
        }

        @Override
        void loadChildren() {
            for (ProjectInfo projectInfo : projects) {
                add(new ProjectNode(projectInfo));
            }
        }
    }

    private static final class ProjectNode extends LazyNode {
        private final ProjectInfo projectInfo;

        private ProjectNode(@NotNull ProjectInfo projectInfo) {
            super(new NodeValue(nonBlank(projectInfo.getName(), "Project"), null), !projectInfo.getSourceIncludes().isEmpty());
            this.projectInfo = projectInfo;
        }

        @Override
        void loadChildren() {
            List<ProjectInfoSourceInclude> sources = PapyrusProjectsPresentation.localIncludes(projectInfo);
            List<ProjectInfoSourceInclude> imports = PapyrusProjectsPresentation.importIncludes(projectInfo);
            if (!sources.isEmpty()) {
                add(new IncludeGroupNode("Sources", sources));
            }
            if (!imports.isEmpty()) {
                add(new IncludeGroupNode("Imports", imports));
            }
        }
    }

    private static final class IncludeGroupNode extends LazyNode {
        private final List<ProjectInfoSourceInclude> includes;

        private IncludeGroupNode(@NotNull String label, @NotNull List<ProjectInfoSourceInclude> includes) {
            super(new NodeValue(label, null), !includes.isEmpty());
            this.includes = List.copyOf(includes);
        }

        @Override
        void loadChildren() {
            for (ProjectInfoSourceInclude include : includes) {
                add(new IncludeNode(include));
            }
        }
    }

    private static final class IncludeNode extends LazyNode {
        private final List<ProjectInfoScript> scripts;

        private IncludeNode(@NotNull ProjectInfoSourceInclude include) {
            super(new NodeValue(PapyrusProjectsPresentation.formatIncludeLabel(include), null), !include.getScripts().isEmpty());
            this.scripts = include.getScripts();
        }

        @Override
        void loadChildren() {
            if (!PapyrusProjectsPresentation.requiresScriptGrouping(scripts)) {
                for (ProjectInfoScript script : PapyrusProjectsPresentation.sortedScripts(scripts)) {
                    add(scriptNode(script));
                }
                return;
            }

            for (Map.Entry<String, List<ProjectInfoScript>> entry : PapyrusProjectsPresentation.groupScripts(scripts).entrySet()) {
                add(new ScriptGroupNode(entry.getKey(), entry.getValue()));
            }
        }
    }

    private static final class ScriptGroupNode extends LazyNode {
        private final List<ProjectInfoScript> scripts;

        private ScriptGroupNode(@NotNull String group, @NotNull List<ProjectInfoScript> scripts) {
            super(new NodeValue(group + " (" + scripts.size() + ")", null), !scripts.isEmpty());
            this.scripts = scripts;
        }

        @Override
        void loadChildren() {
            if (scripts.size() <= SCRIPT_PAGE_SIZE) {
                for (ProjectInfoScript script : scripts) {
                    add(scriptNode(script));
                }
                return;
            }

            for (int start = 0; start < scripts.size(); start += SCRIPT_PAGE_SIZE) {
                int end = Math.min(start + SCRIPT_PAGE_SIZE, scripts.size());
                add(new ScriptPageNode(start, end, scripts.subList(start, end)));
            }
        }
    }

    private static final class ScriptPageNode extends LazyNode {
        private final List<ProjectInfoScript> scripts;

        private ScriptPageNode(int start, int end, @NotNull List<ProjectInfoScript> scripts) {
            super(new NodeValue((start + 1) + "-" + end, null), !scripts.isEmpty());
            this.scripts = List.copyOf(scripts);
        }

        @Override
        void loadChildren() {
            for (ProjectInfoScript script : scripts) {
                add(scriptNode(script));
            }
        }
    }

    private static DefaultMutableTreeNode scriptNode(ProjectInfoScript script) {
        return new DefaultMutableTreeNode(new NodeValue(
                nonBlank(script.getIdentifier(), "Script"),
                script.getFilePath()
        ));
    }



    private static final class PapyrusTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public @NotNull Component getTreeCellRendererComponent(
                javax.swing.JTree tree,
                Object value,
                boolean selected,
                boolean expanded,
                boolean leaf,
                int row,
                boolean hasFocus
        ) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof NodeValue(String label, String filePath)) {
                if (filePath != null) {
                    setIcon(PapyrusIcons.SCRIPT);
                } else if (label.endsWith(" [remote]")) {
                    setIcon(PapyrusIcons.SCRIPT_LINK);
                }
            }
            return this;
        }
    }

    private record NodeValue(String label, String filePath) {
        @Override
        public @NotNull String toString() {
            return label;
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
