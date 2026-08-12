package dev.papyrus.jetbrains.projects;

import com.intellij.openapi.project.Project;
import com.intellij.ide.HelpTooltipKt;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

final class PapyrusScriptNavigatorDialog extends DialogWrapper {

    static final String SEARCH_ACCESSIBLE_NAME = "Search Papyrus scripts";

    private final List<PapyrusScriptNavigatorModel.ScriptTarget> targets;
    private final Consumer<PapyrusScriptNavigatorModel.ScriptTarget> onChosen;
    private final JBTextField searchField = new JBTextField();
    private final DefaultListModel<PapyrusScriptNavigatorModel.ScriptTarget> resultModel = new DefaultListModel<>();
    private final JBList<PapyrusScriptNavigatorModel.ScriptTarget> resultList = new JBList<>(resultModel);

    PapyrusScriptNavigatorDialog(
            @NotNull Project project,
            @NotNull List<PapyrusScriptNavigatorModel.ScriptTarget> targets,
            @NotNull Consumer<PapyrusScriptNavigatorModel.ScriptTarget> onChosen
    ) {
        super(project, true);
        this.targets = List.copyOf(targets);
        this.onChosen = onChosen;
        setTitle("Navigate to Papyrus Script");
        setOKButtonText("Open");
        setModal(false);

        searchField.getAccessibleContext().setAccessibleName(SEARCH_ACCESSIBLE_NAME);
        HelpTooltipKt.setToolTipText(searchField, HtmlChunk.text("Filter by script identifier, project, source include, or file path"));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                updateResults();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                updateResults();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                updateResults();
            }
        });
        searchField.addActionListener(event -> doOKAction());

        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setVisibleRowCount(14);
        resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && event.getButton() == MouseEvent.BUTTON1) {
                    doOKAction();
                }
            }
        });

        init();
        updateResults();
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        JPanel searchPanel = new JPanel(new BorderLayout(8, 0));
        searchPanel.add(new JBLabel("Search:"), BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);
        panel.add(searchPanel, BorderLayout.NORTH);
        panel.add(new JBScrollPane(resultList), BorderLayout.CENTER);
        panel.setPreferredSize(JBUI.size(720, 420));
        return panel;
    }

    @Override
    public @NotNull JComponent getPreferredFocusedComponent() {
        return searchField;
    }

    @Override
    protected void doOKAction() {
        PapyrusScriptNavigatorModel.ScriptTarget target = resultList.getSelectedValue();
        if (target == null) {
            return;
        }
        super.doOKAction();
        onChosen.accept(target);
    }

    private void updateResults() {
        List<PapyrusScriptNavigatorModel.ScriptTarget> matches = PapyrusScriptNavigatorModel.search(
                targets,
                searchField.getText(),
                PapyrusScriptNavigatorModel.DEFAULT_RESULT_LIMIT
        );
        resultModel.clear();
        for (PapyrusScriptNavigatorModel.ScriptTarget target : matches) {
            resultModel.addElement(target);
        }
        if (!matches.isEmpty()) {
            resultList.setSelectedIndex(0);
            resultList.ensureIndexIsVisible(0);
        }
        setOKActionEnabled(!matches.isEmpty());
    }
}
