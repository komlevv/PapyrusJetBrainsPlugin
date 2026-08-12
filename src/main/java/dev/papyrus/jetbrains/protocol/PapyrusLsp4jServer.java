package dev.papyrus.jetbrains.protocol;

import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;

import java.util.concurrent.CompletableFuture;

public interface PapyrusLsp4jServer extends LanguageServer {

    @JsonRequest("papyrus/projectInfos")
    CompletableFuture<ProjectInfos> projectInfos(ProjectInfosParams params);

    @JsonRequest("textDocument/assembly")
    CompletableFuture<DocumentAssembly> documentAssembly(DocumentAssemblyParams params);

    @JsonRequest("textDocument/scriptInfo")
    CompletableFuture<DocumentScriptInfo> documentScriptInfo(DocumentScriptInfoParams params);

    @JsonRequest("textDocument/syntaxTree")
    CompletableFuture<DocumentSyntaxTree> documentSyntaxTree(DocumentSyntaxTreeParams params);
}
