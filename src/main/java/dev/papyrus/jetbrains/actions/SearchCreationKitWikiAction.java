package dev.papyrus.jetbrains.actions;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import dev.papyrus.jetbrains.lsp.PapyrusLanguageService;
import dev.papyrus.jetbrains.protocol.DocumentScriptInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class SearchCreationKitWikiAction extends AnAction implements DumbAware {
    private static final String SKYRIM_SEARCH_URL = "https://www.creationkit.com/index.php?search=";

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        VirtualFile file = PapyrusActionUtil.getFile(event);
        if (project == null || editor == null || !PapyrusActionUtil.isExtension(file, "psc")) {
            return;
        }

        String searchText = PapyrusActionUtil.getWikiSearchText(editor);
        if (searchText == null) {
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                DocumentScriptInfo info = PapyrusLanguageService.getInstance(project).requestScriptInfo(file);
                if (!isResolved(info)) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!project.isDisposed()) {
                            PapyrusActionUtil.showError(
                                    project,
                                    "Failed to open Creation Kit search page because this script is currently unresolved."
                            );
                        }
                    });
                    return;
                }

                String searchUrl = buildSkyrimSearchUrl(searchText);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (project.isDisposed()) {
                        return;
                    }
                    try {
                        PapyrusExternalUrlOpener.open(searchUrl);
                    } catch (RuntimeException throwable) {
                        PapyrusActionUtil.showError(
                                project,
                                "Failed to open Creation Kit search page: " + throwable.getMessage()
                        );
                    }
                });
            } catch (RuntimeException throwable) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()) {
                        PapyrusActionUtil.showError(
                                project,
                                "Failed to query Papyrus script status: " + throwable.getMessage()
                        );
                    }
                });
            }
        });
    }

    static boolean isResolved(@Nullable DocumentScriptInfo info) {
        return info != null && !info.getIdentifiers().isEmpty();
    }

    static @NotNull String buildSkyrimSearchUrl(@NotNull String searchText) {
        String encoded = URLEncoder.encode(searchText, StandardCharsets.UTF_8).replace("+", "%20");
        return SKYRIM_SEARCH_URL + encoded;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(
                event.getData(CommonDataKeys.EDITOR) != null
                        && PapyrusActionUtil.isExtension(PapyrusActionUtil.getFile(event), "psc")
        );
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
