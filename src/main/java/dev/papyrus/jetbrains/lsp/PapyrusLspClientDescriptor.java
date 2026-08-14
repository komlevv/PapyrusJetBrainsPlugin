package dev.papyrus.jetbrains.lsp;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.platform.lsp.api.Lsp4jClient;
import com.intellij.platform.lsp.api.LspServerListener;
import com.intellij.platform.lsp.api.LspServerNotificationsHandler;
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor;
import com.intellij.platform.lsp.api.customization.LspCompletionCustomizer;
import com.intellij.platform.lsp.api.customization.LspCompletionSupport;
import com.intellij.platform.lsp.api.customization.LspCodeActionsCustomizer;
import com.intellij.platform.lsp.api.customization.LspCodeActionsDisabled;
import com.intellij.platform.lsp.api.customization.LspCustomization;
import com.intellij.platform.lsp.api.customization.LspDiagnosticsCustomizer;
import com.intellij.platform.lsp.api.customization.LspRenameCustomizer;
import com.intellij.platform.lsp.api.customization.LspRenameDisabled;
import dev.papyrus.jetbrains.config.PapyrusSettings;
import dev.papyrus.jetbrains.projects.PapyrusProjectsService;
import dev.papyrus.jetbrains.runtime.PapyrusLaunchConfiguration;
import dev.papyrus.jetbrains.runtime.PapyrusLaunchConfigurationResolver;
import dev.papyrus.jetbrains.runtime.PapyrusManagedHostCommandLine;
import dev.papyrus.jetbrains.runtime.PapyrusHostRuntimeStager;
import dev.papyrus.jetbrains.status.PapyrusLspOutputService;
import dev.papyrus.jetbrains.protocol.PapyrusLsp4jServer;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DidChangeWatchedFilesCapabilities;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ReferenceOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PapyrusLspClientDescriptor extends ProjectWideLspClientDescriptor {

    private volatile Path serverWorkspaceRoot;

    private @NotNull Path ensureServerWorkspacePrepared() {
        Path root = serverWorkspaceRoot;
        if (root != null) {
            return root;
        }
        synchronized (this) {
            root = serverWorkspaceRoot;
            if (root == null) {
                root = PapyrusProjectsService.getInstance(getProject())
                        .prepareLanguageServerWorkspaceForStart();
                serverWorkspaceRoot = root;
            }
            return root;
        }
    }

    public PapyrusLspClientDescriptor(Project project) {
        super(project, "Papyrus");
    }

    @Override
    public boolean isSupportedFile(@NotNull VirtualFile file) {
        String extension = file.getExtension();
        return "psc".equalsIgnoreCase(extension);
    }

    @Override
    public @NotNull String getLanguageId(@NotNull VirtualFile file) {
        return "ppj".equalsIgnoreCase(file.getExtension()) ? "papyrus-project" : "papyrus";
    }

    @Override
    @SuppressWarnings("deprecation") // Older papyrus-lang initialization paths still read rootUri/rootPath; both must use the validated snapshot.
    public @NotNull InitializeParams createInitializeParams() {
        InitializeParams params = super.createInitializeParams();
        Path root = ensureServerWorkspacePrepared();
        String uri = root.toUri().toASCIIString();
        params.setRootUri(uri);
        params.setRootPath(root.toString());
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(uri, "Papyrus Validated Projects")));
        return params;
    }

    @Override
    public @NotNull LspCustomization getLspCustomization() {
        return new LspCustomization() {
            @Override
            public @NotNull LspCompletionCustomizer getCompletionCustomizer() {
                return new LspCompletionSupport() {
                    @Override
                    @SuppressWarnings("UnstableApiUsage") // Platform 262 marks getCompletionPrefix itself experimental; papyrus-lang needs this hook when completion items omit textEdit.
                    public @NotNull String getCompletionPrefix(
                            @NotNull CompletionParameters parameters,
                            @NotNull String defaultPrefix
                    ) {
                        int dotIndex = defaultPrefix.lastIndexOf('.');
                        return dotIndex >= 0 ? defaultPrefix.substring(dotIndex + 1) : defaultPrefix;
                    }
                };
            }

            @Override
            public @NotNull LspDiagnosticsCustomizer getDiagnosticsCustomizer() {
                return new PapyrusDiagnosticsSupport();
            }

            @Override
            public @NotNull LspRenameCustomizer getRenameCustomizer() {
                // The platform's generic LSP rename applier cannot surface our project-bound
                // safety decisions. Refactor | Rename is handled by PapyrusRenameHandler instead.
                return LspRenameDisabled.INSTANCE;
            }

            @Override
            public @NotNull LspCodeActionsCustomizer getCodeActionsCustomizer() {
                return LspCodeActionsDisabled.INSTANCE;
            }
        };
    }

    @Override
    public @NotNull LspServerListener getLspServerListener() {
        return new LspServerListener() {
            @Override
            public void serverInitialized(@NotNull InitializeResult params) {
                PapyrusLspOutputService.getInstance(getProject()).appendLine("Language service started.");
                ServerCapabilities capabilities = params.getCapabilities();
                if (capabilities == null) {
                    capabilities = new ServerCapabilities();
                    params.setCapabilities(capabilities);
                }

                if (capabilities.getCompletionProvider() == null) {
                    CompletionOptions completionOptions = new CompletionOptions();
                    completionOptions.setResolveProvider(false);
                    completionOptions.setTriggerCharacters(List.of(".", " "));
                    capabilities.setCompletionProvider(completionOptions);
                }

                // papyrus-lang advertises References through dynamic registration, but its
                // initialize response also carries an explicit static false. IntelliJ Platform 2026.2
                // treats that static false as authoritative and never consults the later
                // dynamic registration. Remove only the conflicting static negative value;
                // the real client/registerCapability notification remains the source of truth.
                Either<Boolean, ReferenceOptions> referencesProvider = capabilities.getReferencesProvider();
                if (referencesProvider != null
                        && referencesProvider.isLeft()
                        && Boolean.FALSE.equals(referencesProvider.getLeft())) {
                    capabilities.setReferencesProvider((Either<Boolean, ReferenceOptions>) null);
                }

                // The generic platform LSP Rename/Code Action paths stay disabled. Papyrus Rename
                // is provided by our guarded Refactor | Rename handler, which asks the same LSP
                // server for semantic edits but validates every target before the IDE changes text.
                capabilities.setRenameProvider(false);
                capabilities.setCodeActionProvider(false);

                // IntelliJ Platform 2026.2 omits the optional rangeLength field from incremental
                // didChange notifications, while papyrus-lang v3.3.0-prerelease.1 relies on it.
                // Keep native didOpen/didClose, but let our public DocumentListener bridge own didChange.
                Either<TextDocumentSyncKind, TextDocumentSyncOptions> sync = capabilities.getTextDocumentSync();
                if (sync != null && sync.isRight() && sync.getRight() != null) {
                    sync.getRight().setChange(TextDocumentSyncKind.None);
                } else {
                    TextDocumentSyncOptions syncOptions = new TextDocumentSyncOptions();
                    syncOptions.setOpenClose(true);
                    syncOptions.setChange(TextDocumentSyncKind.None);
                    capabilities.setTextDocumentSync(Either.forRight(syncOptions));
                }

                // Project info supplies the authoritative Papyrus import graph. Refresh after
                // capability normalization so the source-only Papyrus Imports library stays current.
                PapyrusProjectsService.getInstance(getProject()).languageServerStarted();
            }
        };
    }

    @Override
    public @NotNull ClientCapabilities getClientCapabilities() {
        ClientCapabilities capabilities = super.getClientCapabilities();
        if (capabilities.getTextDocument() != null
                && capabilities.getTextDocument().getCompletion() != null) {
            capabilities.getTextDocument().getCompletion().setDynamicRegistration(true);
        }
        if (capabilities.getTextDocument() != null) {
            if (capabilities.getTextDocument().getRename() != null) {
                capabilities.getTextDocument().getRename().setDynamicRegistration(false);
            }
            if (capabilities.getTextDocument().getCodeAction() != null) {
                capabilities.getTextDocument().getCodeAction().setDynamicRegistration(false);
            }
            if (capabilities.getTextDocument().getReferences() != null) {
                capabilities.getTextDocument().getReferences().setDynamicRegistration(true);
            }
        }

        WorkspaceClientCapabilities workspace = capabilities.getWorkspace();
        if (workspace != null) {
            // The Papyrus server does not need unsolicited workspace/applyEdit requests.
            // Safe Rename is client-initiated and applies only plugin-validated text edits.
            // Code Actions remain disabled. The LSP notifications wrapper also rejects applyEdit
            // if a server ignores this advertised capability.
            workspace.setApplyEdit(false);

            DidChangeWatchedFilesCapabilities watchedFiles = workspace.getDidChangeWatchedFiles();
            if (watchedFiles != null) {
                // papyrus-lang registers workspace/didChangeWatchedFiles with empty options.
                // IntelliJ Platform 2026.2 expects a non-null watcher list, so our guarded VFS bridge
                // owns source-tree changes and validates PPJs before any project reload.
                watchedFiles.setDynamicRegistration(false);
            }
        }

        return capabilities;
    }

    @Override
    public @NotNull Lsp4jClient createLsp4jClient(
            @NotNull LspServerNotificationsHandler serverNotificationsHandler
    ) {
        return new PapyrusLsp4jClient(
                getProject(),
                new PapyrusSafeServerNotificationsHandler(
                        serverNotificationsHandler,
                        () -> {
                            Path root = PapyrusProjectsService.getInstance(getProject()).getLanguageServerWorkspaceRoot();
                            return List.of(new WorkspaceFolder(
                                    root.toUri().toASCIIString(),
                                    "Papyrus Validated Projects"
                            ));
                        }
                )
        );
    }

    @Override
    public @NotNull Class<? extends LanguageServer> getLsp4jServerClass() {
        return PapyrusLsp4jServer.class;
    }

    @Override
    public @NotNull GeneralCommandLine createCommandLine() {
        ensureServerWorkspacePrepared();

        PapyrusLaunchConfiguration configuration = PapyrusLaunchConfigurationResolver.resolve(
                PapyrusSettings.getInstance().getState()
        );
        PapyrusHostRuntimeStager.StagedHostRuntime stagedRuntime = PapyrusHostRuntimeStager.stage(
                configuration.hostWorkingDirectory(),
                configuration.hostExecutable(),
                configuration.compilerAssemblyPath(),
                PathManager.getSystemDir().resolve("papyrus").resolve("host-runtime")
        );

        List<String> parameters = new ArrayList<>();
        addOption(parameters, "compilerAssemblyPath", configuration.compilerAssemblyPath().toString());
        addOption(parameters, "flagsFileName", configuration.flagsFileName());
        addOption(parameters, "ambientProjectName", configuration.ambientProjectName());
        addOptionalOption(parameters, "defaultScriptSourceFolder", configuration.defaultScriptSourceFolder());
        addOptionalOption(parameters, "defaultAdditionalImports", configuration.defaultAdditionalImports());
        addOption(parameters, "creationKitInstallPath", configuration.creationKitInstallPath().toString());

        parameters.add("--relativeIniPaths");
        parameters.addAll(configuration.relativeIniPaths());

        addOption(parameters, "remotesInstallPath", configuration.remotesInstallPath().toString());

        PapyrusLspOutputService output = PapyrusLspOutputService.getInstance(getProject());
        output.appendLine("Creating Language Client instance with options:");
        output.appendLine("compilerAssemblyPath: " + configuration.compilerAssemblyPath());
        output.appendLine("flagsFileName: " + configuration.flagsFileName());
        output.appendLine("ambientProjectName: " + configuration.ambientProjectName());
        if (configuration.defaultScriptSourceFolder() != null) {
            output.appendLine("defaultScriptSourceFolder: " + configuration.defaultScriptSourceFolder());
        }
        if (configuration.defaultAdditionalImports() != null) {
            output.appendLine("defaultAdditionalImports: " + configuration.defaultAdditionalImports());
        }
        output.appendLine("creationKitInstallPath: " + configuration.creationKitInstallPath());
        output.appendLine("relativeIniPaths: " + configuration.relativeIniPaths());
        output.appendLine("remotesInstallPath: " + configuration.remotesInstallPath());
        output.appendBlankLine();
        output.appendLine("Starting language service...");

        return new PapyrusManagedHostCommandLine(stagedRuntime.executable().toString())
                .withWorkDirectory(stagedRuntime.workingDirectory().toFile())
                .withParameters(parameters);
    }

    private static void addOption(List<String> parameters, String name, String value) {
        parameters.add("--" + name);
        parameters.add(value);
    }

    private static void addOptionalOption(List<String> parameters, String name, String value) {
        if (value != null && !value.isBlank()) {
            addOption(parameters, name, value);
        }
    }
}
