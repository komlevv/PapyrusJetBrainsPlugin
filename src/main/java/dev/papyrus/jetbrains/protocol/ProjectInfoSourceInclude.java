package dev.papyrus.jetbrains.protocol;

import java.util.Collections;
import java.util.List;

public final class ProjectInfoSourceInclude {
    private String name;
    private String fullPath;
    private boolean isImport;
    private boolean isRemote;
    private List<ProjectInfoScript> scripts;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFullPath() {
        return fullPath;
    }

    public void setFullPath(String fullPath) {
        this.fullPath = fullPath;
    }

    public boolean isImport() {
        return isImport;
    }

    public void setImport(boolean value) {
        isImport = value;
    }

    public boolean isRemote() {
        return isRemote;
    }

    public void setRemote(boolean value) {
        isRemote = value;
    }

    public List<ProjectInfoScript> getScripts() {
        return scripts != null ? scripts : Collections.emptyList();
    }

    public void setScripts(List<ProjectInfoScript> scripts) {
        this.scripts = scripts;
    }
}
