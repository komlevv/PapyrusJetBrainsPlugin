package dev.papyrus.jetbrains.debug;

import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XStackFrame;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

final class PapyrusExecutionStack extends XExecutionStack {

    private static final int PAGE_SIZE = 100;

    private final PapyrusDebugProcess process;
    private final int threadId;
    private final PapyrusStackFrame topFrame;

    PapyrusExecutionStack(
            @NotNull PapyrusDebugProcess process,
            int threadId,
            @NotNull String displayName,
            @Nullable PapyrusStackFrame topFrame
    ) {
        super(displayName);
        this.process = process;
        this.threadId = threadId;
        this.topFrame = topFrame;
    }

    int getThreadId() {
        return threadId;
    }

    @Override
    public @Nullable XStackFrame getTopFrame() {
        return topFrame;
    }

    @Override
    public void computeStackFrames(int firstFrameIndex, @NotNull XStackFrameContainer container) {
        process.requestStackFrames(threadId, firstFrameIndex, PAGE_SIZE)
                .thenAccept(frames -> {
                    if (!container.isObsolete()) {
                        container.addStackFrames(frames, frames.size() < PAGE_SIZE);
                    }
                })
                .exceptionally(error -> {
                    if (!container.isObsolete()) {
                        container.errorOccurred(PapyrusDebugProcess.rootMessage(error));
                    }
                    return null;
                });
    }
}
