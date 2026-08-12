package dev.papyrus.jetbrains.debug;

import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XNamedValue;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueNode;
import com.intellij.xdebugger.frame.XValuePlace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class PapyrusVariableValue extends XNamedValue {

    private final PapyrusDebugProcess process;
    private final String value;
    private final String type;
    private final int variablesReference;

    PapyrusVariableValue(
            @NotNull PapyrusDebugProcess process,
            @NotNull String name,
            @NotNull String value,
            @Nullable String type,
            int variablesReference
    ) {
        super(name);
        this.process = process;
        this.value = value;
        this.type = type;
        this.variablesReference = variablesReference;
    }

    @Override
    public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
        node.setPresentation(null, type, value, variablesReference > 0);
    }

    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        if (variablesReference <= 0) {
            node.addChildren(XValueChildrenList.EMPTY, true);
            return;
        }
        process.requestVariables(variablesReference)
                .thenAccept(variables -> {
                    if (node.isObsolete()) {
                        return;
                    }
                    XValueChildrenList children = new XValueChildrenList(variables.size());
                    for (PapyrusVariableValue variable : variables) {
                        children.add(variable);
                    }
                    node.addChildren(children, true);
                })
                .exceptionally(error -> {
                    if (!node.isObsolete()) {
                        node.setErrorMessage(PapyrusDebugProcess.rootMessage(error));
                    }
                    return null;
                });
    }
}
