# Papyrus JetBrains Plugin roadmap — current only

This document contains only **remaining decisions/work**. Completed per-version incident history belongs in source control and archived test reports, not in the living roadmap.

## Current baseline

- Current source: **0.2.146**.
- 0.2.126: plugin-owned cleanup for all 24 submitted CLion inspection screenshots; no intended Papyrus feature change.
- Current authoritative gate: user-verified **59 unit / 16 exact-upstream server / 39 real-CLion = 114/114** on **0.2.146**.
- Hardened semantic Papyrus Rename is VERIFIED at the 0.2.112 baseline.
- The broad non-debugger feature port/parity burn-down is complete and verified in 0.2.138. Stage 1 closed Completion/Definition/References semantic edges, Stage 2 the real-IDE document-sync/Syntax Tree/diagnostics gap, Stage 3 Project Infos/script-status parity, and Stage 4 safe task discovery/compiler-output navigation.
- Bounded safe-Pyro compile, the native `Papyrus Project` Run Configuration, and opt-in `Build Project / Ctrl+F9` routing are VERIFIED. 0.2.124 keeps the target IDE on CLion 2026.2.1, removes IDEA-specific Starter/build metadata, and ports New Project to `DirectoryProjectGenerator`.
- Before that gate, run `gradlew.bat printIdeTarget`; it must report CLion `CL`, branch 262, and a `*64.exe` Windows launcher.
- If the existing `262.8665.337` Driver/Starter set shows a binary/API failure against CLion `262.9437.136`, replace `driver-sdk`, `ide-starter-driver`, `ide-starter-junit5`, and `ide-starter-squashed` together with one matching 262 build. Do not mix their versions.
- Upstream reference: `joelday/papyrus-lang v3.3.0-prerelease.1`.
- Active target: Skyrim Special Edition / Anniversary Edition on CLion 2026.2 / IntelliJ Platform 262.

- The 0.2.115 gate is fully green and verifies the validated PPJ snapshot, real bundled Pyro, real Creation Kit compiler, project-local output, and source immutability.

The current safe/read-only SSE/AE feature set is in good parity shape. See `PORT_STATUS.md` for the detailed matrix.

## 1. Upstream parity decisions already made

### 1.1 Automatic Welcome — intentionally skipped

**Status: INTENTIONAL DIFFERENCE.**

Tagged upstream automatically shows Welcome on first install and around its historical update threshold. The user explicitly chose not to reproduce automatic opening in the IDE.

Keep:

- manual **Papyrus: Show Getting Started Help**.

Do not add automatic first-install/update Welcome unless the user reverses this decision.

### 1.2 JetBrains-IDE-specific polish already completed

These are not strict upstream parity requirements and should not be used to inflate upstream-parity claims:

- explicit `Sources` / `Imports` project hierarchy;
- bounded large-source grouping;
- richer status tooltip;
- cached `projectInfos` **Navigate...** script search.

All are currently verified by the existing test suite.

## 2. Active parity coverage burn-down

Debugger is explicitly excluded from this burn-down. Work proceeds in independent green stages:

The active non-debugger parity burn-down was completed in the **verified 0.2.138 baseline** and remains green in the user-verified 0.2.146 gate. Stage 4 adds project-local `.ppj` task discovery/selection and project-bounded Papyrus compiler hyperlinks without widening the compile write boundary. After the fully green 0.2.138 Windows gate, remaining work is optional hardening/release readiness or an explicit future decision to enter a safety-held area.

Fallout 4 and Skyrim LE remain outside the active SSE/AE scope. Debugger installation/attach/breakpoints remain explicitly deferred.

## 3. Safety-held and bounded write features

These are genuine upstream capabilities, but they are **not normal next-step parity work**. Enter them only after an explicit user decision and a dedicated safety design.

### 3.1 Pyro compile / IDE build integration

**Status: BOUNDED COMPILE VERIFIED; NATIVE RUN CONFIGURATION VERIFIED IN 0.2.116; OPT-IN BUILD PROJECT VERIFIED IN 0.2.117; SAFE TASK DISCOVERY + COMPILER MATCHER VERIFIED IN 0.2.138; ADVANCED PYRO REMAINS SAFETY HOLD.**

The verified bounded compiler intentionally does not reproduce the whole VS Code task-definition surface. The explicit **Papyrus: Compile Project** action, the verified **Run Configuration: Papyrus Project**, and the verified opt-in **Build Project** bridge share one fail-closed compile service:

- selected `.ppj` must be a real file under the IDE project root;
- active game is forced to SSE/AE;
- `Output` must resolve inside the project and may not traverse a filesystem link;
- package, zip, anonymize, Variables, all pre/post build/import/compile/package/zip/anonymize events, and HTTP(S) remote Import/Folder paths are rejected;
- compiler/game/import locations remain read-only inputs;
- the validated absolute project-local Output is passed back to Pyro as `--output-path`, so the PPJ cannot redirect compiled `.pex` files elsewhere after preflight;
- the explicit action and Build Project bridge capture process output in **Papyrus Projects | Output**; the native Run Configuration uses the IDE's standard Run console and lifecycle;
- 0.2.134 discovers only real, non-link `.ppj` files under the IDE project root, labels them `Compile Project (<relative ppj>)`, and routes the chosen file through the same preflight rather than exposing raw Pyro task options;
- 0.2.134 installs the exact tagged-upstream compiler line shape as a JetBrains console filter, but creates hyperlinks only for canonical real files under the IDE project root; external compiler/game/Creation Kit/import paths remain non-clickable through this matcher.

0.2.116 added the first IDE-native execution entry point:

- `Run | Edit Configurations... | Papyrus Project`;
- one project-file field with `$PROJECT_DIR$` support and a file chooser;
- standard IDE Run console, Stop, and Rerun lifecycle through `CommandLineState` + `KillableProcessHandler`;
- generated Papyrus projects receive `.run/Papyrus_Compile.run.xml`;
- the explicit action and Run Configuration share one project-level execution gate, so two Pyro builds cannot race in the same project.

**Verified 0.2.117 design:** `PapyrusProjectTaskRunner` is a standard IntelliJ Platform extension but remains completely opt-in. `PapyrusProjectSettings.buildSystem` now defaults to the product-neutral `ide` value; legacy persisted `intellij` is normalized on load, so ordinary projects keep native IDE behavior. Papyrus-generated projects select `buildSystem=papyrus`; existing projects can switch under **Settings → Build, Execution, Deployment → Build Tools → Papyrus**. When selected, module build tasks are handled by the same safe compile service; no second compiler path is created.

**0.2.124 CLion fixture-cleanup candidate:** global Papyrus language-service settings remain under **Settings → Languages & Frameworks → Papyrus**. `PapyrusDirectoryProjectGenerator` is registered through `com.intellij.directoryProjectGenerator`, the supported non-Java IDE project-generator extension in the 262 platform used by CLion. The generator creates only `skyrimse.ppj`, `Source/Scripts`, and Papyrus `.run` configurations under the new project and opts only that project into `Papyrus (Pyro)`. I33 resolves the live CLion New Project action by presentation text and verifies `Papyrus` in the heavyweight popup via Driver `popup().jBlist(...)`; it intentionally does not enter project creation. Starter separately accepts CLion's first-run **Open Project Wizard** through Driver when the isolated test config has no saved toolchain.

Still held:

- arbitrary Pyro task-definition options;
- package/BSA/BA2 and ZIP creation;
- anonymization;
- PPJ pre/post events;
- remote import download/cache initialization through Pyro;
- arbitrary temp/log/archive destinations.

Do not widen this subset until there is a concrete user need and a separate write-boundary audit.

### 3.2 Install debugger support

**Status: SAFETY HOLD.**

Required before enabling:

- exact files copied/overwritten;
- game-directory vs mod-manager-directory policy;
- version validation;
- rollback/cancel behavior;
- tests against isolated fake game/mod-manager roots only.

### 3.3 Attach debugger / breakpoints

**Status: SAFETY HOLD.**

The current `PapyrusAttach` configuration is deliberately inert and rejects execution.

Required before enabling:

- protocol/process lifecycle audit;
- port and target selection rules;
- no implicit game launch or install;
- deterministic disconnect/cleanup;
- real-IDE debugger tests in an isolated environment.

### 3.4 LSP Rename

**Status: VERIFIED at 0.2.112.**

Approved design remains intentionally small:

- `Refactor | Rename` is the IDE entry point;
- `papyrus-lang` supplies semantic definition/reference information through `textDocument/definition` and `textDocument/rename`;
- the generic platform LSP Rename applier stays disabled for Papyrus;
- the plugin validates definition ownership before showing the rename dialog;
- every returned target must be an existing writable project-owned `.psc`;
- every returned text edit must replace the selected identifier text with exactly the requested new identifier;
- invalid/keyword new identifiers are rejected before `textDocument/rename`;
- `ScriptName` rename is blocked until a separate coordinated `.psc` file-rename design exists;
- Creation Kit/game, import-only dependency, remote, vendor/cache, non-project, read-only, malformed, unresolved, overlapping, insert/delete, and resource/document operations fail closed;
- one unsafe target rejects the entire operation before any edit is applied;
- safe edits are applied as one IDE write command and remain undoable;
- blocking LSP waits execute on a pooled thread, with dialogs/application returning to the EDT only after the response is ready;
- unsolicited server `workspace/applyEdit` is rejected by a notifications wrapper even if a server ignores the advertised `workspace.applyEdit=false` capability;
- user-triggered blocked/failure paths remain explicit and include the reason and path when known.

0.2.112 passed the complete **40/11/29** gate, so this block is closed. Do not add more Rename complexity unless a real defect or coordinated `ScriptName`/file-rename requirement appears.

### 3.5 LSP Code Actions

**Status: NOT IN TAGGED UPSTREAM SERVER / DEFENSIVELY DISABLED.**

`papyrus-lang v3.3.0-prerelease.1` registers no Code Action handler. The generic platform Code Action surface stays disabled so a future/different server build cannot introduce edit/command execution without a separate audit. If upstream later adds Code Actions, reuse the Rename write-boundary policy for edits and separately audit executable commands.

## 4. Multi-game generalization

### 4.1 Fallout 4

**Status: OUT OF SCOPE until explicitly requested.**

Would require, at minimum:

- game-specific settings/registry resolution;
- launch/runtime configuration;
- FO4 Creation Kit INIs/flags;
- project generation;
- FO4-specific snippet parity;
- raw server and real-IDE matrix coverage;
- debugger decisions separately.

### 4.2 Skyrim LE

**Status: OUT OF SCOPE until explicitly requested.**

Would require its own install/INI/compiler/project-generation and test matrix.

## 5. Optional JetBrains-IDE-only hardening

These are not upstream parity requirements and should be undertaken only when they solve a real observed problem.

### 5.1 Deterministic performance/scale tests

**Status: OPTIONAL, NOT UPSTREAM PARITY.**

Tagged upstream does not provide the proposed IDE tree/navigator scale tests. Current code already has deterministic bounds for:

- project-tree direct-vs-grouped threshold;
- navigator result cap;
- lazy Projects-tree behavior in the real target IDE.

Add a larger synthetic scale suite only if performance becomes an observed issue or before generalizing to much larger game/source configurations.

### 5.2 Breadcrumbs or additional symbol UI

**Status: OPTIONAL / PLATFORM-DEPENDENT.**

Do not build synthetic PSI solely to mimic a VS Code presentation. Prefer public IntelliJ Platform/LSP APIs if the target platform exposes a clean integration point in a future version.

### 5.3 Additional runtime coverage

Potential non-blocking gaps:

- dedicated manual Getting Started UI scenario;
- dedicated Registry-only real-IDE startup scenario;
- dedicated `papyrus/projectsUpdated` notification/cache-refresh scenario.

These improve confidence but are not known product defects.

## 6. Release-readiness work distinct from feature parity

The current project is a local/offline development build. Before treating it as a distributable general JetBrains IDE plugin, separately address:

- developer-machine default paths in settings/build configuration;
- vendor/plugin metadata;
- dependency acquisition/packaging strategy;
- supported IDE/platform version range;
- first-run configuration UX for machines without the current local directory layout;
- reproducible CI/build environment if desired.

This work is not upstream Papyrus feature parity.

## 7. Iteration rule

For every future iteration:

1. identify whether the change is upstream parity, safety work, out-of-scope generalization, test hardening, or JetBrains-IDE-only polish;
2. update `PORT_STATUS.md` before claiming a parity change;
3. keep all six living `.md` documents consistent;
4. preserve the external-input read-only invariant unless the feature is an explicitly approved bounded write path;
5. add the strongest appropriate test layer without weakening existing real-IDE gates;
6. run `gradlew.bat test` on the target Windows environment and use `build/papyrus-test-report.txt` as the authoritative result.

- CLion LSP compatibility: `plugin.xml` declares both `com.intellij.modules.lsp` and `com.intellij.modules.ultimate`.
