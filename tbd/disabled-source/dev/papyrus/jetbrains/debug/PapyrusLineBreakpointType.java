package dev.papyrus.jetbrains.debug;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.breakpoints.XLineBreakpointType;
import org.jetbrains.annotations.NotNull;

public final class PapyrusLineBreakpointType extends XLineBreakpointType<PapyrusBreakpointProperties> {

    public PapyrusLineBreakpointType() {
        super("papyrus-line", "Papyrus Line Breakpoints");
    }

    @Override
    public boolean canPutAt(@NotNull VirtualFile file, int line, @NotNull Project project) {
        return "psc".equalsIgnoreCase(file.getExtension());
    }

    @Override
    public PapyrusBreakpointProperties createBreakpointProperties(@NotNull VirtualFile file, int line) {
        return new PapyrusBreakpointProperties();
    }
}
