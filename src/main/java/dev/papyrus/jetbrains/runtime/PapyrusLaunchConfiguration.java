package dev.papyrus.jetbrains.runtime;

import java.nio.file.Path;
import java.util.List;

public record PapyrusLaunchConfiguration(
        Path hostExecutable,
        Path hostWorkingDirectory,
        Path compilerAssemblyPath,
        String flagsFileName,
        String ambientProjectName,
        String defaultScriptSourceFolder,
        String defaultAdditionalImports,
        Path creationKitInstallPath,
        List<String> relativeIniPaths,
        Path remotesInstallPath
) {
}
