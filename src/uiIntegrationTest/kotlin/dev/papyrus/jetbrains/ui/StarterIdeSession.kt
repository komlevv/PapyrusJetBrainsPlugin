package dev.papyrus.jetbrains.ui

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.client.utility
import com.intellij.driver.model.LockSemantics
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.WaitForException
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.isDialogOpened
import com.intellij.driver.sdk.ui.components.elements.waitForNoOpenedDialogs
import com.intellij.driver.sdk.ui.ui
import com.intellij.ide.starter.driver.engine.BackgroundRun
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.DefaultIdeDistributionFactory
import com.intellij.ide.starter.ide.IDEStartConfig
import com.intellij.ide.starter.ide.IdeDownloader
import com.intellij.ide.starter.ide.IdeInstaller
import com.intellij.ide.starter.ide.InstalledIde
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.models.VMOptions
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import dev.papyrus.jetbrains.runtime.WindowsKillOnCloseJob
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration as JavaDuration
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class StarterIdeSession private constructor(
    private val backgroundRun: BackgroundRun,
    val driver: Driver,
    val project: Project,
    private val ideProcessId: Long,
    private val ideJob: WindowsKillOnCloseJob,
) : AutoCloseable {

    companion object {
        private const val PLUGIN_ID = "dev.papyrus.intellij-papyrus"

        // This handle intentionally lives until the UI-test JVM exits. Closing it while the current JVM
        // is a job member would terminate the controller itself. Windows closes the handle automatically
        // on normal or abnormal JVM termination, which kills any still-running inherited child processes.
        private val testProcessLifetimeJob: WindowsKillOnCloseJob by lazy {
            val job = WindowsKillOnCloseJob.create()
            try {
                job.assign(ProcessHandle.current().pid())
                job
            } catch (error: Throwable) {
                job.close()
                throw error
            }
        }

        fun start(fixture: UiTestEnvironment.Fixture): StarterIdeSession {
            val ideHome = UiTestEnvironment.ideHome()
            val pluginZip = UiTestEnvironment.pluginZip()
            val ideInfo = localIdeInfo(ideHome)
            val testCase = TestCase(ideInfo, LocalProjectInfo(fixture.root))
            val context = Starter.newContext("papyrus-ui-features", testCase)
                .setMemorySize(2048)
                .applyVMOptionsPatch {
                    addSystemProperty("idea.trust.all.projects", true)
                    addSystemProperty("ide.show.tips.on.startup.default.value", false)
                    addSystemProperty("papyrus.ui.integration.test", true)
                    addSystemProperty("jna.boot.library.path", ideHome.resolve("lib/jna/amd64"))
                    addSystemProperty("jna.nosys", true)
                    addSystemProperty("jna.noclasspath", true)
                    addLine("--enable-native-access=ALL-UNNAMED")
                }

            UiTestEnvironment.writeSettings(context.paths.configDir)
            context.pluginConfigurator
                .installPluginFromPath(pluginZip)
                .assertPluginIsInstalled(PLUGIN_ID)

            // Establish the controller process tree boundary before Starter creates CLion. This removes
            // the launch-to-assignment gap: spawned IDE processes inherit this job immediately.
            testProcessLifetimeJob
            val backgroundRun = context.runIdeWithDriver()
            var ideJob: WindowsKillOnCloseJob? = null
            try {
                val ideProcessId = backgroundRun.process.id.toLong()
                val assignedIdeJob = WindowsKillOnCloseJob.create()
                ideJob = assignedIdeJob
                assignedIdeJob.assign(ideProcessId)
                val driver = backgroundRun.driver

                val foreground = WindowsForegroundHelper.forceForeground(ideProcessId, JavaDuration.ofSeconds(5))
                check(foreground.success()) { "Failed to foreground spawned IDE: ${foreground.describe()}" }

                acceptClionOpenProjectWizardIfPresent(driver, 8.seconds)
                val project = waitForProject(driver, 45.seconds)
                return StarterIdeSession(backgroundRun, driver, project, ideProcessId, assignedIdeJob)
            } catch (error: Throwable) {
                runCatching { backgroundRun.closeIdeAndWait() }
                    .onFailure(error::addSuppressed)
                runCatching { ideJob?.close() }
                    .onFailure(error::addSuppressed)
                throw error
            }
        }

        private fun localIdeInfo(ideHome: Path): IdeInfo {
            return IdeInfo(
                productCode = UiTestEnvironment.ideProductCode(),
                platformPrefix = UiTestEnvironment.idePlatformPrefix(),
                executableFileName = UiTestEnvironment.ideExecutableFileName(),
                buildNumber = UiTestEnvironment.ideBuildNumber(),
                version = UiTestEnvironment.ideVersion(),
                fullName = UiTestEnvironment.ideName(),
                getInstaller = { LocalIdeInstaller(ideHome) },
            )
        }

        /**
         * CLion shows its Toolchains step when started with a fresh config directory and no imported settings.
         * UI integration tests intentionally use an isolated config, so accept the product-owned first-run dialog
         * through Driver instead of writing CLion-private toolchain XML into the test config.
         *
         * Keep wizard discovery independent from project-open state. CLion may initialize and show the project frame
         * before this modal first-run wizard appears, so project readiness must not short-circuit wizard discovery.
         */
        private fun acceptClionOpenProjectWizardIfPresent(driver: Driver, discoveryTimeout: Duration) {
            val ui = driver.ui
            val wizardXpath = "//div[@title='Open Project Wizard']"
            val discoveryDeadline = System.nanoTime() + discoveryTimeout.inWholeNanoseconds
            var opened = false
            while (System.nanoTime() < discoveryDeadline) {
                if (runCatching { ui.isDialogOpened(wizardXpath) }.getOrDefault(false)) {
                    opened = true
                    break
                }
                Thread.sleep(100)
            }
            if (!opened) return

            ui.dialog(title = "Open Project Wizard") {
                val ok = okButton.waitFound(10.seconds)
                waitFor(
                    message = "CLion Open Project Wizard OK button enabled",
                    timeout = 60.seconds,
                    interval = 100.milliseconds,
                ) {
                    runCatching { ok.isEnabled() }.getOrDefault(false)
                }
                ok.click()
            }

            try {
                ui.waitForNoOpenedDialogs(60.seconds)
            } catch (error: WaitForException) {
                val wizardStillOpen = runCatching { ui.isDialogOpened(wizardXpath) }.getOrNull()
                val projectCount = runCatching { driver.service<ProjectManagerRemote>().getOpenProjects().size }.getOrNull()
                throw AssertionError(
                    "CLion first-run dialog did not settle after Open Project Wizard confirmation. " +
                        "wizardStillOpen=$wizardStillOpen, openProjects=$projectCount",
                    error,
                )
            }
        }

        private fun waitForProject(driver: Driver, timeout: Duration): Project {
            val deadline = System.nanoTime() + timeout.inWholeNanoseconds
            var last: Throwable? = null
            while (System.nanoTime() < deadline) {
                try {
                    val projects = driver.service<ProjectManagerRemote>().getOpenProjects()
                    if (projects.size == 1) return projects.single()
                } catch (error: Throwable) {
                    last = error
                }
                Thread.sleep(100)
            }
            throw IllegalStateException("Expected exactly one open IDE project", last)
        }
    }

    fun forceForeground(): WindowsForegroundHelper.ActivationResult =
        WindowsForegroundHelper.forceForeground(ideProcessId, JavaDuration.ofSeconds(5))

    fun isForeground(): Boolean = WindowsForegroundHelper.isForegroundProcess(ideProcessId)

    /**
     * Queries a top-level Driver dialog through the same UiRobot API used by startup handling.
     * Keep Driver UI extension imports localized in this session wrapper so tests do not need
     * to know whether `ui` is an extension property or a member of Driver.
     */
    fun isDialogOpened(xpath: String): Boolean =
        runCatching { driver.ui.isDialogOpened(xpath) }.getOrDefault(false)

    inline fun <reified T : Any> service(): T = driver.service()
    inline fun <reified T : Any> service(project: Project): T = driver.service(project)
    inline fun <reified T : Any> utility(): T = driver.utility()
    inline fun <reified T : Any> cast(value: Any): T = driver.cast(value, T::class)

    fun <T> read(block: Driver.() -> T): T = driver.withReadAction(code = block)
    fun <T> write(block: Driver.() -> T): T = driver.withWriteAction(code = block)
    fun <T> context(block: Driver.() -> T): T = driver.withContext(code = block)
    fun <T> edt(block: Driver.() -> T): T = driver.withContext(
        dispatcher = OnDispatcher.EDT,
        semantics = LockSemantics.NO_LOCK,
        code = block,
    )

    // Keep short-lived remote objects hard-referenced for the whole related EDT operation.
    fun <T> projectEdt(block: Driver.(Project) -> T): T = driver.withContext(
        dispatcher = OnDispatcher.EDT,
        semantics = LockSemantics.NO_LOCK,
    ) {
        val projects = service<ProjectManagerRemote>().getOpenProjects()
        check(projects.size == 1) { "Expected exactly one open IDE project, got ${projects.size}" }
        block(projects.single())
    }

    fun waitFor(message: String, timeout: Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        var last: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                if (condition()) return
                last = null
            } catch (error: Throwable) {
                last = error
            }
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for $message").also { error ->
            last?.let(error::addSuppressed)
        }
    }

    override fun close() {
        val papyrusHosts = papyrusHostDescendants()
        var closeFailure: Throwable? = null
        try {
            backgroundRun.closeIdeAndWait()
        } catch (error: Throwable) {
            closeFailure = error
        } finally {
            // Closing the test JVM also closes this native handle automatically. The explicit close here
            // makes normal test completion deterministic and force-kills a stuck IDE if graceful shutdown failed.
            ideJob.close()
        }

        val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
        while (papyrusHosts.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        val survivors = papyrusHosts.filter(ProcessHandle::isAlive)
        if (survivors.isNotEmpty()) {
            survivors.forEach { it.destroyForcibly() }
            val orphanFailure = IllegalStateException(
                "Papyrus language host survived IDE/test shutdown: ${survivors.joinToString { it.pid().toString() }}",
            )
            if (closeFailure != null) {
                closeFailure.addSuppressed(orphanFailure)
            } else {
                closeFailure = orphanFailure
            }
        }
        closeFailure?.let { throw it }
    }

    private fun papyrusHostDescendants(): List<ProcessHandle> {
        val ideHandle = ProcessHandle.of(ideProcessId).orElse(null) ?: return emptyList()
        return ideHandle.descendants().use { descendants ->
            descendants.filter { process ->
                val command = process.info().command().orElse("")
                    .replace('\\', '/')
                    .substringAfterLast('/')
                command.equals("DarkId.Papyrus.Host.Skyrim.exe", ignoreCase = true)
            }.toList()
        }
    }

    @Remote("com.intellij.openapi.project.ProjectManager")
    internal interface ProjectManagerRemote {
        fun getOpenProjects(): Array<Project>
    }

    private class LocalIdeInstaller(private val ideHome: Path) : IdeInstaller {
        override val downloader: IdeDownloader
            get() = error("Offline UI tests must not download the IDE")

        override suspend fun install(ideInfo: IdeInfo): Pair<String, InstalledIde> {
            val installed = DefaultIdeDistributionFactory.installIDE(ideHome, ideInfo.executableFileName)
            return installed.build to LocalInstalledIde(installed, ideHome)
        }
    }

    private class LocalInstalledIde(
        private val delegate: InstalledIde,
        private val ideHome: Path,
    ) : InstalledIde by delegate {
        override val patchedVMOptionsFile: Path? = null

        override fun startConfig(vmOptions: VMOptions, logsDir: Path): IDEStartConfig =
            LocalStartConfig(ideHome, UiTestEnvironment.ideLauncher(), vmOptions)
    }

    private class LocalStartConfig(
        private val ideHome: Path,
        launcher: Path,
        vmOptions: VMOptions,
    ) : IDEStartConfig {
        override val workDir: Path = ideHome
        override val commandLine: List<String>
        override val environmentVariables: Map<String, String>

        init {
            val executable = launcher.toAbsolutePath().normalize()
            require(Files.isRegularFile(executable)) { "Missing 64-bit IDE executable: $executable" }
            require(executable.fileName.toString().lowercase().endsWith("64.exe")) {
                "Refusing to launch a non-64-bit or ambiguous IDE executable: $executable"
            }
            val vmOptionsFile = UiTestEnvironment.ideVmOptions().toAbsolutePath().normalize()
            require(Files.isRegularFile(vmOptionsFile)) { "Missing 64-bit IDE VM options: $vmOptionsFile" }
            require(vmOptionsFile.fileName.toString().lowercase().contains("64")) {
                "Refusing to use ambiguous IDE VM options: $vmOptionsFile"
            }
            commandLine = listOf(executable.toString())

            val options = vmOptions.data()
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .joinToString(" ", transform = ::quoteOption)

            val environment = System.getenv()
                .filterKeys { key ->
                    !key.endsWith("_PROPERTIES") &&
                        !key.endsWith("_VM_OPTIONS") &&
                        key != "JAVA_HOME"
                }
                .toMutableMap()
            environment.putAll(vmOptions.environmentVariables)
            val inherited = environment["JAVA_TOOL_OPTIONS"]?.takeIf(String::isNotBlank)
            environment["JAVA_TOOL_OPTIONS"] = listOfNotNull(inherited, options.takeIf(String::isNotBlank)).joinToString(" ")
            environmentVariables = environment.toMap()
        }

        private fun quoteOption(option: String): String = when {
            option.none(Char::isWhitespace) -> option
            '"' !in option -> "\"$option\""
            '\'' !in option -> "'$option'"
            else -> error("Cannot quote JVM option: $option")
        }
    }

}
