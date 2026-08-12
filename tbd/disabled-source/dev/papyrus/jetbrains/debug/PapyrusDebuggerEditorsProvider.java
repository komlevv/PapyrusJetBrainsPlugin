package dev.papyrus.jetbrains.debug;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.EvaluationMode;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class PapyrusDebuggerEditorsProvider extends XDebuggerEditorsProvider {

    @Override
    public @NotNull FileType getFileType() {
        return PlainTextFileType.INSTANCE;
    }

    @Override
    public @NotNull Document createDocument(
            @NotNull Project project,
            @NotNull XExpression expression,
            @Nullable XSourcePosition sourcePosition,
            @NotNull EvaluationMode mode,
            @Nullable String purpose
    ) {
        return EditorFactory.getInstance().createDocument(expression.getExpression());
    }

    @Override
    public boolean isEvaluateExpressionFieldEnabled() {
        return true;
    }
}
