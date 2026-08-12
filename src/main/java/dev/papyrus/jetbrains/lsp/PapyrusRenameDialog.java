package dev.papyrus.jetbrains.lsp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;

final class PapyrusRenameDialog extends DialogWrapper {

    static final String FIELD_ACCESSIBLE_NAME = "Papyrus rename new name";

    private final JBTextField nameField;

    PapyrusRenameDialog(@NotNull Project project, @NotNull String currentName) {
        super(project, true);
        setTitle("Rename Papyrus Symbol");
        setOKButtonText("Rename");
        nameField = new JBTextField(currentName);
        nameField.setColumns(32);
        nameField.getAccessibleContext().setAccessibleName(FIELD_ACCESSIBLE_NAME);
        nameField.selectAll();
        nameField.addActionListener(event -> doOKAction());
        init();
    }

    @Override
    protected @NotNull JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(new JBLabel("New name:"), BorderLayout.WEST);
        panel.add(nameField, BorderLayout.CENTER);
        panel.setPreferredSize(JBUI.size(420, panel.getPreferredSize().height));
        return panel;
    }

    @Override
    public @NotNull JComponent getPreferredFocusedComponent() {
        return nameField;
    }

    @NotNull String newName() {
        return nameField.getText().trim();
    }

    @Override
    protected void doOKAction() {
        String value = newName();
        if (value.isEmpty()) {
            setErrorText("Papyrus symbol name cannot be empty.", nameField);
            return;
        }
        if (!PapyrusRenameSafety.isValidRenameIdentifier(value)) {
            setErrorText(
                    "Use a Papyrus identifier: letters, digits, and underscores only; "
                            + "the first character must be a letter or underscore, and keywords are not allowed.",
                    nameField
            );
            return;
        }
        super.doOKAction();
    }
}
