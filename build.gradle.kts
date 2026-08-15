import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    java
}

group = "dev.papyrus"
version = "0.2.168"

data class IdeLaunchTarget(
    val launcher: File,
    val vmOptions: File,
)

data class IdeTarget(
    val home: File,
    val name: String,
    val version: String,
    val buildNumber: String,
    val productCode: String,
    val platformPrefix: String,
    val executableFileName: String,
    val envVarBaseName: String,
    val launch: IdeLaunchTarget,
)

fun jsonStringField(json: String, key: String): String? {
    val pattern = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
    val encoded = pattern.find(json)?.groupValues?.get(1) ?: return null
    return encoded
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}

fun jsonObjectsInArray(json: String, key: String): List<String> {
    val keyIndex = json.indexOf("\"$key\"")
    if (keyIndex < 0) return emptyList()
    val arrayStart = json.indexOf('[', keyIndex)
    if (arrayStart < 0) return emptyList()

    val objects = mutableListOf<String>()
    var objectStart = -1
    var objectDepth = 0
    var arrayDepth = 1
    var inString = false
    var escaped = false
    var index = arrayStart + 1
    while (index < json.length && arrayDepth > 0) {
        val ch = json[index]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '"') {
                inString = false
            }
            index++
            continue
        }
        when (ch) {
            '"' -> inString = true
            '[' -> arrayDepth++
            ']' -> arrayDepth--
            '{' -> {
                if (arrayDepth == 1 && objectDepth == 0) objectStart = index
                objectDepth++
            }
            '}' -> {
                objectDepth--
                if (arrayDepth == 1 && objectDepth == 0 && objectStart >= 0) {
                    objects += json.substring(objectStart, index + 1)
                    objectStart = -1
                }
            }
        }
        index++
    }
    return objects
}

fun normalizedBuildNumber(value: String): String = value.substringAfter('-', value)
fun buildBranch(value: String): String = normalizedBuildNumber(value).substringBefore('.')
fun buildParts(value: String): List<Int> = Regex("\\d+").findAll(normalizedBuildNumber(value)).map { it.value.toInt() }.toList()

fun compareBuildNumbers(left: String, right: String): Int {
    val a = buildParts(left)
    val b = buildParts(right)
    for (index in 0 until maxOf(a.size, b.size)) {
        val av = a.getOrElse(index) { 0 }
        val bv = b.getOrElse(index) { 0 }
        if (av != bv) return av.compareTo(bv)
    }
    return 0
}

fun platformPrefix(productCode: String): String = when (productCode) {
    "CL" -> "CLion"
    "IU", "IC" -> "idea"
    else -> productCode
}

fun parseIdeTarget(home: File): IdeTarget? {
    val infoFile = home.resolve("product-info.json")
    if (!infoFile.isFile) return null
    val json = infoFile.readText(StandardCharsets.UTF_8)
    val name = jsonStringField(json, "name") ?: return null
    val version = jsonStringField(json, "version") ?: return null
    val buildNumber = jsonStringField(json, "buildNumber") ?: return null
    val productCode = jsonStringField(json, "productCode") ?: return null
    val envVarBaseName = jsonStringField(json, "envVarBaseName") ?: productCode

    val launches = jsonObjectsInArray(json, "launch").mapNotNull { launchJson ->
        val os = jsonStringField(launchJson, "os")
        val arch = jsonStringField(launchJson, "arch")
        val launcherPath = jsonStringField(launchJson, "launcherPath") ?: return@mapNotNull null
        val vmOptionsPath = jsonStringField(launchJson, "vmOptionsFilePath") ?: return@mapNotNull null
        val launcherName = File(launcherPath).name.lowercase()
        val vmOptionsName = File(vmOptionsPath).name.lowercase()
        val isWindows = os == null || os.equals("Windows", ignoreCase = true)
        val is64Arch = arch == null || arch.lowercase() in setOf("amd64", "x86_64", "x64")
        val is64Launcher = launcherName.endsWith("64.exe")
        val is64VmOptions = vmOptionsName.contains("64") && vmOptionsName.endsWith(".vmoptions")
        if (!isWindows || !is64Arch || !is64Launcher || !is64VmOptions) return@mapNotNull null
        IdeLaunchTarget(home.resolve(launcherPath), home.resolve(vmOptionsPath))
    }
    val launch = launches.firstOrNull() ?: return null
    val executableFileName = launch.launcher.name.removeSuffix("64.exe")
    return IdeTarget(
        home = home.toPath().toAbsolutePath().normalize().toFile(),
        name = name,
        version = version,
        buildNumber = normalizedBuildNumber(buildNumber),
        productCode = productCode,
        platformPrefix = platformPrefix(productCode),
        executableFileName = executableFileName,
        envVarBaseName = envVarBaseName,
        launch = launch,
    )
}

fun findIdeHomes(): List<File> {
    val productInfoFiles = linkedSetOf<Path>()

    fun scan(root: File, maxDepth: Int) {
        if (!root.isDirectory) return
        Files.walk(root.toPath(), maxDepth).use { paths ->
            paths.filter { it.fileName?.toString() == "product-info.json" }
                .forEach { productInfoFiles.add(it.toAbsolutePath().normalize()) }
        }
    }

    val programFiles = System.getenv("ProgramFiles")?.takeIf { it.isNotBlank() } ?: "C:/Program Files"
    scan(File(programFiles, "JetBrains"), 3)

    val localAppData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
    if (localAppData != null) {
        scan(File(localAppData, "Programs"), 3)
        scan(File(localAppData, "JetBrains/Toolbox/apps"), 6)
    }
    return productInfoFiles.mapNotNull { it.parent?.toFile() }
}

fun resolveIdeTarget(project: Project): IdeTarget {
    val requestedProduct = project.providers.gradleProperty("papyrusIdeProductCode").orNull
        ?: System.getenv("PAPYRUS_IDE_PRODUCT_CODE")?.takeIf { it.isNotBlank() }
        ?: "CL"
    val requestedBranch = project.providers.gradleProperty("papyrusIdeBranch").orNull
        ?: System.getenv("PAPYRUS_IDE_BRANCH")?.takeIf { it.isNotBlank() }
        ?: "262"
    val overrideHome = project.providers.gradleProperty("papyrusIdeHome").orNull
        ?: project.providers.gradleProperty("papyrusIdeaHome").orNull
        ?: System.getenv("PAPYRUS_IDE_HOME")?.takeIf { it.isNotBlank() }

    val candidates = if (overrideHome != null) {
        listOf(File(overrideHome))
    } else {
        findIdeHomes()
    }

    val parsed = candidates.mapNotNull(::parseIdeTarget)
    val matching = parsed.filter {
        it.productCode.equals(requestedProduct, ignoreCase = true) && buildBranch(it.buildNumber) == requestedBranch
    }
    val target = matching.maxWithOrNull { left, right -> compareBuildNumbers(left.buildNumber, right.buildNumber) }
        ?: error(buildString {
            append("No compatible 64-bit JetBrains IDE found. Required productCode=")
            append(requestedProduct)
            append(", platform branch=")
            append(requestedBranch)
            append(". Set -PpapyrusIdeHome=<IDE home> or PAPYRUS_IDE_HOME. ")
            append("The selected product-info.json must expose a Windows amd64/x86_64 *64.exe launcher and *64*.vmoptions.")
        })

    require(target.launch.launcher.isFile) { "Missing 64-bit IDE launcher: ${target.launch.launcher.absolutePath}" }
    require(target.launch.vmOptions.isFile) { "Missing 64-bit IDE VM options: ${target.launch.vmOptions.absolutePath}" }
    return target
}

val ideTarget = resolveIdeTarget(project)
val ideHome = ideTarget.home
val testDeps = projectDir.resolve("third_party/papyrus-test-deps")

val papyrusLangVersion = "v3.3.0-prerelease.1"
val papyrusLangVendorDir = projectDir.resolve("vendor/papyrus-lang/$papyrusLangVersion")
val papyrusLangVsix = papyrusLangVendorDir.resolve("papyrus-lang-vscode.vsix")
val papyrusLangVsixSha256 = "c4cf68d74471d4646b1c7dcff36f30293b507ebee215cc931cef051a0f8766db"
val extractedVsixDir = layout.buildDirectory.dir("vendor/papyrus-lang/$papyrusLangVersion")
val extractedVsixRoot = extractedVsixDir.map { it.dir("extension") }
val extractedVsixRemotes = extractedVsixRoot.map { it.dir("pyro/remote") }

val projectJvmTarget = JavaVersion.VERSION_25

val kotlinHome = file(
    providers.gradleProperty("papyrusKotlinHome")
        .getOrElse("X:/kotlinc")
)
val kotlinCompiler = kotlinHome.resolve("bin/kotlinc.bat")
val uiKotlinSources = file("src/uiIntegrationTest/kotlin")
val uiKotlinClasses = layout.buildDirectory.dir("classes/kotlin/uiIntegrationTest")
val uiKotlinArgs = layout.buildDirectory.file("tmp/compileUiIntegrationTestKotlin/kotlinc.args")

val idePlatform = files(
    fileTree(ideHome.resolve("lib")) {
        include("**/*.jar")
        exclude("papyrus-test-deps/**")
    },
    fileTree(ideHome.resolve("plugins/textmate-plugin/lib")) { include("**/*.jar") }
)
val junit = files(
    fileTree(testDeps) { include("junit*.jar", "junit/**/*.jar") }
)
val starter = files(
    fileTree(testDeps.resolve("starter")) {
        include("**/*.jar")
        exclude("ide-starter-product-*.jar")
    },
    fileTree(ideHome.resolve("plugins")) { include("**/*.jar") }
)
val driver = files(
    fileTree(ideHome.resolve("plugins/performanceTesting/lib")) { include("**/*.jar") },
    fileTree(testDeps.resolve("driver")) { include("**/*.jar") }
)

val creationKitHome = file("X:/SteamLibrary/steamapps/common/Skyrim Special Edition")
val papyrusIni = file("Y:/dev/PapyrusTest/IntellijPapyrus.ini")

tasks.register("printIdeTarget") {
    group = "help"
    description = "Prints the auto-resolved 64-bit JetBrains IDE target."
    doLast {
        println("IDE name: ${ideTarget.name}")
        println("IDE product code: ${ideTarget.productCode}")
        println("IDE version: ${ideTarget.version}")
        println("IDE build: ${ideTarget.buildNumber}")
        println("IDE home: ${ideTarget.home.absolutePath}")
        println("IDE launcher: ${ideTarget.launch.launcher.absolutePath}")
        println("IDE VM options: ${ideTarget.launch.vmOptions.absolutePath}")
        println("IDE test Java: ${ideHome.resolve("jbr/bin/java.exe").absolutePath}")
    }
}

java {
    sourceCompatibility = projectJvmTarget
    targetCompatibility = projectJvmTarget
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(projectJvmTarget.majorVersion.toInt())
    if (providers.gradleProperty("papyrusLintDeprecation").orNull?.toBoolean() == true) {
        options.compilerArgs.add("-Xlint:deprecation")
    }
}

val serverIntegrationTest = sourceSets.create("serverIntegrationTest") {
    java.srcDir("src/serverIntegrationTest/java")
    resources.srcDir("src/serverIntegrationTest/resources")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
val uiIntegrationTest = sourceSets.create("uiIntegrationTest") {
    java.srcDir("src/uiIntegrationTest/java")
    resources.srcDir("src/uiIntegrationTest/resources")
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[serverIntegrationTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[serverIntegrationTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())
configurations[uiIntegrationTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[uiIntegrationTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    compileOnly(idePlatform)
    testImplementation(idePlatform)
    testImplementation(junit)
    add(serverIntegrationTest.implementationConfigurationName, idePlatform)
    add(serverIntegrationTest.implementationConfigurationName, junit)
    add(uiIntegrationTest.implementationConfigurationName, idePlatform)
    add(uiIntegrationTest.implementationConfigurationName, junit)
    add(uiIntegrationTest.implementationConfigurationName, starter)
    add(uiIntegrationTest.implementationConfigurationName, driver)
}


fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun requirePapyrusLangVsix() {
    require(papyrusLangVsix.isFile) {
        "Missing vendored papyrus-lang VSIX: ${papyrusLangVsix.absolutePath}. " +
                "Copy papyrus-lang-vscode.vsix v3.3.0-prerelease.1 into the vendor directory."
    }
    val actual = sha256(papyrusLangVsix)
    require(actual.equals(papyrusLangVsixSha256, ignoreCase = true)) {
        "Unexpected papyrus-lang VSIX SHA-256: $actual (expected $papyrusLangVsixSha256)"
    }
}

val extractPapyrusLangVsix = tasks.register<Sync>("extractPapyrusLangVsix") {
    group = "build setup"
    description = "Extracts the pinned vendored papyrus-lang VSIX for offline tests."
    inputs.file(papyrusLangVsix)
    outputs.dir(extractedVsixDir)
    outputs.dir(extractedVsixRemotes)
    doFirst { requirePapyrusLangVsix() }
    from({ zipTree(papyrusLangVsix) })
    into(extractedVsixDir)
    doLast {
        val remotes = extractedVsixRemotes.get().asFile
        require(remotes.mkdirs() || remotes.isDirectory) {
            "Failed to create required Papyrus remotes directory: ${remotes.absolutePath}"
        }
    }
}

tasks.processResources {
    doFirst { requirePapyrusLangVsix() }
    from(papyrusLangVsix) {
        into("papyrus/vendor/$papyrusLangVersion")
        rename { "papyrus-lang-vscode.vsix" }
    }
}

fun requireIdeHome() {
    require(ideHome.isDirectory) { "IDE home does not exist: ${ideHome.absolutePath}" }
    require(ideHome.resolve("product-info.json").isFile) { "Missing IDE product-info.json" }
    require(ideHome.resolve("jbr/bin/java.exe").isFile) { "Missing IDE bundled Java" }
    require(ideHome.resolve("lib/jna/amd64").isDirectory) { "Missing IDE amd64 JNA natives" }
    require(ideHome.resolve("plugins/textmate-plugin/lib/textmate-plugin.jar").isFile) { "Missing TextMate plugin" }
}

fun missingClasses(classNames: List<String>, classpath: Set<File>): List<String> {
    val missing = classNames.toMutableSet()
    for (jar in classpath.filter { it.isFile && it.extension.equals("jar", true) }.sortedBy { it.path }) {
        if (missing.isEmpty()) break
        runCatching {
            ZipFile(jar).use { zip ->
                missing.removeIf { zip.getEntry(it.replace('.', '/') + ".class") != null }
            }
        }
    }
    return classNames.filter { it in missing }
}

fun requireTestDependency(relativePath: String) {
    val file = testDeps.resolve(relativePath)
    require(file.isFile) {
        "Missing project-local Papyrus test dependency: ${file.absolutePath}. " +
                "Copy the offline Papyrus test dependency bundle into third_party/papyrus-test-deps."
    }
}

fun requireJUnit() {
    requireTestDependency("junit-platform-console-standalone-1.11.4.jar")
    val missing = missingClasses(
        listOf(
            "org.junit.jupiter.api.Test",
            "org.junit.jupiter.api.Assertions",
            "org.junit.jupiter.engine.JupiterTestEngine"
        ),
        files(idePlatform, junit).files
    )
    require(missing.isEmpty()) { "Missing JUnit classes: ${missing.joinToString()}" }
}

fun requireUiTestSdk() {
    listOf(
        "starter/allure-java-commons-2.25.0.jar",
        "starter/allure-model-2.25.0.jar",
        "starter/kaverit-jvm-2.10.0.jar",
        "starter/kodein-di-jvm-7.26.1.jar"
    ).forEach(::requireTestDependency)

    fun singleSdkJar(relativeDir: String, prefix: String): File {
        val directory = testDeps.resolve(relativeDir)
        val matches = directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".jar") }
            ?.sortedBy { it.name }
            .orEmpty()
        require(matches.size == 1) {
            "Expected exactly one $prefix*.jar under ${directory.absolutePath}, found: ${matches.joinToString { it.name }}"
        }
        return matches.single()
    }

    val versionedSdk = listOf(
        singleSdkJar("driver", "driver-sdk-") to "driver-sdk-",
        singleSdkJar("starter", "ide-starter-driver-") to "ide-starter-driver-",
        singleSdkJar("starter", "ide-starter-junit5-") to "ide-starter-junit5-",
        singleSdkJar("starter", "ide-starter-squashed-") to "ide-starter-squashed-",
    )
    val sdkVersions = versionedSdk.map { (jar, prefix) -> jar.name.removePrefix(prefix).removeSuffix(".jar") }.toSet()
    require(sdkVersions.size == 1) { "Starter/Driver SDK builds must match exactly: ${sdkVersions.sorted().joinToString()}" }
    val sdkVersion = sdkVersions.single()
    require(buildBranch(sdkVersion) == buildBranch(ideTarget.buildNumber)) {
        "Starter/Driver SDK branch $sdkVersion does not match IDE branch ${ideTarget.buildNumber}"
    }
    if (sdkVersion != ideTarget.buildNumber) {
        logger.info(
            "Papyrus UI SDK build $sdkVersion differs from ${ideTarget.name} build ${ideTarget.buildNumber}; " +
                "same-branch compatibility mode is active."
        )
    }

    val missing = missingClasses(
        listOf(
            "com.intellij.ide.starter.runner.Starter",
            "com.intellij.ide.starter.driver.engine.RunWithDriverKt",
            "com.intellij.driver.client.Driver",
            "com.intellij.driver.sdk.Project",
            "com.intellij.driver.sdk.ui.remote.Component"
        ),
        files(starter, driver).files
    )
    require(missing.isEmpty()) { "Missing Starter/Driver test classes: ${missing.joinToString()}" }
    require(ideTarget.launch.vmOptions.isFile) { "Missing 64-bit IDE VM options: ${ideTarget.launch.vmOptions.absolutePath}" }
    require(ideHome.resolve("jbr/bin/java.exe").isFile) { "Missing bundled IDE JBR: ${ideHome.resolve("jbr/bin/java.exe").absolutePath}" }
    require(ideHome.resolve("lib/jna/amd64").isDirectory) { "Missing IDE amd64 JNA natives" }
}

fun requireKotlinCompiler() {
    require(kotlinCompiler.isFile) { "Missing Kotlin compiler: ${kotlinCompiler.absolutePath}" }
    require(kotlinHome.resolve("lib/kotlin-compiler.jar").isFile) { "Missing Kotlin compiler libraries under ${kotlinHome.absolutePath}" }
}

tasks.named<JavaCompile>("compileJava") {
    doFirst { requireIdeHome() }
}
tasks.named<JavaCompile>("compileTestJava") {
    outputs.upToDateWhen { false }
    doFirst {
        project.delete(destinationDirectory.get().asFile)
        requireIdeHome()
        requireJUnit()
    }
}
tasks.named<JavaCompile>(serverIntegrationTest.compileJavaTaskName) {
    outputs.upToDateWhen { false }
    doFirst {
        project.delete(destinationDirectory.get().asFile)
        requireIdeHome()
        requireJUnit()
    }
}
tasks.named<JavaCompile>(uiIntegrationTest.compileJavaTaskName) {
    outputs.upToDateWhen { false }
    doFirst {
        project.delete(destinationDirectory.get().asFile)
        requireIdeHome()
        requireJUnit()
        requireUiTestSdk()
    }
}

val compileUiIntegrationTestKotlin = tasks.register<Exec>("compileUiIntegrationTestKotlin") {
    group = "verification"
    description = "Compiles Kotlin sources for real IDE UI integration tests using the local offline compiler."
    dependsOn(tasks.named(uiIntegrationTest.compileJavaTaskName))
    inputs.files(fileTree(uiKotlinSources) { include("**/*.kt") })
    outputs.dir(uiKotlinClasses)
    outputs.upToDateWhen { false }

    doFirst {
        requireIdeHome()
        requireJUnit()
        requireUiTestSdk()
        requireKotlinCompiler()

        val outputDir = uiKotlinClasses.get().asFile
        project.delete(outputDir)
        outputDir.mkdirs()

        val sources = fileTree(uiKotlinSources) { include("**/*.kt") }
            .files
            .sortedBy { it.absolutePath }
        require(sources.isNotEmpty()) { "No Kotlin UI integration test sources found under ${uiKotlinSources.absolutePath}" }

        val javaClasses = tasks.named<JavaCompile>(uiIntegrationTest.compileJavaTaskName)
            .get().destinationDirectory.get().asFile
        val kotlinClasspath = files(uiIntegrationTest.compileClasspath, javaClasses).asPath
        val argsFile = uiKotlinArgs.get().asFile
        argsFile.parentFile.mkdirs()

        fun quoteArg(value: String): String = "\"${value.replace('\\', '/').replace("\"", "\\\"")}\""

        argsFile.writeText(
            buildString {
                appendLine("-jvm-target")
                appendLine(projectJvmTarget.majorVersion)
                appendLine("-no-stdlib")
                appendLine("-no-reflect")
                appendLine("-classpath")
                appendLine(quoteArg(kotlinClasspath))
                appendLine("-d")
                appendLine(quoteArg(outputDir.absolutePath))
                sources.forEach { appendLine(quoteArg(it.absolutePath)) }
            },
            StandardCharsets.UTF_8
        )

        commandLine(
            "cmd.exe", "/d", "/c", "call", kotlinCompiler.absolutePath, "@${argsFile.absolutePath}"
        )
    }
}

tasks.named(uiIntegrationTest.classesTaskName) {
    dependsOn(compileUiIntegrationTestKotlin)
}

tasks.named<Jar>("jar") {
    archiveBaseName.set(project.name)
    archiveVersion.set(project.version.toString())
}

val buildPlugin = tasks.register<Zip>("buildPlugin") {
    group = "build"
    description = "Builds the offline Papyrus plugin distribution ZIP."
    dependsOn(tasks.named("jar"))
    archiveBaseName.set(project.name)
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    into(project.name) {
        into("lib") { from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) }
    }
}

fun Test.configurePapyrusLogging() {
    useJUnitPlatform()
    testLogging {
        events("started", "passed", "skipped", "failed", "standardOut", "standardError")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

tasks.test {
    description = "Runs the complete Papyrus test suite."
    configurePapyrusLogging()
    dependsOn(extractPapyrusLangVsix)
    doFirst { requireIdeHome(); requireJUnit(); requirePapyrusLangVsix() }
    systemProperty("papyrus.test.projectDir", projectDir.absolutePath)
    systemProperty("papyrus.test.vsixRoot", extractedVsixRoot.get().asFile.absolutePath)
    systemProperty("jna.boot.library.path", ideHome.resolve("lib/jna/amd64").absolutePath)
    systemProperty("jna.nosys", "true")
    systemProperty("jna.noclasspath", "true")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val serverTestTask = tasks.register<Test>("serverIntegrationTest") {
    group = "verification"
    description = "Runs black-box tests against papyrus-lang v3.3.0-prerelease.1."
    dependsOn(tasks.named(serverIntegrationTest.classesTaskName), extractPapyrusLangVsix)
    testClassesDirs = serverIntegrationTest.output.classesDirs
    classpath = serverIntegrationTest.runtimeClasspath
    configurePapyrusLogging()
    doFirst { requireIdeHome(); requireJUnit(); requirePapyrusLangVsix() }
    systemProperty("papyrus.test.projectDir", projectDir.absolutePath)
    systemProperty("papyrus.test.outputDir", layout.buildDirectory.dir("server-integration-test").get().asFile.absolutePath)
    systemProperty("papyrus.test.vsixRoot", extractedVsixRoot.get().asFile.absolutePath)
    systemProperty("papyrus.test.creationKitHome", creationKitHome.absolutePath)
    systemProperty("papyrus.test.ini", papyrusIni.absolutePath)
    systemProperty("jna.boot.library.path", ideHome.resolve("lib/jna/amd64").absolutePath)
    systemProperty("jna.nosys", "true")
    systemProperty("jna.noclasspath", "true")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val uiTestTask = tasks.register<Test>("uiIntegrationTest") {
    executable(ideHome.resolve("jbr/bin/java.exe"))
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("slf4j.provider", "org.slf4j.jul.JULServiceProvider")
    group = "verification"
    description = "Runs real CLion 2026.2 feature tests through Starter/Driver."
    dependsOn(buildPlugin, tasks.named(uiIntegrationTest.classesTaskName), compileUiIntegrationTestKotlin, extractPapyrusLangVsix)
    testClassesDirs = files(uiIntegrationTest.output.classesDirs, uiKotlinClasses)
    classpath = files(uiIntegrationTest.runtimeClasspath, uiKotlinClasses)
    configurePapyrusLogging()
    doFirst { requireIdeHome(); requireJUnit(); requireUiTestSdk(); requireKotlinCompiler(); requirePapyrusLangVsix() }
    systemProperty("papyrus.test.projectDir", projectDir.absolutePath)
    systemProperty("papyrus.test.ideHome", ideHome.absolutePath)
    systemProperty("papyrus.test.ideName", ideTarget.name)
    systemProperty("papyrus.test.ideVersion", ideTarget.version)
    systemProperty("papyrus.test.ideBuildNumber", ideTarget.buildNumber)
    systemProperty("papyrus.test.ideProductCode", ideTarget.productCode)
    systemProperty("papyrus.test.idePlatformPrefix", ideTarget.platformPrefix)
    systemProperty("papyrus.test.ideExecutableFileName", ideTarget.executableFileName)
    systemProperty("papyrus.test.ideLauncher", ideTarget.launch.launcher.absolutePath)
    systemProperty("papyrus.test.ideVmOptions", ideTarget.launch.vmOptions.absolutePath)
    systemProperty("papyrus.test.vsixRoot", extractedVsixRoot.get().asFile.absolutePath)
    systemProperty("papyrus.test.creationKitHome", creationKitHome.absolutePath)
    systemProperty("papyrus.test.ini", papyrusIni.absolutePath)
    systemProperty("papyrus.test.pluginZip", buildPlugin.flatMap { it.archiveFile }.get().asFile.absolutePath)
    systemProperty("jna.boot.library.path", ideHome.resolve("lib/jna/amd64").absolutePath)
    systemProperty("jna.nosys", "true")
    systemProperty("jna.noclasspath", "true")
}

serverTestTask.configure { ignoreFailures = true }
uiTestTask.configure { ignoreFailures = true; mustRunAfter(serverTestTask) }
tasks.test {
    ignoreFailures = true
    dependsOn(buildPlugin, serverTestTask, uiTestTask)
    mustRunAfter(uiTestTask)
}

data class TestSummary(val tests: Int, val failures: Int, val errors: Int, val skipped: Int, val details: List<String>)

fun readSummary(taskName: String): TestSummary {
    val root = layout.buildDirectory.dir("test-results/$taskName").get().asFile.toPath()
    if (!Files.isDirectory(root)) return TestSummary(0, 1, 0, 0, listOf("No JUnit XML: $root"))
    var tests = 0
    var failures = 0
    var errors = 0
    var skipped = 0
    val details = mutableListOf<String>()
    Files.list(root).use { files ->
        files.filter { it.fileName.toString().endsWith(".xml") }.sorted().forEach { file ->
            val suite = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).documentElement
            tests += suite.getAttribute("tests").toIntOrNull() ?: 0
            failures += suite.getAttribute("failures").toIntOrNull() ?: 0
            errors += suite.getAttribute("errors").toIntOrNull() ?: 0
            skipped += suite.getAttribute("skipped").toIntOrNull() ?: 0
            val cases = suite.getElementsByTagName("testcase")
            for (i in 0 until cases.length) {
                val testCase = cases.item(i) as org.w3c.dom.Element
                for (tag in listOf("failure", "error")) {
                    val nodes = testCase.getElementsByTagName(tag)
                    for (j in 0 until nodes.length) {
                        val node = nodes.item(j) as org.w3c.dom.Element
                        details += "${testCase.getAttribute("classname")}.${testCase.getAttribute("name")}: ${node.getAttribute("message")}\n${node.textContent.trim()}"
                    }
                }
            }
        }
    }
    return TestSummary(tests, failures, errors, skipped, details)
}

val reportFile = layout.buildDirectory.file("papyrus-test-report.txt")

tasks.test {
    doLast {
        val stages = listOf(
            "UNIT" to readSummary("test"),
            "PAPYRUS-LANG" to readSummary("serverIntegrationTest"),
            "REAL CLION UI" to readSummary("uiIntegrationTest")
        )
        val pluginZip = layout.buildDirectory.file("distributions/${project.name}-${project.version}.zip").get().asFile
        val failed = !pluginZip.isFile || stages.any { (_, s) -> s.tests == 0 || s.failures > 0 || s.errors > 0 }
        val report = buildString {
            append("Papyrus JetBrains Plugin ${project.version}\n")
            append("Generated: ${OffsetDateTime.now()}\n")
            append("Command: gradlew.bat test\n")
            append("Overall: ${if (failed) "FAIL" else "PASS"}\n\n")
            append("[BUILD]\n${if (pluginZip.isFile) "PASS" else "FAIL"} - ${pluginZip.absolutePath}\n\n")
            for ((name, s) in stages) {
                append("[$name]\n")
                append("${if (s.tests > 0 && s.failures == 0 && s.errors == 0) "PASS" else "FAIL"} - tests=${s.tests}, failures=${s.failures}, errors=${s.errors}, skipped=${s.skipped}\n")
                s.details.forEach { append(it).append("\n\n") }
                append('\n')
            }
        }
        val output = reportFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(report, StandardCharsets.UTF_8)
        logger.lifecycle("Papyrus test report: ${output.absolutePath}")
        if (failed) throw GradleException("Papyrus test suite failed. See ${output.absolutePath}")
    }
}
