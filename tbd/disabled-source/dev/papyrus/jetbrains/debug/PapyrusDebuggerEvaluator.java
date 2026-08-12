package dev.papyrus.jetbrains.debug;

import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class PapyrusDebuggerEvaluator extends XDebuggerEvaluator {

    private final PapyrusDebugProcess process;
    private final int frameId;

    PapyrusDebuggerEvaluator(@NotNull PapyrusDebugProcess process, int frameId) {
        this.process = process;
        this.frameId = frameId;
    }

    @Override
    public void evaluate(
            @NotNull String expression,
            @NotNull XEvaluationCallback callback,
            @Nullable XSourcePosition expressionPosition
    ) {
        process.evaluate(expression, frameId)
                .thenAccept(callback::evaluated)
                .exceptionally(error -> {
                    callback.errorOccurred(PapyrusDebugProcess.rootMessage(error));
                    return null;
                });
    }

    @Override
    public boolean isCodeFragmentEvaluationSupported() {
        return false;
    }
}
