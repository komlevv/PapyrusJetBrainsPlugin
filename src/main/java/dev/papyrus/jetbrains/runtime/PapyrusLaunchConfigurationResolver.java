package dev.papyrus.jetbrains.runtime;

import dev.papyrus.jetbrains.config.PapyrusSettings;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public final class PapyrusLaunchConfigurationResolver {

    private static final Path HOST_RELATIVE_PATH = Path.of(
            "bin", "Debug", "net472", "DarkId.Papyrus.Host.Skyrim", "DarkId.Papyrus.Host.Skyrim.exe"
    );
    private static final Path REMOTES_RELATIVE_PATH = Path.of("pyro", "remote");

    private PapyrusLaunchConfigurationResolver() {
    }

    public static PapyrusLaunchConfiguration resolve(PapyrusSettings.SettingsState state) {
        return resolve(state, WindowsPapyrusRegistryReader.INSTANCE, PapyrusBundledVsix::getExtensionRoot);
    }

    static PapyrusLaunchConfiguration resolve(
            PapyrusSettings.SettingsState state,
            PapyrusRegistryReader registryReader,
            Path vsixRoot
    ) {
        return resolve(state, registryReader, () -> vsixRoot);
    }

    private static PapyrusLaunchConfiguration resolve(
            PapyrusSettings.SettingsState state,
            PapyrusRegistryReader registryReader,
            Supplier<Path> vsixRootSupplier
    ) {
        Path creationKitInstallPath = PapyrusGameInstallPathResolver.resolveSkyrimSpecialEdition(
                state.creationKitInstallPath,
                registryReader
        ).orElseThrow(() -> new ReadinessException(
                PapyrusLaunchReadiness.Kind.MISSING_GAME,
                "Skyrim Special Edition installation was not found. Configure it in Settings | Papyrus " +
                        "or install Skyrim Special Edition so the Bethesda registry entry is available."
        ));

        List<String> iniPaths = parseIniPaths(state.iniPaths);
        CreationKitPapyrusConfig creationKitConfig = CreationKitIniLoader.load(creationKitInstallPath, iniPaths);
        Path compilerPath = resolveCompilerPath(state.compilerPathOverride, creationKitInstallPath, creationKitConfig);

        Path vsixRoot = vsixRootSupplier.get().toAbsolutePath().normalize();
        if (!Files.isDirectory(vsixRoot)) {
            throw new IllegalStateException("Bundled Papyrus VSIX extension root does not exist: " + vsixRoot);
        }
        Path hostExecutable = vsixRoot.resolve(HOST_RELATIVE_PATH).normalize();
        if (!Files.isRegularFile(hostExecutable)) {
            throw new IllegalStateException("Papyrus language server executable was not found: " + hostExecutable);
        }

        Path remotesPath = vsixRoot.resolve(REMOTES_RELATIVE_PATH).normalize();
        if (!Files.isDirectory(remotesPath)) {
            throw new IllegalStateException("Papyrus remotes directory was not found: " + remotesPath);
        }

        return new PapyrusLaunchConfiguration(
                hostExecutable,
                hostExecutable.getParent(),
                compilerPath,
                nonBlankOrDefault(state.flagsFileName, "TESV_Papyrus_Flags.flg"),
                nonBlankOrDefault(state.ambientProjectName, "Creation Kit"),
                creationKitConfig.scriptSourceFolder(),
                creationKitConfig.additionalImports(),
                creationKitInstallPath,
                iniPaths,
                remotesPath
        );
    }

    public static @NotNull PapyrusLaunchReadiness readiness(PapyrusSettings.SettingsState state) {
        return readiness(state, WindowsPapyrusRegistryReader.INSTANCE, PapyrusBundledVsix::getExtensionRoot);
    }

    static @NotNull PapyrusLaunchReadiness readiness(
            PapyrusSettings.SettingsState state,
            PapyrusRegistryReader registryReader,
            Path vsixRoot
    ) {
        return readiness(state, registryReader, () -> vsixRoot);
    }

    private static @NotNull PapyrusLaunchReadiness readiness(
            PapyrusSettings.SettingsState state,
            PapyrusRegistryReader registryReader,
            Supplier<Path> vsixRootSupplier
    ) {
        if (!state.enabled) {
            return PapyrusLaunchReadiness.disabled();
        }
        try {
            resolve(state, registryReader, vsixRootSupplier);
            return PapyrusLaunchReadiness.ready();
        } catch (ReadinessException exception) {
            if (exception.kind == PapyrusLaunchReadiness.Kind.MISSING_GAME) {
                return PapyrusLaunchReadiness.missingGame(exception.getMessage());
            }
            return PapyrusLaunchReadiness.compilerMissing(exception.getMessage());
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            return PapyrusLaunchReadiness.error(
                    message == null || message.isBlank() ? exception.getClass().getSimpleName() : message
            );
        }
    }

    private static Path resolveCompilerPath(
            String compilerPathOverride,
            Path creationKitInstallPath,
            CreationKitPapyrusConfig creationKitConfig
    ) {
        Path compilerPath;
        try {
            if (compilerPathOverride != null && !compilerPathOverride.isBlank()) {
                compilerPath = Path.of(compilerPathOverride).normalize();
            } else {
                String compilerFolder = creationKitConfig.compilerFolder();
                if (compilerFolder == null || compilerFolder.isBlank()) {
                    compilerFolder = "Papyrus Compiler\\";
                }
                compilerPath = creationKitInstallPath.resolve(compilerFolder).normalize();
            }
        } catch (RuntimeException exception) {
            throw ReadinessException.invalidCompilerPath(exception);
        }

        if (!Files.isDirectory(compilerPath)) {
            throw new ReadinessException(
                    PapyrusLaunchReadiness.Kind.COMPILER_MISSING,
                    "Papyrus Compiler directory was not found: " + compilerPath
            );
        }

        return compilerPath;
    }

    public static List<String> parseIniPaths(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return List.of("CreationKit.ini", "CreationKitCustom.ini");
        }

        return Arrays.stream(rawValue.replace("\r\n", "\n").split("\n"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private static String nonBlankOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static final class ReadinessException extends IllegalStateException {
        private final PapyrusLaunchReadiness.Kind kind;

        private ReadinessException(PapyrusLaunchReadiness.Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        private ReadinessException(Throwable cause) {
            super("Papyrus Compiler path is invalid.", cause);
            this.kind = PapyrusLaunchReadiness.Kind.COMPILER_MISSING;
        }

        private static @NotNull ReadinessException invalidCompilerPath(@NotNull Throwable cause) {
            return new ReadinessException(cause);
        }
    }
}
