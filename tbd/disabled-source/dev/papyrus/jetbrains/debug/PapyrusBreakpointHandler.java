package dev.papyrus.jetbrains.debug;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import org.jetbrains.annotations.NotNull;

final class PapyrusBreakpointHandler extends XBreakpointHandler<XLineBreakpoint<PapyrusBreakpointProperties>> {

    private final PapyrusDebugProcess process;

    PapyrusBreakpointHandler(@NotNull PapyrusDebugProcess process) {
        super(PapyrusLineBreakpointType.class);
        this.process = process;
    }

    @Override
    public void registerBreakpoint(@NotNull XLineBreakpoint<PapyrusBreakpointProperties> breakpoint) {
        process.registerBreakpoint(breakpoint);
    }

    @Override
    public void unregisterBreakpoint(
            @NotNull XLineBreakpoint<PapyrusBreakpointProperties> breakpoint,
            boolean temporary
    ) {
        process.unregisterBreakpoint(breakpoint);
    }

    static VirtualFile getFile(@NotNull XLineBreakpoint<PapyrusBreakpointProperties> breakpoint) {
        return VirtualFileManager.getInstance().findFileByUrl(breakpoint.getFileUrl());
    }
}
