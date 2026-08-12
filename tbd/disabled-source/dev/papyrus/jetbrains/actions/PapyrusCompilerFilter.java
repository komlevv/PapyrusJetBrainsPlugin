package dev.papyrus.jetbrains.actions;

import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.execution.filters.RegexpFilter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;

final class PapyrusCompilerFilter extends RegexpFilter {

    private final Project project;

    PapyrusCompilerFilter(@NotNull Project project) {
        super(project, "^$FILE_PATH$\\($LINE$,$COLUMN$\\):.*");
        this.project = project;
    }

    @Override
    protected @Nullable HyperlinkInfo createOpenFileHyperlink(String fileName, int line, int column) {
        String normalized = fileName.replace(File.separatorChar, '/');
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized);
        return file != null ? new OpenFileHyperlinkInfo(project, file, line, column) : null;
    }
}
