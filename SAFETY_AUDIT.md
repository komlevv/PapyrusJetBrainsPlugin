# Papyrus JetBrains Plugin safety audit — current baseline


Current source baseline: **0.2.147**; latest user-verified green baseline: **0.2.146**. It carries forward the plugin-owned fixes for all 24 submitted CLion inspection screenshots and all three earlier parity stages, then adds Stage 4 bounded PPJ task discovery/compiler-output navigation without widening the compile destination policy. The latest user-verified full runtime gate is **0.2.146 — 59/59 UNIT, 16/16 PAPYRUS-LANG, 39/39 REAL CLION UI = 114/114**. 0.2.126 keeps the naming and safe Rename persistence coverage from 0.2.125; vendor/generated artifacts remain untouched. `INSPECTION_AUDIT.md` is the authoritative per-screenshot disposition. Safe Rename, bounded explicit Pyro compilation, the native `Papyrus Project` Run Configuration, and the opt-in Build Project bridge remain VERIFIED; Stage 4 task discovery/matcher remains verified by the 0.2.146 full Windows gate. The active target is CLion 2026.2.1 / IntelliJ Platform 262. Legacy `dev.papyrus.intellij...` plugin/configuration/state identifiers are intentionally retained only as compatibility IDs; they do not indicate an IntelliJ IDEA runtime target.

0.2.141 changes diagnostics verification only. It adds no `publishDiagnostics` interception, no LSP cache observer, and no production diagnostics branch. Raw server coverage still verifies incremental invalid → valid → invalid push diagnostics directly. REAL CLION I35 now mirrors the same semantic edits with incremental `if → Debug.Trace(...) → if` replacements and compares the same native `HighlightSeverity.ERROR` position before and after the valid state, avoiding any assumption about parser error ranges for unrelated malformed syntax. Production safety boundaries, document-sync compatibility, Windows Job Object containment, and compile path validation are unchanged.

0.2.142 changed only UI-test startup readiness, but its combined `wizardVisible || isProjectOpened()` condition introduced a first-run race: Platform 262 can report an initialized/showing project before CLion displays its modal `Open Project Wizard`, allowing the harness to skip wizard handling. No production behavior was changed.

The explicit Compile Project action and opt-in Build Project bridge write process text to `Papyrus Projects | Output`; the native Run Configuration uses the IDE's standard Run console and `KillableProcessHandler` lifecycle. 0.2.138 retains the 0.2.134 conversion of the Output tab to the native console surface and attaches the same read-only, project-bounded compiler hyperlink filter to both console paths. Advanced Pyro package/zip/anonymize/events/remotes and debugger behavior remain held.

0.2.136 changed no production write boundary and fixed only the test startup race. 0.2.137 did not widen any write boundary and hardened the language-host process lifecycle through the exact Platform 262 GeneralCommandLine preparation/process-creation hooks. On Windows a JDK-only guardian starts first and waits on a unique gate; the guardian is assigned to a Job Object configured with JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE before the gate is signaled, and only then may it start the real Papyrus host, which inherits the job. If job creation/configuration/assignment or gate signaling fails, the real host is never allowed to start outside containment. Raw server-integration hosts use the same guarded fail-closed Job Object boundary. Before Starter launches CLion, the UI-test JVM assigns itself to a process-lifetime kill-on-close job, so the IDE is contained from creation even if the controller dies during startup. CLion is additionally assigned to a per-session PID-scoped job for deterministic normal cleanup, so failed or terminated tests do not depend on JUnit cleanup alone to tear down the IDE and its Papyrus host.

This file describes the **current safety model**, not historical incidents.

## 0.2.147 status-threading safety review

0.2.147 changes no write, process, network, or compiler boundary. `PapyrusScriptStatusService.currentModificationStamp()` now uses `FileDocumentManager.getCachedDocument()` instead of `getDocument()`. The status service only needs the cached document to observe unsaved editor modification stamps; if no document exists, it falls back to `VirtualFile.getModificationStamp()`. This avoids loading a document from a pooled thread and therefore avoids the IntelliJ Platform read-lock assertion without adding a blocking read action.

## 0.2.146 compile regression safety review

0.2.146 changes no runtime write/process boundary. It only converts the checked `URISyntaxException` from the server-test guardian code-source URL to the method's existing `IOException` contract, so the 0.2.145 narrowed cleanup semantics remain intact.

## 0.2.145 lint cleanup safety review

0.2.145 changes no write boundary. Rename still rejects `documentChanges`, unresolved/non-project targets, non-writable documents, malformed or insert/delete edits, non-exact identifier replacements, overlapping edits, stale document stamps, and empty usable plans before any write command runs. The Rename refactor only extracts those checks into smaller helpers.

Windows host containment remains fail-closed. If guardian startup/Job assignment fails, process-tree and Job cleanup still run for `IOException`, `RuntimeException`, and `Error`; cleanup failures are attached as suppressed exceptions and the original failure type is rethrown. Runtime staging still publishes only an immutable fingerprint directory under the owned staging root and treats `FileAlreadyExistsException` as the expected concurrent-winner case. Compile snapshot deletion and optional Creation Kit INI reads remain non-fatal by design, but failures are now visible in debug logs.

## 1. Primary invariant

Passive/background Papyrus language tooling must treat external game/tool/source inputs as **read-only**.

The plugin must not silently modify:

- the pinned vendored `papyrus-lang` VSIX and its extracted IDE-cache copy;
- Skyrim SE/AE or Creation Kit installation files;
- Papyrus compiler files/flags/INI inputs;
- import-only Papyrus dependency source trees;
- MO2 or other mod-manager directories;
- arbitrary files returned by LSP workspace edits.

This invariant is the reason unrestricted Pyro and debugger features remain disabled even though a narrow project-local compile path is verified and exposed through two explicit IDE entry points.

## 2. External read-only inputs

The active language-service runtime may read:

- embedded/extracted bundled VSIX host/runtime/resources;
- Skyrim SE/AE / Creation Kit installation;
- configured Creation Kit INI files;
- compiler assembly/executable inputs;
- flags file;
- source/import paths resolved by the upstream language server;
- supported Windows Registry install-path keys.

Registry access is read-only. Missing/invalid values are treated as not found; the plugin does not write Registry keys.

## 3. Allowed write domains

### 3.1 User editor/project writes

Normal IDE editor actions may modify the file the user is actively editing, for example:

- typing;
- comments;
- pair insertion;
- indentation;
- Live Template expansion.

These are normal explicit editor operations, not background external-source mutation.

### 3.2 IDE configuration/state

The plugin may write its own IDE settings/state, such as the **Settings → Languages & Frameworks → Papyrus** values and enabled state.

Disabling the language service stops Papyrus LSP clients but does not modify game/vendor-VSIX/source inputs.

### 3.3 IDE system/cache runtime staging

The built plugin embeds the unchanged pinned VSIX. On first use the plugin may extract that payload into a versioned IDE system/cache directory after validating its pinned SHA-256. Required empty runtime-layout directories that are not representable in the upstream archive, currently `extension/pyro/remote`, may be created inside that IDE/build-owned extracted copy. To satisfy the upstream host's adjacent-runtime dependency behavior, the host runtime may then be staged into a separate content-fingerprinted cache copy.

The staging layer:

- only accepts host executables inside the declared source runtime tree;
- writes into the IDE-owned cache target;
- reuses identical content fingerprints;
- does not patch the source vendor VSIX or game installation.

Unit coverage verifies both reuse and rejection of an out-of-tree host executable.

### 3.4 Explicit Generate Project action

**Papyrus: Generate Skyrim SE Project Files** is the only currently active feature designed to create a new project directory.

Current constraints:

- user invocation is required;
- output must be a new direct child directory of the selected parent;
- existing targets are not overwritten;
- unsafe Windows folder names are rejected;
- generation inside the configured Skyrim installation is rejected;
- staging is used before final placement;
- tests verify generated content and external-input integrity.

Coverage: unit generator tests + real-IDE Order 22.

### 3.5 Native Papyrus New Project wizard

**File → New Project → Papyrus** is an explicit project-creation write path. It uses the platform `DirectoryProjectGenerator` extension consumed by CLion and runs only for creation of a new project. The IDE owns the project root creation and project-settings flow; the Papyrus step then creates only Papyrus-owned content under that new root:

- `skyrimse.ppj`;
- `Source/Scripts/`;
- `.run/Papyrus_Compile.run.xml`;
- `.run/Papyrus_Skyrim_SE_AE.run.xml`.

The generator requires a resolved Skyrim SE/AE installation, rejects a project root inside the game installation, uses `CREATE_NEW` for generated files, and never rewrites existing `.idea` state. The live new-project `PapyrusProjectSettings` service is set to `buildSystem=papyrus` and `projectFile=skyrimse.ppj`; this automatic opt-in is limited to the new Papyrus project being created. Existing projects remain `buildSystem=intellij` unless the user explicitly changes the project setting.

Coverage: generator/descriptor unit contracts plus real-CLion I33 candidate.

### 3.6 Bounded Papyrus project compilation

The bounded compiler is VERIFIED from 0.2.115. The language server does not perform the write; the plugin launches the bundled pinned `pyro.exe` only after validating the selected project file. The same compiler is reachable through **Papyrus: Compile Project**, the native **Run Configuration: Papyrus Project**, and, only after explicit project selection, **Build Project / Ctrl+F9**. Since 0.2.134 the explicit action may first discover real `.ppj` files under the IDE project root and ask which project to compile; discovery itself grants no additional Pyro capability and the chosen file must still pass the same preflight.

Required preflight:

- selected file is an existing non-link `.ppj` under the canonical IDE project root;
- only Skyrim SE/AE is accepted;
- root `Output` is required, cannot use variable/environment expansion, must resolve under the canonical project root, and may not traverse an existing filesystem link;
- `Anonymize`, `Package`, and `Zip` cannot be enabled;
- `Variables`, `Packages`, `ZipFiles`, and every pre/post Build/Import/Compile/Anonymize/Package/Zip event element are rejected;
- HTTP(S) remote Import/Folder paths and expandable Import/Folder paths are rejected.

After validation the shared compile service writes the exact validated declaration-free PPJ content to a temporary file beside the original, passes that snapshot to Pyro, and passes the absolute validated project-local directory as `--output-path`. The temporary PPJ is deleted after the process returns; the original PPJ is never rewritten. Because Pyro executes the validated snapshot rather than reopening the original, a post-validation change to the original PPJ cannot widen that build invocation. A user-invoked build may create or replace `.pex` files in that configured Output directory, which is the intended bounded write. Game, Creation Kit, compiler, flags, and dependency/source imports remain read-only inputs. Neither entry point exposes archive/package/zip/anonymize/remote/event options. The explicit action sends stdout/stderr to **Papyrus Projects | Output**; the Run Configuration uses the IDE's standard Run console. 0.2.138 keeps the read-only compiler-output matcher and accepts raw upstream or observed Pyro-logger-wrapped absolute/project-relative compiler diagnostics only after removing the logger envelope and applying canonical project-boundary checks; IntelliJ VFS lookup remains deferred until navigation and performs a bounded synchronous markDirtyAndRefresh along the validated target path. It creates navigation hyperlinks only for canonical, existing, non-link files under the canonical IDE project root, so compiler diagnostics cannot turn external game/Creation Kit/import paths into one-click navigation targets. Both entry points share one per-project execution gate, so a second build is rejected while another Papyrus build is active.

Coverage: compile-safety unit contracts + real-IDE Order 30 VERIFIED; 0.2.116 verified the shared execution gate and native Run Configuration in I31; 0.2.117 verified opt-in/default build selection and I32 for Build Project.

### 3.7 Build/test-owned paths

Development/test code may write only to build-owned or isolated fixture locations, including:

- Gradle `build/` output;
- isolated Starter/Driver project fixture outside the source Git checkout;
- test-owned synthetic source/project directories;
- temporary files created by tests and restored/cleaned according to the test contract.

The installed CLion directory is treated as read-only test input. IDE discovery reads `product-info.json` only; it never edits the installation or `.vmoptions`. Only a Windows 64-bit launch entry is accepted. Offline JUnit/Starter/Driver JARs are copied into project-owned `third_party/papyrus-test-deps/`; the build no longer requires writing helper dependencies under `C:/Program Files/JetBrains/...`. These JARs are test-only and are excluded from the plugin distribution.

## 4. Active read-only actions

### Creation Kit Wiki Search

May launch an external URL only after explicit user invocation. It does not edit sources. The real-IDE test replaces browser launch with a test seam and verifies selection behavior.

### View Assembly

Requests upstream assembly and opens a **read-only in-memory** `.disassemble.pas` file.

The real-IDE test verifies:

- TextMate assembly typing/highlighting;
- virtual file and document are read-only;
- source `.psc` is unchanged;
- no `.pas` file is materialized in the project.

### Projects tree / script navigator

Reads cached LSP `projectInfos` and opens only the exact file path reported by the language server. It does not create an index or rewrite source files.

### Script status / status bar / output transcript

Read-only UI. The Output tab stores a bounded in-memory transcript for the current plugin session and does not create a second language server or write a log into external source/game locations.

## 5. LSP mutation boundary

### Papyrus Rename — bounded and VERIFIED

`Refactor | Rename` is handled by `PapyrusRenameHandler`, not by the generic platform LSP Rename applier. The server is a semantic oracle only: it resolves the definition and proposes `WorkspaceEdit` text edits. The plugin alone decides whether those edits may become IDE document writes.

A target is writable only when all of the following hold:

- it resolves to an existing local `.psc` file;
- its canonical path is under the IDE project base/root;
- `ProjectFileIndex` reports it as project content;
- both the VFS file and disk path are writable;
- it is not under the configured Creation Kit / game installation;
- it is not under the bundled papyrus-lang vendor/cache;
- it is not a remote source;
- it is not an Import-only LSP source. The upstream Skyrim template reports `.\Source\Scripts` twice (as both `<Import>` and `<Folder>`), so an exact most-specific Source/Folder include proves project ownership; paths reported only as Imports remain read-only, including dependencies physically stored under the project root.

Additional fail-closed rules:

- any `documentChanges` / resource operation is rejected;
- unresolved/non-file URIs are rejected;
- malformed, out-of-range, overlapping, insertion, or deletion edits are rejected;
- every accepted `TextEdit` must replace text equal to the selected identifier, case-insensitively, and its `newText` must equal the exact name the user requested;
- new names must be identifier-shaped and must not be Papyrus keywords;
- a definition resolving to `ScriptName` is blocked because safely renaming a script also requires coordinated `.psc` file rename, which is not implemented;
- a modification-stamp change while definition/rename data is being calculated aborts the operation;
- if **one** target is unsafe, the **entire** rename is rejected before any document edit is applied;
- safe edits are applied in one IDE `WriteCommandAction`, preserving normal Undo behavior;
- every user-triggered blocked or failed rename shows an explicit dialog containing the reason and target path when known.

Before asking for a new name, the plugin requests `textDocument/definition`. If the selected symbol resolves to Creation Kit/import-only/external source such as a base `Quest` type, Rename is blocked immediately. The final workspace-edit validation remains mandatory even after definition preflight succeeds.

Blocking `projectInfos`, definition, and rename waits run on a pooled thread. UI validation, dialogs, and application return to the EDT only after the background request is complete, so a slow server no longer freezes the IDE UI for the request timeout.

The generic LSP Rename customizer remains disabled so it cannot bypass this firewall.

### Code Actions — defensively disabled

`codeActionProvider` remains forced off and the platform Code Action customizer is disabled. Tagged `papyrus-lang v3.3.0-prerelease.1` does not register a Code Action handler, so this is currently a defensive future-server boundary rather than a missing tagged-upstream feature. Any future Code Action support must reuse the project-bound edit checks and separately audit executable commands.

### `workspace/applyEdit` — advertised false and physically rejected

The Papyrus client capability continues to set `workspace.applyEdit=false`. In addition, `PapyrusSafeServerNotificationsHandler` intercepts standard server `workspace/applyEdit` requests and returns `applied=false` without delegating to the IDE workspace-edit applier. This protects against a buggy or future server that ignores the advertised capability. Safe Rename is client-initiated and never depends on this server-to-client mutation channel.

## 6. Advanced Pyro safety hold

The whole upstream Pyro task engine is **not** enabled. PPJ features outside the bounded compile subset can execute pre/post commands, initialize remote imports, anonymize compiled scripts, create packages/archives/ZIPs, and use independent log/temp/archive destinations. Those behaviors remain blocked by the preflight or are not exposed by the action.

Do not widen the enabled subset without a concrete use case and a separate write-destination audit.

## 7. Debugger safety hold

The tagged VSIX includes debugger install, attach, and breakpoint surfaces. The JetBrains IDE plugin does **not** expose executable debugger behavior.

A `PapyrusAttach` run-configuration type exists only as inert metadata/future integration scaffolding:

- it advertises SSE/AE attach metadata;
- its editor states that attach is disabled;
- configuration validation always returns the safety-gate error;
- `getState()` returns `null`;
- no debugger process/connection is started.

Debugger-support installation is not registered.

## 8. Language-service process boundary

Starting the Papyrus language server is an intentional plugin runtime action, but its launch inputs are resolved read-only from configured/Registry/game/bundled-VSIX data.

The plugin may:

- start/stop/restart the upstream host;
- send LSP notifications/requests;
- maintain IDE-memory caches/transcripts.

It must not use language-service startup as a reason to alter the external host/compiler/game source tree.

## 9. Test-harness safety

Real-IDE tests use the installed CLion binaries as read-only input and run against a disposable fixture outside the plugin Git checkout. This avoids parent-VCS prompts and prevents UI-test state from being added to the source repository.

Test-only support code:

- is gated by `papyrus.ui.integration.test`;
- is not registered as a normal plugin action/service entry point;
- exists to inspect/control the spawned test IDE safely;
- must not bypass the production feature when the scenario is intended to test that feature.

Physical-input tests target the explicitly foregrounded spawned IDE and restore their fixture contents.

## 10. Current safety verification

Current user-verified 0.2.146 CLion gate:

- 59 unit tests;
- 16 raw exact-upstream server tests;
- 39 real-CLion tests;
- aggregate: **114/114 PASS**.

Especially relevant safety gates include:

- project-generator path/overwrite constraints;
- immutable runtime staging;
- Windows Job Object kill-on-close contract: a real child JVM must die when its owning job handle closes; the language host and the UI-test CLion use separate PID-scoped jobs;
- bundled-VSIX extraction/traversal tests now cover normalization when the upstream archive omits the empty `pyro/remote` directory;
- read-only Registry boundary;
- launch-readiness fail-closed behavior;
- View Assembly source/no-disk-output assertions;
- Wiki Search browser-launch test seam;
- Generate Project cancel and unsafe-target assertions;
- status/output no-LSP-restart assertions;
- explicit disable/no-restart assertions;
- script navigator exact-LSP-path behavior;
- 0.2.133 remote parity coverage uses a pre-populated cache under the disposable server-test workspace; it does not enable Pyro remote fetching or write to the vendored VSIX cache;
- 0.2.133 I37 creates/deletes only `Source/Scripts/ProjectRefreshProbe.psc` through test-only VFS support under the disposable real-CLion project, then requires refreshed `projectInfos` and removes the probe;
- 0.2.134 task-discovery unit contracts reject links/temp compile snapshots and expose only project-local PPJs; I38 selects a discovered PPJ but still proves output through the bounded project-local compile path;
- 0.2.138 compiler-diagnostic contracts preserve the tagged upstream line shape and add the exact observed Pyro `COMPILATION FAILED:` envelope; I39 requires a real compiler failure, no successful PEX, and project-local source navigation through the matcher;
- 0.2.112 verifies the hardened blocked/successful Rename flows and mutation firewall;
- 0.2.115 verified the compile preflight plus a real bundled-Pyro compile that leaves project/Creation Kit `.psc` sources unchanged, deletes its temporary validated PPJ snapshot, and places the resulting `.pex` under project-local Output.
- 0.2.116 verified I31 through IDE Run Configuration infrastructure. 0.2.117 verified I32, which first verifies the ordinary-project default remains the IDE-default mode, explicitly switches the project to `papyrus`, then invokes standard Build Project and verifies the same write boundary.
- 0.2.124 keeps the generator contract on platform `DirectoryProjectGenerator`; I33 verifies `Papyrus` in the real CLion heavyweight New Project popup. The harness accepts CLion's first-run Toolchains dialog only through Driver UI and does not synthesize or write CLion-private toolchain configuration.

## 11. Limits of the current guarantee

The safety model is strong for the current **active SSE/AE safe surface**, but it is not a proof about features that are not enabled.

In particular, there is no current safety guarantee for future implementations of:

- advanced Pyro task/package/archive/zip/event/remote behavior outside the bounded Compile Project subset;
- debugger plugin installation;
- debugger attach/breakpoints;
- future Code Actions/commands;
- Fallout 4 / Skyrim LE write/debug flows.

Those features remain held/out-of-scope precisely so the existing safety guarantee is not weakened. Papyrus Rename is VERIFIED inside the bounded active-write model at 0.2.112, and bounded Compile Project is VERIFIED at 0.2.115. The verified 0.2.117 `ProjectTaskRunner` is an additional opt-in entry point to that same compile boundary. Its registration alone does not alter ordinary projects because `canRun` is false unless the project setting explicitly selects Papyrus build. The New Project wizard runtime introduced in 0.2.118 is the only path that opts a just-created Papyrus project into that build mode automatically.

0.2.143 is test-harness-only. It restored `DialogUiComponent.okButton`, but the user reproduced the same wizard hang, disproving the button-locator hypothesis as the root cause. 0.2.144 fixes the actual 0.2.142/0.2.143 race by restoring independent wizard discovery before project readiness, matching the full-green 0.2.138 ordering. Production plugin behavior, process containment, compile boundaries, and debugger behavior are unchanged.
