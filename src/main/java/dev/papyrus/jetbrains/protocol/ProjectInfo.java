package dev.papyrus.jetbrains.protocol;

import java.util.Collections;
import java.util.List;

public final class ProjectInfo {
    private String name;
    private List<ProjectInfoSourceInclude> sourceIncludes;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<ProjectInfoSourceInclude> getSourceIncludes() {
        return sourceIncludes != null ? sourceIncludes : Collections.emptyList();
    }

    public void setSourceIncludes(List<ProjectInfoSourceInclude> sourceIncludes) {
        this.sourceIncludes = sourceIncludes;
    }
}
