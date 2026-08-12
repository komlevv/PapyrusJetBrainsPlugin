package dev.papyrus.jetbrains.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
@Timeout(value = 4, unit = TimeUnit.MINUTES)
final class PapyrusServerFeatureTest {

    @Test
    void serverInitializesAndDynamicallyRegistersReferences() throws Exception {
        try (Fixture fixture = Fixture.start("registration")) {
            assertRpcSuccess(fixture.initializeResponse, "initialize");
            RawLspClient.ServerRequest registration = fixture.client.awaitReferencesRegistration(Duration.ofSeconds(10));
            assertNotNull(registration, "papyrus-lang must use LSP dynamic registration");
            assertTrue(registration.json().contains("textDocument/references"),
                    "dynamic registration must include textDocument/references: " + registration.json());
        }
    }

    @Test
    void completionReturnsPapyrusMembers() throws Exception {
        String text = """
                Scriptname CompletionFeature extends Quest

                Function Test()
                    Debug.
                EndFunction
                """;
        try (Fixture fixture = Fixture.start("completion", "CompletionFeature.psc", text)) {
            String projectInfos = fixture.client.request("papyrus/projectInfos", "{}", Duration.ofSeconds(30));
            assertRpcSuccess(projectInfos, "papyrus/projectInfos");
            assertTrue(projectInfos.contains("CompletionFeature"),
                    "completion fixture must be resolved by the server project: " + projectInfos);
            String uri = fixture.scriptUri();
            fixture.client.didOpen(uri, text);
            RawLspClient.Position position = RawLspClient.position(text, text.indexOf("Debug.") + "Debug.".length());
            String response = fixture.client.request("textDocument/completion",
                    "{\"textDocument\":{\"uri\":" + RawLspClient.json(uri) + "},\"position\":" + position.json()
                            + ",\"context\":{\"triggerKind\":2,\"triggerCharacter\":\".\"}}",
                    Duration.ofSeconds(30));
            assertRpcSuccess(response, "completion");
            assertTrue(response.contains("Trace") || response.contains("Notification"),
                    "completion must expose Debug members: " + response);
        }
    }


    @Test
    void completionCoversSemanticScopesAndDeclarationBoundaries() throws Exception {
        String base = """
                Scriptname CompletionBase extends Quest

                String Property BaseProperty Auto

                Function BaseFunction()
                EndFunction

                Event BaseEvent()
                EndEvent
                """;
        String globals = """
                Scriptname CompletionGlobals extends Quest

                Function GlobalProbe() Global
                EndFunction

                Function InstanceProbe()
                EndFunction
                """;
        String scope = """
                Scriptname CompletionScope extends CompletionBase

                String Property LocalProperty Auto

                Function LocalFunction()
                EndFunction

                Function Test(String ParameterName)
                    CompletionGlobals LocalScriptValue
                    String LocalValue = ParameterName
                    Self.LocalFunction()
                    Parent.BaseFunction()
                    String[] Values = new String[1]
                    Int ValueCount = Values.Length
                    CompletionGlobals.GlobalProbe()
                EndFunction
                """;

        try (Fixture fixture = Fixture.start("completion-scopes")) {
            Path baseFile = fixture.writeScript("CompletionBase.psc", base);
            Path globalsFile = fixture.writeScript("CompletionGlobals.psc", globals);
            Path scopeFile = fixture.writeScript("CompletionScope.psc", scope);
            fixture.restartAfterScripts();

            String baseUri = fixture.openScript(baseFile, base);
            String globalsUri = fixture.openScript(globalsFile, globals);
            String scopeUri = fixture.openScript(scopeFile, scope);

            String functionBody = completionAt(
                    fixture,
                    scopeUri,
                    scope,
                    scope.indexOf("ParameterName", scope.indexOf("LocalValue =")) + 2,
                    null);
            assertCompletionLabel(functionBody, "ParameterName");
            assertCompletionLabel(functionBody, "LocalValue");
            assertCompletionLabel(functionBody, "LocalProperty");
            assertCompletionLabel(functionBody, "LocalFunction");

            int scriptTypeOffset = scope.indexOf("CompletionGlobals LocalScriptValue") + 2;
            String scriptTypeCompletion = completionAt(
                    fixture, scopeUri, scope, scriptTypeOffset, null);
            assertCompletionLabel(scriptTypeCompletion, "CompletionGlobals");

            String selfMembers = completionAt(
                    fixture,
                    scopeUri,
                    scope,
                    scope.indexOf("Self.LocalFunction") + "Self.".length(),
                    ".");
            assertCompletionLabel(selfMembers, "LocalFunction");
            assertCompletionLabel(selfMembers, "LocalProperty");
            assertCompletionLabel(selfMembers, "BaseFunction");
            assertCompletionLabel(selfMembers, "BaseProperty");

            String parentMembers = completionAt(
                    fixture,
                    scopeUri,
                    scope,
                    scope.indexOf("Parent.BaseFunction") + "Parent.".length(),
                    ".");
            assertCompletionLabel(parentMembers, "BaseFunction");
            assertCompletionLabel(parentMembers, "BaseEvent");
            assertNoCompletionLabel(parentMembers, "LocalFunction");
            assertNoCompletionLabel(parentMembers, "LocalProperty");

            String arrayMembers = completionAt(
                    fixture,
                    scopeUri,
                    scope,
                    scope.indexOf("Values.Length") + "Values.".length(),
                    ".");
            assertCompletionLabel(arrayMembers, "Length");

            String globalMembers = completionAt(
                    fixture,
                    scopeUri,
                    scope,
                    scope.indexOf("CompletionGlobals.GlobalProbe") + "CompletionGlobals.".length(),
                    ".");
            assertCompletionLabel(globalMembers, "GlobalProbe");
            assertNoCompletionLabel(globalMembers, "InstanceProbe");

            int parameterDeclarationOffset = scope.indexOf("ParameterName)");
            String parameterDeclaration = completionAt(
                    fixture, scopeUri, scope, parameterDeclarationOffset, null);
            assertNoCompletionLabel(parameterDeclaration, "ParameterName");

            int localDeclarationOffset = scope.indexOf("LocalValue =");
            String localDeclaration = completionAt(
                    fixture, scopeUri, scope, localDeclarationOffset, null);
            assertNoCompletionLabel(localDeclaration, "LocalValue");
        }
    }

    @Test
    void hoverReturnsPapyrusDocumentation() throws Exception {
        String text = """
                Scriptname HoverFeature extends Quest

                Function Test()
                    Debug.Notification("hover")
                EndFunction
                """;
        try (Fixture fixture = Fixture.start("hover", "HoverFeature.psc", text)) {
            String uri = fixture.scriptUri();
            fixture.client.didOpen(uri, text);
            RawLspClient.Position position = RawLspClient.position(text, text.indexOf("Notification") + 2);
            String response = fixture.client.request("textDocument/hover",
                    "{\"textDocument\":{\"uri\":" + RawLspClient.json(uri) + "},\"position\":" + position.json() + "}",
                    Duration.ofSeconds(30));
            assertRpcSuccess(response, "hover");
            assertFalse(response.contains("\"result\":null"), "hover must return documentation: " + response);
            assertTrue(response.toLowerCase().contains("notification"), "hover must describe Notification: " + response);
        }
    }

    @Test
    void signatureHelpReturnsPapyrusParameters() throws Exception {
        String text = """
                Scriptname SignatureFeature extends Quest

                Function Test()
                    Debug.Notification(
                EndFunction
                """;
        try (Fixture fixture = Fixture.start("signature", "SignatureFeature.psc", text)) {
            String uri = fixture.scriptUri();
            fixture.client.didOpen(uri, text);
            RawLspClient.Position position = RawLspClient.position(text, text.indexOf("Debug.Notification(") + "Debug.Notification(".length());
            String response = fixture.client.request("textDocument/signatureHelp",
                    "{\"textDocument\":{\"uri\":" + RawLspClient.json(uri) + "},\"position\":" + position.json() + "}",
                    Duration.ofSeconds(30));
            assertRpcSuccess(response, "signatureHelp");
            assertFalse(response.contains("\"result\":null"), "signatureHelp must return a signature: " + response);
            assertTrue(response.toLowerCase().contains("notification"), "signatureHelp must describe Notification: " + response);
        }
    }

    @Test
    void diagnosticsTrackIncrementalInvalidValidInvalidChanges() throws Exception {
        String invalidText = """
                Scriptname DiagnosticsFeature extends Quest

                Function Test()
                    if
                EndFunction
                """;
        String fixedStatement = "Debug.Trace(\"fixed-diagnostic\")";
        int invalidMarkerOffset = invalidText.indexOf("    if");
        assertTrue(invalidMarkerOffset >= 0, "invalid marker must exist in the diagnostics fixture");
        invalidMarkerOffset += 4;
        String fixedText = invalidText.substring(0, invalidMarkerOffset)
                + fixedStatement
                + invalidText.substring(invalidMarkerOffset + 2);

        try (Fixture fixture = Fixture.start("diagnostics", "DiagnosticsFeature.psc", invalidText)) {
            String uri = fixture.scriptUri();
            fixture.client.didOpen(uri, invalidText);

            String initialDiagnostics = fixture.client.awaitPublishedDiagnostics(uri, false, Duration.ofSeconds(30));
            assertNotNull(initialDiagnostics, "invalid Papyrus must publish non-empty diagnostics");

            RawLspClient.Position invalidStart = RawLspClient.position(invalidText, invalidMarkerOffset);
            RawLspClient.Position invalidEnd = RawLspClient.position(invalidText, invalidMarkerOffset + 2);
            fixture.client.didChange(uri, 2, invalidStart, invalidEnd, 2, fixedStatement);

            String clearedDiagnostics = fixture.client.awaitPublishedDiagnostics(uri, true, Duration.ofSeconds(30));
            assertNotNull(clearedDiagnostics, "valid unsaved Papyrus must publish an empty diagnostics list");

            int fixedMarkerOffset = fixedText.indexOf(fixedStatement);
            assertTrue(fixedMarkerOffset >= 0, "fixed marker must exist after the incremental edit");
            RawLspClient.Position fixedStart = RawLspClient.position(fixedText, fixedMarkerOffset);
            RawLspClient.Position fixedEnd = RawLspClient.position(fixedText, fixedMarkerOffset + fixedStatement.length());
            fixture.client.didChange(uri, 3, fixedStart, fixedEnd, fixedStatement.length(), "if");

            String restoredDiagnostics = fixture.client.awaitPublishedDiagnostics(uri, false, Duration.ofSeconds(30));
            assertNotNull(restoredDiagnostics, "a second invalid unsaved edit must republish diagnostics");
        }
    }

    @Test
    void definitionResolvesAcrossFiles() throws Exception {
        String target = """
                Scriptname DefinitionTarget extends Quest

                Function SharedProbe()
                EndFunction
                """;
        String caller = """
                Scriptname DefinitionCaller extends Quest
                DefinitionTarget Property Target Auto

                Function Test()
                    Target.SharedProbe()
                EndFunction
                """;
        try (Fixture fixture = Fixture.start("definition")) {
            OpenedScriptPair scripts = openScriptPair(
                    fixture, "DefinitionTarget.psc", target, "DefinitionCaller.psc", caller);
            RawLspClient.Position position = RawLspClient.position(caller, caller.indexOf("SharedProbe") + 2);
            String response = fixture.client.request("textDocument/definition",
                    "{\"textDocument\":{\"uri\":" + RawLspClient.json(scripts.callerUri()) + "},\"position\":" + position.json() + "}",
                    Duration.ofSeconds(30));
            assertRpcSuccess(response, "definition");
            assertTrue(response.contains(RawLspClient.json(scripts.targetUri())),
                    "definition must navigate to DefinitionTarget: " + response);
        }
    }

    @Test
    void referencesReturnSameAndCrossFileUsages() throws Exception {
        String target = """
                Scriptname ReferencesTarget extends Quest

                Function SharedProbe()
                EndFunction

                Function LocalCall()
                    SharedProbe()
                EndFunction
                """;
        String caller = """
                Scriptname ReferencesCaller extends Quest
                ReferencesTarget Property Target Auto

                Function Test()
                    Target.SharedProbe()
                    Target.SharedProbe()
                EndFunction
                """;
        try (Fixture fixture = Fixture.start("references")) {
            OpenedScriptPair scripts = openScriptPair(
                    fixture, "ReferencesTarget.psc", target, "ReferencesCaller.psc", caller);
            RawLspClient.Position position = RawLspClient.position(caller, caller.indexOf("SharedProbe") + 2);
            String response = fixture.client.request("textDocument/references",
                    "{\"textDocument\":{\"uri\":" + RawLspClient.json(scripts.callerUri()) + "},\"position\":" + position.json()
                            + ",\"context\":{\"includeDeclaration\":true}}",
                    Duration.ofSeconds(30));
            assertRpcSuccess(response, "references");
            assertTrue(count(response, RawLspClient.json(scripts.callerUri())) >= 2,
                    "references must contain both cross-file calls: " + response);
            assertTrue(count(response, RawLspClient.json(scripts.targetUri())) >= 1,
                    "references must contain the same-file call: " + response);
        }
    }


    @Test
    void definitionHandlesInheritedCaseInsensitiveSelfAndUnresolvedSymbols() throws Exception {
        String base = """
                Scriptname DefinitionEdgeBase extends Quest

                Function InheritedProbe()
                EndFunction
                """;
        String derived = """
                Scriptname DefinitionEdgeDerived extends DefinitionEdgeBase

                Function LocalProbe()
                EndFunction
                """;
        String caller = """
                Scriptname DefinitionEdgeCaller extends Quest
                DefinitionEdgeDerived Property Target Auto

                Function Test()
                    target.inheritedprobe()
                    Target.LocalProbe()
                    UnknownProbe()
                EndFunction
                """;

        try (Fixture fixture = Fixture.start("definition-edges")) {
            Path baseFile = fixture.writeScript("DefinitionEdgeBase.psc", base);
            Path derivedFile = fixture.writeScript("DefinitionEdgeDerived.psc", derived);
            Path callerFile = fixture.writeScript("DefinitionEdgeCaller.psc", caller);
            fixture.restartAfterScripts();

            String baseUri = fixture.openScript(baseFile, base);
            String derivedUri = fixture.openScript(derivedFile, derived);
            String callerUri = fixture.openScript(callerFile, caller);

            String inherited = definitionAt(
                    fixture, callerUri, caller, caller.indexOf("inheritedprobe") + 2);
            assertTrue(inherited.contains(RawLspClient.json(baseUri)),
                    "case-insensitive inherited definition must resolve to the base script: " + inherited);
            assertFalse(inherited.contains(RawLspClient.json(derivedUri)),
                    "inherited definition must not resolve to the derived script: " + inherited);

            String local = definitionAt(
                    fixture, callerUri, caller, caller.indexOf("LocalProbe") + 2);
            assertTrue(local.contains(RawLspClient.json(derivedUri)),
                    "derived member definition must resolve to the declaring script: " + local);

            String declarationSelf = definitionAt(
                    fixture, baseUri, base, base.indexOf("InheritedProbe") + 2);
            assertTrue(declarationSelf.contains(RawLspClient.json(baseUri)),
                    "definition requested on a declaration must resolve to its own file: " + declarationSelf);

            String unresolved = definitionAt(
                    fixture, callerUri, caller, caller.indexOf("UnknownProbe") + 2);
            assertRpcSuccess(unresolved, "definition unresolved edge");
            assertTrue(unresolved.contains("\"result\":null"),
                    "unresolved identifiers must return a null definition rather than a wrong location: " + unresolved);
        }
    }

    @Test
    void referencesStayBoundToTheExactSymbolAcrossSameNamedMembers() throws Exception {
        String typeA = """
                Scriptname ReferencesEdgeA extends Quest

                Function SharedProbe()
                EndFunction

                Function LocalCall()
                    SharedProbe()
                EndFunction
                """;
        String typeB = """
                Scriptname ReferencesEdgeB extends Quest

                Function SharedProbe()
                EndFunction

                Function LocalCall()
                    SharedProbe()
                EndFunction
                """;
        String caller = """
                Scriptname ReferencesEdgeCaller extends Quest
                ReferencesEdgeA Property A Auto
                ReferencesEdgeB Property B Auto

                Function Test()
                    a.sharedprobe()
                    A.SharedProbe()
                    B.SharedProbe()
                    UnknownProbe()
                EndFunction
                """;

        try (Fixture fixture = Fixture.start("reference-edges")) {
            Path typeAFile = fixture.writeScript("ReferencesEdgeA.psc", typeA);
            Path typeBFile = fixture.writeScript("ReferencesEdgeB.psc", typeB);
            Path callerFile = fixture.writeScript("ReferencesEdgeCaller.psc", caller);
            fixture.restartAfterScripts();

            String typeAUri = fixture.openScript(typeAFile, typeA);
            String typeBUri = fixture.openScript(typeBFile, typeB);
            String callerUri = fixture.openScript(callerFile, caller);

            String fromCaseInsensitiveUsage = referencesAt(
                    fixture, callerUri, caller, caller.indexOf("sharedprobe") + 2);
            assertTrue(count(fromCaseInsensitiveUsage, RawLspClient.json(callerUri)) >= 2,
                    "references for A.SharedProbe must include both caller usages: " + fromCaseInsensitiveUsage);
            assertTrue(count(fromCaseInsensitiveUsage, RawLspClient.json(typeAUri)) >= 1,
                    "references for A.SharedProbe must include the same-symbol local usage: " + fromCaseInsensitiveUsage);
            assertFalse(fromCaseInsensitiveUsage.contains(RawLspClient.json(typeBUri)),
                    "references must not leak to a same-named member on another script type: " + fromCaseInsensitiveUsage);

            String fromDeclaration = referencesAt(
                    fixture, typeAUri, typeA, typeA.indexOf("SharedProbe") + 2);
            assertTrue(count(fromDeclaration, RawLspClient.json(callerUri)) >= 2,
                    "references requested on the declaration must find cross-file usages: " + fromDeclaration);
            assertTrue(count(fromDeclaration, RawLspClient.json(typeAUri)) >= 1,
                    "references requested on the declaration must find same-file usages: " + fromDeclaration);
            assertFalse(fromDeclaration.contains(RawLspClient.json(typeBUri)),
                    "declaration references must remain isolated from the same-named B member: " + fromDeclaration);

            String unresolved = referencesAt(
                    fixture, callerUri, caller, caller.indexOf("UnknownProbe") + 2);
            assertRpcSuccess(unresolved, "references unresolved edge");
            assertTrue(unresolved.contains("\"result\":null"),
                    "unresolved identifiers must return null references rather than unrelated matches: " + unresolved);
        }
    }

    @Test
    void renameReturnsSemanticWorkspaceEditAcrossFiles() throws Exception {
        String target = """
                Scriptname RenameTarget extends Quest

                Function SharedProbe()
                EndFunction

                Function LocalCall()
                    SharedProbe()
                EndFunction
                """;
        String caller = """
                Scriptname RenameCaller extends Quest
                RenameTarget Property Target Auto

                Function Test()
                    Target.SharedProbe()
                EndFunction
                """;
        try (Fixture fixture = Fixture.start("rename")) {
            OpenedScriptPair scripts = openScriptPair(
                    fixture, "RenameTarget.psc", target, "RenameCaller.psc", caller);
            RawLspClient.Position position = RawLspClient.position(caller, caller.indexOf("SharedProbe") + 2);
            String response = fixture.client.request("textDocument/rename",
                    "{\"textDocument\":{\"uri\":" + RawLspClient.json(scripts.callerUri()) + "},\"position\":"
                            + position.json() + ",\"newName\":\"SharedProbeRenamed\"}",
                    Duration.ofSeconds(30));
            assertRpcSuccess(response, "rename");
            assertTrue(response.contains("SharedProbeRenamed"),
                    "rename must return the requested replacement text: " + response);
            assertTrue(response.contains(RawLspClient.json(scripts.targetUri())),
                    "rename must include the declaration/same-file usages: " + response);
            assertTrue(response.contains(RawLspClient.json(scripts.callerUri())),
                    "rename must include cross-file usages: " + response);
        }
    }

    @Test
    void documentSymbolsExposePapyrusStructure() throws Exception {
        String text = """
                Scriptname StructureFeature extends Quest

                String Property RuntimeLabel Auto

                Function SharedProbe()
                EndFunction

                State RuntimeState
                    Event OnBeginState()
                    EndEvent
                EndState
                """;
        try (Fixture fixture = Fixture.start("symbols", "StructureFeature.psc", text)) {
            String uri = fixture.scriptUri();
            fixture.client.didOpen(uri, text);
            String response = fixture.client.request("textDocument/documentSymbol",
                    "{\"textDocument\":{\"uri\":" + RawLspClient.json(uri) + "}}", Duration.ofSeconds(30));
            assertRpcSuccess(response, "documentSymbol");
            for (String expected : new String[]{"StructureFeature", "RuntimeLabel", "SharedProbe", "RuntimeState", "OnBeginState"}) {
                assertTrue(response.contains("\"name\":" + RawLspClient.json(expected)),
                        "document symbols must include " + expected + ": " + response);
            }
        }
    }

    @Test
    void projectInfosAndScriptInfoPreserveLocalSourcePrecedence() throws Exception {
        String winningText = """
                Scriptname PrecedenceProbe extends Quest

                String Property SourceMarker = "winning-local-source" Auto
                """;
        String importedText = """
                Scriptname PrecedenceProbe extends Quest

                String Property SourceMarker = "lower-priority-import" Auto
                """;

        try (Fixture fixture = Fixture.start("project-precedence")) {
            Path winningFile = fixture.writeScript("PrecedenceProbe.psc", winningText);
            Path importDir = fixture.workspace.resolve(Path.of("Import", "Scripts"));
            Files.createDirectories(importDir);
            Path importedFile = importDir.resolve("PrecedenceProbe.psc");
            Files.writeString(importedFile, importedText, StandardCharsets.UTF_8);
            // The tagged server reverses PPJ Imports and then appends the Folder source include,
            // so Source/Scripts is the final duplicate-identifier winner.
            fixture.addImport(".\\Import\\Scripts");
            fixture.restartAfterScripts();

            String projectInfos = fixture.client.request("papyrus/projectInfos", "{}", Duration.ofSeconds(30));
            assertRpcSuccess(projectInfos, "papyrus/projectInfos precedence");
            assertTrue(projectInfos.contains(RawLspClient.json(importDir.toString())),
                    "projectInfos must report the explicit import include: " + projectInfos);
            assertTrue(projectInfos.contains(RawLspClient.json(winningFile.getParent().toString())),
                    "projectInfos must report the local source include: " + projectInfos);
            assertTrue(projectInfos.contains("\"isImport\":true"),
                    "projectInfos must preserve import metadata: " + projectInfos);
            assertTrue(projectInfos.contains("\"isImport\":false"),
                    "projectInfos must preserve source metadata: " + projectInfos);

            String importedUri = importedFile.toUri().toString();
            fixture.client.didOpen(importedUri, importedText);
            String scriptInfo = fixture.client.request("textDocument/scriptInfo",
                    "{\"textDocument\":{\"uri\":" + RawLspClient.json(importedUri) + "}}",
                    Duration.ofSeconds(30));
            assertRpcSuccess(scriptInfo, "textDocument/scriptInfo precedence");
            assertTrue(scriptInfo.contains(RawLspClient.json(winningFile.toString())),
                    "scriptInfo must resolve the duplicate identifier to the local source winner: " + scriptInfo);
            assertFalse(scriptInfo.contains(RawLspClient.json(importedFile.toString())),
                    "lower-priority import must not be reported as the winning identifier file: " + scriptInfo);
        }
    }

    @Test
    void projectInfosResolvePrepopulatedRemoteCacheWithoutNetworkDownload() throws Exception {
        String remoteUrl = "https://github.com/example/papyrus-remote/tree/main/Scripts";
        String remoteText = """
                Scriptname RemoteFeature extends Quest

                Function RemoteProbe()
                EndFunction
                """;
        String callerText = """
                Scriptname RemoteCaller extends Quest
                RemoteFeature Property Target Auto

                Function Test()
                    Target.RemoteProbe()
                EndFunction
                """;

        try (Fixture fixture = Fixture.start("project-remote-cache")) {
            Path remoteScripts = fixture.remoteCachePath(remoteUrl);
            Files.createDirectories(remoteScripts);
            Path remoteFile = remoteScripts.resolve("RemoteFeature.psc");
            Files.writeString(remoteFile, remoteText, StandardCharsets.UTF_8);
            Path callerFile = fixture.writeScript("RemoteCaller.psc", callerText);
            fixture.addImport(remoteUrl);
            fixture.restartAfterScripts();

            String projectInfos = fixture.client.request("papyrus/projectInfos", "{}", Duration.ofSeconds(30));
            assertRpcSuccess(projectInfos, "papyrus/projectInfos remote cache");
            assertTrue(projectInfos.contains("\"isRemote\":true"),
                    "projectInfos must preserve cached remote metadata: " + projectInfos);
            assertTrue(projectInfos.contains(RawLspClient.json(remoteScripts.toString())),
                    "projectInfos must expose the resolved cached remote path: " + projectInfos);
            assertTrue(projectInfos.contains("RemoteFeature"),
                    "projectInfos must enumerate scripts from the pre-populated remote cache: " + projectInfos);

            String callerUri = callerFile.toUri().toString();
            fixture.client.didOpen(callerUri, callerText);
            int typeOffset = callerText.indexOf("RemoteFeature") + 2;
            String definition = definitionAt(fixture, callerUri, callerText, typeOffset);
            assertTrue(definition.contains(RawLspClient.json(remoteFile.toUri().toString())),
                    "definition must resolve into the pre-populated remote cache: " + definition);
        }
    }

    @Test
    void customSyntaxTreeEndpointTracksOpenBuffer() throws Exception {
        String markerA = "__SYNTAX_TREE_A__";
        String markerB = "__SYNTAX_TREE_B__";
        String text = """
                Scriptname SyntaxTreeFeature extends Quest

                Function Test()
                    Debug.Trace("__SYNTAX_TREE_A__")
                EndFunction
                """;
        try (Fixture fixture = Fixture.start("syntax-tree", "SyntaxTreeFeature.psc", text)) {
            String uri = fixture.scriptUri();
            fixture.client.didOpen(uri, text);

            String initialTree = fixture.client.request("textDocument/syntaxTree",
                    "{\"textDocument\":{\"uri\":" + RawLspClient.json(uri) + "}}", Duration.ofSeconds(30));
            assertRpcSuccess(initialTree, "textDocument/syntaxTree");
            assertFalse(initialTree.contains("\"result\":null"),
                    "syntaxTree must return a tree for the resolved open document: " + initialTree);
            assertTrue(initialTree.contains("SyntaxTreeFeature"),
                    "syntaxTree must describe the resolved script: " + initialTree);
            assertTrue(initialTree.contains(markerA),
                    "syntaxTree must reflect the current didOpen buffer: " + initialTree);

            int markerOffset = text.indexOf(markerA);
            RawLspClient.Position start = RawLspClient.position(text, markerOffset);
            RawLspClient.Position end = RawLspClient.position(text, markerOffset + markerA.length());
            fixture.client.didChange(uri, start, end, markerA.length(), markerB);

            String updatedTree = awaitSyntaxTreeContains(fixture, uri, markerB, Duration.ofSeconds(10));
            assertTrue(updatedTree.contains(markerB),
                    "syntaxTree must converge to the incremental didChange buffer: " + updatedTree);
            assertFalse(updatedTree.contains(markerA),
                    "syntaxTree must not retain the replaced buffer marker: " + updatedTree);
        }
    }

    @Test
    void customProjectInfoAndAssemblyEndpointsWork() throws Exception {
        String text = """
                Scriptname ProtocolFeature extends Quest

                Function Test()
                    Debug.Trace("protocol")
                EndFunction
                """;
        try (Fixture fixture = Fixture.start("protocol", "ProtocolFeature.psc", text)) {
            String projectInfos = fixture.client.request("papyrus/projectInfos", "{}", Duration.ofSeconds(30));
            assertRpcSuccess(projectInfos, "papyrus/projectInfos");
            assertTrue(projectInfos.contains("Creation Kit"), "ambient project must be present: " + projectInfos);

            String uri = fixture.scriptUri();
            fixture.client.didOpen(uri, text);
            String scriptInfo = fixture.client.request("textDocument/scriptInfo",
                    "{\"textDocument\":{\"uri\":" + RawLspClient.json(uri) + "}}", Duration.ofSeconds(30));
            assertRpcSuccess(scriptInfo, "textDocument/scriptInfo");
            assertTrue(scriptInfo.contains("ProtocolFeature"), "scriptInfo must resolve the script: " + scriptInfo);

            String assembly = fixture.client.request("textDocument/assembly",
                    "{\"textDocument\":{\"uri\":" + RawLspClient.json(uri) + "}}", Duration.ofSeconds(30));
            assertRpcSuccess(assembly, "textDocument/assembly");
            assertFalse(assembly.contains("\"result\":null"), "assembly must return a value: " + assembly);
        }
    }


    private static String completionAt(
            Fixture fixture,
            String uri,
            String text,
            int offset,
            String triggerCharacter
    ) throws Exception {
        RawLspClient.Position position = RawLspClient.position(text, offset);
        String context = triggerCharacter == null
                ? "{\"triggerKind\":1}"
                : "{\"triggerKind\":2,\"triggerCharacter\":" + RawLspClient.json(triggerCharacter) + "}";
        String response = fixture.client.request("textDocument/completion",
                "{\"textDocument\":{\"uri\":" + RawLspClient.json(uri) + "},\"position\":"
                        + position.json() + ",\"context\":" + context + "}",
                Duration.ofSeconds(30));
        assertRpcSuccess(response, "completion semantic edge");
        return response;
    }

    private static String definitionAt(
            Fixture fixture,
            String uri,
            String text,
            int offset
    ) throws Exception {
        RawLspClient.Position position = RawLspClient.position(text, offset);
        String response = fixture.client.request("textDocument/definition",
                "{\"textDocument\":{\"uri\":" + RawLspClient.json(uri) + "},\"position\":"
                        + position.json() + "}",
                Duration.ofSeconds(30));
        assertRpcSuccess(response, "definition edge");
        return response;
    }

    private static String referencesAt(
            Fixture fixture,
            String uri,
            String text,
            int offset
    ) throws Exception {
        RawLspClient.Position position = RawLspClient.position(text, offset);
        String response = fixture.client.request("textDocument/references",
                "{\"textDocument\":{\"uri\":" + RawLspClient.json(uri) + "},\"position\":"
                        + position.json() + ",\"context\":{\"includeDeclaration\":true}}",
                Duration.ofSeconds(30));
        assertRpcSuccess(response, "references edge");
        return response;
    }

    private static void assertCompletionLabel(String response, String label) {
        assertTrue(response.contains("\"label\":" + RawLspClient.json(label)),
                "completion must include " + label + ": " + response);
    }

    private static void assertNoCompletionLabel(String response, String label) {
        assertFalse(response.contains("\"label\":" + RawLspClient.json(label)),
                "completion must not include " + label + " in this scope: " + response);
    }

    private static String awaitSyntaxTreeContains(
            Fixture fixture,
            String uri,
            String marker,
            Duration timeout
    ) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        String last = null;
        while (System.nanoTime() < deadline) {
            last = fixture.client.request("textDocument/syntaxTree",
                    "{\"textDocument\":{\"uri\":" + RawLspClient.json(uri) + "}}", Duration.ofSeconds(10));
            assertRpcSuccess(last, "textDocument/syntaxTree");
            if (!last.contains("\"result\":null") && last.contains(marker)) {
                return last;
            }
            LockSupport.parkNanos(Duration.ofMillis(100).toNanos());
        }
        throw new AssertionError("Timed out waiting for syntaxTree marker " + marker + ": " + last);
    }

    private static OpenedScriptPair openScriptPair(
            Fixture fixture,
            String targetFileName,
            String targetText,
            String callerFileName,
            String callerText
    ) throws Exception {
        Path targetFile = fixture.writeScript(targetFileName, targetText);
        Path callerFile = fixture.writeScript(callerFileName, callerText);
        fixture.restartAfterScripts();
        String targetUri = targetFile.toUri().toString();
        String callerUri = callerFile.toUri().toString();
        fixture.client.didOpen(targetUri, targetText);
        fixture.client.didOpen(callerUri, callerText);
        return new OpenedScriptPair(targetUri, callerUri);
    }

    private record OpenedScriptPair(String targetUri, String callerUri) {
    }

    private static void assertRpcSuccess(String response, String method) {
        assertNotNull(response, method + " response must not be null");
        assertTrue(response.contains("\"result\""), method + " must return a JSON-RPC result: " + response);
        assertFalse(response.contains("\"error\""), method + " must not return an error: " + response);
    }

    private static int count(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static final class Fixture implements AutoCloseable {
        private final ServerTestEnvironment environment;
        private final Path workspace;
        private final Path stderr;
        private Process process;
        private RawLspClient client;
        private String initializeResponse;
        private Path scriptFile;

        private Fixture(ServerTestEnvironment environment, Path workspace, Path stderr) {
            this.environment = environment;
            this.workspace = workspace;
            this.stderr = stderr;
        }

        static Fixture start(String name) throws Exception {
            ServerTestEnvironment environment = ServerTestEnvironment.load();
            Path workspace = environment.createWorkspace(name);
            Fixture fixture = new Fixture(environment, workspace, environment.outputDir.resolve(name + "-stderr.log"));
            fixture.startProcess();
            return fixture;
        }

        static Fixture start(String name, String scriptName, String text) throws Exception {
            Fixture fixture = start(name);
            fixture.writeScript(scriptName, text);
            fixture.restartAfterScripts();
            fixture.scriptFile = fixture.workspace.resolve(Path.of("Source", "Scripts", scriptName));
            return fixture;
        }

        Path writeScript(String name, String text) throws Exception {
            Path file = workspace.resolve(Path.of("Source", "Scripts", name));
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return file;
        }

        String openScript(Path file, String text) throws Exception {
            String uri = file.toUri().toString();
            client.didOpen(uri, text);
            return uri;
        }

        @SuppressWarnings("UseOptimizedEelFunctions") // Raw server fixtures always use a local Windows workspace, not a remote Eel filesystem.
        void addImport(String importPath) throws Exception {
            Path projectFile = workspace.resolve("runtime.ppj");
            String ppj = Files.readString(projectFile, StandardCharsets.UTF_8);
            String closingImports = "    </Imports>";
            if (!ppj.contains(closingImports)) {
                throw new IllegalStateException("runtime.ppj has no Imports closing tag");
            }
            String escaped = importPath
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
            ppj = ppj.replace(closingImports, "        <Import>" + escaped + "</Import>\n" + closingImports);
            Files.writeString(projectFile, ppj, StandardCharsets.UTF_8);
        }

        Path remoteCachePath(String remoteUrl) throws Exception {
            // Match the tagged SourceInclude.RemoteArgs cache layout: first four SHA-1 bytes.
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(remoteUrl.getBytes(StandardCharsets.UTF_8));
            String hash = HexFormat.of().formatHex(digest, 0, 4);
            return environment.remoteCacheDir(workspace)
                    .resolve(hash)
                    .resolve("example")
                    .resolve("papyrus-remote")
                    .resolve("Scripts");
        }

        String scriptUri() {
            return scriptFile.toUri().toString();
        }

        void restartAfterScripts() throws Exception {
            stopProcess();
            startProcess();
        }

        private void startProcess() throws Exception {
            process = environment.startServer(workspace, stderr);
            client = new RawLspClient(process, workspace.toUri().toString());
            initializeResponse = client.initialize();
            assertRpcSuccess(initializeResponse, "initialize");
        }

        private void stopProcess() {
            if (client != null) {
                try { client.shutdown(); } catch (Exception ignored) {}
                client.close();
                client = null;
            } else if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            process = null;
        }

        @Override
        public void close() {
            stopProcess();
        }
    }
}
