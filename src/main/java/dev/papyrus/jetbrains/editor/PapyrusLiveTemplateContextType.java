package dev.papyrus.jetbrains.editor;

import com.intellij.codeInsight.template.TemplateActionContext;
import com.intellij.codeInsight.template.TemplateContextType;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public final class PapyrusLiveTemplateContextType extends TemplateContextType {

    public PapyrusLiveTemplateContextType() {
        super("Papyrus");
    }

    @Override
    public boolean isInContext(@NotNull TemplateActionContext context) {
        PsiFile file = context.getFile().getOriginalFile();
        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile != null) {
            return "psc".equalsIgnoreCase(virtualFile.getExtension());
        }

        String name = file.getName();
        return name.length() >= 4 && name.regionMatches(true, name.length() - 4, ".psc", 0, 4);
    }
}
