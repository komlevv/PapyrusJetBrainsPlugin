package dev.papyrus.jetbrains.projects;

import com.intellij.icons.AllIcons;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.openapi.project.DumbAware;
import com.intellij.psi.PsiDirectory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Applies Papyrus include labels only to directory roots shown inside the Papyrus synthetic
 * library. The ordinary Project-view node for the same physical directory remains unchanged.
 */
public final class PapyrusImportProjectViewDecorator implements ProjectViewNodeDecorator, DumbAware {

    @Override
    public void decorate(@NotNull ProjectViewNode<?> node, @NotNull PresentationData data) {
        Object value = node.getValue();
        if (!(value instanceof PsiDirectory directory)) {
            return;
        }

        var parent = node.getParent();
        Object parentValue = parent == null ? null : parent.getValue();
        PapyrusImportSyntheticLibrary library = syntheticLibraryParent(parentValue);
        if (library == null) {
            return;
        }

        String label = library.getRootDisplayLabel(directory.getVirtualFile());
        if (label != null && !label.isBlank()) {
            data.setPresentableText(label);
        }
        data.setIcon(AllIcons.Nodes.PpLibFolder);
    }

    static boolean isPapyrusImportLibraryParent(@Nullable Object parentValue) {
        return parentValue instanceof PapyrusImportSyntheticLibrary;
    }

    private static @Nullable PapyrusImportSyntheticLibrary syntheticLibraryParent(@Nullable Object parentValue) {
        return parentValue instanceof PapyrusImportSyntheticLibrary library ? library : null;
    }
}
