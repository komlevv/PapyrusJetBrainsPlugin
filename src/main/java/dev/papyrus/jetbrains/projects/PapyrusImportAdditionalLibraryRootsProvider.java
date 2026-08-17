package dev.papyrus.jetbrains.projects;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider;
import com.intellij.openapi.roots.SyntheticLibrary;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/** Provides current local PPJ imports to IntelliJ as a synthetic source library. */
public final class PapyrusImportAdditionalLibraryRootsProvider extends AdditionalLibraryRootsProvider {

    @Override
    public @NotNull Collection<SyntheticLibrary> getAdditionalProjectLibraries(@NotNull Project project) {
        PapyrusImportSyntheticLibrary library = PapyrusImportLibraryService.getInstance(project).getCurrentLibrary();
        return library == null ? List.of() : List.of(library);
    }
}
