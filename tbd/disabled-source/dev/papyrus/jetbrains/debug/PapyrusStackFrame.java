package dev.papyrus.jetbrains.debug;

import com.google.gson.JsonObject;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTextContainer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XValueChildrenList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class PapyrusStackFrame extends XStackFrame {

    private final PapyrusDebugProcess process;
    private final int frameId;
    private final String name;
    private final VirtualFile sourceFile;
    private final int line;

    private PapyrusStackFrame(
            @NotNull PapyrusDebugProcess process,
            int frameId,
            @NotNull String name,
            @Nullable VirtualFile sourceFile,
            int line
    ) {
        this.process = process;
        this.frameId = frameId;
        this.name = name;
        this.sourceFile = sourceFile;
        this.line = line;
    }

    static @NotNull PapyrusStackFrame fromJson(
            @NotNull PapyrusDebugProcess process,
            @NotNull JsonObject object
    ) {
        JsonObject source = DapConnection.object(object, "source");
        String sourcePath = source != null ? DapConnection.string(source, "path") : null;
        VirtualFile sourceFile = sourcePath != null && !sourcePath.isBlank()
                ? LocalFileSystem.getInstance().refreshAndFindFileByPath(sourcePath)
                : null;
        return new PapyrusStackFrame(
                process,
                DapConnection.integer(object, "id", 0),
                valueOr(DapConnection.string(object, "name"), "Papyrus frame"),
                sourceFile,
                Math.max(0, DapConnection.integer(object, "line", 1) - 1)
        );
    }

    @Override
    public @Nullable XSourcePosition getSourcePosition() {
        return sourceFile != null ? XDebuggerUtil.getInstance().createPosition(sourceFile, line) : null;
    }

    @Override
    public void customizePresentation(@NotNull ColoredTextContainer component) {
        component.append(name, SimpleTextAttributes.REGULAR_ATTRIBUTES);
        XSourcePosition position = getSourcePosition();
        if (position != null) {
            component.append(
                    "  " + position.getFile().getName() + ":" + (position.getLine() + 1),
                    SimpleTextAttributes.GRAYED_ATTRIBUTES
            );
        }
    }

    @Override
    public @NotNull XDebuggerEvaluator getEvaluator() {
        return new PapyrusDebuggerEvaluator(process, frameId);
    }

    @Override
    public void computeChildren(@NotNull XCompositeNode node) {
        process.requestScopes(frameId)
                .thenAccept(scopes -> {
                    if (node.isObsolete()) {
                        return;
                    }
                    XValueChildrenList children = new XValueChildrenList(scopes.size());
                    for (PapyrusScopeValue scope : scopes) {
                        children.add(scope);
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

    @Override
    public @NotNull Object getEqualityObject() {
        return frameId;
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
