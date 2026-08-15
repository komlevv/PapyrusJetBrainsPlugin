package dev.papyrus.jetbrains.ui

import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.sdk.ActionManager
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.VirtualFile
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.elements.button
import com.intellij.driver.sdk.ui.components.elements.checkBox
import com.intellij.driver.sdk.ui.components.elements.dialog
import com.intellij.driver.sdk.ui.components.elements.jBlist
import com.intellij.driver.sdk.ui.components.elements.popup
import com.intellij.driver.sdk.ui.components.elements.textField
import com.intellij.driver.sdk.ui.components.elements.waitForNoOpenedDialogs
import com.intellij.driver.sdk.ui.remote.Component
import com.intellij.driver.sdk.ui.xQuery
import com.intellij.driver.sdk.ui.remote.Window
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@EnabledOnOs(OS.WINDOWS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@Timeout(value = 8, unit = TimeUnit.MINUTES)
internal class PapyrusEditorFeaturesUiTest {
    companion object {
        private const val PLUGIN_ID = "dev.papyrus.intellij-papyrus"
        private val SHORT = 8.seconds
        private val NORMAL = 20.seconds
    }

    private lateinit var fixture: UiTestEnvironment.Fixture
    private lateinit var ide: StarterIdeSession

    @BeforeAll
    fun startIde() {
        fixture = UiTestEnvironment.create()
        ide = StarterIdeSession.start(fixture)
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        refreshVfs(fixture.target)
        val targetPath = fixture.target.toAbsolutePath().normalize().toString().replace('\\', '/')
        ide.waitFor("Papyrus target file to enter the project content index", 60.seconds) {
            support.isProjectContentFile(ide.project, targetPath)
        }
        val targetFilePath = fixture.target.toAbsolutePath().normalize().toString()
        try {
            ide.waitFor("Papyrus LSP client from project startup", 60.seconds) {
                languageService().hasRunningClient()
            }
            ide.waitFor("Papyrus projectInfos from project startup", 60.seconds) {
                support.papyrusProjectInfosReady(ide.project) &&
                    support.papyrusProjectInfosContainsFile(ide.project, targetFilePath)
            }
            ide.waitFor("Papyrus Projects availability from project startup", 30.seconds) {
                ide.service<ToolWindowManagerRemote>(ide.project)
                    .getToolWindow("Papyrus Projects")
                    ?.isAvailable() == true
            }
        } catch (error: AssertionError) {
            throw AssertionError(
                "Papyrus startup detection failed before any .psc editor was opened. " +
                    "Client states: [${support.papyrusLspClientStates(ide.project)}]. " +
                    "Output: ${support.papyrusLspOutputSnapshot(ide.project)}",
                error,
            )
        }

        val editor = open(fixture.target)
        assertNotNull(editor)
    }

    @AfterAll
    fun stopIde() {
        if (::ide.isInitialized) ide.close()
    }

    @AfterEach
    fun resetUiState() {
        if (!::ide.isInitialized || !::fixture.isInitialized) {
            return
        }
        val baselinePath = fixture.target.toAbsolutePath().normalize().toString().replace('\\', '/')
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        support.disposeVisibleDialog("Settings")
        support.disposeVisibleDialog("New Project")
        support.disposeVisibleDialog("Papyrus Rename Blocked")
        support.disposeVisibleDialog("Papyrus Rename Failed")
        support.disposeVisibleDialog("Rename Papyrus Symbol")
        support.cleanupTransientUi(ide.project, baselinePath)
        ide.waitFor("Papyrus UI cleanup baseline", SHORT) {
            selectedEditorName() == fixture.target.fileName.toString() &&
                ide.service<ToolWindowManagerRemote>(ide.project).getToolWindow("Find")?.isVisible() != true &&
                ide.service<ToolWindowManagerRemote>(ide.project).getToolWindow("Papyrus Projects")?.isVisible() != true &&
                ide.service<ToolWindowManagerRemote>(ide.project).getToolWindow("Services")?.isVisible() != true
        }
    }

    @Test
    @Order(1)
    fun pscOpensAsTextMatePapyrusFile() {
        val (fileTypeName, languageId) = ide.projectEdt { project ->
            val file = virtualFileInContext(fixture.target)
            val fileType = service<FileTypeManagerRemote>().getFileTypeByFile(file)
            val psi = service<PsiManagerRemote>(project).findFile(file)
            assertNotNull(fileType)
            assertNotNull(psi)
            fileType!!.getName() to psi!!.getLanguage().getID()
        }
        assertTrue(fileTypeName.equals("textmate", ignoreCase = true))
        assertTrue(languageId.equals("textmate", ignoreCase = true))
    }

    @Test
    @Order(2)
    fun completionShowsPapyrusMembers() {
        val editor = open(fixture.target)
        val text = editor.getDocument().getText()
        val dot = text.indexOf("Debug.Notification")
        assertTrue(dot >= 0)
        focusEditor(editor, dot + "Debug.".length)
        invokeAction("CodeCompletion", editor.getContentComponent())

        val lookupManager = ide.service<LookupManagerRemote>(ide.project)
        ide.waitFor("completion lookup", NORMAL) { lookupManager.getActiveLookup() != null }
        val lookup = lookupManager.getActiveLookup()
        assertNotNull(lookup)
        assertTrue(lookup!!.isCompletion())
        val items = safe(lookup.getItems()).mapTo(linkedSetOf()) { it.getLookupString() }
        assertTrue(items.contains("Notification") || items.contains("Trace"), "Papyrus completion items: $items")
        ide.edt { lookupManager.hideActiveLookup() }
    }

    @Test
    @Order(3)
    fun invalidPapyrusProducesEditorDiagnostics() {
        val editor = open(fixture.diagnostics)
        val file = editor.getVirtualFile()
        val psiManager = ide.service<PsiManagerRemote>(ide.project)
        val daemon = ide.service<DaemonCodeAnalyzerRemote>(ide.project)
        ide.waitFor("diagnostics analysis", 30.seconds) {
            val psi = psiManager.findFile(file)
            psi != null && daemon.isAllAnalysisFinished(psi)
        }
        val highlights = ide.read { daemon.getHighlights(editor.getDocument(), null, ide.project) }
        val descriptions = safe(highlights).mapNotNull { it.getDescription()?.takeIf(String::isNotBlank) }
        assertFalse(descriptions.isEmpty(), "Invalid Papyrus has no visible editor diagnostic")
    }

    @Test
    @Order(4)
    fun gotoDeclarationNavigatesToPapyrusDefinition() {
        val editor = open(fixture.caller)
        val text = editor.getDocument().getText()
        val usage = text.indexOf("SharedProbe")
        assertTrue(usage >= 0)
        focusEditor(editor, usage + 2)
        invokeAction("GotoDeclaration", editor.getContentComponent())
        val manager = fileEditorManager()
        ide.waitFor("Goto Declaration to FeatureTarget.psc", NORMAL) {
            manager.getSelectedTextEditor()?.getVirtualFile()?.getPath()?.replace('\\', '/')?.endsWith("/FeatureTarget.psc") == true
        }
    }

    @Test
    @Order(40)
    fun realClionClientHasStaticDefinitionProvider() {
        val states = ide.utility<PapyrusUiTestSupportRemote>().papyrusDefinitionProviderStates(ide.project)
        assertTrue(states.isNotBlank(), "No Papyrus LSP client definition capability state was reported")
        val providers = states.split(", ")
        assertTrue(
            providers.all { it == "boolean:true" || it == "options" },
            "CLion Go To Declaration requires a static definitionProvider; actual Papyrus client states: $states",
        )
    }

    @Test
    @Order(41)
    fun gotoDeclarationNavigatesFromLocalScriptType() {
        val editor = open(fixture.caller)
        val text = editor.getDocument().getText()
        val usage = text.indexOf("FeatureTarget")
        assertTrue(usage >= 0)
        focusEditor(editor, usage + 2)
        invokeAction("GotoDeclaration", editor.getContentComponent())
        val expected = fixture.target.toAbsolutePath().normalize().toString().replace('\\', '/')
        ide.waitFor("Goto Declaration from local script type to FeatureTarget.psc", NORMAL) {
            fileEditorManager().getSelectedTextEditor()?.getVirtualFile()?.getPath()?.replace('\\', '/')
                ?.equals(expected, ignoreCase = true) == true
        }
    }

    @Test
    @Order(42)
    fun gotoDeclarationNavigatesFromVanillaQuestType() {
        val quest = UiTestEnvironment.creationKitHome().resolve("Data/Source/Scripts/Quest.psc").toRealPath()
        assertTrue(Files.isRegularFile(quest), "Missing vanilla Quest.psc test dependency: $quest")
        val editor = open(fixture.caller)
        val text = editor.getDocument().getText()
        val usage = text.indexOf("Quest")
        assertTrue(usage >= 0)
        focusEditor(editor, usage + 2)
        invokeAction("GotoDeclaration", editor.getContentComponent())
        val expected = quest.toString().replace('\\', '/')
        ide.waitFor("Goto Declaration from vanilla Quest type to Quest.psc", NORMAL) {
            fileEditorManager().getSelectedTextEditor()?.getVirtualFile()?.getPath()?.replace('\\', '/')
                ?.equals(expected, ignoreCase = true) == true
        }
    }

    @Test
    @Order(43)
    fun gotoDeclarationShortcutNavigatesToPapyrusDefinition() {
        val editor = open(fixture.caller)
        val text = editor.getDocument().getText()
        val usage = text.indexOf("SharedProbe")
        assertTrue(usage >= 0)
        focusEditor(editor, usage + 2)
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val bindings = support.activeShortcutBindings("GotoDeclaration")
        assertTrue(bindings.contains("GotoDeclaration"), "GotoDeclaration is not bound in the active keymap: $bindings")
        support.startShortcutDispatchTrace()
        invokeShortcut("GotoDeclaration", editor.getContentComponent())
        val expected = fixture.target.toAbsolutePath().normalize().toString().replace('\\', '/')
        try {
            ide.waitFor("Ctrl+B to FeatureTarget.psc", NORMAL) {
                fileEditorManager().getSelectedTextEditor()?.getVirtualFile()?.getPath()?.replace('\\', '/')
                    ?.equals(expected, ignoreCase = true) == true
            }
            support.stopShortcutDispatchTrace()
        } catch (error: AssertionError) {
            val trace = support.stopShortcutDispatchTrace()
            throw AssertionError(
                "Ctrl+B did not navigate to the local member definition. Active keymap: $bindings. Dispatch trace: $trace",
                error,
            )
        }
    }

    @Test
    @Order(44)
    fun gotoDeclarationShortcutNavigatesFromLocalScriptType() {
        val editor = open(fixture.caller)
        val text = editor.getDocument().getText()
        val usage = text.indexOf("FeatureTarget")
        assertTrue(usage >= 0)
        focusEditor(editor, usage + 2)
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val bindings = support.activeShortcutBindings("GotoDeclaration")
        support.startShortcutDispatchTrace()
        invokeShortcut("GotoDeclaration", editor.getContentComponent())
        val expected = fixture.target.toAbsolutePath().normalize().toString().replace('\\', '/')
        try {
            ide.waitFor("Ctrl+B from local script type to FeatureTarget.psc", NORMAL) {
                fileEditorManager().getSelectedTextEditor()?.getVirtualFile()?.getPath()?.replace('\\', '/')
                    ?.equals(expected, ignoreCase = true) == true
            }
            support.stopShortcutDispatchTrace()
        } catch (error: AssertionError) {
            val trace = support.stopShortcutDispatchTrace()
            throw AssertionError(
                "Ctrl+B did not navigate from the local script type. Active keymap: $bindings. Dispatch trace: $trace",
                error,
            )
        }
    }

    @Test
    @Order(45)
    fun gotoDeclarationShortcutNavigatesFromVanillaQuestType() {
        val quest = UiTestEnvironment.creationKitHome().resolve("Data/Source/Scripts/Quest.psc").toRealPath()
        assertTrue(Files.isRegularFile(quest), "Missing vanilla Quest.psc test dependency: $quest")
        val editor = open(fixture.caller)
        val text = editor.getDocument().getText()
        val usage = text.indexOf("Quest")
        assertTrue(usage >= 0)
        focusEditor(editor, usage + 2)
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val bindings = support.activeShortcutBindings("GotoDeclaration")
        support.startShortcutDispatchTrace()
        invokeShortcut("GotoDeclaration", editor.getContentComponent())
        val expected = quest.toString().replace('\\', '/')
        try {
            ide.waitFor("Ctrl+B from vanilla Quest type to Quest.psc", NORMAL) {
                fileEditorManager().getSelectedTextEditor()?.getVirtualFile()?.getPath()?.replace('\\', '/')
                    ?.equals(expected, ignoreCase = true) == true
            }
            support.stopShortcutDispatchTrace()
        } catch (error: AssertionError) {
            val trace = support.stopShortcutDispatchTrace()
            throw AssertionError(
                "Ctrl+B did not navigate from the vanilla Quest type. Active keymap: $bindings. Dispatch trace: $trace",
                error,
            )
        }
    }

    @Test
    @Order(46)
    fun gotoDeclarationNavigatesBetweenVanillaImports() {
        val quest = UiTestEnvironment.creationKitHome().resolve("Data/Source/Scripts/Quest.psc").toRealPath()
        val form = UiTestEnvironment.creationKitHome().resolve("Data/Source/Scripts/Form.psc").toRealPath()
        assertTrue(Files.isRegularFile(quest), "Missing vanilla Quest.psc test dependency: $quest")
        assertTrue(Files.isRegularFile(form), "Missing vanilla Form.psc test dependency: $form")

        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val questPath = quest.toString().replace('\\', '/')
        val formPath = form.toString().replace('\\', '/')
        ide.waitFor("Papyrus vanilla imports to become library sources", 60.seconds) {
            !support.isProjectContentFile(ide.project, questPath) &&
                !support.isProjectContentFile(ide.project, formPath) &&
                support.isProjectLibrarySourceFile(ide.project, questPath) &&
                support.isProjectLibrarySourceFile(ide.project, formPath) &&
                support.papyrusImportLibraryExternalRootTypes(ide.project)
                    .contains("sourcesExternal=true")
        }

        val editor = open(quest)
        val text = editor.getDocument().getText()
        val extendsForm = text.indexOf("extends Form", ignoreCase = true)
        assertTrue(extendsForm >= 0, "Quest.psc does not contain the expected extends Form declaration")
        val usage = extendsForm + "extends ".length + 2
        focusEditor(editor, usage)
        invokeAction("GotoDeclaration", editor.getContentComponent())

        ide.waitFor("Goto Declaration from imported Quest.psc to imported Form.psc", NORMAL) {
            fileEditorManager().getSelectedTextEditor()?.getVirtualFile()?.getPath()?.replace('\\', '/')
                ?.equals(formPath, ignoreCase = true) == true
        }
    }

    @Test
    @Order(47)
    fun gotoDeclarationShortcutNavigatesBetweenVanillaImports() {
        val quest = UiTestEnvironment.creationKitHome().resolve("Data/Source/Scripts/Quest.psc").toRealPath()
        val form = UiTestEnvironment.creationKitHome().resolve("Data/Source/Scripts/Form.psc").toRealPath()
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val questPath = quest.toString().replace('\\', '/')
        val formPath = form.toString().replace('\\', '/')

        ide.waitFor("Papyrus vanilla imports to stay library sources", 60.seconds) {
            !support.isProjectContentFile(ide.project, questPath) &&
                !support.isProjectContentFile(ide.project, formPath) &&
                support.isProjectLibrarySourceFile(ide.project, questPath) &&
                support.isProjectLibrarySourceFile(ide.project, formPath)
        }

        val editor = open(quest)
        val text = editor.getDocument().getText()
        val extendsForm = text.indexOf("extends Form", ignoreCase = true)
        assertTrue(extendsForm >= 0, "Quest.psc does not contain the expected extends Form declaration")
        val usage = extendsForm + "extends ".length + 2
        focusEditor(editor, usage)

        val bindings = support.activeShortcutBindings("GotoDeclaration")
        support.startShortcutDispatchTrace()
        invokeShortcut("GotoDeclaration", editor.getContentComponent())
        try {
            ide.waitFor("Ctrl+B from imported Quest.psc to imported Form.psc", NORMAL) {
                fileEditorManager().getSelectedTextEditor()?.getVirtualFile()?.getPath()?.replace('\\', '/')
                    ?.equals(formPath, ignoreCase = true) == true
            }
            support.stopShortcutDispatchTrace()
        } catch (error: AssertionError) {
            val trace = support.stopShortcutDispatchTrace()
            throw AssertionError(
                "Ctrl+B did not navigate between imported Papyrus scripts. Active keymap: $bindings. Dispatch trace: $trace",
                error,
            )
        }
    }

    @Test
    @Order(5)
    fun foldingCreatesPapyrusFoldRegions() {
        val editor = open(fixture.target)
        val folding = ide.service<CodeFoldingManagerRemote>(ide.project)
        ide.write { folding.updateFoldRegions(editor) }
        ide.waitFor("Papyrus fold regions", SHORT) {
            ide.read { editor.getFoldingModel().getAllFoldRegions().size >= 3 }
        }
        assertTrue(ide.read { editor.getFoldingModel().getAllFoldRegions().size >= 3 })
    }

    @Test
    @Order(6)
    fun findUsagesShowsSameAndCrossFileReferences() {
        val editor = open(fixture.target)
        val text = editor.getDocument().getText()
        val usage = text.indexOf("SharedProbe()", text.indexOf("Function Test()"))
        assertTrue(usage >= 0)
        focusEditor(editor, usage + 2)
        invokeShortcut("FindUsages", editor.getContentComponent())

        val find = ide.service<ToolWindowManagerRemote>(ide.project).getToolWindow("Find")
        assertNotNull(find, "IDE Find tool window is missing")
        ide.waitFor("Find Usages result tab", NORMAL) {
            find!!.isVisible() && find.getContentManager().getContentCount() > 0
        }
        ide.waitFor("cross-file Find Usages result", NORMAL) {
            val texts = visibleTexts()
            texts.any { it.contains("FeatureCaller") } && texts.any { it.contains("SharedProbe") }
        }
    }

    @Test
    @Order(7)
    fun quickDefinitionPreviewsPapyrusDefinition() {
        val editor = open(fixture.caller)
        val text = editor.getDocument().getText()
        val usage = text.indexOf("SharedProbe")
        assertTrue(usage >= 0)
        focusEditor(editor, usage + 2)
        val before = LinkedHashSet(visibleTexts())
        invokeShortcut("QuickImplementations", editor.getContentComponent())
        ide.waitFor("Quick Definition preview", SHORT) {
            visibleTexts().asSequence()
                .filterNot(before::contains)
                .any { it.contains("FeatureTarget") || it.contains("SharedProbe") }
        }
        assertTrue(
            checkNotNull(fileEditorManager().getSelectedTextEditor()).getVirtualFile().getPath().replace('\\', '/').endsWith("/FeatureCaller.psc"),
            "Quick Definition must not navigate away from caller",
        )
    }

    @Test
    @Order(8)
    fun quickDocumentationShowsPapyrusHover() {
        val editor = open(fixture.target)
        val text = editor.getDocument().getText()
        val symbol = text.indexOf("Notification")
        assertTrue(symbol >= 0)
        focusEditor(editor, symbol + 2)
        invokeAction("QuickJavaDoc", editor.getContentComponent())
        val documentation = ide.service<DocumentationManagerRemote>(ide.project)
        ide.waitFor("Papyrus Quick Documentation", NORMAL) { documentation.isPopupVisible() }
        assertTrue(documentation.isPopupVisible())
    }

    @Test
    @Order(9)
    fun parameterInfoShowsPapyrusSignatureHelp() {
        val editor = open(fixture.target)
        val text = editor.getDocument().getText()
        val call = "Debug.Notification("
        val offset = text.indexOf(call)
        assertTrue(offset >= 0)
        focusEditor(editor, offset + call.length)
        invokeAction("ParameterInfo", editor.getContentComponent())
        val parameterInfo = ide.utility<ParameterInfoControllerRemote>()
        ide.waitFor("Papyrus Parameter Info", NORMAL) {
            parameterInfo.existsWithVisibleHintForEditor(editor, true)
        }
        assertTrue(parameterInfo.existsWithVisibleHintForEditor(editor, true))
    }

    @Test
    @Order(10)
    fun fileStructureShowsPapyrusDocumentSymbols() {
        val editor = open(fixture.target)
        focusEditor(editor, editor.getDocument().getText().indexOf("Function Test()") + 2)
        invokeAction("FileStructurePopup", editor.getContentComponent())
        for (expected in listOf("RuntimeLabel", "SharedProbe", "RuntimeState", "OnBeginState", "Test")) {
            ide.waitFor("File Structure symbol $expected", SHORT) {
                visibleTexts().any { it.contains(expected) }
            }
        }
    }

    @Test
    @Order(11)
    fun textMateHighlightsRepresentativePapyrusScopes() {
        val editor = open(fixture.ergonomics)
        try {
            val text = editor.getDocument().getText()
            val probes = linkedMapOf(
                "; line comment" to "comment.line.semicolon.papyrus",
                "hello" to "string.quoted.double.papyrus",
                "Int Count" to "storage.type.papyrus",
                "42" to "constant.numeric.integer.papyrus",
                "If Count" to "keyword.control.flow.papyrus",
            )
            for ((needle, scope) in probes) {
                val offset = text.indexOf(needle)
                assertTrue(offset >= 0, "Missing highlighting fixture token: $needle")
                ide.waitFor("TextMate scope $scope", SHORT) {
                    tokenScopeAt(editor, offset).contains(scope)
                }
            }

            val parameterText = """
                Scriptname TextMateParameterCommaProbe extends Quest

                Function Valid(Int element, FormList hpList, Race suppliedRace)
                EndFunction

                Function Invalid(, Int element)
                EndFunction
            """.trimIndent() + "\n"
            replaceDocument(editor, parameterText)

            val current = editor.getDocument().getText()
            val validComma = current.indexOf(", FormList")
            val validParameterType = current.indexOf("FormList hpList")
            val invalidSignature = current.indexOf("Invalid(,")
            assertTrue(validComma >= 0, "Missing valid parameter separator comma")
            assertTrue(validParameterType >= 0, "Missing valid second parameter")
            assertTrue(invalidSignature >= 0, "Missing invalid leading parameter comma")
            val invalidComma = invalidSignature + "Invalid(".length

            ide.waitFor("valid comma parameter TextMate scope", SHORT) {
                tokenScopeAt(editor, validParameterType).contains("storage.type.variable.papyrus")
            }
            assertFalse(
                tokenScopeAt(editor, validComma).contains("invalid.illegal.function.papyrus"),
                "A valid parameter separator comma must not be highlighted as illegal",
            )
            ide.waitFor("invalid leading parameter comma TextMate scope", SHORT) {
                tokenScopeAt(editor, invalidComma).contains("invalid.illegal.function.papyrus")
            }
        } finally {
            restoreErgonomics(editor)
        }
    }

    @Test
    @Order(12)
    fun commentActionsUsePapyrusCommentSyntax() {
        val editor = open(fixture.ergonomics)
        val baseline = """
            Scriptname CommentProbe extends Quest

            Function Test()
                Debug.Trace("x")
            EndFunction
        """.trimIndent() + "\n"
        try {
            replaceDocument(editor, baseline)
            var text = editor.getDocument().getText()
            val lineOffset = text.indexOf("Debug.Trace")
            focusEditor(editor, lineOffset + 2)
            invokeAction("CommentByLineComment", editor.getContentComponent())
            text = editor.getDocument().getText()
            assertTrue(
                text.lineSequence().any { line ->
                    line.trimStart().startsWith(";") && line.contains("Debug.Trace(\"x\")")
                },
                "Line comment result: $text",
            )
            val uncommentOffset = editor.getDocument().getText().indexOf("Debug.Trace")
            assertTrue(uncommentOffset >= 0, "Missing Debug.Trace after line comment")
            focusEditor(editor, uncommentOffset + 2)
            invokeAction("CommentByLineComment", editor.getContentComponent())
            assertEquals(baseline, editor.getDocument().getText())

            text = editor.getDocument().getText()
            val start = text.indexOf("Debug.Trace")
            val end = start + "Debug.Trace(\"x\")".length
            focusEditor(editor, start + 2)
            ide.edt { editor.getSelectionModel().setSelection(start, end) }
            invokeAction("CommentByBlockComment", editor.getContentComponent())
            text = editor.getDocument().getText()
            assertTrue(text.contains(";/") && text.contains("/;") && text.contains("Debug.Trace(\"x\")"), "Block comment result: $text")
        } finally {
            replaceDocument(editor, fixture.ergonomicsText)
        }
    }

    @Test
    @Order(13)
    fun smartTypingUsesPapyrusPairs() {
        val editor = open(fixture.ergonomics)
        val baseline = "Scriptname PairProbe extends Quest\n\nFunction Test()\n    \nEndFunction\n"
        try {
            for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}', '"' to '"')) {
                replaceDocument(editor, baseline)
                val start = baseline.indexOf("    \n") + 4
                focusEditor(editor, start)
                typeChar(editor, open)
                assertEquals("$open$close", editor.getDocument().getText().substring(start, start + 2), "Opening $open")
                assertEquals(start + 1, caretOffset(editor), "Caret inside $open$close")
                typeChar(editor, close)
                assertEquals("$open$close", editor.getDocument().getText().substring(start, start + 2), "Closing $close must overtype")
                assertEquals(start + 2, caretOffset(editor), "Caret after $open$close")
            }
        } finally {
            replaceDocument(editor, fixture.ergonomicsText)
        }
    }

    @Test
    @Order(14)
    fun enterUsesPapyrusIndentationRules() {
        val editor = open(fixture.ergonomics)
        try {
            val cases = listOf(
                "If True" to 4,
                "    Else" to 8,
                "    ElseIf True" to 8,
                "While True" to 4,
                "Function Test()" to 4,
                "    EndFunction" to 0,
                "Event OnInit()" to 4,
                "    EndEvent" to 0,
                "State Waiting" to 4,
                "    EndState" to 0,
                "String Property Value" to 4,
                "    EndProperty" to 0,
                "Struct Entry" to 4,
                "    EndStruct" to 0,
                "String Property Value Auto" to 0,
                "Function Test() Native" to 0,
            )
            for ((previousLine, expectedIndent) in cases) {
                replaceDocument(editor, previousLine)
                focusEditor(editor, previousLine.length)
                invokeAction("EditorEnter", editor.getContentComponent())
                assertEquals(previousLine + "\n" + " ".repeat(expectedIndent), editor.getDocument().getText(), previousLine)
            }
        } finally {
            replaceDocument(editor, fixture.ergonomicsText)
        }
    }

    @Test
    @Order(15)
    fun ifLiveTemplateExpandsAndTraversesWithPhysicalTab() {
        val editor = open(fixture.ergonomics)
        val baseline = "Scriptname TemplateProbe extends Quest\n\n"
        try {
            replaceDocument(editor, baseline)
            focusEditor(editor, baseline.length)
            typeText(editor, "if")
            pressTab(editor)

            val expanded = baseline + "If (true)\n\t; code\nEndIf"
            waitForDocument(editor, expanded, "if live template expansion")
            assertEquals("true", selectedText(editor), "if condition tab stop")

            typeText(editor, "Count > 0")
            ide.waitFor("if linked condition edit", SHORT) {
                editor.getDocument().getText().contains("If (Count > 0)")
            }
            pressTab(editor)
            ide.waitFor("if body tab stop", SHORT) { selectedText(editor) == "; code" }
            assertEquals("; code", selectedText(editor))

            typeText(editor, "Count += 1")
            pressTab(editor)
            val expected = baseline + "If (Count > 0)\n\tCount += 1\nEndIf"
            waitForDocument(editor, expected, "if live template completion")
            assertFalse(hasSelection(editor), "if template must finish after final tab stop")
            assertEquals(expected.indexOf("Count += 1") + "Count += 1".length, caretOffset(editor))
        } finally {
            restoreErgonomics(editor)
        }
    }

    @Test
    @Order(16)
    fun functionLiveTemplateExpandsAndTraversesWithPhysicalTab() {
        val editor = open(fixture.ergonomics)
        val baseline = "Scriptname TemplateProbe extends Quest\n\n"
        try {
            replaceDocument(editor, baseline)
            focusEditor(editor, baseline.length)
            typeText(editor, "function")
            allowFunctionCompletionPopup()
            pressTab(editor)

            val expanded = baseline + "Function Foo()\n\t; code\nEndFunction"
            waitForDocument(editor, expanded, "function live template expansion")
            assertEquals("Foo", selectedText(editor), "function name tab stop")

            typeText(editor, "RunProbe")
            pressTab(editor)
            ide.waitFor("function body tab stop", SHORT) { selectedText(editor) == "; code" }
            assertEquals("; code", selectedText(editor))

            typeText(editor, "return")
            pressTab(editor)
            val expected = baseline + "Function RunProbe()\n\treturn\nEndFunction"
            waitForDocument(editor, expected, "function live template completion")
            assertFalse(hasSelection(editor), "function template must finish after final tab stop")
            assertEquals(expected.indexOf("return") + "return".length, caretOffset(editor))
        } finally {
            restoreErgonomics(editor)
        }
    }

    @Test
    @Order(17)
    fun propertyFullLiveTemplateLinksRepeatedVariablesAndTraversesWithPhysicalTab() {
        val editor = open(fixture.ergonomics)
        val baseline = "Scriptname TemplateProbe extends Quest\n\n"
        try {
            replaceDocument(editor, baseline)
            focusEditor(editor, baseline.length)
            typeText(editor, "property full")
            pressTab(editor)

            val expandedBody =
                "type propertynameField\n" +
                    "type Property propertyname Hidden\n" +
                    "\ttype Function Get()\n" +
                    "\t\treturn propertynameField\n" +
                    "\tEndFunction\n" +
                    "\tFunction Set(type value)\n" +
                    "\t\tpropertynameField = value\n" +
                    "\tEndFunction\n" +
                    "EndProperty"
            waitForDocument(editor, baseline + expandedBody, "property full live template expansion")
            assertEquals("type", selectedText(editor), "property type tab stop")

            typeText(editor, "String")
            ide.waitFor("property repeated type propagation", SHORT) {
                val text = editor.getDocument().getText().removePrefix(baseline)
                text == expandedBody.replace("type", "String")
            }
            pressTab(editor)
            ide.waitFor("property name tab stop", SHORT) { selectedText(editor) == "propertyname" }

            typeText(editor, "Value")
            ide.waitFor("property repeated name propagation", SHORT) {
                val text = editor.getDocument().getText().removePrefix(baseline)
                text.contains("String ValueField") &&
                    text.contains("String Property Value Hidden") &&
                    text.contains("return ValueField") &&
                    text.contains("ValueField = value")
            }
            pressTab(editor)
            ide.waitFor("property Hidden tab stop", SHORT) { selectedText(editor) == " Hidden" }
            assertEquals(" Hidden", selectedText(editor))

            pressTab(editor)
            val expectedBody =
                "String ValueField\n" +
                    "String Property Value Hidden\n" +
                    "\tString Function Get()\n" +
                    "\t\treturn ValueField\n" +
                    "\tEndFunction\n" +
                    "\tFunction Set(String value)\n" +
                    "\t\tValueField = value\n" +
                    "\tEndFunction\n" +
                    "EndProperty"
            val expected = baseline + expectedBody
            waitForDocument(editor, expected, "property full live template completion")
            assertFalse(hasSelection(editor), "property full template must finish after Hidden")
            assertEquals(baseline.length + "String ValueField".length, caretOffset(editor), "property full final cursor")
        } finally {
            restoreErgonomics(editor)
        }
    }

    @Test
    @Order(18)
    fun projectsToolWindowNavigatesToScriptAndKeepsCreationKitTreeLazy() {
        val toolWindow = ide.service<ToolWindowManagerRemote>(ide.project).getToolWindow("Papyrus Projects")
        assertNotNull(toolWindow, "Papyrus Projects tool window is missing")
        ide.edt { toolWindow!!.show() }
        ide.waitFor("Papyrus Projects tool window", NORMAL) { toolWindow!!.isVisible() }

        ide.waitFor("Papyrus Projects game root", NORMAL) {
            projectsTreeRows().any { it.path == listOf("Skyrim SE/AE") }
        }
        var rows = projectsTreeRows()
        val game = rows.singleOrNull { it.path == listOf("Skyrim SE/AE") }
        assertNotNull(game, "Projects tree must expose the upstream Skyrim SE/AE game group: $rows")
        assertTrue(game!!.expanded, "Skyrim SE/AE game group should be expanded by default")

        val projectPaths = rows
            .filter { it.path.size == 2 && it.path.first() == "Skyrim SE/AE" && it.path.last() != "Loading..." }
            .map { it.path }
        assertFalse(projectPaths.isEmpty(), "Projects tree has no projects below Skyrim SE/AE: $rows")

        var featureTargetPath: List<String>? = null
        var featureProjectPath: List<String>? = null
        for (projectPath in projectPaths) {
            assertTrue(expandProjectsPath(projectPath), "Could not expand project ${projectPath.joinToString(" / ")}")
            rows = projectsTreeRows()
            val sourcesPath = projectPath + "Sources"
            if (!rows.any { it.path == sourcesPath }) continue
            assertTrue(expandProjectsPath(sourcesPath), "Could not expand project Sources group")
            rows = projectsTreeRows()
            val sourceIncludes = rows.filter { it.path.size == 4 && it.path.take(3) == sourcesPath }
            assertFalse(sourceIncludes.isEmpty(), "Project Sources group has no include nodes: $rows")
            for (sourceInclude in sourceIncludes) {
                assertTrue(expandProjectsPath(sourceInclude.path), "Could not expand local source include ${sourceInclude.path}")
                rows = projectsTreeRows()
                featureTargetPath = rows
                    .firstOrNull { row -> row.path == sourceInclude.path + "FeatureTarget" }
                    ?.path
                if (featureTargetPath != null) {
                    featureProjectPath = projectPath
                    break
                }
            }
            if (featureTargetPath != null) break
        }
        assertNotNull(featureTargetPath, "FeatureTarget is missing from explicit Sources/include hierarchy: $rows")

        val resolvedFeatureProjectPath = checkNotNull(featureProjectPath)
        val importsPath = resolvedFeatureProjectPath + "Imports"
        assertTrue(rows.any { it.path == importsPath }, "Runtime project Imports group is missing: $rows")
        assertTrue(expandProjectsPath(importsPath), "Could not expand runtime project Imports group")
        rows = projectsTreeRows()
        val importIncludes = rows.filter { it.path.size == 4 && it.path.take(3) == importsPath }
        assertFalse(importIncludes.isEmpty(), "Runtime project Imports group has no include nodes: $rows")
        assertTrue(
            importIncludes.any { row -> row.childCount == 1 && rows.any { child -> child.path == row.path + "Loading..." } },
            "Imported source nodes must remain lazy before their own expansion: $rows",
        )

        val creationKitPath = listOf("Skyrim SE/AE", "Creation Kit")
        assertTrue(rows.any { it.path == creationKitPath }, "Ambient Creation Kit project is missing: $rows")
        assertTrue(expandProjectsPath(creationKitPath), "Could not expand Creation Kit project")
        rows = projectsTreeRows()
        val creationKitSourcesPath = creationKitPath + "Sources"
        assertTrue(rows.any { it.path == creationKitSourcesPath }, "Creation Kit Sources group is missing: $rows")
        assertTrue(expandProjectsPath(creationKitSourcesPath), "Could not expand Creation Kit Sources group")
        rows = projectsTreeRows()

        val lazySource = rows.firstOrNull { row ->
            row.path.size == 4 &&
                row.path.take(3) == creationKitSourcesPath &&
                row.path.last() != "Loading..." &&
                row.childCount == 1 &&
                rows.any { child -> child.path == row.path + "Loading..." }
        }
        assertNotNull(lazySource, "Creation Kit source include must remain lazy before expansion: $rows")
        val lazySourcePath = checkNotNull(lazySource).path

        assertTrue(expandProjectsPath(lazySourcePath), "Could not expand Creation Kit source include")
        rows = projectsTreeRows()
        val sourceChildren = rows.filter {
            it.path.size == lazySourcePath.size + 1 && it.path.take(lazySourcePath.size) == lazySourcePath
        }
        assertTrue(sourceChildren.isNotEmpty(), "Expanded Creation Kit source include has no children: $rows")
        val lazyGroups = sourceChildren.filter { row -> rows.any { child -> child.path == row.path + "Loading..." } }
        if (lazyGroups.isEmpty()) {
            assertTrue(
                sourceChildren.size <= 400 && sourceChildren.all { it.childCount == 0 },
                "Small Creation Kit source must materialize at most 400 direct script leaves: $rows",
            )
        } else {
            assertTrue(
                sourceChildren.size <= 64 && lazyGroups.size == sourceChildren.size,
                "Large Creation Kit source must remain bounded in lazy script groups after first expansion: $rows",
            )
        }

        open(fixture.ergonomics)
        assertTrue(doubleClickProjectsPath(checkNotNull(featureTargetPath)), "Could not double-click FeatureTarget tree node")
        ide.waitFor("FeatureTarget opened from Papyrus Projects", NORMAL) {
            fileEditorManager().getSelectedTextEditor()?.getVirtualFile()?.getPath()?.replace('\\', '/')?.endsWith("/FeatureTarget.psc") == true
        }
    }

    @Test
    @Order(19)
    fun overriddenScriptStatusShowsWinningFileAndNavigates() {
        open(fixture.overridden)
        val expectedNotification =
            "This script is overridden by another source file. Papyrus language features use the overriding file."

        ide.waitFor("overridden Papyrus Script Status notification", 30.seconds) {
            val texts = visibleTexts()
            texts.any { it == expectedNotification } && texts.any { it == "Open overriding file" }
        }
        assertTrue(
            visibleTexts().any { it == expectedNotification },
            "Overridden Script Status notification text is missing",
        )
        assertTrue(
            ide.utility<PapyrusUiTestSupportRemote>().clickVisibleText(ide.project, "Open overriding file"),
            "Could not click the real overridden-script notification action",
        )

        ide.waitFor("overriding Papyrus source opened", NORMAL) {
            fileEditorManager().getSelectedTextEditor()?.getVirtualFile()?.getPath()?.replace('\\', '/')
                ?.equals(fixture.overriding.toString().replace('\\', '/'), ignoreCase = true) == true
        }
        val winningEditor = checkNotNull(fileEditorManager().getSelectedTextEditor())
        assertEquals(fixture.overridingText, winningEditor.getDocument().getText())
        assertEquals(fixture.overriddenText, Files.readString(fixture.overridden))
        assertEquals(fixture.overridingText, Files.readString(fixture.overriding))
    }

    @Test
    @Order(20)
    fun viewAssemblyOpensReadOnlyInMemoryEditorAndLeavesSourcesUnchanged() {
        val assemblyName = "FeatureTarget.disassemble.pas"
        val sourceBefore = Files.readString(fixture.target)
        val assemblyFilesBefore = onDiskAssemblyFiles()
        val sourceEditor = open(fixture.target)
        focusEditor(sourceEditor, 0)

        invokeAction("Papyrus.ViewAssembly", sourceEditor.getContentComponent())

        try {
            try {
                ide.waitFor("Papyrus assembly editor", 30.seconds) {
                    selectedEditorName() == assemblyName
                }
            } catch (error: AssertionError) {
                throw AssertionError(
                    "Papyrus assembly editor did not open; selected=${selectedEditorName()}; visible=${visibleTexts()}",
                    error,
                )
            }

            ide.waitFor("Papyrus Assembly TextMate typing", SHORT) {
                ide.projectEdt { project ->
                    val manager = service<FileEditorManagerRemote>(project)
                    val editor = manager.getSelectedTextEditor() ?: return@projectEdt false
                    val file = editor.getVirtualFile()
                    val fileType = service<FileTypeManagerRemote>().getFileTypeByFile(file)
                    val psi = service<PsiManagerRemote>(project).findFile(file)
                    fileType?.getName()?.equals("textmate", ignoreCase = true) == true &&
                        psi?.getLanguage()?.getID()?.equals("textmate", ignoreCase = true) == true
                }
            }

            val state = ide.projectEdt { project ->
                val manager = service<FileEditorManagerRemote>(project)
                val editor = checkNotNull(manager.getSelectedTextEditor()) { "No selected assembly editor" }
                val virtualFile = editor.getVirtualFile()
                val file = cast(virtualFile, VirtualFileStateRemote::class)
                val fileType = checkNotNull(service<FileTypeManagerRemote>().getFileTypeByFile(virtualFile)) {
                    "Assembly virtual file has no file type"
                }
                val psi = checkNotNull(service<PsiManagerRemote>(project).findFile(virtualFile)) {
                    "Assembly virtual file has no PSI"
                }
                AssemblyEditorState(
                    name = file.getName(),
                    path = file.getPath(),
                    fileTypeName = fileType.getName(),
                    languageId = psi.getLanguage().getID(),
                    fileWritable = file.isWritable(),
                    documentWritable = editor.getDocument().isWritable(),
                    text = editor.getDocument().getText(),
                )
            }

            assertEquals(assemblyName, state.name)
            assertEquals("textmate", state.fileTypeName.lowercase(), "Assembly must use the TextMate file type")
            assertEquals("textmate", state.languageId.lowercase(), "Assembly PSI must use the TextMate language")
            assertTrue(
                state.text.contains("FeatureTarget"),
                "Assembly editor does not contain the source script identifier. Path=${state.path}",
            )

            val assemblyEditor = checkNotNull(fileEditorManager().getSelectedTextEditor())
            val functionMarker = ".function SharedProbe"
            val functionOffset = state.text.indexOf(functionMarker, ignoreCase = true)
            assertTrue(functionOffset >= 0, "Assembly does not contain expected function marker: $functionMarker")
            val functionNameOffset = functionOffset + functionMarker.indexOf("SharedProbe")
            ide.waitFor("Papyrus Assembly function TextMate scope", SHORT) {
                tokenScopeAt(assemblyEditor, functionNameOffset)
                    .contains("entity.name.function.papyrus assembly")
            }
            assertTrue(
                tokenScopeAt(assemblyEditor, functionNameOffset)
                    .contains("entity.name.function.papyrus assembly"),
                "Assembly function name is not highlighted by the Papyrus Assembly grammar: " +
                    tokenScopeAt(assemblyEditor, functionNameOffset),
            )

            assertFalse(state.fileWritable, "Assembly virtual file must be read-only")
            assertFalse(state.documentWritable, "Assembly editor document must be read-only")
            assertEquals(sourceBefore, Files.readString(fixture.target), "View Assembly must not mutate its source script")
            assertEquals(
                assemblyFilesBefore,
                onDiskAssemblyFiles(),
                "View Assembly must not materialize .pas output inside the build-owned project",
            )
        } finally {
            closeSelectedFileIfNamed(assemblyName)
        }

        ide.waitFor("Papyrus assembly editor closed", SHORT) {
            selectedEditorName() != assemblyName
        }
        assertEquals(sourceBefore, Files.readString(fixture.target), "Closing assembly editor must not mutate source")
        assertEquals(assemblyFilesBefore, onDiskAssemblyFiles(), "Closing assembly editor must not create .pas output")
    }

    @Test
    @Order(21)
    fun creationKitWikiSearchUsesEditorSelectionAndDoesNotLaunchBrowser() {
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val sourceBefore = Files.readString(fixture.target)
        val editor = open(fixture.target)
        val text = editor.getDocument().getText()
        val selectedText = "Debug.Notification(\"ui-test\")"
        val selectionStart = text.indexOf(selectedText)
        assertTrue(selectionStart >= 0, "Wiki-search selection fixture is missing")

        support.clearCapturedExternalUrl()
        focusEditor(editor, selectionStart)
        ide.edt {
            editor.getSelectionModel().setSelection(selectionStart, selectionStart + selectedText.length)
        }
        invokeAction("Papyrus.SearchCreationKitWiki", editor.getContentComponent())

        val selectedUrl =
            "https://www.creationkit.com/index.php?search=Debug.Notification%28%22ui-test%22%29"
        ide.waitFor("Creation Kit Wiki selected-text request", NORMAL) {
            support.capturedExternalUrl() == selectedUrl
        }
        assertEquals(selectedUrl, support.capturedExternalUrl())

        support.clearCapturedExternalUrl()
        val wordOffset = text.indexOf("SharedProbe")
        assertTrue(wordOffset >= 0, "Wiki-search caret-word fixture is missing")
        ide.edt {
            editor.getSelectionModel().removeSelection()
            editor.getCaretModel().moveToOffset(wordOffset + 2)
        }
        focusEditor(editor, wordOffset + 2)
        invokeAction("Papyrus.SearchCreationKitWiki", editor.getContentComponent())

        val wordUrl = "https://www.creationkit.com/index.php?search=SharedProbe"
        ide.waitFor("Creation Kit Wiki caret-word request", NORMAL) {
            support.capturedExternalUrl() == wordUrl
        }
        assertEquals(wordUrl, support.capturedExternalUrl())
        assertEquals(sourceBefore, Files.readString(fixture.target), "Wiki Search must not mutate its source script")
    }

    @Test
    @Order(22)
    fun generateSkyrimProjectActionHonorsSafeBoundariesAndCancel() {
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val actionRoot = fixture.root.parent.resolve("generate-action")
        val projectsParent = actionRoot.resolve("projects")
        val fakeGame = actionRoot.resolve("fake-game")
        Files.createDirectories(projectsParent)
        Files.createDirectories(fakeGame)
        val sentinel = projectsParent.resolve("keep.txt")
        Files.writeString(sentinel, "keep\n")
        val entriesBeforeCancel = directoryEntryNames(projectsParent)
        val originalGamePath = support.creationKitInstallPath()
        val editor = open(fixture.target)

        support.setCreationKitInstallPath(fakeGame.toString())
        try {
            support.clearCapturedActionMessage()
            support.cancelProjectGeneration()
            invokeAction("Papyrus.GenerateSkyrimProject", editor.getContentComponent())
            assertEquals(entriesBeforeCancel, directoryEntryNames(projectsParent), "Cancel must not create any project files")
            assertTrue(support.capturedActionMessageText() == null, "Cancel must not report a generation result")

            val projectName = "ActionGenerated"
            val generated = projectsParent.resolve(projectName)
            support.clearCapturedActionMessage()
            support.prepareProjectGeneration(projectsParent.toString(), projectName)
            invokeAction("Papyrus.GenerateSkyrimProject", editor.getContentComponent())

            ide.waitFor("generated Papyrus project", NORMAL) {
                Files.isRegularFile(generated.resolve("skyrimse.ppj")) &&
                    support.capturedActionMessageKind() == "INFO"
            }
            assertEquals("keep\n", Files.readString(sentinel), "Generation must not replace sibling files in the chosen parent")
            assertTrue(Files.isRegularFile(generated.resolve("skyrimse.ppj")))
            assertTrue(Files.isRegularFile(generated.resolve(".idea/papyrus.xml")))
            assertTrue(Files.isRegularFile(generated.resolve(".run/Papyrus_Skyrim_SE_AE.run.xml")))
            assertFalse(Files.exists(generated.resolve(".vscode")), "Generated IDE project must not contain VS Code metadata")
            assertFalse(Files.exists(generated.resolve("SkyrimSE.code-workspace")), "Generated IDE project must not contain a VS Code workspace")
            assertTrue(support.papyrusAttachConfigurationTypeRegistered(), "PapyrusAttach Run Configuration type must be registered in the real IDE")
            val projectSettings = Files.readString(generated.resolve(".idea/papyrus.xml"))
            assertTrue(projectSettings.contains("gameId\" value=\"skyrimSpecialEdition"))
            assertTrue(projectSettings.contains("projectFile\" value=\"skyrimse.ppj"))
            val runConfiguration = Files.readString(generated.resolve(".run/Papyrus_Skyrim_SE_AE.run.xml"))
            assertTrue(runConfiguration.contains("type=\"PapyrusAttach\""))
            assertTrue(runConfiguration.contains("request\" value=\"attach"))
            assertTrue(runConfiguration.contains("name=\"Papyrus: Skyrim SE/AE\""))
            assertTrue(runConfiguration.contains("projectFile\" value=\"\$PROJECT_DIR\$/skyrimse.ppj"))
            assertEquals(
                setOf("keep.txt", projectName),
                directoryEntryNames(projectsParent),
                "The selected folder must remain a parent containing exactly the pre-existing sibling and new child",
            )
            val generationMessage = requireNotNull(support.capturedActionMessageText())
            assertTrue(
                generationMessage.startsWith("Papyrus project generated in new folder:\n"),
                "Success message must identify Papyrus project generation",
            )
            val reportedGeneratedPath = Path.of(generationMessage.substringAfterLast('\n').trim())
            assertTrue(
                Files.isSameFile(reportedGeneratedPath, generated),
                "Success message must name the generated child directory",
            )

            val generatedSnapshot = generatedFileSnapshot(generated)
            support.clearCapturedActionMessage()
            support.prepareProjectGeneration(projectsParent.toString(), projectName)
            invokeAction("Papyrus.GenerateSkyrimProject", editor.getContentComponent())
            ide.waitFor("no-overwrite generation rejection", NORMAL) {
                support.capturedActionMessageKind() == "ERROR" &&
                    support.capturedActionMessageText()?.contains("already exists") == true
            }
            assertEquals(generatedSnapshot, generatedFileSnapshot(generated), "No-overwrite rejection must leave the project unchanged")
            assertEquals("keep\n", Files.readString(sentinel), "No-overwrite rejection must leave sibling files unchanged")

            val forbiddenTarget = fakeGame.resolve("InsideConfiguredSkyrim")
            support.clearCapturedActionMessage()
            support.prepareProjectGeneration(fakeGame.toString(), forbiddenTarget.fileName.toString())
            invokeAction("Papyrus.GenerateSkyrimProject", editor.getContentComponent())
            ide.waitFor("configured Skyrim directory generation rejection", NORMAL) {
                support.capturedActionMessageKind() == "ERROR" &&
                    support.capturedActionMessageText()?.contains("inside the configured Skyrim installation") == true
            }
            assertFalse(Files.exists(forbiddenTarget), "Generation must not create a project inside the configured Skyrim installation")
        } finally {
            support.setCreationKitInstallPath(originalGamePath)
            support.clearCapturedActionMessage()
        }
    }

    @Test
    @Order(23)
    fun statusBarTracksActivePapyrusEditorAndHidesForUnrelatedFiles() {
        val unrelated = fixture.root.resolve("status-bar-unrelated.txt")
        Files.writeString(unrelated, "unrelated\n")
        try {
            open(fixture.target)
            ide.waitFor("Papyrus running status with resolved script details", NORMAL) {
                if (visibleTexts().none { it == "Papyrus: running" }) return@waitFor false
                val tooltip = ide.utility<PapyrusUiTestSupportRemote>()
                    .visibleTextTooltip(ide.project, "Papyrus: running")
                    .orEmpty()
                tooltip.contains("Current file: FeatureTarget.psc") &&
                    tooltip.contains("Script status: resolved") &&
                    tooltip.contains("Workspace root:")
            }

            open(fixture.root.resolve("runtime.ppj"))
            ide.waitFor("Papyrus project running status details", NORMAL) {
                if (visibleTexts().none { it == "Papyrus: running" }) return@waitFor false
                val tooltip = ide.utility<PapyrusUiTestSupportRemote>()
                    .visibleTextTooltip(ide.project, "Papyrus: running")
                    .orEmpty()
                tooltip.contains("Current file: runtime.ppj") &&
                    !tooltip.contains("Script status:")
            }

            open(unrelated)
            ide.waitFor("Papyrus status hidden for unrelated editor", NORMAL) {
                visibleTexts().none { it.startsWith("Papyrus:") }
            }

            open(fixture.target)
            ide.waitFor("Papyrus running status restored", NORMAL) {
                visibleTexts().any { it == "Papyrus: running" }
            }
        } finally {
            open(fixture.target)
            Files.deleteIfExists(unrelated)
        }
    }

    @Test
    @Order(24)
    fun statusBarReportsMissingCompilerWithoutModalUi() {
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val originalCompilerPath = support.compilerPathOverride()
        val missingCompiler = fixture.root.resolve("missing-status-compiler")
        try {
            open(fixture.target)
            ide.waitFor("Papyrus running before compiler status test", NORMAL) {
                visibleTexts().any { it == "Papyrus: running" }
            }

            support.setCompilerPathOverride(missingCompiler.toString())
            ide.waitFor("Papyrus compiler missing status", NORMAL) {
                visibleTexts().any { it == "Papyrus: compiler missing" }
            }
        } finally {
            support.setCompilerPathOverride(originalCompilerPath)
            open(fixture.target)
            ide.waitFor("Papyrus running after compiler status restore", NORMAL) {
                visibleTexts().any { it == "Papyrus: running" }
            }
        }
    }

    @Test
    @Order(25)
    fun statusBarClickOpensExistingPapyrusOutputWithoutRestartingLsp() {
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val outputWindow = ide.service<ToolWindowManagerRemote>(ide.project).getToolWindow("Papyrus Projects")
        assertNotNull(outputWindow, "Papyrus Projects tool window is missing")

        open(fixture.target)
        ide.waitFor("Papyrus running before status output click", NORMAL) {
            visibleTexts().any { it == "Papyrus: running" }
        }
        ide.waitFor("Papyrus output hidden before status output click", SHORT) {
            outputWindow!!.isVisible().not()
        }

        val clientCountBefore = support.papyrusLspClientCount(ide.project)
        assertTrue(clientCountBefore > 0, "Papyrus LSP client must already exist before opening output")
        val sourceBefore = Files.readString(fixture.target)
        support.clearPapyrusLspOutputDiagnostic()

        assertTrue(
            support.clickVisibleText(ide.project, "Papyrus: running"),
            "Papyrus status label was not found for click",
        )
        var outputDiagnostic = "not sampled"
        try {
            ide.waitFor("Papyrus language-service Output tab", NORMAL) {
                outputDiagnostic = support.papyrusLspOutputDiagnostic(ide.project)
                outputDiagnostic.contains("toolWindowVisible=true") &&
                    outputDiagnostic.contains("selectedContent=Output") &&
                    outputDiagnostic.contains("outputContainsStarted=true") &&
                    outputDiagnostic.contains("output selected")
            }
        } catch (error: AssertionError) {
            throw AssertionError(
                "Papyrus language-service output did not become visible. Diagnostic: $outputDiagnostic",
                error,
            )
        }

        assertEquals(
            clientCountBefore,
            support.papyrusLspClientCount(ide.project),
            "Opening Papyrus output must reuse the existing LSP client",
        )
        assertEquals(sourceBefore, Files.readString(fixture.target), "Opening Papyrus output must not modify source files")
    }

    @Test
    @Order(26)
    fun missingStatusOpensPapyrusSettingsAndExplicitDisableStopsLsp() {
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val originalEnabled = support.papyrusEnabled()
        val originalCompilerPath = support.compilerPathOverride()
        val missingCompiler = fixture.root.resolve("missing-locate-or-disable-compiler")
        var settingsDialogOpen = false

        try {
            open(fixture.target)
            ide.waitFor("Papyrus running before locate-or-disable test", NORMAL) {
                visibleTexts().any { it == "Papyrus: running" }
            }

            support.setCompilerPathOverride(missingCompiler.toString())
            ide.waitFor("Papyrus compiler missing before settings click", NORMAL) {
                visibleTexts().any { it == "Papyrus: compiler missing" }
            }

            assertTrue(
                support.clickVisibleText(ide.project, "Papyrus: compiler missing"),
                "Papyrus compiler-missing status was not found for click",
            )
            ide.waitFor("Settings dialog opened from missing status", NORMAL) {
                ide.isDialogOpened("//div[@title='Settings']")
            }
            settingsDialogOpen = true
            ide.waitFor("Papyrus Settings page selected from missing status", NORMAL) {
                visibleTexts().any { it == "Enable Papyrus language service" }
            }

            with(ide.driver) {
                ideFrame {
                    dialog(title = "Settings") {
                        val enabled = checkBox { byVisibleText("Enable Papyrus language service") }
                        enabled.uncheck()
                        ide.waitFor("Papyrus enable checkbox cleared", SHORT) {
                            runCatching { !enabled.isSelected() }.getOrDefault(false)
                        }
                        button("OK").click()
                    }
                    waitForNoOpenedDialogs()
                }
            }
            settingsDialogOpen = false

            ide.waitFor("Papyrus language service disabled", NORMAL) {
                val texts = visibleTexts()
                !support.papyrusEnabled() &&
                    support.papyrusLspClientCount(ide.project) == 0 &&
                    texts.none { it == "Enable Papyrus language service" } &&
                    texts.none {
                        it == "Papyrus: running" ||
                            it == "Papyrus: starting" ||
                            it == "Papyrus: compiler missing" ||
                            it == "Papyrus: game missing"
                    }
            }

            open(fixture.root.resolve("runtime.ppj"))
            Thread.sleep(750)
            assertEquals(
                0,
                support.papyrusLspClientCount(ide.project),
                "Opening another Papyrus document must not restart LSP while support is explicitly disabled",
            )
        } finally {
            if (settingsDialogOpen) {
                runCatching { closeModalDialogWithButton("Settings", "Cancel") }
            }
            support.setCompilerPathOverride(originalCompilerPath)
            support.setPapyrusEnabled(originalEnabled)
            support.refreshPapyrusEnablement(ide.project)
            open(fixture.target)
            if (originalEnabled) {
                ide.waitFor("Papyrus running after locate-or-disable restore", NORMAL) {
                    visibleTexts().any { it == "Papyrus: running" }
                }
            }
        }
    }

    @Test
    @Order(27)
    fun scriptNavigatorFiltersProjectInfosAndOpensExactLspReportedFile() {
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val sourceBefore = Files.readString(fixture.target)
        open(fixture.ergonomics)

        val toolWindow = ide.service<ToolWindowManagerRemote>(ide.project).getToolWindow("Papyrus Projects")
        assertNotNull(toolWindow, "Papyrus Projects tool window is missing")
        ide.edt { toolWindow!!.show() }
        ide.waitFor("Papyrus Projects tool window for navigator", NORMAL) { toolWindow!!.isVisible() }
        ide.waitFor("Papyrus Projects navigator readiness", NORMAL) {
            projectsTreeRows().any { it.path == listOf("Skyrim SE/AE") } &&
                visibleTexts().any { it == "Navigate..." }
        }

        assertTrue(
            support.clickVisibleText(ide.project, "Navigate..."),
            "Papyrus Projects Navigate button was not found",
        )
        ide.waitFor("Papyrus script navigator dialog", NORMAL) {
            val texts = visibleTexts()
            texts.any { it == "Search:" } && texts.any { it == "Search Papyrus scripts" }
        }

        assertTrue(
            support.setVisibleTextFieldAndSubmit(ide.project, "Search Papyrus scripts", "FeatureTarget"),
            "Papyrus script navigator search field was not found",
        )
        ide.waitFor("FeatureTarget opened from Papyrus script navigator", NORMAL) {
            fileEditorManager().getSelectedTextEditor()?.getVirtualFile()?.getPath()?.replace('\\', '/')
                ?.equals(fixture.target.toString().replace('\\', '/'), ignoreCase = true) == true
        }
        assertEquals(sourceBefore, Files.readString(fixture.target), "Script navigation must not modify source files")
    }

    @Test
    @Order(28)
    fun renameOfCreationKitSymbolShowsExplicitBlockedError() {
        val targetBefore = Files.readString(fixture.target)
        val callerBefore = Files.readString(fixture.caller)
        val editor = open(fixture.target)
        val text = editor.getDocument().getText()
        val quest = text.indexOf("Quest")
        assertTrue(quest >= 0)
        focusEditor(editor, quest + 2)

        invokeShortcut("RenameElement", editor.getContentComponent())
        ide.waitFor("Papyrus Rename blocked error", NORMAL) {
            val texts = visibleTexts()
            texts.any { it.contains("Papyrus Rename was blocked before making any changes") } &&
                texts.any { it.contains("Quest") } &&
                texts.any { it.contains("Reason:") } &&
                texts.any { it.contains("Path:") } &&
                texts.any { it.contains("read-only", ignoreCase = true) || it.contains("outside the writable IDE project") }
        }

        closeModalDialogWithButton("Papyrus Rename Blocked", "OK")
        assertEquals(targetBefore, Files.readString(fixture.target), "Blocked Rename must not modify the source file")
        assertEquals(callerBefore, Files.readString(fixture.caller), "Blocked Rename must not modify other project files")
    }

    @Test
    @Order(29)
    fun renameOfProjectSymbolUsesLspSemanticsAndChangesOnlyProjectScripts() {
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val originalTarget = Files.readString(fixture.target)
        val originalCaller = Files.readString(fixture.caller)
        assertEquals(fixture.targetText, originalTarget)
        assertEquals(fixture.callerText, originalCaller)

        try {
            val editor = open(fixture.caller)
            val text = editor.getDocument().getText()
            val sharedProbe = text.indexOf("SharedProbe")
            assertTrue(sharedProbe >= 0)
            focusEditor(editor, sharedProbe + 2)

            invokeShortcut("RenameElement", editor.getContentComponent())
            ide.waitFor("Papyrus Rename dialog", NORMAL) {
                val texts = visibleTexts()
                texts.any { it == "Rename Papyrus Symbol" } &&
                    texts.any { it == "Papyrus rename new name" }
            }

            with(ide.driver) {
                ideFrame {
                    dialog(title = "Rename Papyrus Symbol") {
                        textField { byAccessibleName("Papyrus rename new name") }.text = "SharedProbeRenamed"
                        button("Rename").click()
                    }
                    waitForNoOpenedDialogs()
                }
            }

            ide.waitFor("Papyrus project rename result", NORMAL) {
                val targetEditor = open(fixture.target)
                val callerEditor = open(fixture.caller)
                targetEditor.getDocument().getText().contains("Function SharedProbeRenamed()") &&
                    targetEditor.getDocument().getText().contains("SharedProbeRenamed()") &&
                    callerEditor.getDocument().getText().contains("Target.SharedProbeRenamed()") &&
                    !targetEditor.getDocument().getText().contains("Function SharedProbe()") &&
                    !callerEditor.getDocument().getText().contains("Target.SharedProbe()")
            }

            support.saveAllDocuments()
            ide.waitFor("Papyrus project rename persisted to disk", NORMAL) {
                val targetOnDisk = Files.readString(fixture.target)
                val callerOnDisk = Files.readString(fixture.caller)
                targetOnDisk.contains("Function SharedProbeRenamed()") &&
                    targetOnDisk.contains("SharedProbeRenamed()") &&
                    callerOnDisk.contains("Target.SharedProbeRenamed()") &&
                    !targetOnDisk.contains("Function SharedProbe()") &&
                    !callerOnDisk.contains("Target.SharedProbe()")
            }
        } finally {
            val targetEditor = open(fixture.target)
            val callerEditor = open(fixture.caller)
            replaceDocument(targetEditor, originalTarget)
            replaceDocument(callerEditor, originalCaller)
            support.saveAllDocuments()
            ide.waitFor("Papyrus rename fixture restored", NORMAL) {
                Files.readString(fixture.target) == originalTarget &&
                    Files.readString(fixture.caller) == originalCaller
            }
        }
    }

    @Test
    @Order(30)
    fun safePapyrusProjectCompileWritesPexOnlyToProjectOutput() {
        val compileProject = fixture.root.resolve("compile.ppj")
        val compileSource = fixture.root.resolve("CompileSource/CompileProbe.psc")
        val compileOutput = fixture.root.resolve("CompileOutput/CompileProbe.pex")
        val questSource = UiTestEnvironment.creationKitHome().resolve("Data/Source/Scripts/Quest.psc")
        val creationKitSources = questSource.parent.toString()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        Files.createDirectories(compileSource.parent)
        Files.writeString(
            compileSource,
            """
                Scriptname CompileProbe extends Quest

                Function Run()
                    Debug.Trace("compile")
                EndFunction
            """.trimIndent() + "\n",
        )
        Files.writeString(
            compileProject,
            """
                <?xml version="1.0" encoding="utf-8"?>
                <PapyrusProject xmlns="PapyrusProject.xsd" Flags="TESV_Papyrus_Flags.flg" Game="sse" Output="CompileOutput" Optimize="false" Release="false" Final="false">
                  <Imports>
                    <Import>.\CompileSource</Import>
                    <Import>$creationKitSources</Import>
                  </Imports>
                  <Folders>
                    <Folder>.\CompileSource</Folder>
                  </Folders>
                </PapyrusProject>
            """.trimIndent() + "\n",
        )

        val compileSourceBefore = Files.readString(compileSource)
        val questBefore = Files.readAllBytes(questSource)
        Files.deleteIfExists(compileOutput)

        val editor = open(compileProject)
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        invokeAction("Papyrus.CompileProject", editor.getContentComponent())

        ide.waitFor("Papyrus safe project compilation", 60.seconds) {
            Files.isRegularFile(compileOutput) &&
                Files.size(compileOutput) > 0L &&
                support.papyrusLspOutputDiagnostic(ide.project).contains("outputContainsCompileCompleted=true") &&
                directoryEntryNames(fixture.root).none { it.startsWith(".papyrus-jetbrains-compile-") }
        }
        assertEquals(compileSourceBefore, Files.readString(compileSource), "Compilation must not modify project source")
        assertArrayEquals(questBefore, Files.readAllBytes(questSource), "Compilation must not modify Creation Kit source")
        assertTrue(
            compileOutput.toAbsolutePath().normalize().startsWith(fixture.root.toAbsolutePath().normalize()),
            "Compiled PEX must stay inside the IDE project",
        )
        assertTrue(
            directoryEntryNames(fixture.root).none { it.startsWith(".papyrus-jetbrains-compile-") },
            "Temporary validated PPJ snapshot must be deleted after compilation",
        )
    }

    @Test
    @Order(31)
    fun nativePapyrusProjectRunConfigurationUsesSafePyroPipeline() {
        val compileProject = fixture.root.resolve("compile.ppj")
        val compileSource = fixture.root.resolve("CompileSource/CompileProbe.psc")
        val compileOutput = fixture.root.resolve("CompileOutput/CompileProbe.pex")
        val questSource = UiTestEnvironment.creationKitHome().resolve("Data/Source/Scripts/Quest.psc")
        assertTrue(Files.isRegularFile(compileProject), "Order 30 must create the compile PPJ fixture")
        assertTrue(Files.isRegularFile(compileSource), "Order 30 must create the compile source fixture")

        val compileSourceBefore = Files.readString(compileSource)
        val questBefore = Files.readAllBytes(questSource)
        Files.deleteIfExists(compileOutput)

        val support = ide.utility<PapyrusUiTestSupportRemote>()
        assertTrue(support.papyrusProjectConfigurationTypeRegistered())
        val configurationName = support.runPapyrusProjectConfiguration(
            ide.project,
            "\$PROJECT_DIR\$/compile.ppj",
        )
        assertTrue(configurationName.startsWith("Papyrus Project UI Test"))
        assertEquals("PapyrusProject", support.selectedRunConfigurationTypeId(ide.project))

        ide.waitFor("native Papyrus Project run configuration", 60.seconds) {
            Files.isRegularFile(compileOutput) &&
                Files.size(compileOutput) > 0L &&
                !support.papyrusProjectCompileRunning(ide.project) &&
                directoryEntryNames(fixture.root).none { it.startsWith(".papyrus-jetbrains-compile-") }
        }

        assertEquals(compileSourceBefore, Files.readString(compileSource), "Run configuration must not modify project source")
        assertArrayEquals(questBefore, Files.readAllBytes(questSource), "Run configuration must not modify Creation Kit source")
        assertTrue(
            compileOutput.toAbsolutePath().normalize().startsWith(fixture.root.toAbsolutePath().normalize()),
            "Run configuration output must stay inside the IDE project",
        )
    }

    @Test
    @Order(32)
    fun buildProjectUsesPapyrusOnlyAfterExplicitProjectBuildSelection() {
        val compileProject = fixture.root.resolve("compile.ppj")
        val compileSource = fixture.root.resolve("CompileSource/CompileProbe.psc")
        val compileOutput = fixture.root.resolve("CompileOutput/CompileProbe.pex")
        val questSource = UiTestEnvironment.creationKitHome().resolve("Data/Source/Scripts/Quest.psc")
        assertTrue(Files.isRegularFile(compileProject), "Order 30 must create the compile PPJ fixture")
        assertTrue(Files.isRegularFile(compileSource), "Order 30 must create the compile source fixture")

        val compileSourceBefore = Files.readString(compileSource)
        val questBefore = Files.readAllBytes(questSource)
        Files.deleteIfExists(compileOutput)

        val support = ide.utility<PapyrusUiTestSupportRemote>()
        assertTrue(support.papyrusProjectTaskRunnerRegistered())
        assertEquals("ide", support.papyrusBuildSystem(ide.project), "Ordinary projects must keep native IDE build behavior by default")

        support.setPapyrusBuildSettings(ide.project, "papyrus", "compile.ppj")
        assertEquals("papyrus", support.papyrusBuildSystem(ide.project))
        val editor = open(compileProject)
        val buildActionId = support.actionIdByText("Build Project")
        assertTrue(
            buildActionId.isNotBlank(),
            "CLion did not expose a Build Project action. Matching actions: ${support.actionDiagnostics("Build Project")}",
        )
        invokeAction(buildActionId, editor.getContentComponent())

        ide.waitFor("Papyrus Build Project integration", 60.seconds) {
            Files.isRegularFile(compileOutput) &&
                Files.size(compileOutput) > 0L &&
                !support.papyrusProjectCompileRunning(ide.project) &&
                directoryEntryNames(fixture.root).none { it.startsWith(".papyrus-jetbrains-compile-") }
        }

        assertEquals(compileSourceBefore, Files.readString(compileSource), "Build Project must not modify project source")
        assertArrayEquals(questBefore, Files.readAllBytes(questSource), "Build Project must not modify Creation Kit source")
        assertTrue(
            compileOutput.toAbsolutePath().normalize().startsWith(fixture.root.toAbsolutePath().normalize()),
            "Build Project output must stay inside the IDE project",
        )

        support.setPapyrusBuildSettings(ide.project, "ide", "runtime.ppj")
        assertEquals("ide", support.papyrusBuildSystem(ide.project))
    }

    @Test
    @Order(33)
    fun newProjectPopupExposesPapyrusDirectoryGenerator() {
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        // New Project is product-owned in CLion 2026.2; resolve the registered action by its presentation text.
        val newProjectActionId = support.actionIdByText("New Project")
        assertTrue(
            newProjectActionId.isNotBlank(),
            "CLion did not expose a New Project action. Matching actions: ${support.actionDiagnostics("New Project")}",
        )
        assertNotNull(
            ide.service<ActionManager>().getAction(newProjectActionId),
            "Resolved New Project action '$newProjectActionId' disappeared before invocation",
        )

        with(ide.driver) {
            ideFrame {
                invokeAction(newProjectActionId, now = false)
                val newProjectPopup = popup().waitFound(NORMAL)
                try {
                    val generatorList = newProjectPopup.jBlist(
                        xQuery { contains(byVisibleText("Papyrus")) },
                    ).waitFound(NORMAL)
                    assertTrue(
                        generatorList.rawItems.any { it.trim() == "Papyrus" },
                        "Real CLion New Project popup does not contain Papyrus. " +
                            "Action: $newProjectActionId; items: ${generatorList.rawItems}",
                    )
                } finally {
                    if (runCatching { newProjectPopup.present() }.getOrDefault(false)) {
                        newProjectPopup.close()
                        newProjectPopup.waitNotFound(SHORT)
                    }
                }
            }
        }
    }

    @Test
    @Order(34)
    fun syntaxTreeTracksUnsavedReplacementAndDeletionThroughDidChangeBridge() {
        val editor = open(fixture.target)
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val original = editor.getDocument().getText()
        assertEquals(fixture.targetText, original)

        ide.waitFor("initial live syntax tree", NORMAL) {
            val tree = support.papyrusSyntaxTreeSnapshot(ide.project, editor)
            tree.contains("RuntimeLabel") && tree.contains("ui-test")
        }

        try {
            val oldMarker = "ui-test"
            val replacementMarker = "did-change-replacement"
            val markerStart = editor.getDocument().getText().indexOf(oldMarker)
            assertTrue(markerStart >= 0)
            replaceDocumentRange(editor, markerStart, markerStart + oldMarker.length, replacementMarker)

            ide.waitFor("syntax tree after unsaved replacement", NORMAL) {
                val tree = support.papyrusSyntaxTreeSnapshot(ide.project, editor)
                tree.contains(replacementMarker) && !tree.contains(oldMarker)
            }

            val property = "String Property RuntimeLabel Auto"
            val propertyStart = editor.getDocument().getText().indexOf(property)
            assertTrue(propertyStart >= 0)
            replaceDocumentRange(editor, propertyStart, propertyStart + property.length, "")

            ide.waitFor("syntax tree after unsaved deletion", NORMAL) {
                val tree = support.papyrusSyntaxTreeSnapshot(ide.project, editor)
                tree.contains(replacementMarker) && !tree.contains("RuntimeLabel")
            }
        } finally {
            replaceDocument(editor, original)
            ide.waitFor("restored live syntax tree", NORMAL) {
                val tree = support.papyrusSyntaxTreeSnapshot(ide.project, editor)
                tree.contains("RuntimeLabel") && tree.contains("ui-test")
            }
            support.saveAllDocuments()
        }

        assertEquals(fixture.targetText, Files.readString(fixture.target))
    }

    @Test
    @Order(35)
    fun diagnosticsRefreshAndClearAfterUnsavedEditorChanges() {
        val editor = open(fixture.diagnostics)
        val original = editor.getDocument().getText()
        assertEquals(fixture.diagnosticsText, original)
        ide.waitFor("initial invalid Papyrus error highlighting", NORMAL) { errorHighlights(editor).isNotEmpty() }
        val initialDiagnosticStart = checkNotNull(errorHighlights(editor).minOfOrNull { it.startOffset }) {
            "Initial Papyrus error highlight has no source range"
        }

        val fixedStatement = "Debug.Trace(\"fixed-diagnostic\")"

        try {
            replaceDocumentRange(editor, 0, 0, "\n")
            ide.waitFor("moved Papyrus error highlighting after live insertion", NORMAL) {
                val movedStart = errorHighlights(editor).minOfOrNull { it.startOffset }
                movedStart != null && movedStart > initialDiagnosticStart
            }
            val movedDiagnosticStart = checkNotNull(errorHighlights(editor).minOfOrNull { it.startOffset }) {
                "Moved Papyrus error highlight has no source range"
            }

            val invalidText = editor.getDocument().getText()
            val invalidStatementStart = invalidText.indexOf("    if\n").let { marker ->
                check(marker >= 0) { "Invalid Papyrus statement was not found after live insertion" }
                marker + 4
            }
            replaceDocumentRange(editor, invalidStatementStart, invalidStatementStart + 2, fixedStatement)
            ide.waitFor("cleared Papyrus error highlighting after live fix", NORMAL) {
                errorHighlights(editor).isEmpty()
            }

            val fixedText = editor.getDocument().getText()
            val fixedStatementStart = fixedText.indexOf(fixedStatement)
            check(fixedStatementStart >= 0) { "Fixed Papyrus statement was not found after live replacement" }
            replaceDocumentRange(
                editor,
                fixedStatementStart,
                fixedStatementStart + fixedStatement.length,
                "if",
            )

            ide.waitFor("restored Papyrus error highlighting after live replacement", NORMAL) {
                errorHighlights(editor).any { it.startOffset == movedDiagnosticStart }
            }
            assertEquals("\n$original", editor.getDocument().getText())
        } finally {
            replaceDocument(editor, original)
            ide.waitFor("restored invalid Papyrus error highlighting", NORMAL) { errorHighlights(editor).isNotEmpty() }
            ide.utility<PapyrusUiTestSupportRemote>().saveAllDocuments()
        }

        assertEquals(fixture.diagnosticsText, Files.readString(fixture.diagnostics))
    }

    @Test
    @Order(36)
    fun unresolvedScriptStatusUsesNativeNotificationAndStatusBar() {
        open(fixture.unresolved)
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val expectedNotification =
            "Script is not included in a Papyrus project or any configured Creation Kit source path."

        ide.waitFor("unresolved Papyrus Script Status notification", 30.seconds) {
            visibleTexts().any { it == expectedNotification }
        }
        assertTrue(
            visibleTexts().any { it == expectedNotification },
            "Unresolved Script Status notification text is missing",
        )
        assertFalse(
            visibleTexts().any { it == "Open overriding file" },
            "Unresolved status must not expose an overriding-file action",
        )

        ide.waitFor("unresolved Papyrus status bar details", NORMAL) {
            if (visibleTexts().none { it == "Papyrus: running" }) return@waitFor false
            val tooltip = support.visibleTextTooltip(ide.project, "Papyrus: running").orEmpty()
            tooltip.contains("Current file: ${fixture.unresolved.fileName}") &&
                tooltip.contains("Script status: unresolved")
        }

        assertEquals(fixture.unresolvedText, Files.readString(fixture.unresolved))
    }

    @Test
    @Order(37)
    fun projectsRefreshAfterRealVfsScriptCreateAndDelete() {
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val toolWindow = ide.service<ToolWindowManagerRemote>(ide.project).getToolWindow("Papyrus Projects")
        assertNotNull(toolWindow, "Papyrus Projects tool window is missing")
        ide.edt { toolWindow!!.show() }
        ide.waitFor("Papyrus Projects refresh listener", NORMAL) {
            toolWindow!!.isVisible() && support.papyrusProjectInfosReady(ide.project)
        }

        val relativePath = "Source/Scripts/ProjectRefreshProbe.psc"
        val expectedFile = fixture.root.resolve(relativePath).toAbsolutePath().normalize()
        val text = """
            Scriptname ProjectRefreshProbe extends Quest

            Function RefreshProbe()
                Debug.Trace("refresh")
            EndFunction
        """.trimIndent() + "\n"

        try {
            assertFalse(
                support.papyrusProjectInfosContainsFile(ide.project, expectedFile.toString()),
                "Refresh probe unexpectedly exists in the initial Project Infos snapshot",
            )

            val watcherEventsBeforeCreate = support.papyrusWorkspaceFileWatcherRelevantEventCount(ide.project)
            val createdPath = support.createProjectTextFile(ide.project, relativePath, text)
            assertTrue(
                expectedFile.toString().replace('\\', '/').equals(createdPath.replace('\\', '/'), ignoreCase = true),
                "Test support created the refresh probe outside the expected project path: expected=$expectedFile actual=$createdPath",
            )
            ide.waitFor("official VFS create event for ProjectRefreshProbe.psc", 15.seconds) {
                support.papyrusWorkspaceFileWatcherRelevantEventCount(ide.project) > watcherEventsBeforeCreate &&
                    support.papyrusWorkspaceFileWatcherLastRelevantEvent(ide.project)
                        .contains("ProjectRefreshProbe.psc", ignoreCase = true)
            }
            ide.waitFor("Project Infos refresh after .psc create", 30.seconds) {
                support.papyrusProjectInfosReady(ide.project) &&
                    support.papyrusProjectInfosContainsFile(ide.project, expectedFile.toString())
            }

            assertTrue(support.deleteProjectFile(ide.project, relativePath), "Could not delete the refresh probe through VFS")
            ide.waitFor("Project Infos refresh after .psc delete", 30.seconds) {
                support.papyrusProjectInfosReady(ide.project) &&
                    !support.papyrusProjectInfosContainsFile(ide.project, expectedFile.toString())
            }
        } finally {
            if (Files.exists(expectedFile)) {
                support.deleteProjectFile(ide.project, relativePath)
            }
        }

        assertFalse(Files.exists(expectedFile), "Project refresh test must leave no generated script behind")
    }

    @Test
    @Order(38)
    fun compileActionDiscoversAndSelectsProjectLocalPyroTask() {
        val source = fixture.root.resolve("CompileSource/CompileProbe.psc")
        val selectedProject = fixture.root.resolve("task-selected.ppj")
        val selectedOutput = fixture.root.resolve("TaskSelectedOutput/CompileProbe.pex")
        val ordinaryOutput = fixture.root.resolve("CompileOutput/CompileProbe.pex")
        val questSource = UiTestEnvironment.creationKitHome().resolve("Data/Source/Scripts/Quest.psc")
        val creationKitSources = questSource.parent.toString()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
        assertTrue(Files.isRegularFile(source), "Order 30 must create the shared compile source")

        Files.writeString(
            selectedProject,
            """
                <?xml version="1.0" encoding="utf-8"?>
                <PapyrusProject xmlns="PapyrusProject.xsd" Flags="TESV_Papyrus_Flags.flg" Game="sse" Output="TaskSelectedOutput" Optimize="false" Release="false" Final="false">
                  <Imports>
                    <Import>.\CompileSource</Import>
                    <Import>$creationKitSources</Import>
                  </Imports>
                  <Folders>
                    <Folder>.\CompileSource</Folder>
                  </Folders>
                </PapyrusProject>
            """.trimIndent() + "\n",
        )
        Files.deleteIfExists(selectedOutput)
        Files.deleteIfExists(ordinaryOutput)

        val support = ide.utility<PapyrusUiTestSupportRemote>()
        support.preparePapyrusCompileSelection("task-selected.ppj")
        val nonProjectEditor = open(fixture.target)
        invokeAction("Papyrus.CompileProject", nonProjectEditor.getContentComponent())

        ide.waitFor("discovered Papyrus Pyro task selection", 60.seconds) {
            Files.isRegularFile(selectedOutput) &&
                Files.size(selectedOutput) > 0L &&
                !support.papyrusProjectCompileRunning(ide.project)
        }
        assertFalse(
            Files.exists(ordinaryOutput),
            "Discovery test selected compile.ppj instead of the requested project-local Pyro task",
        )
        assertTrue(
            support.papyrusLspOutputSnapshot(ide.project).contains("[compile] Project: ${selectedProject.toRealPath()}"),
            "Compile output does not identify the discovered project selected for the task",
        )
    }

    @Test
    @Order(39)
    fun compilerProblemMatcherParsesRealFailureAndNavigatesProjectSource() {
        val brokenSource = fixture.root.resolve("BrokenCompile/BrokenProbe.psc")
        val brokenProject = fixture.root.resolve("broken.ppj")
        val brokenOutput = fixture.root.resolve("BrokenOutput/BrokenProbe.pex")
        val questSource = UiTestEnvironment.creationKitHome().resolve("Data/Source/Scripts/Quest.psc")
        val creationKitSources = questSource.parent.toString()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        Files.createDirectories(brokenSource.parent)
        Files.writeString(
            brokenSource,
            """
                Scriptname BrokenProbe extends Quest

                Function Run()
                    Int Value =
                EndFunction
            """.trimIndent() + "\n",
        )
        Files.writeString(
            brokenProject,
            """
                <?xml version="1.0" encoding="utf-8"?>
                <PapyrusProject xmlns="PapyrusProject.xsd" Flags="TESV_Papyrus_Flags.flg" Game="sse" Output="BrokenOutput" Optimize="false" Release="false" Final="false">
                  <Imports>
                    <Import>.\BrokenCompile</Import>
                    <Import>$creationKitSources</Import>
                  </Imports>
                  <Folders>
                    <Folder>.\BrokenCompile</Folder>
                  </Folders>
                </PapyrusProject>
            """.trimIndent() + "\n",
        )
        Files.deleteIfExists(brokenOutput)

        val support = ide.utility<PapyrusUiTestSupportRemote>()
        support.clearCapturedActionMessage()
        val editor = open(brokenProject)
        invokeAction("Papyrus.CompileProject", editor.getContentComponent())

        ide.waitFor("real Papyrus compiler diagnostic", 60.seconds) {
            support.firstPapyrusCompilerDiagnostic(ide.project).isNotBlank() &&
                !support.papyrusProjectCompileRunning(ide.project)
        }
        val diagnosticLine = support.firstPapyrusCompilerDiagnostic(ide.project)
        assertTrue(
            diagnosticLine.replace('\\', '/').contains("BrokenCompile/BrokenProbe.psc("),
            "Unexpected Papyrus compiler problem line: $diagnosticLine",
        )
        assertFalse(Files.exists(brokenOutput), "Broken Papyrus source must not produce a successful PEX")

        val navigatedPath = support.navigateFirstPapyrusCompilerDiagnostic(ide.project)
        assertTrue(
            navigatedPath.replace('\\', '/').endsWith("BrokenCompile/BrokenProbe.psc", ignoreCase = true),
            "Problem matcher did not resolve the project-local compiler diagnostic: $navigatedPath; diagnostic=$diagnosticLine",
        )
        ide.waitFor("Papyrus compiler diagnostic hyperlink navigation", NORMAL) {
            support.selectedEditorFilePath(ide.project).replace('\\', '/').equals(
                brokenSource.toRealPath().toString().replace('\\', '/'),
                ignoreCase = true,
            )
        }
    }


    @Test
    @Order(48)
    fun invalidPpjReloadIsBlockedAndRecoversWithoutIdeRestart() {
        val support = ide.utility<PapyrusUiTestSupportRemote>()
        val toolWindow = ide.service<ToolWindowManagerRemote>(ide.project).getToolWindow("Papyrus Projects")
        assertNotNull(toolWindow, "Papyrus Projects tool window is missing")
        ide.edt { toolWindow!!.show() }
        // Order 39 intentionally opens the Output content for compiler diagnostics. This test
        // asserts visible Projects status text, so do not inherit the previously selected tab.
        assertTrue(
            support.selectPapyrusProjectsContent(ide.project),
            "Papyrus Projects tool window did not select the Projects content before PPJ status assertions",
        )

        // Earlier compile tests intentionally create additional project-local PPJs, which marks the
        // guarded project graph DIRTY. Establish a fresh, event-confirmed baseline instead of
        // assuming those prior filesystem changes left the status at READY.
        support.reloadPapyrusProjects(ide.project)
        ide.waitFor("event-confirmed Papyrus Projects baseline before PPJ validation test", 60.seconds) {
            toolWindow!!.isVisible() &&
                support.papyrusProjectInfosReady(ide.project) &&
                support.papyrusProjectsStatusPhase(ide.project) == "READY"
        }
        val serverWorkspace = Path.of(support.papyrusLanguageServerWorkspaceRoot(ide.project)).toAbsolutePath().normalize()
        assertFalse(
            serverWorkspace.startsWith(fixture.root.toAbsolutePath().normalize()),
            "papyrus-lang must use a private validated PPJ snapshot workspace, not the editable project root: $serverWorkspace",
        )

        assertTrue(
            support.papyrusWorkspaceFileWatcherStarted(ide.project),
            "Project-bound VirtualFileManager.VFS_CHANGES listener is not active before PPJ edit",
        )

        val projectFile = fixture.root.resolve("runtime.ppj")
        val original = Files.readString(projectFile)
        val closingImports = "</Imports>"
        assertTrue(original.contains(closingImports), "runtime.ppj does not contain an Imports section")
        val invalidImport = ".\\DefinitelyMissingPpjImport"
        val invalid = original.replace(
            closingImports,
            "        <Import>$invalidImport</Import>\n    $closingImports",
        )
        val targetPath = fixture.target.toRealPath().toString()

        try {
            // Refresh must consume the live editor buffer, not require Ctrl+S first. Keep the disk
            // file unchanged, make the open Document invalid, and prove validation sees only the
            // unsaved text.
            val projectEditor = open(projectFile)
            replaceDocument(projectEditor, invalid)
            ide.waitFor("dirty PPJ state after unsaved editor edit", NORMAL) {
                support.papyrusProjectsStatusPhase(ide.project) == "DIRTY"
            }
            assertEquals(
                original,
                Files.readString(projectFile),
                "Editing runtime.ppj in the IDE unexpectedly saved it before Refresh",
            )

            support.reloadPapyrusProjects(ide.project)
            ide.waitFor("unsaved PPJ validation failure", NORMAL) {
                support.papyrusProjectsStatusPhase(ide.project) == "VALIDATION_ERROR" &&
                    support.papyrusProjectsStatusSummary(ide.project) ==
                    "PPJ validation failed: import directory does not exist"
            }
            assertTrue(
                support.papyrusProjectsStatusDetails(ide.project).contains(invalidImport),
                "Refresh ignored the unsaved PPJ editor buffer",
            )
            assertEquals(
                original,
                Files.readString(projectFile),
                "Refreshing an unsaved PPJ must not force-save the editor document",
            )

            replaceDocument(projectEditor, original)
            ide.waitFor("dirty PPJ state after unsaved editor repair", NORMAL) {
                support.papyrusProjectsStatusPhase(ide.project) == "DIRTY"
            }
            support.reloadPapyrusProjects(ide.project)
            ide.waitFor("unsaved PPJ recovery without Ctrl+S", 60.seconds) {
                support.papyrusProjectsStatusPhase(ide.project) == "READY" &&
                    support.papyrusProjectInfosContainsFile(ide.project, targetPath)
            }
            assertEquals(
                original,
                Files.readString(projectFile),
                "Successful Refresh unexpectedly wrote the in-memory PPJ back to disk",
            )

            // Saving after an already-applied in-memory Refresh represents the same PPJ generation
            // and must not make Projects dirty again.
            support.saveAllDocuments()
            ide.waitFor("PPJ remains ready after saving an already-applied editor buffer", NORMAL) {
                support.papyrusProjectsStatusPhase(ide.project) == "READY"
            }
            closeSelectedFileIfNamed("runtime.ppj")

            val watcherRelevantBeforeInvalidEdit = support.papyrusWorkspaceFileWatcherRelevantEventCount(ide.project)
            support.createProjectTextFile(ide.project, "runtime.ppj", invalid)
            ide.waitFor("official VFS content event for invalid PPJ edit", NORMAL) {
                support.papyrusWorkspaceFileWatcherRelevantEventCount(ide.project) > watcherRelevantBeforeInvalidEdit &&
                    support.papyrusWorkspaceFileWatcherLastRelevantEvent(ide.project)
                        .replace('\\', '/')
                        .contains("runtime.ppj", ignoreCase = true)
            }
            ide.waitFor("dirty PPJ backend state after invalid edit", NORMAL) {
                support.papyrusProjectsStatusPhase(ide.project) == "DIRTY"
            }
            ide.waitFor("visible dirty PPJ status after invalid edit", NORMAL) {
                visibleTexts().any { it.contains("Papyrus project file changed") }
            }

            assertTrue(
                support.livePapyrusProjectInfosContainsFile(ide.project, targetPath),
                "Saving an invalid PPJ must not send native didSave or destroy the live server project graph",
            )

            support.reloadPapyrusProjects(ide.project)
            ide.waitFor("visible PPJ validation failure", NORMAL) {
                support.papyrusProjectsStatusPhase(ide.project) == "VALIDATION_ERROR" &&
                    support.papyrusProjectsStatusSummary(ide.project) == "PPJ validation failed: import directory does not exist" &&
                    visibleTexts().any { it.contains("ERROR: PPJ validation failed: import directory does not exist") }
            }

            val details = support.papyrusProjectsStatusDetails(ide.project)
            assertTrue(details.contains("runtime.ppj"), "Validation details do not identify the PPJ: $details")
            assertTrue(details.contains(invalidImport), "Validation details do not identify the invalid Import: $details")
            assertTrue(details.contains("DefinitelyMissingPpjImport"), "Validation details do not include the resolved missing path: $details")
            assertTrue(
                support.papyrusProjectsShowingLastKnownGood(ide.project),
                "Validation failure must explicitly retain the last-known-good project snapshot",
            )
            assertTrue(
                support.papyrusProjectInfosContainsFile(ide.project, targetPath),
                "Validation failure removed the last-known-good Projects snapshot",
            )
            assertTrue(
                support.livePapyrusProjectInfosContainsFile(ide.project, targetPath),
                "Guarded validation failure still damaged the live papyrus-lang project graph",
            )

            // Cold/restart path: the editable PPJ is still invalid. The new server process must be
            // initialized from the persisted last validated snapshot instead of discovering the bad
            // runtime.ppj directly from the real workspace.
            support.restartPapyrusLanguageServer(ide.project)
            ide.waitFor("invalid PPJ cold-start fallback to last validated snapshot", 60.seconds) {
                support.papyrusProjectsStatusPhase(ide.project) == "VALIDATION_ERROR" &&
                    support.livePapyrusProjectInfosContainsFile(ide.project, targetPath)
            }
            assertTrue(
                support.papyrusProjectsShowingLastKnownGood(ide.project),
                "Restarting the LSP with an invalid PPJ did not retain the validated fallback snapshot",
            )

            val watcherRelevantBeforeFix = support.papyrusWorkspaceFileWatcherRelevantEventCount(ide.project)
            support.createProjectTextFile(ide.project, "runtime.ppj", original)
            ide.waitFor("official VFS content event after fixing PPJ import", NORMAL) {
                support.papyrusWorkspaceFileWatcherRelevantEventCount(ide.project) > watcherRelevantBeforeFix &&
                    support.papyrusWorkspaceFileWatcherLastRelevantEvent(ide.project)
                        .replace('\\', '/')
                        .contains("runtime.ppj", ignoreCase = true)
            }
            ide.waitFor("dirty PPJ state after fixing import", NORMAL) {
                support.papyrusProjectsStatusPhase(ide.project) == "DIRTY"
            }
            support.reloadPapyrusProjects(ide.project)
            ide.waitFor("event-confirmed PPJ recovery", 60.seconds) {
                support.papyrusProjectsStatusPhase(ide.project) == "READY" &&
                    support.papyrusProjectInfosContainsFile(ide.project, targetPath)
            }
            assertTrue(
                support.livePapyrusProjectInfosContainsFile(ide.project, targetPath),
                "Papyrus project graph did not recover after fixing the PPJ and pressing Refresh",
            )
        } finally {
            if (Files.readString(projectFile) != original) {
                support.createProjectTextFile(ide.project, "runtime.ppj", original)
                support.reloadPapyrusProjects(ide.project)
            }
        }
    }

    private fun closeModalDialogWithButton(title: String, buttonText: String) {
        with(ide.driver) {
            ideFrame {
                dialog(title = title) {
                    button(buttonText).click()
                }
                waitForNoOpenedDialogs()
            }
        }
    }

    private fun directoryEntryNames(directory: Path): Set<String> = Files.list(directory).use { stream ->
        stream.map { it.fileName.toString() }.toList().toSet()
    }

    private fun generatedFileSnapshot(root: Path): Map<String, String> = Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) }
            .sorted()
            .toList()
            .associate { path -> root.relativize(path).toString().replace('\\', '/') to Files.readString(path) }
    }

    private data class AssemblyEditorState(
        val name: String,
        val path: String,
        val fileTypeName: String,
        val languageId: String,
        val fileWritable: Boolean,
        val documentWritable: Boolean,
        val text: String,
    )

    private fun onDiskAssemblyFiles(): Set<Path> = Files.walk(fixture.root).use { stream ->
        stream
            .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".pas", ignoreCase = true) }
            .map { it.toAbsolutePath().normalize() }
            .toList()
            .toSet()
    }

    private fun closeSelectedFileIfNamed(name: String) {
        ide.projectEdt { project ->
            val manager = service<FileEditorManagerRemote>(project)
            val editor = manager.getSelectedTextEditor() ?: return@projectEdt
            val file = editor.getVirtualFile()
            if (file.getName() == name) {
                manager.closeFile(file)
            }
        }
    }

    private fun selectedEditorName(): String? = ide.projectEdt { project ->
        val manager = service<FileEditorManagerRemote>(project)
        manager.getSelectedTextEditor()?.getVirtualFile()?.getName()
    }

    private data class ProjectsTreeRow(
        val path: List<String>,
        val childCount: Int,
        val expanded: Boolean,
    )

    private fun projectsTreeRows(): List<ProjectsTreeRow> {
        val snapshot = ide.utility<PapyrusUiTestSupportRemote>().projectsTreeSnapshot(ide.project)
        if (snapshot.isBlank()) return emptyList()
        return snapshot.lineSequence().filter(String::isNotBlank).map { line ->
            val fields = line.split('\u001E')
            check(fields.size == 3) { "Invalid Projects tree snapshot row: $line" }
            ProjectsTreeRow(
                path = fields[0].split('\u001F'),
                childCount = fields[1].toInt(),
                expanded = fields[2].toBooleanStrict(),
            )
        }.toList()
    }

    private fun expandProjectsPath(path: List<String>): Boolean =
        ide.utility<PapyrusUiTestSupportRemote>().expandProjectsTreePath(ide.project, path.joinToString("\u001F"))

    private fun doubleClickProjectsPath(path: List<String>): Boolean =
        ide.utility<PapyrusUiTestSupportRemote>().doubleClickProjectsTreePath(ide.project, path.joinToString("\u001F"))

    private fun waitForDocument(editor: EditorRemote, expected: String, description: String) {
        try {
            ide.waitFor(description, SHORT) { editor.getDocument().getText() == expected }
        } catch (error: AssertionError) {
            val actual = editor.getDocument().getText()
            val selected = selectedText(editor)
            val lookupActive = ide.service<LookupManagerRemote>(ide.project).getActiveLookup() != null
            throw AssertionError(
                "$description failed. Expected <$expected> but was <$actual>; selected=<$selected>; completionLookupActive=$lookupActive",
                error,
            )
        }
    }

    private fun allowFunctionCompletionPopup() {
        val lookupManager = ide.service<LookupManagerRemote>(ide.project)
        val deadline = System.nanoTime() + 1.seconds.inWholeNanoseconds
        while (System.nanoTime() < deadline) {
            if (lookupManager.getActiveLookup() != null) return
            Thread.sleep(25)
        }
    }

    private fun typeText(editor: EditorRemote, text: String) {
        for (charTyped in text) typeChar(editor, charTyped)
    }

    private fun pressTab(editor: EditorRemote) {
        val component = editor.getContentComponent()
        bringIdeToFront()
        ide.edt { component.requestFocus() }
        ide.waitFor("editor focus before physical Tab", 3.seconds) {
            ide.isForeground() && component.isFocusOwner()
        }
        val robot = Robot().apply { autoDelay = 25 }
        robot.keyPress(KeyEvent.VK_TAB)
        robot.keyRelease(KeyEvent.VK_TAB)
    }

    private fun selectedText(editor: EditorRemote): String? =
        ide.edt { editor.getSelectionModel().getSelectedText() }

    private fun hasSelection(editor: EditorRemote): Boolean =
        ide.edt { editor.getSelectionModel().hasSelection() }

    private fun restoreErgonomics(editor: EditorRemote) {
        try {
            val lookupManager = ide.service<LookupManagerRemote>(ide.project)
            ide.edt { lookupManager.hideActiveLookup() }
        } catch (_: RuntimeException) {
        }
        replaceDocument(editor, fixture.ergonomicsText)
    }

    private fun tokenScopeAt(editor: EditorRemote, offset: Int): String =
        ide.utility<PapyrusUiTestSupportRemote>().tokenScopeAt(editor, offset)

    private fun replaceDocument(editor: EditorRemote, text: String) {
        val expected = normalizeDocumentText(text)
        ide.utility<PapyrusUiTestSupportRemote>().replaceDocument(ide.project, editor, text)
        ide.waitFor("document replacement", SHORT) { editor.getDocument().getText() == expected }
    }

    private fun replaceDocumentRange(editor: EditorRemote, startOffset: Int, endOffset: Int, text: String) {
        val before = editor.getDocument().getText()
        check(startOffset in 0..before.length && endOffset in startOffset..before.length) {
            "Invalid document range $startOffset..$endOffset for length ${before.length}"
        }
        val replacement = normalizeDocumentText(text)
        val expected = before.substring(0, startOffset) + replacement + before.substring(endOffset)
        ide.utility<PapyrusUiTestSupportRemote>()
            .replaceDocumentRange(ide.project, editor, startOffset, endOffset, text)
        ide.waitFor("document range replacement", SHORT) { editor.getDocument().getText() == expected }
    }

    private fun normalizeDocumentText(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    private data class EditorHighlight(
        val startOffset: Int,
        val severity: String,
    )

    private fun diagnosticHighlights(editor: EditorRemote): List<EditorHighlight> {
        val daemon = ide.service<DaemonCodeAnalyzerRemote>(ide.project)
        val highlights = ide.read { daemon.getHighlights(editor.getDocument(), null, ide.project) }
        return safe(highlights).mapNotNull { highlight ->
            highlight.getDescription()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            EditorHighlight(
                startOffset = highlight.getStartOffset(),
                severity = highlight.getSeverity().getName(),
            )
        }
    }

    private fun errorHighlights(editor: EditorRemote): List<EditorHighlight> =
        diagnosticHighlights(editor).filter { it.severity == "ERROR" }

    private fun caretOffset(editor: EditorRemote): Int =
        ide.utility<PapyrusUiTestSupportRemote>().caretOffset(editor)

    private fun typeChar(editor: EditorRemote, charTyped: Char) {
        val editorEx = ide.cast<EditorExRemote>(editor)
        val typedAction = ide.utility<TypedActionUtilRemote>().getInstance()
        ide.edt { typedAction.actionPerformed(editor, charTyped, editorEx.getDataContext()) }
    }

    private fun languageService(): PapyrusLanguageServiceRemote = ide.service(ide.project)

    private fun refreshVfs(path: Path) {
        val normalizedPath = path.toAbsolutePath().normalize().toString().replace('\\', '/')
        ide.write {
            val fs = utility(LocalFileSystemUtilRemote::class).getInstance()
            checkNotNull(fs.refreshAndFindFileByPath(normalizedPath)) {
                "IDE VFS did not find $path during refresh"
            }
        }
    }

    private fun open(path: Path): EditorRemote {
        val normalizedPath = path.toAbsolutePath().normalize().toString().replace('\\', '/')
        refreshVfs(path)
        return ide.projectEdt { project ->
            val fs = utility(LocalFileSystemUtilRemote::class).getInstance()
            val file = checkNotNull(fs.findFileByPath(normalizedPath)) {
                "IDE VFS did not find $path after refresh"
            }
            val manager = service<FileEditorManagerRemote>(project)
            manager.openFile(file, true, true)
            val editor = checkNotNull(manager.getSelectedTextEditor()) { "No selected text editor after opening $path" }
            check(editor.getVirtualFile().getPath().equals(file.getPath(), ignoreCase = true)) {
                "Selected editor does not match $path"
            }
            editor
        }
    }

    private fun virtualFileInContext(path: Path): VirtualFile {
        val fs = ide.utility<LocalFileSystemUtilRemote>().getInstance()
        val normalizedPath = path.toAbsolutePath().normalize().toString().replace('\\', '/')
        return checkNotNull(fs.findFileByPath(normalizedPath)) {
            "IDE VFS did not find $path"
        }
    }

    private fun fileEditorManager(): FileEditorManagerRemote = ide.service(ide.project)

    private fun moveCaret(editor: EditorRemote, offset: Int) {
        assertTrue(offset in 0..editor.getDocument().getText().length)
        ide.edt { editor.getCaretModel().moveToOffset(offset) }
    }

    private fun focusEditor(editor: EditorRemote, offset: Int) {
        moveCaret(editor, offset)
        val ideWindow = bringIdeToFront()
        val component = editor.getContentComponent()
        ide.edt { component.requestFocus() }
        ide.waitFor("Papyrus editor keyboard focus", 3.seconds) {
            if (!ide.isForeground()) ide.forceForeground()
            ide.isForeground() && ideWindow.isFocused() && component.isFocusOwner()
        }
    }

    private fun bringIdeToFront(): Window {
        val activation = ide.forceForeground()
        assertTrue(activation.success()) { "Windows refused IDE foreground: ${activation.describe()}" }
        val visible = safe(ide.utility<Window>().getWindows())
            .filter(Window::isShowing)
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: throw AssertionError("No visible IDE AWT window")
        ide.waitFor("IDE Windows foreground", 5.seconds) {
            if (!ide.isForeground() && !ide.forceForeground().success()) return@waitFor false
            if (!visible.isFocused()) ide.edt {
                visible.toFront()
                visible.requestFocus()
            }
            ide.isForeground() && visible.isFocused()
        }
        return visible
    }

    private fun invokeAction(actionId: String, component: Component) {
        ide.context {
            this.invokeAction(actionId, component = component)
        }
    }

    private fun invokeShortcut(actionId: String, component: Component) {
        val shortcut = checkNotNull(ide.service<ActionManager>().getKeyboardShortcut(actionId)) {
            "Action $actionId has no keyboard shortcut"
        }
        val stroke = shortcut.getFirstKeyStroke()
        bringIdeToFront()
        ide.edt { component.requestFocus() }
        ide.waitFor("editor focus before $actionId shortcut", 3.seconds) {
            ide.isForeground() && component.isFocusOwner()
        }
        val robot = Robot().apply { autoDelay = 25 }
        val modifiers = stroke.getModifiers()
        val pressed = ArrayList<Int>(4)

        fun press(mask: Int, keyCode: Int) {
            if ((modifiers and mask) != 0) {
                robot.keyPress(keyCode)
                pressed += keyCode
            }
        }

        press(InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_SHIFT)
        press(InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_CONTROL)
        press(InputEvent.ALT_DOWN_MASK, KeyEvent.VK_ALT)
        press(InputEvent.META_DOWN_MASK, KeyEvent.VK_META)
        robot.keyPress(stroke.getKeyCode())
        robot.keyRelease(stroke.getKeyCode())
        pressed.asReversed().forEach(robot::keyRelease)
    }

    private fun visibleTexts(): List<String> {
        val result = LinkedHashSet<String>()
        ide.service<WindowManagerRemote>().getIdeFrame(ide.project)?.getComponent()?.let {
            collect(it, result, 0, 12_000)
        }
        for (window in safe(ide.utility<Window>().getWindows())) {
            try {
                if (window.isShowing()) collect(ide.cast<Component>(window), result, 0, 12_000)
            } catch (_: RuntimeException) {
            }
        }
        return result.toList()
    }

    private fun collect(component: Component?, result: MutableSet<String>, depth: Int, budget: Int): Int {
        if (component == null || depth > 48 || budget <= 0) return budget
        try {
            if (!component.isShowing()) return budget - 1
        } catch (_: RuntimeException) {
        }
        try {
            add(result, component.getAccessibleContext()?.getAccessibleName())
        } catch (_: RuntimeException) {
        }
        try {
            add(result, ide.cast<LabelRemote>(component).getText())
        } catch (_: RuntimeException) {
        }
        try {
            add(result, ide.cast<ButtonRemote>(component).getText())
        } catch (_: RuntimeException) {
        }
        try {
            add(result, ide.cast<TextComponentRemote>(component).getText())
        } catch (_: RuntimeException) {
        }
        var left = budget - 1
        try {
            for (child in safe(ide.cast<ContainerRemote>(component).getComponents())) {
                left = collect(child, result, depth + 1, left)
                if (left <= 0) break
            }
        } catch (_: RuntimeException) {
        }
        return left
    }

    private fun add(target: MutableSet<String>, value: String?) {
        val text = value?.trim().orEmpty()
        if (text.isNotEmpty() && text.length < 4096) target += text
    }

    private fun <T> safe(values: List<T>?): List<T> = values ?: emptyList()
    private fun <T> safe(values: Array<T>?): List<T> = values?.toList() ?: emptyList()

    @Remote(value = "dev.papyrus.jetbrains.lsp.PapyrusLanguageService", plugin = PLUGIN_ID)
    internal interface PapyrusLanguageServiceRemote {
        fun hasRunningClient(): Boolean
    }

    @Remote(value = "dev.papyrus.jetbrains.testing.PapyrusUiTestSupport", plugin = PLUGIN_ID)
    internal interface PapyrusUiTestSupportRemote {
        fun replaceDocument(project: Project, editor: EditorRemote, text: String)
        fun replaceDocumentRange(
            project: Project,
            editor: EditorRemote,
            startOffset: Int,
            endOffset: Int,
            text: String,
        )
        fun papyrusSyntaxTreeSnapshot(project: Project, editor: EditorRemote): String
        fun tokenScopeAt(editor: EditorRemote, offset: Int): String
        fun caretOffset(editor: EditorRemote): Int
        fun saveAllDocuments()
        fun clearCapturedExternalUrl()
        fun capturedExternalUrl(): String?
        fun preparePapyrusCompileSelection(projectFile: String)
        fun papyrusLspOutputSnapshot(project: Project): String
        fun firstPapyrusCompilerDiagnostic(project: Project): String
        fun navigateFirstPapyrusCompilerDiagnostic(project: Project): String
        fun selectedEditorFilePath(project: Project): String
        fun prepareProjectGeneration(parentDirectory: String, folderName: String)
        fun cancelProjectGeneration()
        fun clearCapturedActionMessage()
        fun capturedActionMessageKind(): String?
        fun capturedActionMessageText(): String?
        fun creationKitInstallPath(): String
        fun setCreationKitInstallPath(path: String)
        fun compilerPathOverride(): String
        fun setCompilerPathOverride(path: String)
        fun papyrusEnabled(): Boolean
        fun setPapyrusEnabled(enabled: Boolean)
        fun refreshPapyrusEnablement(project: Project)
        fun papyrusAttachConfigurationTypeRegistered(): Boolean
        fun papyrusProjectConfigurationTypeRegistered(): Boolean
        fun papyrusProjectTaskRunnerRegistered(): Boolean
        fun papyrusBuildSystem(project: Project): String
        fun setPapyrusBuildSettings(project: Project, buildSystem: String, projectFile: String)
        fun runPapyrusProjectConfiguration(project: Project, projectFile: String): String
        fun papyrusProjectCompileRunning(project: Project): Boolean
        fun selectedRunConfigurationTypeId(project: Project): String
        fun papyrusLspClientCount(project: Project): Int
        fun papyrusLspClientStates(project: Project): String
        fun papyrusDefinitionProviderStates(project: Project): String
        fun activeShortcutBindings(actionId: String): String
        fun startShortcutDispatchTrace()
        fun shortcutDispatchTraceSnapshot(): String
        fun stopShortcutDispatchTrace(): String
        fun isProjectContentFile(project: Project, filePath: String): Boolean
        fun isProjectLibrarySourceFile(project: Project, filePath: String): Boolean
        fun papyrusImportLibraryExternalRootTypes(project: Project): String
        fun clearPapyrusLspOutputDiagnostic()
        fun papyrusLspOutputDiagnostic(project: Project): String
        fun selectedToolWindowTreePath(project: Project, toolWindowId: String): String
        fun disposeVisibleDialog(dialogTitle: String)
        fun cleanupTransientUi(project: Project, baselinePath: String)
        fun createProjectTextFile(project: Project, relativePath: String, text: String): String
        fun deleteProjectFile(project: Project, relativePath: String): Boolean
        fun papyrusProjectInfosReady(project: Project): Boolean
        fun papyrusProjectInfosContainsFile(project: Project, filePath: String): Boolean
        fun livePapyrusProjectInfosContainsFile(project: Project, filePath: String): Boolean
        fun papyrusWorkspaceFileWatcherStarted(project: Project): Boolean
        fun papyrusWorkspaceFileWatcherRelevantEventCount(project: Project): Long
        fun papyrusWorkspaceFileWatcherLastRelevantEvent(project: Project): String
        fun selectPapyrusProjectsContent(project: Project): Boolean
        fun papyrusProjectsStatusPhase(project: Project): String
        fun papyrusProjectsStatusSummary(project: Project): String
        fun papyrusProjectsStatusDetails(project: Project): String
        fun papyrusProjectsShowingLastKnownGood(project: Project): Boolean
        fun reloadPapyrusProjects(project: Project)
        fun restartPapyrusLanguageServer(project: Project)
        fun papyrusLanguageServerWorkspaceRoot(project: Project): String
        fun projectsTreeSnapshot(project: Project): String
        fun expandProjectsTreePath(project: Project, encodedPath: String): Boolean
        fun doubleClickProjectsTreePath(project: Project, encodedPath: String): Boolean
        fun visibleTextTooltip(project: Project, text: String): String?
        fun clickVisibleText(project: Project, text: String): Boolean
        fun setVisibleTextFieldAndSubmit(project: Project, accessibleName: String, text: String): Boolean
        fun actionIdByText(text: String): String
        fun actionDiagnostics(text: String): String
    }

    @Remote("com.intellij.openapi.vfs.LocalFileSystem")
    internal interface LocalFileSystemUtilRemote {
        fun getInstance(): LocalFileSystemRemote
    }

    @Remote("com.intellij.openapi.vfs.LocalFileSystem")
    internal interface LocalFileSystemRemote {
        fun refreshAndFindFileByPath(path: String): VirtualFile?
        fun findFileByPath(path: String): VirtualFile?
    }

    @Remote("com.intellij.openapi.fileEditor.FileEditorManager")
    internal interface FileEditorManagerRemote {
        fun openFile(file: VirtualFile, focusEditor: Boolean, searchForOpen: Boolean): Array<FileEditorRemote>
        fun closeFile(file: VirtualFile)
        fun getSelectedTextEditor(): EditorRemote?
    }

    @Remote("com.intellij.openapi.vfs.VirtualFile")
    internal interface VirtualFileStateRemote {
        fun getName(): String
        fun getPath(): String
        fun isWritable(): Boolean
    }

    @Remote("com.intellij.openapi.fileEditor.FileEditor")
    internal interface FileEditorRemote {
        fun getFile(): VirtualFile
    }

    @Remote("com.intellij.openapi.editor.Editor")
    internal interface EditorRemote {
        fun getDocument(): DocumentRemote
        fun getVirtualFile(): VirtualFile
        fun getCaretModel(): CaretModelRemote
        fun getSelectionModel(): SelectionModelRemote
        fun getFoldingModel(): FoldingModelRemote
        fun getContentComponent(): Component
    }

    @Remote("com.intellij.openapi.editor.Document")
    internal interface DocumentRemote {
        fun getText(): String
        fun isWritable(): Boolean
    }

    @Remote("com.intellij.openapi.editor.CaretModel")
    internal interface CaretModelRemote {
        fun moveToOffset(offset: Int)
        fun getOffset(): Int
    }

    @Remote("com.intellij.openapi.editor.SelectionModel")
    internal interface SelectionModelRemote {
        fun setSelection(startOffset: Int, endOffset: Int)
        fun removeSelection()
        fun getSelectedText(): String?
        fun hasSelection(): Boolean
    }

    @Remote("com.intellij.openapi.editor.ex.EditorEx")
    internal interface EditorExRemote {
        fun getDataContext(): DataContextRemote
    }

    @Remote("com.intellij.openapi.actionSystem.DataContext")
    internal interface DataContextRemote

    @Remote("com.intellij.openapi.editor.actionSystem.TypedAction")
    internal interface TypedActionUtilRemote {
        fun getInstance(): TypedActionRemote
    }

    @Remote("com.intellij.openapi.editor.actionSystem.TypedAction")
    internal interface TypedActionRemote {
        fun actionPerformed(editor: EditorRemote, charTyped: Char, dataContext: DataContextRemote)
    }

    @Remote("com.intellij.openapi.editor.FoldingModel")
    internal interface FoldingModelRemote {
        fun getAllFoldRegions(): Array<FoldRegionRemote>
    }

    @Remote("com.intellij.openapi.editor.FoldRegion")
    internal interface FoldRegionRemote {
        fun getStartOffset(): Int
        fun getEndOffset(): Int
    }

    @Remote("com.intellij.openapi.fileTypes.FileTypeManager")
    internal interface FileTypeManagerRemote {
        fun getFileTypeByFile(file: VirtualFile): FileTypeRemote?
    }

    @Remote("com.intellij.openapi.fileTypes.FileType")
    internal interface FileTypeRemote {
        fun getName(): String
    }

    @Remote("com.intellij.psi.PsiManager")
    internal interface PsiManagerRemote {
        fun findFile(file: VirtualFile): PsiFileRemote?
    }

    @Remote("com.intellij.psi.PsiFile")
    internal interface PsiFileRemote {
        fun getLanguage(): LanguageRemote
    }

    @Remote("com.intellij.lang.Language")
    internal interface LanguageRemote {
        fun getID(): String
    }

    @Remote("com.intellij.codeInsight.lookup.LookupManager")
    internal interface LookupManagerRemote {
        fun getActiveLookup(): LookupRemote?
        fun hideActiveLookup()
    }

    @Remote("com.intellij.codeInsight.lookup.Lookup")
    internal interface LookupRemote {
        fun isCompletion(): Boolean
        fun getItems(): List<LookupElementRemote>?
    }

    @Remote("com.intellij.codeInsight.lookup.LookupElement")
    internal interface LookupElementRemote {
        fun getLookupString(): String
    }

    @Remote(
        serviceInterface = "com.intellij.codeInsight.daemon.DaemonCodeAnalyzer",
        value = "com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl",
    )
    internal interface DaemonCodeAnalyzerRemote {
        fun isAllAnalysisFinished(psiFile: PsiFileRemote): Boolean
        fun getHighlights(document: DocumentRemote, severity: HighlightSeverityRemote?, project: Project): List<HighlightInfoRemote>?
    }

    @Remote("com.intellij.codeInsight.daemon.impl.HighlightInfo")
    internal interface HighlightInfoRemote {
        fun getDescription(): String?
        fun getStartOffset(): Int
        fun getSeverity(): HighlightSeverityRemote
    }

    @Remote("com.intellij.lang.annotation.HighlightSeverity")
    internal interface HighlightSeverityRemote {
        fun getName(): String
    }

    @Remote("com.intellij.codeInsight.folding.CodeFoldingManager")
    internal interface CodeFoldingManagerRemote {
        fun updateFoldRegions(editor: EditorRemote)
    }

    @Remote("com.intellij.lang.documentation.ide.impl.DocumentationManager")
    internal interface DocumentationManagerRemote {
        fun isPopupVisible(): Boolean
    }

    @Remote("com.intellij.codeInsight.hint.ParameterInfoControllerBase")
    internal interface ParameterInfoControllerRemote {
        fun existsWithVisibleHintForEditor(editor: EditorRemote, anyHintType: Boolean): Boolean
    }

    @Remote("com.intellij.openapi.wm.ToolWindowManager")
    internal interface ToolWindowManagerRemote {
        fun getToolWindow(id: String): ToolWindowRemote?
    }

    @Remote("com.intellij.openapi.wm.ToolWindow")
    internal interface ToolWindowRemote {
        fun isVisible(): Boolean
        fun isAvailable(): Boolean
        fun show()
        fun getContentManager(): ContentManagerRemote
    }

    @Remote("com.intellij.ui.content.ContentManager")
    internal interface ContentManagerRemote {
        fun getContentCount(): Int
    }

    @Remote("com.intellij.openapi.wm.WindowManager")
    internal interface WindowManagerRemote {
        fun getIdeFrame(project: Project): IdeFrameRemote?
    }

    @Remote("com.intellij.openapi.wm.IdeFrame")
    internal interface IdeFrameRemote {
        fun getComponent(): Component?
    }

    @Remote("java.awt.Container")
    internal interface ContainerRemote {
        fun getComponents(): Array<Component>?
    }

    @Remote("javax.swing.JLabel")
    internal interface LabelRemote {
        fun getText(): String?
    }

    @Remote("javax.swing.AbstractButton")
    internal interface ButtonRemote {
        fun getText(): String?
    }

    @Remote("javax.swing.text.JTextComponent")
    internal interface TextComponentRemote {
        fun getText(): String?
    }
}
