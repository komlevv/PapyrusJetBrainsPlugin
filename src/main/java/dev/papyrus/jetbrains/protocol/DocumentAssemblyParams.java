package dev.papyrus.jetbrains.protocol;

import org.eclipse.lsp4j.TextDocumentIdentifier;

public final class DocumentAssemblyParams {
    private TextDocumentIdentifier textDocument;

    public DocumentAssemblyParams() {
    }

    public DocumentAssemblyParams(TextDocumentIdentifier textDocument) {
        this.textDocument = textDocument;
    }

    public TextDocumentIdentifier getTextDocument() {
        return textDocument;
    }

    public void setTextDocument(TextDocumentIdentifier textDocument) {
        this.textDocument = textDocument;
    }
}
