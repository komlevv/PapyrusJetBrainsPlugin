package dev.papyrus.jetbrains.protocol;

import java.util.ArrayList;
import java.util.List;

public final class DocumentScriptInfo {
    private List<String> identifiers = new ArrayList<>();
    private List<IdentifierFiles> identifierFiles = new ArrayList<>();
    private List<String> searchPaths = new ArrayList<>();

    public List<String> getIdentifiers() {
        return identifiers != null ? identifiers : List.of();
    }

    public void setIdentifiers(List<String> identifiers) {
        this.identifiers = identifiers;
    }

    public List<IdentifierFiles> getIdentifierFiles() {
        return identifierFiles != null ? identifierFiles : List.of();
    }

    public void setIdentifierFiles(List<IdentifierFiles> identifierFiles) {
        this.identifierFiles = identifierFiles;
    }

    public List<String> getSearchPaths() {
        return searchPaths != null ? searchPaths : List.of();
    }

    public void setSearchPaths(List<String> searchPaths) {
        this.searchPaths = searchPaths;
    }
}
