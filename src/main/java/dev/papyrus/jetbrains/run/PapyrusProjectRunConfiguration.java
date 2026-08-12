package dev.papyrus.jetbrains.run;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.JDOMExternalizerUtil;
import dev.papyrus.jetbrains.actions.PapyrusProjectCompileSafety;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

public final class PapyrusProjectRunConfiguration extends RunConfigurationBase<RunConfigurationOptions> {
    public static final String DEFAULT_PROJECT_FILE = "$PROJECT_DIR$/skyrimse.ppj";

    private String projectFile = DEFAULT_PROJECT_FILE;

    public PapyrusProjectRunConfiguration(
            @NotNull Project project,
            @NotNull PapyrusProjectConfigurationType factory,
            @NotNull String name
    ) {
        super(project, factory, name);
    }

    public @NotNull String getProjectFile() {
        return projectFile;
    }

    public void setProjectFile(@NotNull String projectFile) {
        this.projectFile = projectFile.trim();
    }

    public @NotNull Path resolveProjectFile() {
        String expanded = PathMacroManager.getInstance(getProject()).expandPath(projectFile);
        if (expanded == null || expanded.isBlank()) {
            throw new IllegalArgumentException("Papyrus project file is not configured.");
        }
        Path path = Path.of(expanded);
        if (!path.isAbsolute()) {
            String basePath = getProject().getBasePath();
            if (basePath == null || basePath.isBlank()) {
                throw new IllegalArgumentException("Papyrus project compilation requires an IDE project root.");
            }
            path = Path.of(basePath).resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    public @NotNull String collapseProjectFile(@NotNull String path) {
        return PathMacroManager.getInstance(getProject()).collapsePath(path, true);
    }

    @Override
    public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new PapyrusProjectRunConfigurationEditor(getProject());
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        if (projectFile.isBlank()) {
            throw new RuntimeConfigurationError("Papyrus project file is not configured.");
        }
        String basePath = getProject().getBasePath();
        if (basePath == null || basePath.isBlank()) {
            throw new RuntimeConfigurationError("Papyrus project compilation requires an IDE project root.");
        }
        try {
            PapyrusProjectCompileSafety.validate(Path.of(basePath), resolveProjectFile());
        } catch (Exception exception) {
            throw new RuntimeConfigurationError(safeMessage(exception));
        }
    }

    @Override
    public @NotNull RunProfileState getState(
            @NotNull Executor executor,
            @NotNull ExecutionEnvironment environment
    ) {
        return new PapyrusProjectCommandLineState(environment, this);
    }

    @Override
    public void readExternal(@NotNull Element element) throws InvalidDataException {
        super.readExternal(element);
        String storedProjectFile = JDOMExternalizerUtil.readField(element, "projectFile");
        if (storedProjectFile != null && !storedProjectFile.isBlank()) {
            projectFile = storedProjectFile;
        }
    }

    @Override
    public void writeExternal(@NotNull Element element) {
        super.writeExternal(element);
        JDOMExternalizerUtil.writeField(element, "projectFile", projectFile);
    }

    private static @NotNull String safeMessage(@NotNull Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
