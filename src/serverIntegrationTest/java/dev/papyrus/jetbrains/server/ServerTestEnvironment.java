package dev.papyrus.jetbrains.server;

import dev.papyrus.jetbrains.runtime.CreationKitIniLoader;
import dev.papyrus.jetbrains.runtime.CreationKitPapyrusConfig;
import dev.papyrus.jetbrains.runtime.PapyrusHostRuntimeStager;
import dev.papyrus.jetbrains.runtime.PapyrusHostGuardianMain;
import dev.papyrus.jetbrains.runtime.WindowsKillOnCloseJob;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("UseOptimizedEelFunctions") // Server integration fixtures are local Windows files; Eel remote paths are not exercised.
final class ServerTestEnvironment {
    private static final String INI_PROPERTY = "papyrus.test.ini";
    final Path projectDir;
    final Path outputDir;
    final Path vsixRoot;
    final Path creationKitHome;
    final Path iniFile;
    final Path compilerDir;
    final Path hostDir;
    final Path hostExe;
    final CreationKitPapyrusConfig ini;

    private ServerTestEnvironment(
            Path projectDir,
            Path outputDir,
            Path vsixRoot,
            Path creationKitHome,
            Path iniFile,
            Path compilerDir,
            Path hostDir,
            Path hostExe,
            CreationKitPapyrusConfig ini
    ) {
        this.projectDir = projectDir;
        this.outputDir = outputDir;
        this.vsixRoot = vsixRoot;
        this.creationKitHome = creationKitHome;
        this.iniFile = iniFile;
        this.compilerDir = compilerDir;
        this.hostDir = hostDir;
        this.hostExe = hostExe;
        this.ini = ini;
    }

    static ServerTestEnvironment load() throws IOException {
        Path projectDir = requiredDirectory("papyrus.test.projectDir");
        Path outputDir = Path.of(requiredProperty("papyrus.test.outputDir")).toAbsolutePath().normalize();
        Files.createDirectories(outputDir);
        Path vsixRoot = requiredDirectory("papyrus.test.vsixRoot");
        Path creationKitHome = requiredDirectory("papyrus.test.creationKitHome");
        Path iniFile = requiredIniFile();
        CreationKitPapyrusConfig ini = CreationKitIniLoader.load(creationKitHome, List.of(iniFile.toString()));

        String compilerFolder = ini.compilerFolder();
        Path compilerDir = creationKitHome.resolve(
                compilerFolder == null || compilerFolder.isBlank() ? "Papyrus Compiler" : compilerFolder
        ).normalize();
        if (!Files.isDirectory(compilerDir)) {
            throw new IOException("Papyrus compiler directory does not exist: " + compilerDir);
        }
        if (!Files.isRegularFile(compilerDir.resolve("antlr.runtime.dll"))) {
            throw new IOException("Missing Papyrus compiler dependency: " + compilerDir.resolve("antlr.runtime.dll"));
        }

        Path hostExe = vsixRoot.resolve(Path.of(
                "bin", "Debug", "net472", "DarkId.Papyrus.Host.Skyrim", "DarkId.Papyrus.Host.Skyrim.exe"
        )).normalize();
        if (!Files.isRegularFile(hostExe)) {
            throw new IOException("Papyrus host executable does not exist: " + hostExe);
        }
        Path hostDir = hostExe.getParent();
        return new ServerTestEnvironment(
                projectDir,
                outputDir,
                vsixRoot,
                creationKitHome,
                iniFile,
                compilerDir,
                hostDir,
                hostExe,
                ini
        );
    }

    Path createWorkspace(String name) throws IOException {
        Path workspace = outputDir.resolve(name).toAbsolutePath().normalize();
        deleteTree(workspace);
        Files.createDirectories(workspace.resolve(Path.of("Source", "Scripts")));
        Files.createDirectories(workspace.resolve("Scripts"));

        Path template = vsixRoot.resolve(Path.of("resources", "sse", "skyrimse.ppj"));
        if (!Files.isRegularFile(template)) {
            throw new IOException("Skyrim SE PPJ template does not exist: " + template);
        }
        Path sourcePath = creationKitHome.resolve(Path.of("Data", "Source", "Scripts"));
        String ppj = Files.readString(template, StandardCharsets.UTF_8)
                .replace("${SKYRIMSE_PATH}", xmlEscape(sourcePath.toString()));
        Files.writeString(workspace.resolve("runtime.ppj"), ppj, StandardCharsets.UTF_8);
        return workspace;
    }

    Process startServer(Path workspace, Path stderrLog) throws IOException {
        Files.createDirectories(stderrLog.getParent());
        PapyrusHostRuntimeStager.StagedHostRuntime staged = PapyrusHostRuntimeStager.stage(
                hostDir,
                hostExe,
                compilerDir,
                outputDir.resolve("host-runtime")
        );

        List<String> command = new ArrayList<>();
        command.add(staged.executable().toString());
        addOption(command, "compilerAssemblyPath", compilerDir.toString());
        addOption(command, "flagsFileName", "TESV_Papyrus_Flags.flg");
        addOption(command, "ambientProjectName", "Creation Kit");
        addOptionalOption(command, "defaultScriptSourceFolder", ini.scriptSourceFolder());
        addOptionalOption(command, "defaultAdditionalImports", ini.additionalImports());
        addOption(command, "creationKitInstallPath", creationKitHome.toString());
        command.add("--relativeIniPaths");
        command.add(iniFile.toString());
        addOption(command, "remotesInstallPath", remoteCacheDir(workspace).toString());

        Path gatePath = PapyrusHostGuardianMain.newGatePath();
        PapyrusHostGuardianMain.removeGate(gatePath);
        Process process = null;
        WindowsKillOnCloseJob job = null;
        try {
            Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe")
                    .toAbsolutePath()
                    .normalize();
            if (!Files.isRegularFile(javaExecutable)) {
                throw new IOException("Missing server-test Java executable for Papyrus host guardian: " + javaExecutable);
            }
            Path guardianClassPath = guardianClassPath();
            List<String> guardianCommand = PapyrusHostGuardianMain.javaCommand(
                    javaExecutable,
                    guardianClassPath.toString(),
                    ProcessHandle.current().pid(),
                    gatePath,
                    command
            );

            job = WindowsKillOnCloseJob.create();
            ProcessBuilder guardianBuilder = new ProcessBuilder(guardianCommand)
                    .directory(staged.workingDirectory().toFile())
                    .redirectError(stderrLog.toFile());
            PapyrusHostGuardianMain.sanitizeJavaLauncherEnvironment(guardianBuilder.environment());
            process = guardianBuilder.start();
            job.assign(process.pid());
            WindowsKillOnCloseJob assignedJob = job;
            process.onExit().whenComplete((ignored, failure) -> {
                PapyrusHostGuardianMain.removeGate(gatePath);
                assignedJob.close();
            });
            PapyrusHostGuardianMain.signal(gatePath);
            return process;
        } catch (IOException | RuntimeException | Error failure) {
            if (process != null) {
                destroyProcessTree(process, failure);
            }
            if (job != null) {
                closeJobAfterFailure(job, failure);
            }
            PapyrusHostGuardianMain.removeGate(gatePath);
            if (failure instanceof IOException ioException) {
                throw ioException;
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw (Error) failure;
        }
    }

    private static Path guardianClassPath() throws IOException {
        try {
            return Path.of(
                    PapyrusHostGuardianMain.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toAbsolutePath().normalize();
        } catch (URISyntaxException exception) {
            throw new IOException("Invalid Papyrus host guardian code-source URI", exception);
        }
    }

    private static void destroyProcessTree(Process process, Throwable originalFailure) {
        process.descendants().forEach(child -> {
            try {
                child.destroyForcibly();
            } catch (RuntimeException cleanupFailure) {
                originalFailure.addSuppressed(cleanupFailure);
            }
        });
        try {
            process.destroyForcibly();
        } catch (RuntimeException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    private static void closeJobAfterFailure(WindowsKillOnCloseJob job, Throwable originalFailure) {
        try {
            job.close();
        } catch (RuntimeException | Error cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
        }
    }

    Path remoteCacheDir(Path workspace) throws IOException {
        Path cache = workspace.resolve(".papyrus-test-remotes").toAbsolutePath().normalize();
        Files.createDirectories(cache);
        return cache;
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            for (Path path : stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void addOption(List<String> command, String name, String value) {
        command.add("--" + name);
        command.add(value);
    }

    private static void addOptionalOption(List<String> command, String name, String value) {
        if (value != null && !value.isBlank()) addOption(command, name, value);
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing system property: " + name);
        }
        return value;
    }

    private static Path requiredDirectory(String property) throws IOException {
        Path path = Path.of(requiredProperty(property)).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) throw new IOException(property + " is not a directory: " + path);
        return path;
    }

    private static Path requiredIniFile() throws IOException {
        Path path = Path.of(requiredProperty(INI_PROPERTY)).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IOException(INI_PROPERTY + " is not a file: " + path);
        return path;
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
