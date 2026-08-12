package dev.papyrus.jetbrains.run;

import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.SimpleConfigurationType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.NotNullLazyValue;
import org.jetbrains.annotations.NotNull;

public final class PapyrusProjectConfigurationType extends SimpleConfigurationType {
    public static final String ID = "PapyrusProject";
    public static final String FACTORY_NAME = "Papyrus Project";

    public PapyrusProjectConfigurationType() {
        super(
                ID,
                FACTORY_NAME,
                "Compile a safe Skyrim SE/AE Papyrus project with bundled Pyro",
                NotNullLazyValue.createValue(() -> IconLoader.getIcon(
                        "/icons/papyrus/Compile_16x.svg",
                        PapyrusProjectConfigurationType.class
                ))
        );
    }

    @Override
    public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
        return new PapyrusProjectRunConfiguration(project, this, "Papyrus Project");
    }
}
