package dev.papyrus.jetbrains.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.LightVirtualFile;
import dev.papyrus.jetbrains.lsp.PapyrusLanguageService;
import dev.papyrus.jetbrains.protocol.DocumentAssembly;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;

public final class ViewAssemblyAction extends AnAction implements DumbAware {
    private static final long ASSEMBLY_RETRY_TIMEOUT_NANOS = Duration.ofSeconds(10).toNanos();
    private static final long ASSEMBLY_RETRY_DELAY_MILLIS = 100L;

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile sourceFile = PapyrusActionUtil.getFile(event);
        if (project == null || !PapyrusActionUtil.isExtension(sourceFile, "psc")) {
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String assembly = requestAssemblyWithRetry(project, sourceFile);
                if (assembly == null || assembly.isBlank()) {
                    ApplicationManager.getApplication().invokeLater(
                            () -> PapyrusActionUtil.showError(project, "Failed to load Papyrus assembly from the language server.")
                    );
                    return;
                }

                ApplicationManager.getApplication().invokeLater(() -> openAssemblyEditor(project, sourceFile, assembly));
            } catch (RuntimeException throwable) {
                ApplicationManager.getApplication().invokeLater(
                        () -> PapyrusActionUtil.showError(project, "Failed to load Papyrus assembly: " + throwable.getMessage())
                );
            }
        });
    }


    private static void openAssemblyEditor(
            @NotNull Project project,
            @NotNull VirtualFile sourceFile,
            @NotNull String assembly
    ) {
        if (project.isDisposed()) {
            return;
        }

        String name = sourceFile.getNameWithoutExtension() + ".disassemble.pas";
        try {
            // Leave the assigned file type unset so the IDE can run FileTypeIdentifiableByVirtualFile
            // detectors. TextMateFileType uses that path to resolve the VSIX grammar by file name.
            LightVirtualFile assemblyFile = new LightVirtualFile(name, assembly);
            FileType detectedType = FileTypeManager.getInstance().getFileTypeByFile(assemblyFile);
            if (!"textmate".equalsIgnoreCase(detectedType.getName())) {
                PapyrusActionUtil.showError(
                        project,
                        "Papyrus Assembly TextMate grammar is unavailable for " + name
                                + " (detected file type: " + detectedType.getName() + ")."
                );
                return;
            }

            // Pin the successfully detected TextMate type on the in-memory file after detection.
            assemblyFile.setFileType(detectedType);
            assemblyFile.setWritable(false);
            FileEditorManager.getInstance(project).openFile(assemblyFile, true);
        } catch (RuntimeException exception) {
            PapyrusActionUtil.showError(
                    project,
                    "Failed to open Papyrus assembly editor: "
                            + exception.getClass().getSimpleName()
                            + ": "
                            + exception.getMessage()
            );
        }
    }

    private static String requestAssemblyWithRetry(@NotNull Project project, @NotNull VirtualFile sourceFile) {
        PapyrusLanguageService languageService = PapyrusLanguageService.getInstance(project);
        long deadline = System.nanoTime() + ASSEMBLY_RETRY_TIMEOUT_NANOS;

        while (!project.isDisposed()) {
            DocumentAssembly result = languageService.requestAssembly(sourceFile);
            String assembly = result != null ? result.getAssembly() : null;
            if (assembly != null && !assembly.isBlank()) {
                return assembly;
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            LockSupport.parkNanos(Duration.ofMillis(ASSEMBLY_RETRY_DELAY_MILLIS).toNanos());
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
        }
        return null;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(
                event.getProject() != null && PapyrusActionUtil.isExtension(PapyrusActionUtil.getFile(event), "psc")
        );
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
