package dev.papyrus.jetbrains.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.LightVirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class ShowPapyrusWelcomeAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null) {
            return;
        }

        try (InputStream stream = ShowPapyrusWelcomeAction.class.getResourceAsStream("/papyrus-welcome.md")) {
            if (stream == null) {
                throw new IOException("Bundled Papyrus help document was not found.");
            }
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            LightVirtualFile file = new LightVirtualFile(
                    "Papyrus Getting Started.md",
                    FileTypeManager.getInstance().getFileTypeByExtension("md"),
                    content
            );
            file.setWritable(false);
            FileEditorManager.getInstance(project).openFile(file, true);
        } catch (IOException | RuntimeException throwable) {
            PapyrusActionUtil.showError(project, "Failed to open Papyrus help: " + throwable.getMessage());
        }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabled(event.getProject() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
