package dev.papyrus.jetbrains.run;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

final class PapyrusProjectRunConfigurationEditor extends SettingsEditor<PapyrusProjectRunConfiguration> {
    private final TextFieldWithBrowseButton projectFileField = new TextFieldWithBrowseButton();
    private final JPanel panel = new JPanel(new GridBagLayout());

    PapyrusProjectRunConfigurationEditor(@NotNull Project project) {
        projectFileField.getTextField().getAccessibleContext().setAccessibleName("Papyrus project file");
        projectFileField.addBrowseFolderListener(
                project,
                FileChooserDescriptorFactory.singleFile()
                        .withTitle("Select Papyrus Project")
                        .withExtensionFilter("ppj")
                        .withDescription("Select a project-local .ppj file to compile with bundled Pyro.")
        );

        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = 0;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = JBUI.insetsRight(8);
        panel.add(new JLabel("Project file:"), labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = 0;
        fieldConstraints.weightx = 1.0;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(projectFileField, fieldConstraints);

        GridBagConstraints noteConstraints = new GridBagConstraints();
        noteConstraints.gridx = 0;
        noteConstraints.gridy = 1;
        noteConstraints.gridwidth = 2;
        noteConstraints.anchor = GridBagConstraints.WEST;
        noteConstraints.insets = JBUI.insetsTop(8);
        panel.add(
                new JLabel("Builder: bundled Pyro; writes are restricted to the validated project-local Output directory."),
                noteConstraints
        );

        GridBagConstraints spacer = new GridBagConstraints();
        spacer.gridx = 0;
        spacer.gridy = 2;
        spacer.gridwidth = 2;
        spacer.weighty = 1.0;
        spacer.fill = GridBagConstraints.VERTICAL;
        panel.add(new JPanel(new BorderLayout()), spacer);
    }

    @Override
    protected void resetEditorFrom(@NotNull PapyrusProjectRunConfiguration configuration) {
        projectFileField.setText(configuration.getProjectFile());
    }

    @Override
    protected void applyEditorTo(@NotNull PapyrusProjectRunConfiguration configuration) throws ConfigurationException {
        String value = projectFileField.getText().trim();
        if (value.isEmpty()) {
            throw new ConfigurationException("Papyrus project file is required.");
        }
        configuration.setProjectFile(configuration.collapseProjectFile(value));
    }

    @Override
    protected @NotNull JComponent createEditor() {
        return panel;
    }
}
