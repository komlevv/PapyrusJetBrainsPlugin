package dev.papyrus.jetbrains.ui

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

internal object UiTestEnvironment {
    const val TARGET_FILE = "Source/Scripts/FeatureTarget.psc"
    const val CALLER_FILE = "Source/Scripts/FeatureCaller.psc"
    const val DIAGNOSTICS_FILE = "Source/Scripts/DiagnosticsFeature.psc"
    const val ERGONOMICS_FILE = "Source/Scripts/EditorErgonomics.psc"
    const val OVERRIDDEN_FILE = "Override/Scripts/UiOverrideProbe.psc"
    const val OVERRIDING_FILE = "Source/Scripts/UiOverrideProbe.psc"
    const val UNRESOLVED_FILE = "Unresolved/Scripts/UiUnresolvedProbe.psc"

    data class Fixture(
        val root: Path,
        val target: Path,
        val caller: Path,
        val diagnostics: Path,
        val ergonomics: Path,
        val overridden: Path,
        val overriding: Path,
        val unresolved: Path,
        val targetText: String,
        val callerText: String,
        val diagnosticsText: String,
        val ergonomicsText: String,
        val overriddenText: String,
        val overridingText: String,
        val unresolvedText: String,
    )

    fun create(): Fixture {
        val projectDir = directoryProperty("papyrus.test.projectDir")
        val vsixRoot = directoryProperty("papyrus.test.vsixRoot")
        val creationKitHome = directoryProperty("papyrus.test.creationKitHome")
        val output = isolatedUiWorkspace(projectDir)
        deleteTree(output)

        val root = output.resolve("project")
        val scripts = root.resolve("Source/Scripts")
        val overrideScripts = root.resolve("Override/Scripts")
        val unresolvedScripts = root.resolve("Unresolved/Scripts")
        Files.createDirectories(scripts)
        Files.createDirectories(overrideScripts)
        Files.createDirectories(unresolvedScripts)
        Files.createDirectories(root.resolve("Scripts"))
        // Keep the Starter fixture as a plain directory project. Starter opens LocalProjectInfo
        // directly; synthesizing .idea/modules.xml + an EMPTY_MODULE adds a second content-root
        // representation in CLion's Project view and is not needed for first-run Toolchains handling.

        val targetText = """
            Scriptname FeatureTarget extends Quest

            String Property RuntimeLabel Auto

            Function SharedProbe()
                Debug.Trace("target")
            EndFunction

            State RuntimeState
                Event OnBeginState()
                    Debug.Trace("state")
                EndEvent
            EndState

            Function Test()
                SharedProbe()
                Debug.Notification("ui-test")
            EndFunction
        """.trimIndent() + "\n"

        val callerText = """
            Scriptname FeatureCaller extends Quest
            FeatureTarget Property Target Auto

            Function Test()
                Target.SharedProbe()
                Target.SharedProbe()
            EndFunction
        """.trimIndent() + "\n"

        val diagnosticsText = """
            Scriptname DiagnosticsFeature extends Quest

            Function Test()
                if
            EndFunction
        """.trimIndent() + "\n"

        val ergonomicsText = """
            Scriptname EditorErgonomics extends Quest

            ; line comment
            String Property Label = "hello" Auto

            Function Test()
                Int Count = 42
                If Count > 0
                    Debug.Trace("value")
                EndIf
            EndFunction
        """.trimIndent() + "\n"

        val overriddenText = """
            Scriptname UiOverrideProbe extends Quest

            String Property SourceMarker = "lower-priority" Auto
        """.trimIndent() + "\n"

        val overridingText = """
            Scriptname UiOverrideProbe extends Quest

            String Property SourceMarker = "winning" Auto
        """.trimIndent() + "\n"

        val unresolvedText = """
            Scriptname UiUnresolvedProbe extends Quest

            Function Test()
                Debug.Trace("unresolved")
            EndFunction
        """.trimIndent() + "\n"

        val target = root.resolve(TARGET_FILE)
        val caller = root.resolve(CALLER_FILE)
        val diagnostics = root.resolve(DIAGNOSTICS_FILE)
        val ergonomics = root.resolve(ERGONOMICS_FILE)
        val overridden = root.resolve(OVERRIDDEN_FILE)
        val overriding = root.resolve(OVERRIDING_FILE)
        val unresolved = root.resolve(UNRESOLVED_FILE)
        Files.writeString(target, targetText, StandardCharsets.UTF_8)
        Files.writeString(caller, callerText, StandardCharsets.UTF_8)
        Files.writeString(diagnostics, diagnosticsText, StandardCharsets.UTF_8)
        Files.writeString(ergonomics, ergonomicsText, StandardCharsets.UTF_8)
        Files.writeString(overridden, overriddenText, StandardCharsets.UTF_8)
        Files.writeString(overriding, overridingText, StandardCharsets.UTF_8)
        Files.writeString(unresolved, unresolvedText, StandardCharsets.UTF_8)
        val template = vsixRoot.resolve("resources/sse/skyrimse.ppj")
        require(Files.isRegularFile(template)) { "Missing Skyrim SE PPJ template: $template" }
        val sourcePath = creationKitHome.resolve("Data/Source/Scripts").toString()
        val sourceImport = "<Import>.\\Source\\Scripts</Import>"
        val overrideImport = "<Import>.\\Override\\Scripts</Import>"
        val templateText = Files.readString(template, StandardCharsets.UTF_8)
        require(templateText.contains(sourceImport)) { "Skyrim SE PPJ template is missing the expected project source import" }
        val ppj = templateText
            .replace(sourceImport, "$sourceImport\n        $overrideImport")
            .replace("\${SKYRIMSE_PATH}", xml(sourcePath))
        Files.writeString(root.resolve("runtime.ppj"), ppj, StandardCharsets.UTF_8)

        return Fixture(
            root,
            target,
            caller,
            diagnostics,
            ergonomics,
            overridden,
            overriding,
            unresolved,
            targetText,
            callerText,
            diagnosticsText,
            ergonomicsText,
            overriddenText,
            overridingText,
            unresolvedText,
        )
    }

    fun writeSettings(configDir: Path) {
        val creationKitHome = directoryProperty("papyrus.test.creationKitHome")
        val ini = fileProperty("papyrus.test.ini")
        val settings = configDir.resolve("options/Papyrus.xml")
        Files.createDirectories(settings.parent)
        val content = """
            <application>
              <component name="dev.papyrus.intellij.config.PapyrusSettings">
                <option name="creationKitInstallPath" value="${xml(creationKitHome.toString())}" />
                <option name="compilerPathOverride" value="" />
                <option name="iniPaths" value="${xml(ini.toString())}" />
                <option name="ambientProjectName" value="Creation Kit" />
                <option name="flagsFileName" value="TESV_Papyrus_Flags.flg" />
              </component>
            </application>
        """.trimIndent() + "\n"
        Files.writeString(settings, content, StandardCharsets.UTF_8)
    }

    private fun isolatedUiWorkspace(projectDir: Path): Path {
        val bases = listOfNotNull(
            System.getProperty("java.io.tmpdir")?.takeIf(String::isNotBlank),
            System.getProperty("user.home")?.takeIf(String::isNotBlank),
        )
        val suffix = Integer.toHexString(projectDir.toString().lowercase().hashCode())
        for (baseValue in bases) {
            val candidate = Path.of(baseValue)
                .toAbsolutePath()
                .normalize()
                .resolve("PapyrusJetBrainsPlugin-ui-integration-test-$suffix")
                .normalize()
            if (!candidate.startsWith(projectDir)) {
                return candidate
            }
        }
        error("UI integration test workspace must be outside the source Git working tree: $projectDir")
    }

    fun ideHome(): Path = directoryProperty("papyrus.test.ideHome")
    fun ideName(): String = stringProperty("papyrus.test.ideName")
    fun ideVersion(): String = stringProperty("papyrus.test.ideVersion")
    fun ideBuildNumber(): String = stringProperty("papyrus.test.ideBuildNumber")
    fun ideProductCode(): String = stringProperty("papyrus.test.ideProductCode")
    fun idePlatformPrefix(): String = stringProperty("papyrus.test.idePlatformPrefix")
    fun ideExecutableFileName(): String = stringProperty("papyrus.test.ideExecutableFileName")
    fun ideLauncher(): Path = fileProperty("papyrus.test.ideLauncher")
    fun ideVmOptions(): Path = fileProperty("papyrus.test.ideVmOptions")
    fun creationKitHome(): Path = directoryProperty("papyrus.test.creationKitHome")
    fun pluginZip(): Path = fileProperty("papyrus.test.pluginZip")

    private fun stringProperty(name: String): String =
        System.getProperty(name)?.takeIf { it.isNotBlank() } ?: error("Missing system property: $name")

    private fun directoryProperty(name: String): Path {
        val value = System.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: error("Missing system property: $name")
        val path = Path.of(value).toAbsolutePath().normalize()
        require(Files.isDirectory(path)) { "$name is not a directory: $path" }
        return path.toRealPath()
    }

    private fun fileProperty(name: String): Path {
        val value = System.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: error("Missing system property: $name")
        val path = Path.of(value).toAbsolutePath().normalize()
        require(Files.isRegularFile(path)) { "$name is not a file: $path" }
        return path.toRealPath()
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun deleteTree(root: Path) {
        if (!root.exists()) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
