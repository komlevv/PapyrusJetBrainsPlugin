package dev.papyrus.jetbrains.run;

import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.SimpleConfigurationType;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NotNullLazyValue;
import org.jetbrains.annotations.NotNull;

public final class PapyrusAttachConfigurationType extends SimpleConfigurationType {

    public static final String ID = "PapyrusAttach";
    public static final String FACTORY_NAME = "Papyrus Attach";

    public PapyrusAttachConfigurationType() {
        super(
                ID,
                FACTORY_NAME,
                "Attach the Papyrus debugger to Skyrim Special Edition/Anniversary Edition",
                NotNullLazyValue.createValue(() -> AllIcons.Actions.StartDebugger)
        );
    }

    @Override
    public @NotNull RunConfiguration createTemplateConfiguration(@NotNull Project project) {
        return new PapyrusAttachRunConfiguration(project, this, "Papyrus: Skyrim SE/AE");
    }
}
