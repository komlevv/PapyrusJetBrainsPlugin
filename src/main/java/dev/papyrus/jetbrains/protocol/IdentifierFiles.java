package dev.papyrus.jetbrains.protocol;

import java.util.ArrayList;
import java.util.List;

public final class IdentifierFiles {
    private String identifier;
    private List<String> files = new ArrayList<>();

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public List<String> getFiles() {
        return files != null ? files : List.of();
    }

    public void setFiles(List<String> files) {
        this.files = files;
    }
}
