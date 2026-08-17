package dev.papyrus.jetbrains.editor;

import com.intellij.lang.Language;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.LanguageSubstitutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Gives Papyrus project files the IntelliJ Platform XML PSI while leaving their file type intact.
 *
 * <p>The upstream VSIX declares {@code .ppj} as its own TextMate language. That is useful for
 * VS Code presentation, but TextMate alone cannot provide IntelliJ's structural XML editing
 * features. Language substitution keeps the existing PPJ file-type ownership and Papyrus project
 * semantics while letting the platform XML parser provide tag matching, tag completion, formatting,
 * and other XML-aware editor behavior.</p>
 */
public final class PapyrusProjectXmlLanguageSubstitutor extends LanguageSubstitutor {
    private static final String XML_LANGUAGE_ID = "XML";

    @Override
    public @Nullable Language getLanguage(@NotNull VirtualFile file, @NotNull Project project) {
        String extension = file.getExtension();
        if (extension == null || !extension.equalsIgnoreCase("ppj")) {
            return null;
        }
        return Language.findLanguageByID(XML_LANGUAGE_ID);
    }
}
