package dev.papyrus.jetbrains.config;

import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.platform.lsp.api.LspClientManager;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import dev.papyrus.jetbrains.lsp.PapyrusLspIntegrationProvider;
import dev.papyrus.jetbrains.projects.PapyrusProjectsService;
import dev.papyrus.jetbrains.status.PapyrusScriptStatusService;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.util.Objects;

public final class PapyrusSettingsConfigurable implements Configurable {

    private JPanel panel;
    private JBCheckBox enabledCheckBox;
    private JBTextField creationKitInstallPathField;
    private JBTextField compilerPathOverrideField;
    private JBTextArea iniPathsField;
    private JBTextField ambientProjectNameField;
    private JBTextField flagsFileNameField;

    @Override
    public @Nls String getDisplayName() {
        return "Papyrus";
    }

    @Override
    public @Nullable JComponent createComponent() {
        enabledCheckBox = new JBCheckBox("Enable Papyrus language service");
        creationKitInstallPathField = new JBTextField();
        compilerPathOverrideField = new JBTextField();
        iniPathsField = new JBTextArea(5, 60);
        ambientProjectNameField = new JBTextField();
        flagsFileNameField = new JBTextField();

        JBScrollPane iniScrollPane = new JBScrollPane(iniPathsField);
        iniScrollPane.setPreferredSize(JBUI.size(700, 110));

        panel = FormBuilder.createFormBuilder()
                .addComponent(enabledCheckBox)
                .addLabeledComponent(new JBLabel("Creation Kit installation path for Skyrim Special Edition:"), creationKitInstallPathField, 1, false)
                .addLabeledComponent(new JBLabel("Papyrus Compiler path override:"), compilerPathOverrideField, 1, false)
                .addLabeledComponent(new JBLabel("Creation Kit INI paths, one per line:"), iniScrollPane, 1, true)
                .addLabeledComponent(new JBLabel("Ambient project name:"), ambientProjectNameField, 1, false)
                .addLabeledComponent(new JBLabel("Papyrus flags file name:"), flagsFileNameField, 1, false)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        PapyrusSettings.SettingsState state = PapyrusSettings.getInstance().getState();
        return enabledCheckBox.isSelected() != state.enabled
                || !Objects.equals(creationKitInstallPathField.getText().trim(), state.creationKitInstallPath)
                || !Objects.equals(compilerPathOverrideField.getText().trim(), state.compilerPathOverride)
                || !Objects.equals(normalizeMultiline(iniPathsField.getText()), normalizeMultiline(state.iniPaths))
                || !Objects.equals(ambientProjectNameField.getText().trim(), state.ambientProjectName)
                || !Objects.equals(flagsFileNameField.getText().trim(), state.flagsFileName);
    }

    @Override
    public void apply() {
        PapyrusSettings.SettingsState state = PapyrusSettings.getInstance().getState();
        boolean oldEnabled = state.enabled;
        boolean newEnabled = enabledCheckBox.isSelected();
        String newCreationKitPath = creationKitInstallPathField.getText().trim();
        String newCompilerOverride = compilerPathOverrideField.getText().trim();
        String newIniPaths = normalizeMultiline(iniPathsField.getText());
        String newAmbientProjectName = ambientProjectNameField.getText().trim();
        String newFlagsFileName = flagsFileNameField.getText().trim();

        boolean enabledChanged = newEnabled != oldEnabled;
        boolean lspConfigurationChanged = !Objects.equals(newCreationKitPath, state.creationKitInstallPath)
                || !Objects.equals(newCompilerOverride, state.compilerPathOverride)
                || !Objects.equals(newIniPaths, normalizeMultiline(state.iniPaths))
                || !Objects.equals(newAmbientProjectName, state.ambientProjectName)
                || !Objects.equals(newFlagsFileName, state.flagsFileName);

        state.enabled = newEnabled;
        state.creationKitInstallPath = newCreationKitPath;
        state.compilerPathOverride = newCompilerOverride;
        state.iniPaths = newIniPaths;
        state.ambientProjectName = newAmbientProjectName;
        state.flagsFileName = newFlagsFileName;

        if (enabledChanged || lspConfigurationChanged) {
            for (Project project : ProjectManager.getInstance().getOpenProjects()) {
                if (project.isDisposed() || project.isDefault() || !hasPapyrusActivity(project)) {
                    continue;
                }
                PapyrusScriptStatusService.getInstance(project).invalidateAll();
                PapyrusProjectsService.getInstance(project).invalidateSnapshot();
                LspClientManager clientManager = LspClientManager.getInstance(project);
                if (!newEnabled) {
                    clientManager.stopClients(PapyrusLspIntegrationProvider.class);
                } else if (enabledChanged) {
                    clientManager.startClientsIfNeeded(PapyrusLspIntegrationProvider.class);
                } else {
                    clientManager.stopAndRestartClientsIfNeeded(PapyrusLspIntegrationProvider.class);
                }
            }
        }
    }

    @Override
    public void reset() {
        if (enabledCheckBox == null) {
            return;
        }

        PapyrusSettings.SettingsState state = PapyrusSettings.getInstance().getState();
        enabledCheckBox.setSelected(state.enabled);
        creationKitInstallPathField.setText(state.creationKitInstallPath);
        compilerPathOverrideField.setText(state.compilerPathOverride);
        iniPathsField.setText(state.iniPaths);
        ambientProjectNameField.setText(state.ambientProjectName);
        flagsFileNameField.setText(state.flagsFileName);
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        enabledCheckBox = null;
        creationKitInstallPathField = null;
        compilerPathOverrideField = null;
        iniPathsField = null;
        ambientProjectNameField = null;
        flagsFileNameField = null;
    }

    private static boolean hasPapyrusActivity(@NotNull Project project) {
        if (!LspClientManager.getInstance(project).getClients(PapyrusLspIntegrationProvider.class).isEmpty()) {
            return true;
        }
        for (com.intellij.openapi.vfs.VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles()) {
            String extension = file.getExtension();
            if ("psc".equalsIgnoreCase(extension) || "ppj".equalsIgnoreCase(extension)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeMultiline(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").trim();
    }
}
