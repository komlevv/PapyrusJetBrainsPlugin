package dev.papyrus.jetbrains.status;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.function.Function;

public final class PapyrusScriptStatusEditorNotificationProvider implements EditorNotificationProvider, DumbAware {

    @Override
    public @Nullable Function<? super @NotNull FileEditor, ? extends @Nullable JComponent> collectNotificationData(
            @NotNull Project project,
            @NotNull VirtualFile file
    ) {
        if (!"psc".equalsIgnoreCase(file.getExtension())) {
            return null;
        }

        PapyrusScriptStatusService service = PapyrusScriptStatusService.getInstance(project);
        PapyrusScriptStatusService.ScriptStatus status = service.getSnapshot(file);
        service.scheduleRefresh(file);

        if (status == null || (!status.unresolved() && !status.overridden())) {
            return null;
        }

        return fileEditor -> createPanel(project, fileEditor, status);
    }

    private static @NotNull EditorNotificationPanel createPanel(
            @NotNull Project project,
            @NotNull FileEditor fileEditor,
            @NotNull PapyrusScriptStatusService.ScriptStatus status
    ) {
        EditorNotificationPanel panel = new EditorNotificationPanel(fileEditor);
        if (status.unresolved()) {
            panel.setText("Script is not included in a Papyrus project or any configured Creation Kit source path.");
            return panel;
        }

        panel.setText("This script is overridden by another source file. Papyrus language features use the overriding file.");
        String overridingFile = status.overridingFile();
        if (overridingFile != null && !overridingFile.isBlank()) {
            panel.createActionLabel("Open overriding file", () -> {
                VirtualFile target = LocalFileSystem.getInstance().refreshAndFindFileByPath(overridingFile);
                if (target != null) {
                    FileEditorManager.getInstance(project).openFile(target, true);
                }
            });
        }
        return panel;
    }
}
