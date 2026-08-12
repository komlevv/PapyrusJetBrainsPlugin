package dev.papyrus.jetbrains.protocol;

import java.util.Collections;
import java.util.List;

public final class ProjectInfos {
    private List<ProjectInfo> projects;

    public List<ProjectInfo> getProjects() {
        return projects != null ? projects : Collections.emptyList();
    }

    public void setProjects(List<ProjectInfo> projects) {
        this.projects = projects;
    }
}
