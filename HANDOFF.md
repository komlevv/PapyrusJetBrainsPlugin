# Papyrus JetBrains Plugin handoff — 0.2.166 source

> Documentation boundary: `README.md` is the public/user-facing GitHub landing page. Version-by-version history, regression notes, test gates, inspection/lint details, local build paths, offline dependency layout, implementation notes, and other maintainer-only operational context belong here or in the dedicated living technical documents, not in `README.md`.

0.2.153 was reported green and proved import-to-import navigation. 0.2.154 moved imports from content roots to source-only library roots; 0.2.155 made that library visible under External Libraries, and the user confirmed the result. 0.2.156 keeps that model and adds Papyrus Projects-style root labels plus filtering of project-local source/import overlap. Expected gate: **72 UNIT + 20 PAPYRUS-LANG + 48 REAL CLION UI**.

0.2.147 fixed the IntelliJ Platform 262 background-thread read-access assertion in script-status polling by using `FileDocumentManager.getCachedDocument()` for modification-stamp checks instead of the read-lock-requiring `getDocument()`. The user reported the 0.2.147 build green; the full 59/16/39 regression gate has not been re-run for that version.

0.2.148 is the clean base line that removes the machine-specific default `Y:\dev\PapyrusTest\IntellijPapyrus.ini` from Papyrus settings. 0.2.150 added the first Papyrus-scoped editor-local `GotoDeclaration` shortcut route; the user's Windows gate proved that it outranks the Rider backend composite action but its `ActionUtil.wrap(...)` delegate still does not navigate. 0.2.151 changes only the local execution step to public `ActionManager.tryToExecute(...)` with the original keyboard event removed. There is no settings migration and no workspace-root experiment in this line.

0.2.145 performed the high-confidence code-quality cleanup; 0.2.146 only fixes the checked `URISyntaxException` compile regression in the server-integration guardian classpath conversion. Production behavior, write boundaries, process containment, and debugger hold are unchanged.

Target runtime/test IDE: **CLion 2026.2.x / IntelliJ Platform 262**. Verified installation: **CLion 2026.2.1 / CL-262.9437.136**.


## 0.2.166 — line-separator-safe editor-buffer UI tests

- `PapyrusUiTestSupport.replaceDocument(...)` and `replaceDocumentRange(...)` now normalize incoming text to the IntelliJ `Document` LF-only representation before calling `Document.replaceString(...)`.
- This fixes the real-CLion PPJ buffer test when the fixture file is stored with Windows CRLF line endings.
- Range-replacement caret positioning now uses the normalized text length.
- Production PPJ editor-buffer Refresh behavior is unchanged from 0.2.165.

## 0.2.165 — unsaved PPJ Refresh

Projects Refresh now captures unsaved `.ppj` editor `Document` text under a read action and validates/materializes that exact in-memory image. It never force-saves the PPJ. Unsaved PPJ edits mark Projects `DIRTY` immediately through the existing document listener bridge; native PPJ LSP synchronization remains disabled. A later Ctrl+S for an already-applied buffer is ignored as a duplicate dirty signal, while external/non-editor PPJ VFS changes still mark the configuration dirty. The immutable validated workspace still provides the TOCTOU and cold-start boundary. Expected gate: **76 UNIT + 20 PAPYRUS-LANG + 48 REAL CLION UI**.

## 0.2.164 — Projects Refresh busy state

The Projects `Refresh` button now switches immediately to a native disabled `Refreshing...` state on click and stays disabled through `VALIDATING`, `RELOADING`, and `SYNCHRONIZING`. The label returns to `Refresh` on success or any terminal validation/server error. The button reserves enough width for both labels so the toolbar does not jump. This is UI feedback only; `PapyrusProjectsService.reloadFromProjectFilesAsync()` already rejects duplicate reload requests under `refreshLock`, so programmatic or pre-listener duplicate invocations remain fail-closed.

## Current source state

- Current source version: **0.2.166**.
- 0.2.126 is the all-24 inspection cleanup. It does not add a Papyrus feature; it refactors plugin-owned warnings, updates deprecated/DevKit APIs, adds descriptor/live-template i18n, and documents every submitted screenshot in `INSPECTION_AUDIT.md`. Vendor/generated artifacts remain untouched.
- Latest user-confirmed green Windows gate: **0.2.164 — 75/75 UNIT, 20/20 PAPYRUS-LANG, 48/48 REAL CLION UI**.
- Hardened semantic **Refactor | Rename** is VERIFIED at the 0.2.112 baseline.
- The broad non-debugger port/parity burn-down is complete and verified in 0.2.138: Stage 1 covers semantic Completion/Definition/References edges, Stage 2 real-IDE Syntax Tree/document sync/diagnostics, Stage 3 Project Infos/script status, and Stage 4 safe Pyro task discovery/compiler-output navigation. Debugger is explicitly deferred.
- Bounded **Compile Project**, the executable `PapyrusProject` IDE Run Configuration, and project-level opt-in Build Project are VERIFIED. 0.2.124 keeps the active runtime/test target on CLion and replaces the Java-only New Project language generator with `DirectoryProjectGenerator`.
- Upstream reference: `joelday/papyrus-lang v3.3.0-prerelease.1`.
- Active IDE target: **CLion 2026.2.1 / CL-262.9437.136**; compatibility lock remains platform branch **262**.
- Active game target: **Skyrim Special Edition / Anniversary Edition**.
- IDE discovery no longer hardcodes a product version/path: `product-info.json` supplies product/version/build/launcher/vmoptions; resolution prefers `-PpapyrusIdeHome`, then `PAPYRUS_IDE_HOME`, then installed JetBrains products. Windows launch is fail-closed to `*64.exe`.
- `ide-starter-product-idea-ultimate` is no longer required or placed on the Starter classpath. The four versioned Driver/Starter JARs must share one build; a different 262 patch build is warned but allowed for the first migration gate.

- 0.2.115 full gate verified real bundled Pyro + Creation Kit compilation, project-local `.pex` output, source immutability, and temporary validated PPJ cleanup.

The pinned upstream VSIX is a project vendor input embedded in the plugin JAR and extracted to IDE system cache at runtime. Offline Starter/Driver/JUnit JARs live in `third_party/papyrus-test-deps/`. Because the tagged VSIX does not encode its empty `extension/pyro/remote` directory, extraction explicitly creates that required runtime directory and treats caches without it as incomplete.

## Current feature state

### Verified safe/read-only SSE/AE surface

The previous green baseline has real-IDE coverage for:

- `.psc` TextMate recognition/highlighting;
- completion;
- diagnostics;
- go to declaration;
- folding;
- Find Usages/references;
- Quick Definition;
- Quick Documentation/hover;
- Parameter Info/signature help;
- File Structure/document symbols;
- comments;
- pairs/smart typing;
- indentation;
- representative common Live Templates;
- Papyrus Projects tree/navigation/lazy presentation;
- overridden-script status/navigation;
- View Assembly read-only in-memory editor;
- Creation Kit Wiki Search behavior;
- bounded Generate Skyrim SE Project behavior;
- active-editor status and richer tooltip;
- compiler-missing status;
- status click → bounded Output transcript without LSP restart;
- missing status → Settings, explicit disable, and no restart while disabled;
- cached `projectInfos` script navigator → exact LSP-reported file.

Raw tagged-server coverage verifies completion, hover, signature help, diagnostics, definition, references, document symbols, dynamic References registration, `syntaxTree`, `projectInfos`, `scriptInfo`, and `assembly`; 0.2.131 added semantic completion scopes/declaration boundaries plus Definition and References edge cases. 0.2.132 added I34/I35 for the JetBrains document-sync bridge and diagnostics refresh. 0.2.133 adds server-side local source/import precedence and isolated pre-populated remote-cache resolution, plus I36 unresolved native status and I37 real-VFS project refresh.

### Safe Rename — VERIFIED

- `Refactor | Rename` is handled by `PapyrusRenameHandler`.
- `textDocument/definition` is used to reject external/system declarations before the rename dialog.
- `textDocument/rename` is used only to obtain semantic proposed text edits.
- Every target is revalidated against canonical project root, IDE project content, writability, Creation Kit/game root, remote/import-only provenance, and bundled vendor/cache boundaries.
- Every text edit must replace the selected identifier text with exactly the requested new identifier; insert/delete/arbitrary edits fail closed.
- New names are validated as identifiers and Papyrus keywords are rejected before the server rename request.
- `ScriptName` rename is explicitly blocked until coordinated `.psc` file rename is designed.
- `documentChanges`, resource operations, unresolved URIs, malformed/out-of-range/overlapping edits, and mixed safe/unsafe target sets fail closed.
- Any user-triggered rejection is explicit: `Papyrus Rename Blocked` shows the reason and target path when known.
- Safe edits are applied as one IDE write command.
- Blocking LSP request waits run on a pooled thread and return to EDT only for validation/dialog/apply UI.
- Generic platform LSP Rename remains disabled, and a notifications guard now rejects unsolicited server `workspace/applyEdit` even if the server ignores the advertised capability.

### Safe Pyro Compile — VERIFIED / native Run VERIFIED / opt-in Build VERIFIED 0.2.117

- `Papyrus.CompileProject` compiles a selected `.ppj` directly; without a selected PPJ it discovers real project-local `.ppj` tasks and lets the user choose one. Every path still enters the same bounded compile preflight.
- `PapyrusProject` is a VERIFIED executable native IDE Run Configuration with a `.ppj` project-file field, standard Run console, Stop, and Rerun lifecycle.
- `PapyrusProjectTaskRunner` is registered through the IntelliJ Platform project-task extension point. It returns `canRun=false` unless this project explicitly selects `Papyrus (Pyro)` in Build Tools settings; ordinary projects therefore retain native IDE build behavior.
- The `.ppj` must be a real file inside the IDE project.
- Compilation is forced to SSE/AE and uses bundled `pyro.exe`; the configured Creation Kit/game and compiler are read-only inputs.
- The `.ppj` Output must remain inside the project; the validated absolute output path is forced on the Pyro command line.
- Anonymize, Package, Zip, Variables, all pre/post events, HTTP(S) remote Import/Folder paths, and expandable Import/Folder/Output paths are rejected before process start.
- The explicit action captures Pyro stdout/stderr into `Papyrus Projects | Output`; the Run Configuration uses the IDE's standard Run console. 0.2.134 attaches the same project-bounded Papyrus compiler hyperlink filter to both consoles.
- I30 VERIFIED the real bundled-Pyro/Creation Kit compiler run against an isolated valid script and requires a non-empty project-local `.pex` while project and Creation Kit source remain unchanged. The original PPJ is never rewritten; Pyro receives a temporary snapshot of the already-validated content so XML declaration compatibility and post-validation mutation are both contained.
- I31 VERIFIED the same fixture through `RunManager` / `PapyrusProject`, including native configuration type, output, snapshot cleanup, source immutability, and release of the shared project-level compile gate.
- I32 VERIFIED the project-level opt-in Build Project bridge while preserving `intellij` as the default build system for ordinary projects.
- I38 covers discovered task selection through the real Compile action. I39 compiles a deliberately broken project source and requires a real compiler problem line plus navigation to the exact project source.
- Advanced Pyro task-definition options/package/zip/event/remote behavior remain held.

### Native New Project integration — 0.2.123 CLion candidate

- Global Papyrus language-service settings are registered under **Settings → Languages & Frameworks → Papyrus**.
- `PapyrusDirectoryProjectGenerator` implements platform `DirectoryProjectGenerator` and is registered as `com.intellij.directoryProjectGenerator`, so Papyrus appears in CLion **File → New Project** without Java-plugin APIs.
- The native wizard uses the platform directory-project flow and then reuses the existing bounded project generator for Papyrus-owned files.
- It creates `skyrimse.ppj`, `Source/Scripts`, `Papyrus: Compile Project`, and the inert Skyrim attach `.run` configuration inside the newly created project only.
- That newly created project is explicitly set to `Build system = Papyrus (Pyro)`; ordinary existing projects keep `IDE default`.
- I33 is now CLion-oriented and follows the Driver UI hierarchy: CLion exposes New Project through a product-owned action rather than a cross-product stable ID; I33 resolves the live action by presentation text and then uses `popup().jBlist(...)`, verifies the real `Papyrus` item, and closes that popup without entering project creation. There is no dependency on Java-plugin wizard APIs or a `Java` item.
- The isolated Starter config is intentionally fresh. CLion therefore may show its product-owned **Open Project Wizard** for first-run Toolchains setup; the harness accepts that top-level dialog through Driver and never writes CLion-private toolchain XML. Startup is fail-safe: if foregrounding, dialog handling, or project acquisition fails before the session is constructed, the spawned IDE is closed immediately.

### Implemented but not dedicated real-IDE-gated

- manual **Papyrus: Show Getting Started Help**;
- Windows Registry fallback is strongly unit-covered but does not have a dedicated Registry-only real-IDE scenario;
- `papyrus/projectsUpdated` cache invalidation is implemented but has no dedicated notification-only scenario;
- `.ppj` TextMate/project-language behavior is exercised indirectly rather than through a dedicated syntax test.

### Intentional difference

- **Automatic Welcome is skipped by user decision.** Do not reintroduce it as parity work.

### Safety holds / defensive disables

Do not enable through normal parity work:

- unrestricted Pyro tasks/package/zip/anonymize/events/remotes;
- debugger-support installation;
- executable debugger attach/breakpoints.

Papyrus Rename is no longer on hold: 0.2.112 verifies its hardened plugin-owned all-or-nothing write firewall and unsolicited `workspace/applyEdit` rejection. Tagged upstream has no Code Action handler; generic Code Actions remain defensively disabled.

The inert `PapyrusAttach` configuration must continue to reject execution until a dedicated debugger safety gate exists.

### Out of current scope

- Fallout 4;
- Skyrim LE;
- their generate-project/config/debug/snippet-specific surfaces.

## Coverage model

There is no JaCoCo/line/branch percentage. Coverage is feature/layer based:

- `U` = unit/contract;
- `S` = raw exact-upstream server;
- `I` = real target-IDE runtime; the active target is CLion.

Current full-green counts: **59 U + 16 S + 39 I = 114 tests** on 0.2.146.

`PORT_STATUS.md` is the authoritative coverage/parity matrix. Do not reconstruct status from old version numbers.

## Architecture summary

- The exact tagged VSIX is SHA-pinned at build time, embedded into the plugin JAR, extracted into a versioned IDE system-cache directory, then loaded through `PapyrusTextMateBundleProvider`.
- No VSIX path remains in user Settings.
- LSP uses IntelliJ Platform native LSP APIs and one project-wide Papyrus client.
- `PapyrusLspClientDescriptor` adapts known IntelliJ Platform 262/upstream incompatibilities:
  - completion prefix after `.`;
  - References static-false vs dynamic registration conflict;
  - `didChange` rangeLength compatibility through a plugin bridge;
  - watched-files registration through the VFS bridge;
  - generic platform Rename and Code Actions are disabled;
  - `workspace.applyEdit=false` is advertised and unsolicited server `workspace/applyEdit` is also physically rejected by `PapyrusSafeServerNotificationsHandler`;
  - `PapyrusRenameHandler` performs client-initiated semantic Rename through the same server but validates all write targets and edit shapes itself.
- `PapyrusLanguageService` owns typed custom requests for projectInfos, assembly, and scriptInfo.
- `PapyrusProjectsService` caches projectInfos; `papyrus/projectsUpdated` invalidates it.
- `PapyrusScriptStatusService` owns scriptInfo caching/invalidation.
- `Papyrus Projects` owns `Projects` and bounded in-memory `Output` tabs.
- Bundled VSIX and host runtime may be extracted/staged only inside IDE system cache; source vendor/game inputs remain read-only.

## Practical PPJ/import and Go To Definition investigation — 2026-08-14

### PPJ/import result

The immediate HPL project-discovery failure is locally explained. Do **not** patch upstream for it now.

Confirmed from the real CLion project:

- `X:\PapyrusBuild\HPL` is correctly discovered when its PPJ contains `./src` as the local `<Import>` / `<Folder>`.
- The external imports that made the project disappear contained an incorrect path. With a correct path, this is no longer evidence of a JetBrains workspace/discovery defect.
- The user deliberately deferred a robustness pass for malformed/unresolvable imports. A typo in one `<Import>` should eventually produce a useful error and should not leave the whole project unusable, but this is not the current task.

Confirmed upstream fragility to preserve for that later robustness pass:

- `papyrus-lang v3.3.0-prerelease.1` `SourceInclude.Path` derives its display name through `File.GetAttributes(path)`. An inaccessible/malformed source path can therefore throw while building project options instead of degrading to an empty/unresolved include. The same code is still present on upstream `develop`.
- `WorkspaceManager.Handle(DidChangeWatchedFilesParams)` in the tagged server has a duplicated `.psc` condition and does not reload a changed `.ppj` through the watched-file path. PPJ save has a separate reload handler, but recovery after a failed project load is still fragile. The same code is still present on upstream `develop`.
- These upstream issues are **deferred**. Do not vendor-patch them while investigating Go To Definition.

### Go To Declaration / `textDocument/definition` investigation

Real-user symptoms:

- `Quest.psc` is visibly loaded in Papyrus Projects from the Skyrim source import; a second `Quest.psc` may also exist in `merged_for_compile`.
- `Ctrl+B` on `Quest` does not navigate. The user reports the same problem for other imported definitions and also reports that local Ctrl+B can fail, so the investigation must not assume that only external VFS targets are affected.
- No more manual user-side probes should be requested for this issue. Reproduce through raw-server and real-CLion automated tests.

Important API facts verified before adding tests:

- LSP `textDocument/definition` can be advertised statically through `ServerCapabilities.definitionProvider` or dynamically through `client/registerCapability` when the client advertises `DefinitionClientCapabilities.dynamicRegistration`.
- IntelliJ Platform 262 advertises `DefinitionCapabilities` with `linkSupport=true`, but its default capability construction does **not** set `definition.dynamicRegistration=true`.
- IntelliJ Platform 262 `LspClientImpl.supportsGotoDefinition()` checks only the static `initializeResult.capabilities.definitionProvider`. In contrast, `supportsFindReferences()` falls back to the dynamically registered References capability.
- IntelliJ Platform 262 does track dynamic `textDocument/definition` registrations internally, but that dynamic table is not consulted by `supportsGotoDefinition()`. `LspImplicitReferenceProvider` calls `supportsGotoDefinition()` before sending `textDocument/definition`, so a missing/false static provider can suppress Ctrl+B before any request reaches papyrus-lang.
- The plugin already has a References compatibility shim for a related static-false/dynamic-registration mismatch. There is currently no Definition-specific shim. Do **not** add one until tests prove it is needed.
- `papyrus-lang v3.3.0-prerelease.1` uses OmniSharp.Extensions LanguageServer/LanguageProtocol 0.10.0. Its `DefinitionHandler.SetCapability()` sets `DynamicRegistration=true`, and the handler itself supports type identifiers through the normal symbol/type resolution path.
- Existing raw tests were not production-equivalent for this capability question: `RawLspClient.initialize()` advertised `definition.dynamicRegistration=true`. Existing real-CLion navigation coverage verified one local member call (`Target.SharedProbe()`), not script-type navigation and not vanilla/import navigation.

Test-only investigation added on top of 0.2.148, with production LSP behavior intentionally unchanged:

1. `RawLspClient.CapabilityProfile.JETBRAINS_262` mirrors the relevant production Definition capability by advertising `definition.linkSupport=true` without dynamic registration.
2. A raw capability-contract test requires a static `definitionProvider` under that client profile and reports an unexpected dynamic Definition registration.
3. Raw `textDocument/definition` tests cover:
   - local Papyrus script type -> local `.psc`;
   - PPJ imported script type -> imported `.psc`;
   - vanilla `Quest` -> `Data/Source/Scripts/Quest.psc`.
4. Real CLion UI tests cover:
   - actual `initializeResult.definitionProvider` state exposed through test-only support;
   - Ctrl+B from the local `FeatureTarget` type;
   - Ctrl+B from vanilla `Quest`.
5. The existing real-CLion `Target.SharedProbe()` navigation test remains as the local-member reference control.

Interpretation of the new tests:

- If raw Definition semantics pass but real CLion reports `definitionProvider=false/null`, the failure is capability gating before the request is sent.
- If the static provider is enabled and raw Definition semantics pass but UI navigation fails, inspect JetBrains request/Location handling next.
- If a raw local/import/Quest Definition test fails, investigate papyrus-lang semantic/project binding for that reference kind before changing the JetBrains adapter.

Do not implement a Definition compatibility workaround until this matrix has been run on the real Windows gate.

## Current user-visible actions

Registered:

- `Papyrus.SearchCreationKitWiki`;
- `Papyrus.ViewAssembly`;
- `Papyrus.GenerateSkyrimProject`;
- `Papyrus.CompileProject`;
- `Papyrus.ShowWelcome`.

Not registered/active:

- unrestricted Pyro task/package/zip/event/remote behavior;
- debugger install/attach execution;
- generic LSP Code Actions.

`Refactor | Rename` is active through `PapyrusRenameHandler`; generic platform LSP Rename remains disabled.

## Current local development baseline

Default source/build paths are local/offline and intentionally machine-specific unless overridden. Relevant defaults currently include:

- IDE: auto-resolved CLion `CL` on branch `262`; current machine target is `C:/Program Files/JetBrains/CLion 2026.2.1`;
- Kotlin: `X:/kotlinc`;
- vendor VSIX input: `<project>/vendor/papyrus-lang/v3.3.0-prerelease.1/papyrus-lang-vscode.vsix`;
- offline test JARs: `<project>/third_party/papyrus-test-deps/`;
- Skyrim SE/AE / Creation Kit: `X:/SteamLibrary/steamapps/common/Skyrim Special Edition`;
- test INI: no machine-specific path is a product default; local test configuration must supply the environment-specific Creation Kit INI through the existing test setup.

The project is not currently documented as Marketplace/reproducible-CI ready.

## Next decision

Run `gradlew.bat printIdeTarget` first and verify it resolves `CLion 2026.2.1`, `CL-262.9437.136`, `bin\clion64.exe`, and `bin\clion64.exe.vmoptions`. Then run the 0.2.145 **59/16/39** Windows regression gate. This gate validates all four non-debugger burn-down stages, including project-local PPJ task selection and real compiler-diagnostic navigation. Keep the key invariant: no existing project is switched away from the native IDE build unless the user explicitly selects `Papyrus (Pyro)`; discovered tasks expose only project-file choice and never raw advanced Pyro options. Debugger remains out of this gate.

## Mandatory iteration rules

- All code/comments/strings remain English.
- Update all six living `.md` files whenever behavior/status changes.
- Keep `PORT_STATUS.md` as the single current parity matrix.
- Do not claim a line/branch coverage percentage without actual instrumentation.
- Do not weaken real-IDE assertions merely to make the build green.
- Treat vendor VSIX/game/compiler/import-only dependency-source locations as read-only unless the user explicitly approves a bounded write feature.
- Authoritative Windows command remains:

```bat
gradlew.bat test
```

- Source of truth report remains `build/papyrus-test-report.txt`.

- CLion LSP compatibility: `plugin.xml` declares both `com.intellij.modules.lsp` and `com.intellij.modules.ultimate`.


## Ctrl+B shortcut dispatch investigation (2026-08-14)

New real-world symptom: invoking **Go to Declaration or Usages** from the editor context menu works, while pressing **Ctrl+B** does not, even though the menu displays Ctrl+B for the same action. This means direct `GotoDeclaration` action execution is not sufficient coverage for the reported failure.

JetBrains Platform 262 default keymap binds `Ctrl+B` to action id `GotoDeclaration`. `GotoDeclarationAction` uses the same `GotoDeclarationOrUsageHandler2` for the action itself, but its `update()` path depends on the input event, action place, editor/data context, and focus. JetBrains source also contains TRACE-only diagnostics specifically for cases where `GotoDeclarationAction` is intermittently disabled.

The UI suite previously called `invokeAction("GotoDeclaration", ...)`, which bypasses keyboard shortcut dispatch and therefore reproduces the working context-menu/direct-action path rather than the failing Ctrl+B path.

Added UI regression coverage that sends the **actual active-keymap shortcut through `java.awt.Robot`** using the existing `invokeShortcut()` helper:

- Ctrl+B on a local member reference (`SharedProbe`)
- Ctrl+B on a local script type (`FeatureTarget`)
- Ctrl+B on the imported vanilla type (`Quest`)

Also added `PapyrusUiTestSupport.activeShortcutBindings()` so failures report the active keymap and every action id bound to the exact shortcut. Production behavior is unchanged.


### Ctrl+B shortcut dispatch root cause and 0.2.150 fix — 2026-08-14

The real CLion UI Robot tests reproduced the user-visible shortcut-only failure. Direct invocation of the platform `GotoDeclaration` action and the context-menu **Go to Declaration or Usages** path work, while physical Ctrl+B does not. The three physical shortcut tests fail consistently for a local member, a local script type, and vanilla `Quest`; raw `papyrus-lang` Definition tests remain green.

The shortcut dispatch trace localizes the failure above the Papyrus LSP layer:

- the editor owns focus and receives Ctrl+B;
- the active keymap resolves the shortcut and includes action ID `GotoDeclaration`;
- instead of performing `GotoDeclaration`, CLion performs `com.jetbrains.rider.actions.RiderActionUpdateInterceptor$RiderBackendCompositeAction`, which reports `Performed` without navigating;
- therefore this issue is not evidence of a PPJ/import, `Quest`, semantic Definition, or external-VFS failure.

API review changed the first proposed fix. A normal `ActionPromoter` is a public platform API, but Platform 262 calls `ActionUpdaterInterceptor.runUpdateSessionForInputEvent()` before falling back to ordinary promoter rearrangement. Because the observed CLion/Rider failure is produced by that interceptor, a promoter-only patch is not a reliable fix and is not present in 0.2.150. The Rider interceptor and remote-action APIs are internal and are not used by the plugin.

0.2.150 uses the platform's public editor-local shortcut mechanism and follows JetBrains' own wrapper pattern:

- `PapyrusEditorShortcutInstaller` subscribes through public `com.intellij.editorFactoryListener` / `EditorFactoryListener`;
- only `.psc` editors receive a local action;
- `ActionUtil.wrap(platformAction)` creates the editor-local wrapper; no plugin-owned Go To Declaration action is registered globally;
- the installer reuses `ActionManager.getAction(IdeActions.ACTION_GOTO_DECLARATION).getShortcutSet()`, so no Ctrl+B literal or global keymap mutation is introduced;
- public `AnAction.registerCustomShortcutSet(...)` attaches that shortcut to the editor content component; JetBrains documents that such an action does not need global registration;
- the local action re-checks `.psc`, is `DumbAware` like the platform declaration action, and delegates with public `ActionWrapperUtil.actionPerformed(...)`, which `AnAction` documentation recommends for action-to-action delegation;
- `EditorFactoryListener.editorReleased(...)` explicitly removes the local shortcut with public `AnAction.unregisterCustomShortcutSet(...)`; the action reference is stored in the editor user data only for that lifecycle.

The existing direct-action and raw LSP tests remain controls. The three physical Ctrl+B real-CLion tests remain the acceptance gate. 0.2.150 must not be called green until the user runs the Windows gate and all three pass.


### 0.2.150 Windows result and 0.2.151 follow-up — 2026-08-14

The user ran the full 0.2.150 gate: **59/59 UNIT PASS**, **20/20 PAPYRUS-LANG PASS**, **42/45 REAL CLION UI PASS**. The only failures are the three physical Ctrl+B Definition scenarios. Their dispatch traces are decisive: the editor has focus, the shortcut is resolved, and the action selected/performed is now `com.intellij.openapi.actionSystem.AnActionWrapper` rather than Rider's backend composite action, but the selected editor never changes. Therefore 0.2.150 fixed shortcut arbitration priority but not the final declaration invocation.

0.2.151 keeps the same editor-local shortcut registration and platform-derived `ShortcutSet`, but replaces `ActionUtil.wrap(platformAction)` with a small local `DumbAwareAction`. Once physical Ctrl+B selects that local action, it calls public `ActionManager.tryToExecute(platformAction, null, editorComponent, null, false)`. The `null` input event plus `now=false` is intentional: Platform 262 waits for focus to settle, rebuilds the editor-component `DataContext`, updates the exact supplied action through the non-input-event branch, and performs it with `inputEvent=null`. This is the closest public-API equivalent to the already-green direct `GotoDeclaration` control while staying outside the physical Ctrl+B arbitration path. The three physical Ctrl+B tests remain the acceptance gate.


### 0.2.151 user confirmation, 0.2.152 diagnostic result, and 0.2.153 import-to-import fix — 2026-08-14

The user confirmed that the 0.2.151 editor-local Ctrl+B interceptor works in real CLion. A diagnostic 0.2.152 build removed the interceptor completely; the user reported that Ctrl+B did not work, so the interceptor is now a proven compatibility requirement for the current CLion/Rider composite action stack. 0.2.153 is based on 0.2.151, not on the no-interceptor diagnostic branch.

A new real-world boundary was then identified: Ctrl+B from a project file into imported `RaceMenuBase.psc` succeeds, but from `RaceMenuBase.psc` on `Scriptname RaceMenuBase extends Quest` into imported `Quest.psc` fails. Both files appear correctly in Papyrus Projects -> Imports. Upstream Platform 262 source explains this: `LspClientImpl.isSupportedFile()` returns false before calling the plugin descriptor whenever `ProjectFileIndex.isInContent(file)` is false. An external Definition target can be opened, but native LSP features stop when that external/import file becomes the current document. Platform 262's LSP manager separately listens for `ContentRootEntity` changes and re-processes open files, including native `didOpen`, once they enter project content.

0.2.153 adds `PapyrusImportContentRootsService`. It reads the authoritative `ProjectInfoSourceInclude` graph from `papyrus/projectInfos`, selects only existing local non-remote imports, canonicalizes/deduplicates them, collapses nested roots, and attaches uncovered imports to the owning IntelliJ module as content roots using public `ModuleRootModificationUtil`. It persists ownership only for exact roots that the plugin itself added; pre-existing user/IDE roots are never adopted and are never removed. Stale plugin-owned roots are removed on later project-info synchronization, and disabling Papyrus clears plugin-owned roots. `PapyrusProjectsService` now refreshes project info after LSP initialization and on every `papyrus/projectsUpdated` notification even if the tool window has never been opened.

Regression coverage adds one unit test for local/import filtering plus nested-root collapse and two real-CLion scenarios: direct `GotoDeclaration` and physical Ctrl+B from imported vanilla `Quest.psc` (`extends Form`) to imported vanilla `Form.psc`. The expected expanded Windows gate is **60 UNIT + 20 PAPYRUS-LANG + 47 REAL CLION UI**. The source-only handoff environment cannot run Gradle because binary `gradle-wrapper.jar`, vendored VSIX, and offline test dependency bundles are intentionally excluded; authoritative verification remains the user's `gradlew.bat test` environment.


### 0.2.154 External Libraries model — 2026-08-14

The user confirmed the 0.2.153 import-to-import navigation scenario works, then rejected its project-model side effect: Papyrus dependencies appeared as top-level project content roots. 0.2.154 therefore replaces `PapyrusImportContentRootsService` with `PapyrusImportLibraryService`. Local non-remote imports are synchronized into one managed module library (`Papyrus Imports`) as `OrderRootType.SOURCES`, keeping them out of project content. The first 0.2.154 runtime check then exposed that IntelliJ does not display an ordinary source-only library in External Libraries; 0.2.156 addresses that presentation rule with a dedicated library type. No migration of 0.2.153 content entries is performed by request; the user will remove those entries manually.

Because Platform 262 native LSP rejects library-source active documents, `PapyrusGotoDeclarationHandler` handles only Papyrus import files outside project content. It sends `textDocument/definition` directly to the running papyrus-lang client and returns PSI targets. Project-content `.psc` files return null from this handler and remain on native LSP, preventing duplicate Definition providers. The physical Ctrl+B interceptor from 0.2.151 is unchanged. UI tests 46-47 now require Quest/Form imports to be library sources and not content before testing direct and physical shortcut navigation.

### 0.2.156 External Libraries visibility fix — 2026-08-14

The user confirmed the 0.2.154 declaration chain works (`project -> import -> import`) but reported that External Libraries is empty. Upstream Platform code explains the mismatch: an ordinary library is considered external using `LibraryType.DEFAULT_EXTERNAL_ROOT_TYPES`, which contains only `CLASSES`, while Papyrus imports are intentionally attached only as `SOURCES`. 0.2.156 introduces a dedicated `PapyrusImportLibraryType` whose external roots are exactly `SOURCES`, and the managed 0.2.154 library is retagged to that type during normal synchronization. The directories are not duplicated as `CLASSES`. The unit gate grows by one library-type regression test to **61 UNIT**; PAPYRUS-LANG remains 20 and REAL CLION UI remains 47, with the import-to-import test strengthened to require a source-external library type.

### 0.2.156 External Libraries naming — 2026-08-14

The user confirmed `External Libraries -> Papyrus Imports` is visible, but root folders were presented only by filesystem leaf names (`Scripts`, `scripts`, `Source`, etc.). 0.2.156 adds a `ProjectViewNodeDecorator` that changes presentation only for exact managed Papyrus import roots and reuses `PapyrusProjectsPresentation.formatIncludeLabel`, keeping labels consistent with the Papyrus Projects tool window.

The upstream `papyrus-lang` Skyrim SE PPJ lists `.\Source\Scripts` in both `<Imports>` and `<Folders>`, so project-local source/import overlap is canonical rather than an error. The managed external library now excludes any import path covered by a local non-remote source include. This removes entries such as the user's own `src: src` from External Libraries without changing PPJ semantics or papyrus-lang resolution. A dedicated unit test covers the `Data: Scripts` / `racemenu: scripts` labels; expected UNIT count is 62.


### 0.2.158 guarded PPJ reload and visible error state — 2026-08-14

The user reported that an invalid PPJ import could collapse the entire papyrus-lang Projects graph until IDE restart and that the Projects Refresh button only re-read `papyrus/projectInfos` rather than reloading PPJ content. Upstream `WorkspaceManager` reloads all projects on PPJ `didSave`, while `ProjectManager.UpdateProjects(ReloadProjects)` clears existing hosts before constructing the replacement graph. 0.2.158 therefore removes PPJ files from native IntelliJ LSP document synchronization and makes reload explicitly guarded.

Project-local PPJ VFS changes now mark Projects as dirty without touching the server. Refresh discovers all real project-local PPJs, validates XML, upstream-style `@Variable` expansion, local Import directories, and Folder directories, then sends exactly one PPJ `didSave` because upstream reloads the whole workspace for any PPJ save. The service waits for `papyrus/projectsUpdated`; there is no fixed completion timeout. Multiple explicit reloads are tracked by generation so stale confirmations cannot overwrite a newer dirty/validation state. Created/deleted PSC changes use the same guarded reload instead of the raw upstream watched-file reload path, but automatic source-tree reloads never restart a still-busy LSP process.

`PapyrusProjectsService` now preserves the last successful snapshot during dirty/reload/error states. The Projects tab shows phase-specific summary/details and explicitly says when the previous project configuration is being displayed. Validation errors include the PPJ relative path and the original/resolved bad Import or Folder. Reload completion is event-driven: `RELOADING` persists until `papyrus/projectsUpdated` arrives. There is no elapsed-time restart heuristic. If the user explicitly presses Refresh again while the previous reload is still unconfirmed, the current PPJs are revalidated and only then is the Papyrus LSP restarted via the public client-manager lifecycle. Nine unit regressions cover valid variable/remote-import expansion, missing imports, missing source folders, malformed XML, unresolved/cyclical/duplicate variables, a missing Imports section, and an invalid PPJ XML namespace. A new REAL CLION UI regression saves an invalid PPJ, verifies the live server graph and last-known-good tree survive, checks the visible validation error, restores the file, presses guarded Refresh, and requires event-confirmed recovery without IDE restart. Expected gate: **72 UNIT + 20 PAPYRUS-LANG + 48 REAL CLION UI**.

### 0.2.157 Windows result and 0.2.158 test stabilization — 2026-08-14

The user ran the 0.2.157 gate. BUILD and PAPYRUS-LANG were green. UNIT had one race in `WindowsKillOnCloseJobTest`: the child PID file could exist briefly before its contents were visible, so the test parsed an empty string. 0.2.158 waits for a non-empty parseable PID instead of mere file existence; production Job Object code is unchanged. REAL CLION UI reached the new PPJ reload test but timed out before the invalid-PPJ step because earlier compile tests intentionally create project-local PPJs and therefore leave the guarded Projects status `DIRTY`. 0.2.158 explicitly performs a guarded Refresh and waits for the real `papyrus/projectsUpdated`-confirmed READY baseline before exercising invalid PPJ validation/recovery. Production reload completion still has no guessed timeout.


## 0.2.160 — PPJ watcher uses standard IntelliJ VFS_CHANGES
- Replaced the `VFS_CHANGES_BG` subscription with the long-standing public `VirtualFileManager.VFS_CHANGES` + `BulkFileListener` route.
- The callback stays lightweight: classify PPJ/PSC/FLG events, mark PPJ dirty, and schedule guarded reload work; no polling or custom filesystem watcher.
- UI regression now proves that a real VFS event for `runtime.ppj` is observed before asserting DIRTY, so VFS delivery and Projects state are diagnosed separately.
- Expected gate remains 72 UNIT + 20 PAPYRUS-LANG + 48 REAL CLION UI.


## 0.2.163 — immutable validated PPJ workspace, cold-start guard, explicit errors

0.2.161 is the user-confirmed green baseline. 0.2.163 closes the remaining PPJ safety gaps without sending editable `.ppj` documents through native LSP synchronization. Successful validation now materializes a private immutable PPJ workspace under the JetBrains system directory. Local Import/Folder/Script/Output paths are expanded and made absolute before the snapshot is published, so relocating the PPJ into the private workspace does not change project semantics. The Papyrus LSP initializes against that private workspace and answers later `workspace/workspaceFolders` requests with the same validated root.

This removes the validation/read TOCTOU window: the server never rereads the mutable project PPJ for an approved generation. A user edit after validation can only make the editable configuration DIRTY; it cannot alter the immutable bytes already selected for the server. A separate editable-project revision is tracked so a late edit cannot be accidentally turned back into READY by a server restart.

Cold start now runs the same preflight. If the current PPJ is invalid, the last persisted validated generation is reused. If no validated generation exists yet, the server starts against a safe empty PPJ workspace instead of discovering the invalid project file. The validation error remains visible while the fallback graph is used.

Projects validation errors are now self-identifying (`ERROR: PPJ validation failed: ...`) and include the concrete cause, e.g. `import directory does not exist`, followed by the original Import and resolved path. Details use a wrapping text area with a zero minimum width so long Windows paths no longer impose an 850 px HTML-label width on the tool window.

Regression coverage adds immutable-path materialization, validated workspace-folder override, an explicit visible `ERROR:` assertion, private-workspace assertion, and LSP restart while the real PPJ remains invalid. Expected UNIT count increases from 72 to 75; PAPYRUS-LANG remains 20 and REAL CLION UI remains 48.
