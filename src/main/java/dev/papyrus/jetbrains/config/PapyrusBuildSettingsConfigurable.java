package dev.papyrus.jetbrains.config;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class PapyrusBuildSettingsConfigurable implements Configurable {
    private static final String IDE_DEFAULT_LABEL = "IDE default";
    private static final String PAPYRUS_LABEL = "Papyrus (Pyro)";

    private final Project project;
    private JPanel panel;
    private ComboBox<String> buildSystemCombo;
    private JBTextField projectFileField;

    public PapyrusBuildSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @Nls String getDisplayName() {
        return "Papyrus Build";
    }

    @Override
    public @Nullable JComponent createComponent() {
        buildSystemCombo = new ComboBox<>(new String[]{IDE_DEFAULT_LABEL, PAPYRUS_LABEL});
        projectFileField = new JBTextField();
        projectFileField.getAccessibleContext().setAccessibleName("Papyrus build project file");

        JBLabel note = new JBLabel(
                "The plugin changes Build Project only when Papyrus (Pyro) is selected for this project."
        );

        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Build system:"), buildSystemCombo, 1, false)
                .addLabeledComponent(new JBLabel("Papyrus project (.ppj):"), projectFileField, 1, false)
                .addComponent(note)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        if (buildSystemCombo == null || projectFileField == null) {
            return false;
        }
        PapyrusProjectSettings.SettingsState state = PapyrusProjectSettings.getInstance(project).getState();
        return !Objects.equals(selectedBuildSystem(), state.buildSystem)
                || !Objects.equals(normalizedProjectFileText(), normalizedStateProjectFile(state.projectFile));
    }

    @Override
    public void apply() throws ConfigurationException {
        PapyrusProjectSettings.SettingsState state = PapyrusProjectSettings.getInstance(project).getState();
        String buildSystem = selectedBuildSystem();
        String projectFile = normalizedProjectFileText();
        if (projectFile.isBlank()) {
            projectFile = PapyrusProjectSettings.DEFAULT_PROJECT_FILE;
        }

        if (PapyrusProjectSettings.BUILD_SYSTEM_PAPYRUS.equals(buildSystem)) {
            validatePapyrusProjectFile(projectFile);
        }

        state.buildSystem = buildSystem;
        state.projectFile = projectFile;
    }

    @Override
    public void reset() {
        if (buildSystemCombo == null || projectFileField == null) {
            return;
        }
        PapyrusProjectSettings.SettingsState state = PapyrusProjectSettings.getInstance(project).getState();
        buildSystemCombo.setSelectedItem(
                PapyrusProjectSettings.BUILD_SYSTEM_PAPYRUS.equals(state.buildSystem)
                        ? PAPYRUS_LABEL
                        : IDE_DEFAULT_LABEL
        );
        projectFileField.setText(normalizedStateProjectFile(state.projectFile));
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        buildSystemCombo = null;
        projectFileField = null;
    }

    private String selectedBuildSystem() {
        Object selected = buildSystemCombo == null ? null : buildSystemCombo.getSelectedItem();
        return PAPYRUS_LABEL.equals(selected)
                ? PapyrusProjectSettings.BUILD_SYSTEM_PAPYRUS
                : PapyrusProjectSettings.BUILD_SYSTEM_IDE;
    }

    private String normalizedProjectFileText() {
        return projectFileField == null ? "" : projectFileField.getText().trim();
    }

    private static String normalizedStateProjectFile(String value) {
        return value == null || value.isBlank() ? PapyrusProjectSettings.DEFAULT_PROJECT_FILE : value.trim();
    }

    private void validatePapyrusProjectFile(String configured) throws ConfigurationException {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            throw new ConfigurationException("Papyrus build requires an IDE project root.");
        }

        try {
            Path root = Path.of(basePath).toRealPath();
            Path candidate = Path.of(configured);
            if (!candidate.isAbsolute()) {
                candidate = root.resolve(candidate);
            }
            Path real = candidate.toRealPath();
            if (!Files.isRegularFile(real)) {
                throw new ConfigurationException("Papyrus project file does not exist: " + candidate);
            }
            if (!real.startsWith(root)) {
                throw new ConfigurationException("Papyrus build project must be inside the IDE project.");
            }
            String fileName = real.getFileName() == null ? "" : real.getFileName().toString();
            if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".ppj")) {
                throw new ConfigurationException("Papyrus build project must be a .ppj file.");
            }
        } catch (ConfigurationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ConfigurationException("Cannot resolve Papyrus project file: " + configured);
        }
    }
}
