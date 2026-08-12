package dev.papyrus.jetbrains.protocol;

import org.eclipse.lsp4j.TextDocumentIdentifier;

public final class DocumentScriptInfoParams {
    private TextDocumentIdentifier textDocument;

    public DocumentScriptInfoParams() {
    }

    public DocumentScriptInfoParams(TextDocumentIdentifier textDocument) {
        this.textDocument = textDocument;
    }

    public TextDocumentIdentifier getTextDocument() {
        return textDocument;
    }

    public void setTextDocument(TextDocumentIdentifier textDocument) {
        this.textDocument = textDocument;
    }
}
