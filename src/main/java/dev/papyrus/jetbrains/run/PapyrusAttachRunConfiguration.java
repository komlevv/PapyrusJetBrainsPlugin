package dev.papyrus.jetbrains.run;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunConfigurationBase;
import com.intellij.execution.configurations.RunConfigurationOptions;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RuntimeConfigurationError;
import com.intellij.execution.configurations.RuntimeConfigurationException;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.JDOMExternalizerUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;

public final class PapyrusAttachRunConfiguration extends RunConfigurationBase<RunConfigurationOptions> {

    public static final String SKYRIM_SE_GAME_ID = "skyrimSpecialEdition";
    public static final String DEFAULT_PROJECT_FILE = "$PROJECT_DIR$/skyrimse.ppj";
    public static final String ATTACH_REQUEST = "attach";

    private String request = ATTACH_REQUEST;
    private String game = SKYRIM_SE_GAME_ID;
    private String projectFile = DEFAULT_PROJECT_FILE;

    public PapyrusAttachRunConfiguration(
            @NotNull Project project,
            @NotNull PapyrusAttachConfigurationType factory,
            @NotNull String name
    ) {
        super(project, factory, name);
    }

    public @NotNull String getRequest() {
        return request;
    }

    public @NotNull String getGame() {
        return game;
    }

    public @NotNull String getProjectFile() {
        return projectFile;
    }

    @Override
    public @NotNull SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
        return new SettingsEditor<PapyrusAttachRunConfiguration>() {
            @Override
            protected void resetEditorFrom(@NotNull PapyrusAttachRunConfiguration configuration) {
            }

            @Override
            protected void applyEditorTo(@NotNull PapyrusAttachRunConfiguration configuration) {
            }

            @Override
            protected @NotNull JComponent createEditor() {
                JPanel panel = new JPanel(new BorderLayout());
                panel.add(
                        new JLabel("Papyrus debugger attach is disabled until the debugger safety gate is completed."),
                        BorderLayout.NORTH
                );
                return panel;
            }
        };
    }

    @Override
    public void checkConfiguration() throws RuntimeConfigurationException {
        if (!ATTACH_REQUEST.equals(request)) {
            throw new RuntimeConfigurationError("Unsupported Papyrus debug request: " + request);
        }
        if (!SKYRIM_SE_GAME_ID.equals(game)) {
            throw new RuntimeConfigurationError("Unsupported Papyrus game: " + game);
        }
        if (projectFile.isBlank()) {
            throw new RuntimeConfigurationError("Papyrus project file is not configured.");
        }
        throw new RuntimeConfigurationError(
                "Papyrus debugger attach is disabled until the debugger safety gate is completed."
        );
    }

    @Override
    public @Nullable RunProfileState getState(
            @NotNull Executor executor,
            @NotNull ExecutionEnvironment environment
    ) {
        return null;
    }

    @Override
    public void readExternal(@NotNull Element element) throws InvalidDataException {
        super.readExternal(element);
        String storedRequest = JDOMExternalizerUtil.readField(element, "request");
        String storedGame = JDOMExternalizerUtil.readField(element, "game");
        String storedProjectFile = JDOMExternalizerUtil.readField(element, "projectFile");
        if (storedRequest != null && !storedRequest.isBlank()) {
            request = storedRequest;
        }
        if (storedGame != null && !storedGame.isBlank()) {
            game = storedGame;
        }
        if (storedProjectFile != null && !storedProjectFile.isBlank()) {
            projectFile = storedProjectFile;
        }
    }

    @Override
    public void writeExternal(@NotNull Element element) {
        super.writeExternal(element);
        JDOMExternalizerUtil.writeField(element, "request", request);
        JDOMExternalizerUtil.writeField(element, "game", game);
        JDOMExternalizerUtil.writeField(element, "projectFile", projectFile);
    }
}
