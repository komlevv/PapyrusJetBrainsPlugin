package dev.papyrus.jetbrains.projects;

import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDirectory;
import org.jetbrains.annotations.NotNull;

/**
 * Reuses Papyrus project include labels for managed import roots in Project View.
 */
public final class PapyrusImportProjectViewDecorator implements ProjectViewNodeDecorator, DumbAware {

    @Override
    public void decorate(@NotNull ProjectViewNode<?> node, @NotNull PresentationData data) {
        Object value = node.getValue();
        if (!(value instanceof PsiDirectory directory)) {
            return;
        }

        Project project = node.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }

        String label = PapyrusImportLibraryService.getInstance(project)
                .getImportRootDisplayLabel(directory.getVirtualFile());
        if (label != null && !label.isBlank()) {
            data.setPresentableText(label);
        }
    }
}
