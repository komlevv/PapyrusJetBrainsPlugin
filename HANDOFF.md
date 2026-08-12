# Papyrus JetBrains Plugin handoff — 0.2.146 green baseline

> Documentation boundary: `README.md` is the public/user-facing GitHub landing page. Version-by-version history, regression notes, test gates, inspection/lint details, local build paths, offline dependency layout, implementation notes, and other maintainer-only operational context belong here or in the dedicated living technical documents, not in `README.md`.

0.2.146 is the current user-verified green baseline. Full Windows gate: **59/59 UNIT + 16/16 PAPYRUS-LANG + 39/39 REAL CLION UI = 114/114**.

0.2.145 performed the high-confidence code-quality cleanup; 0.2.146 only fixes the checked `URISyntaxException` compile regression in the server-integration guardian classpath conversion. Production behavior, write boundaries, process containment, and debugger hold are unchanged.

Target runtime/test IDE: **CLion 2026.2.x / IntelliJ Platform 262**. Verified installation: **CLion 2026.2.1 / CL-262.9437.136**.

## Current source state

- Current source version: **0.2.146**.
- 0.2.126 is the all-24 inspection cleanup. It does not add a Papyrus feature; it refactors plugin-owned warnings, updates deprecated/DevKit APIs, adds descriptor/live-template i18n, and documents every submitted screenshot in `INSPECTION_AUDIT.md`. Vendor/generated artifacts remain untouched.
- Authoritative user-reported Windows gate: **0.2.146 — 59/59 UNIT, 16/16 PAPYRUS-LANG, 39/39 REAL CLION UI = 114/114**.
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
- test INI: `Y:/dev/PapyrusTest/IntellijPapyrus.ini`.

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
