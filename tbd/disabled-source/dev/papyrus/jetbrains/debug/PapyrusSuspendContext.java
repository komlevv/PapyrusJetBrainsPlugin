package dev.papyrus.jetbrains.debug;

import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XSuspendContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

final class PapyrusSuspendContext extends XSuspendContext {

    private final XExecutionStack activeStack;
    private final XExecutionStack[] stacks;

    PapyrusSuspendContext(@NotNull XExecutionStack activeStack, @NotNull List<? extends XExecutionStack> stacks) {
        this.activeStack = activeStack;
        this.stacks = stacks.toArray(XExecutionStack[]::new);
    }

    @Override
    public @NotNull XExecutionStack getActiveExecutionStack() {
        return activeStack;
    }

    @Override
    public XExecutionStack @NotNull [] getExecutionStacks() {
        return stacks.clone();
    }
}
