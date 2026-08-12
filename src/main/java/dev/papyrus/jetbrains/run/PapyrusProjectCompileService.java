package dev.papyrus.jetbrains.run;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.KillableProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import dev.papyrus.jetbrains.actions.PapyrusProjectCompileSafety;
import dev.papyrus.jetbrains.config.PapyrusSettings;
import dev.papyrus.jetbrains.runtime.PapyrusLaunchConfiguration;
import dev.papyrus.jetbrains.runtime.PapyrusLaunchConfigurationResolver;
import dev.papyrus.jetbrains.runtime.PapyrusRuntimePaths;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PapyrusProjectCompileService {
    private static final Logger LOG = Logger.getInstance(PapyrusProjectCompileService.class);
    private static final Set<Project> RUNNING = ConcurrentHashMap.newKeySet();

    private PapyrusProjectCompileService() {
    }

    public static boolean tryAcquire(@NotNull Project project) {
        return RUNNING.add(project);
    }

    public static boolean isRunning(@NotNull Project project) {
        return RUNNING.contains(project);
    }

    public static void release(@NotNull Project project) {
        RUNNING.remove(project);
    }

    public static @NotNull PreparedCompile prepare(
            @NotNull Project project,
            @NotNull Path projectFile
    ) throws Exception {
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            throw new IllegalArgumentException("Papyrus project compilation requires an IDE project root.");
        }

        PapyrusProjectCompileSafety.Plan plan = PapyrusProjectCompileSafety.validate(Path.of(basePath), projectFile);
        PapyrusLaunchConfiguration launch = PapyrusLaunchConfigurationResolver.resolve(
                PapyrusSettings.getInstance().getState()
        );
        Path pyro = PapyrusRuntimePaths.getPyroExecutable();
        Path compiler = launch.compilerAssemblyPath().resolve("PapyrusCompiler.exe").normalize();
        if (!Files.isRegularFile(compiler)) {
            throw new IllegalStateException("PapyrusCompiler.exe was not found: " + compiler);
        }

        Files.createDirectories(plan.outputDirectory());
        Path snapshot = createPyroProjectSnapshot(plan);
        boolean success = false;
        try {
            List<String> parameters = new ArrayList<>();
            addOption(parameters, "input-path", snapshot.toString());
            addOption(parameters, "game-type", "sse");
            addOption(parameters, "game-path", launch.creationKitInstallPath().toString());
            addOption(parameters, "compiler-path", compiler.toString());
            addOption(parameters, "output-path", plan.outputDirectory().toString());

            GeneralCommandLine commandLine = new GeneralCommandLine(pyro.toString())
                    .withWorkDirectory(plan.workingDirectory().toFile())
                    .withParameters(parameters);
            success = true;
            return new PreparedCompile(plan, snapshot, commandLine);
        } finally {
            if (!success) {
                deleteSnapshot(snapshot);
            }
        }
    }

    public static @NotNull ProcessHandler startProcess(
            @NotNull Project project,
            @NotNull PreparedCompile prepared
    ) throws ExecutionException {
        boolean handedOff = false;
        try {
            KillableProcessHandler handler = new KillableProcessHandler(prepared.commandLine());
            handler.addProcessListener(new ProcessListener() {
                @Override
                public void processTerminated(@NotNull ProcessEvent event) {
                    cleanup(prepared);
                    release(project);
                }
            });
            ProcessTerminatedListener.attach(handler);
            handedOff = true;
            return handler;
        } finally {
            if (!handedOff) {
                cleanup(prepared);
                release(project);
            }
        }
    }

    public static void cleanup(@NotNull PreparedCompile prepared) {
        deleteSnapshot(prepared.snapshot());
        refreshOutput(prepared.plan().outputDirectory());
    }

    private static @NotNull Path createPyroProjectSnapshot(
            @NotNull PapyrusProjectCompileSafety.Plan plan
    ) throws IOException {
        Path snapshot = Files.createTempFile(plan.workingDirectory(), ".papyrus-jetbrains-compile-", ".ppj");
        boolean written = false;
        try {
            Files.writeString(snapshot, plan.pyroProjectXml(), StandardCharsets.UTF_8);
            written = true;
            return snapshot;
        } finally {
            if (!written) {
                Files.deleteIfExists(snapshot);
            }
        }
    }

    private static void deleteSnapshot(@NotNull Path snapshot) {
        try {
            Files.deleteIfExists(snapshot);
        } catch (IOException exception) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Failed to delete temporary Papyrus compile snapshot: " + snapshot, exception);
            }
        }
    }

    private static void addOption(@NotNull List<String> parameters, @NotNull String name, @NotNull String value) {
        parameters.add("--" + name);
        parameters.add(value);
    }

    private static void refreshOutput(@NotNull Path outputDirectory) {
        VirtualFile output = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(outputDirectory);
        if (output != null) {
            VfsUtil.markDirtyAndRefresh(false, true, true, output);
        }
    }

    public record PreparedCompile(
            @NotNull PapyrusProjectCompileSafety.Plan plan,
            @NotNull Path snapshot,
            @NotNull GeneralCommandLine commandLine
    ) {
    }
}
