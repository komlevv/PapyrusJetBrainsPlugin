package dev.papyrus.jetbrains.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.frame.XSuspendContext;
import dev.papyrus.jetbrains.config.PapyrusSettings;
import dev.papyrus.jetbrains.runtime.CreationKitIniLoader;
import dev.papyrus.jetbrains.runtime.CreationKitPapyrusConfig;
import dev.papyrus.jetbrains.runtime.PapyrusLaunchConfigurationResolver;
import dev.papyrus.jetbrains.runtime.PapyrusRuntimePaths;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PapyrusDebugProcess extends XDebugProcess {

    private record ThreadInfo(int id, String name) {
    }

    private final Project project;
    private final XDebugSession session;
    private final DapConnection connection;
    private final PapyrusBreakpointHandler breakpointHandler;
    private final XDebuggerEditorsProvider editorsProvider = new PapyrusDebuggerEditorsProvider();
    private final Map<String, Set<XLineBreakpoint<PapyrusBreakpointProperties>>> breakpoints = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private volatile boolean adapterInitialized;
    private volatile int currentThreadId;

    public PapyrusDebugProcess(
            @NotNull XDebugSession session,
            @Nullable Path projectPath
    ) throws ExecutionException {
        super(session);
        this.project = session.getProject();
        this.session = session;
        this.breakpointHandler = new PapyrusBreakpointHandler(this);

        try {
            this.connection = DapConnection.start(buildAdapterCommand(projectPath));
        } catch (IOException | RuntimeException exception) {
            throw new ExecutionException("Failed to start the Papyrus debug adapter: " + exception.getMessage(), exception);
        }

        connection.setEventHandler(this::handleEvent);
        connection.setStderrHandler(text -> printConsole(text, ConsoleViewContentType.SYSTEM_OUTPUT));
        connection.setCloseHandler(() -> ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed() && !session.isStopped() && !stopping.get()) {
                session.reportError("Papyrus debug adapter disconnected unexpectedly.");
                session.stop();
            }
        }));
        connection.startReading();
    }

    @Override
    public void sessionInitialized() {
        initializeAdapter();
    }

    @Override
    public @NotNull XDebuggerEditorsProvider getEditorsProvider() {
        return editorsProvider;
    }

    @Override
    public XBreakpointHandler<?> @NotNull [] getBreakpointHandlers() {
        return new XBreakpointHandler<?>[]{breakpointHandler};
    }

    @Override
    public boolean checkCanInitBreakpoints() {
        return adapterInitialized;
    }

    @Override
    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }

        if (!connection.isAlive()) {
            connection.close();
            return;
        }

        JsonObject arguments = new JsonObject();
        arguments.addProperty("restart", false);
        arguments.addProperty("terminateDebuggee", false);
        connection.request("disconnect", arguments)
                .orTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                .whenComplete((ignored, error) -> connection.close());
    }

    @Override
    public void resume(@Nullable XSuspendContext context) {
        sendThreadRequest("continue");
    }

    @Override
    public void startStepOver(@Nullable XSuspendContext context) {
        sendThreadRequest("next");
    }

    @Override
    public void startStepInto(@Nullable XSuspendContext context) {
        sendThreadRequest("stepIn");
    }

    @Override
    public void startStepOut(@Nullable XSuspendContext context) {
        sendThreadRequest("stepOut");
    }

    @Override
    public void startPausing() {
        sendThreadRequest("pause");
    }

    void registerBreakpoint(@NotNull XLineBreakpoint<PapyrusBreakpointProperties> breakpoint) {
        VirtualFile file = PapyrusBreakpointHandler.getFile(breakpoint);
        if (file == null) {
            return;
        }
        breakpoints.computeIfAbsent(pathKey(file), ignored -> ConcurrentHashMap.newKeySet()).add(breakpoint);
        if (adapterInitialized) {
            syncBreakpoints(file);
        }
    }

    void unregisterBreakpoint(@NotNull XLineBreakpoint<PapyrusBreakpointProperties> breakpoint) {
        VirtualFile file = PapyrusBreakpointHandler.getFile(breakpoint);
        if (file == null) {
            return;
        }
        Set<XLineBreakpoint<PapyrusBreakpointProperties>> fileBreakpoints = breakpoints.get(pathKey(file));
        if (fileBreakpoints != null) {
            fileBreakpoints.remove(breakpoint);
            if (fileBreakpoints.isEmpty()) {
                breakpoints.remove(pathKey(file));
            }
        }
        if (adapterInitialized) {
            syncBreakpoints(file);
        }
    }

    @NotNull CompletableFuture<List<PapyrusStackFrame>> requestStackFrames(int threadId, int startFrame, int levels) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("threadId", threadId);
        arguments.addProperty("startFrame", startFrame);
        arguments.addProperty("levels", levels);
        return connection.request("stackTrace", arguments).thenApply(body -> {
            JsonArray frames = array(body, "stackFrames");
            if (frames == null) {
                return List.of();
            }
            List<PapyrusStackFrame> result = new ArrayList<>();
            for (JsonElement element : frames) {
                if (element.isJsonObject()) {
                    result.add(PapyrusStackFrame.fromJson(this, element.getAsJsonObject()));
                }
            }
            return result;
        });
    }

    @NotNull CompletableFuture<List<PapyrusScopeValue>> requestScopes(int frameId) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("frameId", frameId);
        return connection.request("scopes", arguments).thenApply(body -> {
            JsonArray scopes = array(body, "scopes");
            if (scopes == null) {
                return List.of();
            }
            List<PapyrusScopeValue> result = new ArrayList<>();
            for (JsonElement element : scopes) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject scope = element.getAsJsonObject();
                String name = valueOr(DapConnection.string(scope, "name"), "Scope");
                int reference = DapConnection.integer(scope, "variablesReference", 0);
                result.add(new PapyrusScopeValue(this, name, reference));
            }
            return result;
        });
    }

    @NotNull CompletableFuture<PapyrusVariableValue> evaluate(@NotNull String expression, int frameId) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("expression", expression);
        arguments.addProperty("frameId", frameId);
        arguments.addProperty("context", "watch");
        return connection.request("evaluate", arguments).thenApply(body -> new PapyrusVariableValue(
                this,
                expression,
                valueOr(DapConnection.string(body, "result"), ""),
                DapConnection.string(body, "type"),
                DapConnection.integer(body, "variablesReference", 0)
        ));
    }

    @NotNull CompletableFuture<List<PapyrusVariableValue>> requestVariables(int variablesReference) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("variablesReference", variablesReference);
        return connection.request("variables", arguments).thenApply(body -> {
            JsonArray variables = array(body, "variables");
            if (variables == null) {
                return List.of();
            }
            List<PapyrusVariableValue> result = new ArrayList<>();
            for (JsonElement element : variables) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject variable = element.getAsJsonObject();
                result.add(new PapyrusVariableValue(
                        this,
                        valueOr(DapConnection.string(variable, "name"), "Value"),
                        valueOr(DapConnection.string(variable, "value"), ""),
                        DapConnection.string(variable, "type"),
                        DapConnection.integer(variable, "variablesReference", 0)
                ));
            }
            return result;
        });
    }

    private void initializeAdapter() {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("clientID", "dev.papyrus.jetbrains-papyrus");
        arguments.addProperty("clientName", "JetBrains IDE");
        arguments.addProperty("adapterID", "papyrus");
        arguments.addProperty("pathFormat", "path");
        arguments.addProperty("linesStartAt1", true);
        arguments.addProperty("columnsStartAt1", true);
        arguments.addProperty("supportsVariableType", true);
        arguments.addProperty("supportsVariablePaging", false);
        arguments.addProperty("supportsRunInTerminalRequest", false);
        arguments.addProperty("locale", "en-US");

        connection.request("initialize", arguments)
                .thenCompose(ignored -> connection.request("attach", new JsonObject()))
                .exceptionally(error -> {
                    reportFatal("Failed to initialize Papyrus debugger", error);
                    return null;
                });
    }

    private void handleEvent(@NotNull String event, @NotNull JsonObject body) {
        switch (event) {
            case "initialized" -> onAdapterInitialized();
            case "stopped" -> onStopped(body);
            case "continued" -> ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed() && !session.isStopped()) {
                    session.sessionResumed();
                }
            });
            case "output" -> {
                String output = DapConnection.string(body, "output");
                String category = DapConnection.string(body, "category");
                if (output != null) {
                    printConsole(output, "stderr".equals(category)
                            ? ConsoleViewContentType.ERROR_OUTPUT
                            : ConsoleViewContentType.NORMAL_OUTPUT);
                }
            }
            case "terminated", "exited" -> ApplicationManager.getApplication().invokeLater(() -> {
                if (!project.isDisposed() && !session.isStopped()) {
                    session.stop();
                }
            });
            default -> {
            }
        }
    }

    private void onAdapterInitialized() {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed() || session.isStopped()) {
                return;
            }

            session.setPauseActionSupported(true);
            session.initBreakpoints();
            adapterInitialized = true;

            CompletableFuture<?>[] syncs = collectBreakpointFiles().stream()
                    .map(this::syncBreakpoints)
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(syncs)
                    .thenCompose(ignored -> connection.request("configurationDone", new JsonObject()))
                    .exceptionally(error -> {
                        reportNonFatal("Papyrus debugger configuration failed", error);
                        return null;
                    });
        });
    }

    private void onStopped(@NotNull JsonObject body) {
        int stoppedThreadId = DapConnection.integer(body, "threadId", 0);
        requestThreads()
                .thenCompose(threads -> {
                    if (threads.isEmpty()) {
                        throw new IllegalStateException("Papyrus debugger reported no threads.");
                    }
                    int activeThreadId = stoppedThreadId > 0 ? stoppedThreadId : threads.getFirst().id();
                    currentThreadId = activeThreadId;

                    List<CompletableFuture<PapyrusExecutionStack>> stackFutures = new ArrayList<>();
                    for (ThreadInfo thread : threads) {
                        stackFutures.add(requestStackFrames(thread.id(), 0, 1).thenApply(frames -> new PapyrusExecutionStack(
                                this,
                                thread.id(),
                                valueOr(thread.name(), "Papyrus thread " + thread.id()),
                                frames.isEmpty() ? null : frames.getFirst()
                        )));
                    }
                    return CompletableFuture.allOf(stackFutures.toArray(CompletableFuture[]::new))
                            .thenApply(ignored -> {
                                List<PapyrusExecutionStack> stacks = stackFutures.stream().map(CompletableFuture::join).toList();
                                PapyrusExecutionStack active = stacks.stream()
                                        .filter(stack -> stack.getThreadId() == activeThreadId)
                                        .findFirst()
                                        .orElse(stacks.getFirst());
                                return new PapyrusSuspendContext(active, stacks);
                            });
                })
                .thenAccept(context -> ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed() && !session.isStopped()) {
                        session.positionReached(context);
                    }
                }))
                .exceptionally(error -> {
                    reportNonFatal("Failed to read Papyrus debugger stack", error);
                    return null;
                });
    }

    private @NotNull CompletableFuture<List<ThreadInfo>> requestThreads() {
        return connection.request("threads", new JsonObject()).thenApply(body -> {
            JsonArray threads = array(body, "threads");
            if (threads == null) {
                return List.of();
            }
            List<ThreadInfo> result = new ArrayList<>();
            for (JsonElement element : threads) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject thread = element.getAsJsonObject();
                int id = DapConnection.integer(thread, "id", 0);
                if (id > 0) {
                    result.add(new ThreadInfo(id, DapConnection.string(thread, "name")));
                }
            }
            return result;
        });
    }

    private void sendThreadRequest(@NotNull String command) {
        int threadId = currentThreadId;
        if (threadId <= 0 || !connection.isAlive()) {
            return;
        }
        JsonObject arguments = new JsonObject();
        arguments.addProperty("threadId", threadId);
        connection.request(command, arguments).exceptionally(error -> {
            reportNonFatal("Papyrus debugger command failed: " + command, error);
            return null;
        });
    }

    private @NotNull CompletableFuture<Void> syncBreakpoints(@NotNull VirtualFile file) {
        if (!adapterInitialized || !connection.isAlive()) {
            return CompletableFuture.completedFuture(null);
        }

        List<XLineBreakpoint<PapyrusBreakpointProperties>> fileBreakpoints = new ArrayList<>(
                breakpoints.getOrDefault(pathKey(file), Set.of())
        );
        fileBreakpoints.sort(Comparator.comparingInt(XLineBreakpoint::getLine));

        JsonObject source = new JsonObject();
        source.addProperty("name", file.getName());
        source.addProperty("path", file.getPath());

        JsonArray dapBreakpoints = new JsonArray();
        for (XLineBreakpoint<PapyrusBreakpointProperties> breakpoint : fileBreakpoints) {
            JsonObject item = new JsonObject();
            item.addProperty("line", breakpoint.getLine() + 1);
            dapBreakpoints.add(item);
        }

        JsonObject arguments = new JsonObject();
        arguments.add("source", source);
        arguments.add("breakpoints", dapBreakpoints);
        arguments.addProperty("sourceModified", false);

        return connection.request("setBreakpoints", arguments)
                .handle((body, error) -> {
                    JsonArray results = error == null ? array(body, "breakpoints") : null;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (project.isDisposed() || session.isStopped()) {
                            return;
                        }
                        for (int index = 0; index < fileBreakpoints.size(); index++) {
                            XLineBreakpoint<PapyrusBreakpointProperties> breakpoint = fileBreakpoints.get(index);
                            if (error != null) {
                                session.setBreakpointInvalid(breakpoint, rootMessage(error));
                                continue;
                            }
                            JsonObject result = results != null && index < results.size() && results.get(index).isJsonObject()
                                    ? results.get(index).getAsJsonObject()
                                    : null;
                            boolean verified = result == null || DapConnection.booleanValue(result, "verified", true);
                            if (verified) {
                                session.setBreakpointVerified(breakpoint);
                            } else {
                                session.setBreakpointInvalid(
                                        breakpoint,
                                        valueOr(DapConnection.string(result, "message"), "Breakpoint was rejected by the Papyrus debug server.")
                                );
                            }
                        }
                    });
                    return null;
                });
    }

    private @NotNull List<VirtualFile> collectBreakpointFiles() {
        List<VirtualFile> files = new ArrayList<>();
        for (Set<XLineBreakpoint<PapyrusBreakpointProperties>> values : breakpoints.values()) {
            for (XLineBreakpoint<PapyrusBreakpointProperties> breakpoint : values) {
                VirtualFile file = PapyrusBreakpointHandler.getFile(breakpoint);
                if (file != null && !files.contains(file)) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    private @NotNull List<String> buildAdapterCommand(@Nullable Path projectPath) {
        PapyrusSettings.SettingsState settings = PapyrusSettings.getInstance().getState();
        if (settings.creationKitInstallPath == null || settings.creationKitInstallPath.isBlank()) {
            throw new IllegalStateException("Skyrim Special Edition path is not configured in Settings | Papyrus.");
        }
        Path creationKitPath = Path.of(settings.creationKitInstallPath).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(creationKitPath)) {
            throw new IllegalStateException("Skyrim Special Edition path does not exist: " + creationKitPath);
        }
        List<String> iniPaths = PapyrusLaunchConfigurationResolver.parseIniPaths(settings.iniPaths);
        CreationKitPapyrusConfig creationKitConfig = CreationKitIniLoader.load(creationKitPath, iniPaths);

        List<String> command = new ArrayList<>();
        command.add(PapyrusRuntimePaths.getDebugAdapterExecutable().toString());
        addOption(command, "port", Integer.toString(settings.debugPort));
        if (projectPath != null) {
            addOption(command, "projectPath", projectPath.toAbsolutePath().normalize().toString());
        }
        addOptionalOption(command, "defaultScriptSourceFolder", creationKitConfig.scriptSourceFolder());
        addOptionalOption(command, "defaultAdditionalImports", creationKitConfig.additionalImports());
        addOption(command, "creationKitInstallPath", creationKitPath.toString());
        command.add("--relativeIniPaths");
        command.addAll(iniPaths);
        addOption(command, "clientProcessId", Long.toString(ProcessHandle.current().pid()));
        addOption(command, "remotesInstallPath", PapyrusRuntimePaths.getRemotesDirectory().toString());
        return command;
    }

    private void printConsole(@NotNull String text, @NotNull ConsoleViewContentType type) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed() && !session.isStopped()) {
                ConsoleView console = session.getConsoleView();
                if (console != null) {
                    console.print(text, type);
                }
            }
        });
    }

    private void reportFatal(@NotNull String prefix, Throwable throwable) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed() && !session.isStopped()) {
                session.reportError(prefix + ": " + rootMessage(throwable));
                session.stop();
            }
        });
    }

    private void reportNonFatal(@NotNull String prefix, Throwable throwable) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed() && !session.isStopped()) {
                session.reportError(prefix + ": " + rootMessage(throwable));
            }
        });
    }

    private static @Nullable JsonArray array(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonArray()) {
            return null;
        }
        return object.getAsJsonArray(name);
    }

    private static String pathKey(VirtualFile file) {
        return file.getPath().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static void addOption(List<String> command, String name, String value) {
        command.add("--" + name);
        command.add(value);
    }

    private static void addOptionalOption(List<String> command, String name, String value) {
        if (value != null && !value.isBlank()) {
            addOption(command, name, value);
        }
    }
}
