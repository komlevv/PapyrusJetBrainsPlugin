package dev.papyrus.jetbrains.projects;

import com.intellij.icons.AllIcons;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.roots.SyntheticLibrary;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable IntelliJ synthetic-library snapshot for the local import roots of a Papyrus project.
 *
 * <p>The same physical directory may also be normal project content. Keeping import membership in
 * a synthetic library preserves that second role without mutating the module model.</p>
 */
public final class PapyrusImportSyntheticLibrary extends SyntheticLibrary implements ItemPresentation {

    static final String PRESENTABLE_NAME = "Papyrus Imports";

    private final List<VirtualFile> sourceRoots;
    private final Map<String, String> labelsByRootUrl;
    private final List<String> rootUrls;

    PapyrusImportSyntheticLibrary(
            @NotNull Collection<? extends VirtualFile> sourceRoots,
            @NotNull Map<String, String> labelsByRootUrl
    ) {
        super("papyrus-imports", null);
        this.sourceRoots = List.copyOf(sourceRoots);
        this.labelsByRootUrl = Map.copyOf(new LinkedHashMap<>(labelsByRootUrl));
        this.rootUrls = this.sourceRoots.stream().map(VirtualFile::getUrl).toList();
    }

    @Override
    public @NotNull Collection<VirtualFile> getSourceRoots() {
        return sourceRoots;
    }

    @Override
    public @NotNull String getPresentableText() {
        return PRESENTABLE_NAME;
    }

    @Override
    public @Nullable Icon getIcon(boolean unused) {
        return AllIcons.Nodes.PpLibFolder;
    }

    @Nullable String getRootDisplayLabel(@NotNull VirtualFile root) {
        return labelsByRootUrl.get(root.getUrl());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PapyrusImportSyntheticLibrary that)) {
            return false;
        }
        return rootUrls.equals(that.rootUrls) && labelsByRootUrl.equals(that.labelsByRootUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rootUrls, labelsByRootUrl);
    }
}
