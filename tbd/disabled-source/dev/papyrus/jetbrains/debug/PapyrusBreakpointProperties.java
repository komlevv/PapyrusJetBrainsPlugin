package dev.papyrus.jetbrains.debug;

import com.intellij.xdebugger.breakpoints.XBreakpointProperties;
import org.jetbrains.annotations.NotNull;

public final class PapyrusBreakpointProperties extends XBreakpointProperties<PapyrusBreakpointProperties.State> {

    public static final class State {
    }

    private State state = new State();

    @Override
    public @NotNull State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }
}
