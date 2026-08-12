package dev.papyrus.jetbrains.protocol;

import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.List;

public final class DocumentSyntaxTreeNode {
    private String name;
    private String text;
    private List<DocumentSyntaxTreeNode> children = new ArrayList<>();
    private Range range;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<DocumentSyntaxTreeNode> getChildren() {
        return children != null ? children : List.of();
    }

    public void setChildren(List<DocumentSyntaxTreeNode> children) {
        this.children = children;
    }

    public Range getRange() {
        return range;
    }

    public void setRange(Range range) {
        this.range = range;
    }
}
