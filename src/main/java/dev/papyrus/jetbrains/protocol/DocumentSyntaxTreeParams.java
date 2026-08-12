package dev.papyrus.jetbrains.protocol;

import org.eclipse.lsp4j.TextDocumentIdentifier;

public final class DocumentSyntaxTreeParams {
    private TextDocumentIdentifier textDocument;

    public DocumentSyntaxTreeParams() {
    }

    public DocumentSyntaxTreeParams(TextDocumentIdentifier textDocument) {
        this.textDocument = textDocument;
    }

    public TextDocumentIdentifier getTextDocument() {
        return textDocument;
    }

    public void setTextDocument(TextDocumentIdentifier textDocument) {
        this.textDocument = textDocument;
    }
}
