package dev.papyrus.jetbrains.protocol;

public final class DocumentSyntaxTree {
    private DocumentSyntaxTreeNode root;

    public DocumentSyntaxTreeNode getRoot() {
        return root;
    }

    public void setRoot(DocumentSyntaxTreeNode root) {
        this.root = root;
    }
}
