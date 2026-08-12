package dev.papyrus.jetbrains.runtime;

import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CreationKitIniLoader {
    private static final Logger LOG = Logger.getInstance(CreationKitIniLoader.class);

    private CreationKitIniLoader() {
    }

    public static CreationKitPapyrusConfig load(Path creationKitInstallPath, List<String> iniPaths) {
        CreationKitPapyrusConfig defaults = CreationKitPapyrusConfig.skyrimSpecialEditionDefaults();
        MutablePapyrusConfig config = new MutablePapyrusConfig(
                defaults.scriptSourceFolder(),
                defaults.additionalImports(),
                defaults.compilerFolder()
        );

        for (String configuredPath : iniPaths) {
            Path iniPath = resolveIniPath(creationKitInstallPath, configuredPath);
            if (!Files.isRegularFile(iniPath)) {
                continue;
            }

            try {
                applyIni(iniPath, config);
            } catch (IOException exception) {
                // The language server treats missing optional Creation Kit INIs as non-fatal.
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Failed to read optional Creation Kit INI: " + iniPath, exception);
                }
            }
        }

        return new CreationKitPapyrusConfig(
                config.scriptSourceFolder,
                config.additionalImports,
                config.compilerFolder
        );
    }

    private static Path resolveIniPath(Path creationKitInstallPath, String configuredPath) {
        Path path = Path.of(configuredPath);
        return path.isAbsolute() ? path.normalize() : creationKitInstallPath.resolve(path).normalize();
    }

    private static void applyIni(Path iniPath, MutablePapyrusConfig config) throws IOException {
        boolean inPapyrusSection = false;

        for (String rawLine : Files.readAllLines(iniPath, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                continue;
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                inPapyrusSection = "Papyrus".equalsIgnoreCase(line.substring(1, line.length() - 1).trim());
                continue;
            }

            if (!inPapyrusSection) {
                continue;
            }

            int equalsIndex = line.indexOf('=');
            if (equalsIndex <= 0) {
                continue;
            }

            String key = line.substring(0, equalsIndex).trim();
            String value = stripQuotes(line.substring(equalsIndex + 1).trim());

            if ("sScriptSourceFolder".equalsIgnoreCase(key)) {
                config.scriptSourceFolder = emptyToNull(value);
            } else if ("sAdditionalImports".equalsIgnoreCase(key)) {
                config.additionalImports = emptyToNull(value);
            } else if ("sCompilerFolder".equalsIgnoreCase(key)) {
                config.compilerFolder = emptyToNull(value);
            }
        }
    }

    private static String stripQuotes(String value) {
        return value.replace("\"", "");
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static final class MutablePapyrusConfig {
        private String scriptSourceFolder;
        private String additionalImports;
        private String compilerFolder;

        private MutablePapyrusConfig(String scriptSourceFolder, String additionalImports, String compilerFolder) {
            this.scriptSourceFolder = scriptSourceFolder;
            this.additionalImports = additionalImports;
            this.compilerFolder = compilerFolder;
        }
    }
}
