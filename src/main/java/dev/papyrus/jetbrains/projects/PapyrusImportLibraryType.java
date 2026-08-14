package dev.papyrus.jetbrains.projects;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.libraries.LibraryProperties;
import com.intellij.openapi.roots.libraries.LibraryType;
import com.intellij.openapi.roots.libraries.NewLibraryConfiguration;
import com.intellij.openapi.roots.libraries.PersistentLibraryKind;
import com.intellij.openapi.roots.libraries.ui.LibraryEditorComponent;
import com.intellij.openapi.roots.libraries.ui.LibraryPropertiesEditor;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;

/**
 * Library type for Papyrus import source roots.
 *
 * <p>Plain IntelliJ libraries are considered external from their CLASSES roots only. Papyrus
 * dependencies are source-only, so this type explicitly exposes SOURCES as its external roots.
 * That keeps imports under External Libraries without misclassifying source directories as
 * compiled library classes.</p>
 */
public final class PapyrusImportLibraryType extends LibraryType<PapyrusImportLibraryType.Properties> {

    static final PersistentLibraryKind<Properties> KIND = new PersistentLibraryKind<>(
            "dev.papyrus.intellij.imports"
    ) {
        @Override
        public @NotNull Properties createDefaultProperties() {
            return new Properties();
        }
    };

    private static final OrderRootType[] EXTERNAL_ROOT_TYPES = {OrderRootType.SOURCES};

    public PapyrusImportLibraryType() {
        super(KIND);
    }

    @Override
    public @Nullable String getCreateActionName() {
        return null;
    }

    @Override
    public @Nullable NewLibraryConfiguration createNewLibrary(
            @NotNull JComponent parentComponent,
            @Nullable VirtualFile contextDirectory,
            @NotNull Project project
    ) {
        return null;
    }

    @Override
    public @Nullable LibraryPropertiesEditor createPropertiesEditor(
            @NotNull LibraryEditorComponent<Properties> editorComponent
    ) {
        return null;
    }

    @Override
    public OrderRootType @NotNull [] getExternalRootTypes() {
        return EXTERNAL_ROOT_TYPES.clone();
    }

    public static final class Properties extends LibraryProperties<Object> {
        @Override
        public @Nullable Object getState() {
            return null;
        }

        @Override
        public void loadState(@NotNull Object state) {
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof Properties;
        }

        @Override
        public int hashCode() {
            return 0;
        }
    }
}
