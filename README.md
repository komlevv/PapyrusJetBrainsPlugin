> **Status: ALPHA** — active development; APIs, behavior, and packaging may still change before the first stable release.
> **Target IDE:** CLion **2026.2.x** on IntelliJ Platform **262**. Verified target: **CLion 2026.2.1 / CL-262.9437.136**.

# Papyrus Language for JetBrains IDEs

Current source baseline: **0.2.146**. The latest user-reported Windows gate is fully green: **59/59 UNIT + 16/16 PAPYRUS-LANG + 39/39 REAL CLION UI = 114/114** on the verified CLion target above.

0.2.145 was a behavior-preserving code-quality cleanup based on an offline javac-AST review of plugin-owned Java; 0.2.146 corrected the single checked-exception compile regression exposed by the first Windows build after that cleanup. Production feature scope and safety boundaries are unchanged.

The active game scope is **Skyrim Special Edition / Anniversary Edition**. Fallout 4 and Skyrim LE remain out of current scope. Debugger work is explicitly deferred.

All 24 submitted IDE inspection screenshots and the later code-quality pass are tracked in `INSPECTION_AUDIT.md`. Vendored upstream content and generated Starter/CLion output are not modified merely to satisfy local inspections.

## Scope

This plugin ports the Papyrus tooling needed for **Skyrim Special Edition / Anniversary Edition** from `joelday/papyrus-lang v3.3.0-prerelease.1` to CLion 2026.2 / IntelliJ Platform 262 using platform LSP, TextMate, editor, tool-window, status-bar, and action APIs.

Current scope is intentionally narrower than the complete VS Code extension:

- Skyrim SE/AE is the active game target.
- Fallout 4 and Skyrim LE are not current targets.
- Safe Skyrim SE/AE project compilation through bundled Pyro is enabled through the explicit Compile Project action, a native Papyrus Project Run Configuration, and an opt-in Build Project bridge. Ordinary projects keep the IDE build system unless the user explicitly selects Papyrus (Pyro); unrestricted Pyro tasks/package/zip/events/remotes and Papyrus debugging remain on safety hold.
- Papyrus semantic Rename is enabled only through a project-bound safety handler; generic LSP Code Actions remain disabled.
- Automatic first-install/update Welcome is intentionally skipped; manual Getting Started help remains available.

See `PORT_STATUS.md` for the authoritative feature-parity matrix and `SAFETY_AUDIT.md` for write boundaries.

## Current verified feature set

### Language/editor

The current **0.2.146** green Windows baseline has real-IDE coverage for:

- `.psc` TextMate recognition and Papyrus syntax highlighting;
- completion, including member completion after `.`;
- diagnostics;
- go to declaration / definition;
- Find Usages through LSP references;
- Quick Definition preview;
- Quick Documentation / hover;
- Parameter Info / signature help;
- File Structure / document symbols;
- Papyrus folding;
- line comments;
- auto-closing/surrounding pairs;
- Papyrus indentation rules;
- representative common Papyrus Live Templates using real editor input and physical Tab traversal.

0.2.112 verifies hardened **Refactor | Rename** end-to-end: the LSP server supplies semantic definition/reference information, while the plugin owns the write policy, exact-edit validation, explicit blocked/error UI, unsolicited `workspace/applyEdit` rejection, and one undoable IDE write command without blocking the UI thread.

The exact tagged VSIX is now a **vendored build input**. Gradle verifies its pinned SHA-256, embeds the unchanged VSIX into the plugin JAR, and the plugin extracts it into a versioned IDE system-cache directory on first use. That extracted bundled copy is the TextMate/LSP/resource source of truth for `.psc`, `.ppj`, and `.disassemble.pas`. No user VSIX path is required in Settings.

### Papyrus Projects and custom protocol

`Papyrus Projects` uses the upstream `papyrus/projectInfos` snapshot and provides:

- explicit `Sources` and `Imports` groups;
- source/import provenance;
- lazy include loading;
- bounded grouping for very large source collections;
- `[remote]` marking for remote imports;
- exact navigation to LSP-reported script paths;
- `Navigate...` search over the cached project snapshot without expanding the tree.

The upstream custom protocol endpoints currently verified against the real tagged server are:

- `papyrus/projectInfos`;
- `textDocument/scriptInfo`;
- `textDocument/assembly`;
- `textDocument/syntaxTree` at raw-server level.

`syntaxTree` currently has no dedicated IDE user-facing view; it is retained as protocol coverage rather than advertised as a UI feature.

### Actions and status UX

Current SSE/AE client surfaces include:

- **Papyrus: Search Creation Kit Wiki**;
- **Papyrus: View Assembly** — opens a read-only in-memory `.disassemble.pas` editor;
- **File → New Project → Papyrus** — CLion-compatible platform directory-project generator that creates the bounded Skyrim SE/AE Papyrus project layout and opts that new project into `Papyrus (Pyro)` build;
- **Papyrus: Generate Skyrim SE Project Files** — explicit bounded project generation into a separate new folder;
- **Papyrus: Compile Project** — bounded bundled-Pyro compilation for a restricted SSE/AE `.ppj`; when invoked without a selected `.ppj`, it discovers real project-local `.ppj` files and offers upstream-style `Compile Project (<relative ppj>)` choices without exposing advanced Pyro options;
- **Run Configuration: Papyrus Project** — VERIFIED in 0.2.116 using the IDE Run/Stop/Rerun actions and the standard Run console while reusing the same safe compile preflight, command construction, snapshot, output boundary, and per-project execution gate;
- **Build Project / Ctrl+F9** — VERIFIED in 0.2.117, enabled only when the project-level Build Tools | Papyrus setting selects `Papyrus (Pyro)`; otherwise the runner returns `canRun=false` and native IDE behavior is untouched;
- `Papyrus Projects` tree and script navigator;
- unresolved and overridden-script status, including winning-file navigation for overridden scripts;
- active-editor-aware Papyrus status bar;
- missing-game and missing-compiler states;
- status click to the plugin-owned bounded `Papyrus Projects` Output tab transcript, now backed by a native JetBrains console with project-bounded clickable Papyrus compiler diagnostics;
- missing-state click to **Settings → Languages & Frameworks → Papyrus**;
- explicit enable/disable of the language service without modifying external game/source inputs;
- read-only Windows Registry fallback for the Skyrim SE/AE install path.

**Papyrus: Show Getting Started Help** is implemented and registered, but does not currently have a dedicated real-IDE scenario. Automatic Welcome is intentionally not implemented.

## Test status and coverage

### Current authoritative green gate

The user reported **0.2.146 green** on the target Windows/CLion environment:

| Layer         | Tests   | Result   | What it proves |
| ------------- | ------: | -------- | -------------- |
| UNIT          | 59      | PASS     | deterministic contracts, safety boundaries, process containment, Safe Rename, bounded Pyro compile/task discovery, compiler-output parsing, native Run Configuration, and opt-in Build Project selection |
| PAPYRUS-LANG  | 16      | PASS     | black-box behavior of the exact pinned upstream server/runtime, including semantic Completion/Definition/References, Rename, diagnostics, Project Infos, and source-resolution behavior |
| REAL CLION UI | 39      | PASS     | real CLion 2026.2.1 behavior through Starter/Driver, including editor/LSP features, diagnostics refresh, Project Infos/status, Safe Rename, bounded compile/run/build integration, compiler navigation, first-run Toolchains handling, and Papyrus New Project |
| **Total**     | **114** | **PASS** | aggregate feature-oriented verification |

The authoritative regression target is **59/16/39 = 114 tests**. 0.2.146 preserves the same feature coverage after the code-quality cleanup and its checked-exception compile correction.

The authoritative command is:

```bat
gradlew.bat test
```

The aggregate result is written to:

```text
build/papyrus-test-report.txt
```

### What “coverage” means in this project

There is currently **no JaCoCo or equivalent line/branch coverage instrumentation**, so no source-line coverage percentage is claimed.

Coverage is tracked per feature and per verification layer:

- **U** — unit/contract coverage;
- **S** — raw exact-upstream server coverage;
- **I** — real target-IDE runtime coverage;
- **INDIRECT** — exercised as part of another authoritative scenario but without a dedicated scenario;
- **HOLD** — intentionally disabled and tested/registered only as disabled where applicable.

The detailed matrix is maintained in `PORT_STATUS.md`.

## Test architecture

### Unit suite — 59 tests

The unit layer covers the behavior that should not depend on spawning the IDE or a real language server, including:

- project generation boundaries;
- Creation Kit Wiki target selection/escaping;
- tagged VSIX assembly grammar declaration;
- exact upstream indentation rules;
- common Live Template snapshot parity;
- safe plugin descriptor surface;
- project-tree presentation and large-source threshold;
- script navigator ranking/provenance/result cap;
- Creation Kit INI loading;
- configured/Registry install-path resolution;
- immutable host-runtime staging;
- launch/readiness classification;
- status-bar state and tooltip presentation;
- bundled-VSIX extraction, checksum handling, and archive traversal rejection;
- Papyrus Rename writable-project, import-only dependency, remote, Creation Kit, vendor-cache, content-root, file-type, and read-only policy;
- Safe Rename identifier validation, exact replacement-shape validation, `ScriptName` detection, and unsolicited `workspace/applyEdit` rejection;
- safe Pyro compile validation for project-local output, SSE-only scope, and rejection of packaging/events/remotes/variables;
- project-local `.ppj` task discovery ordering/temp-snapshot/symlink boundaries and exact Papyrus compiler problem-line parsing, including captured stderr prefixes.

### Raw papyrus-lang suite — 16 tests

This layer starts the exact tagged upstream host and verifies:

1. initialization and dynamic References registration;
2. completion;
3. hover;
4. signature help;
5. diagnostics;
6. definition;
7. references;
8. document symbols;
9. semantic `textDocument/rename` workspace edits across project scripts;
10. `textDocument/syntaxTree` buffer synchronization;
11. `projectInfos` + `scriptInfo` + `assembly` custom protocol behavior;
12. semantic Completion scope/declaration matrix;
13. Definition inherited/case-insensitive/unresolved edges;
14. References same-name type isolation, declaration-origin, case-insensitive usage, and unresolved behavior;
15. `projectInfos`/`scriptInfo` local source-vs-import provenance and duplicate-identifier precedence;
16. pre-populated remote-import cache metadata plus semantic definition resolution into the cached remote source, without network download.

### Real CLion UI suite — 39 ordered scenarios

The current suite verifies, in order:

1. `.psc` TextMate recognition;
2. completion;
3. diagnostics;
4. go to declaration;
5. folding;
6. Find Usages;
7. Quick Definition;
8. Quick Documentation;
9. Parameter Info;
10. File Structure;
11. representative TextMate scopes;
12. comment actions;
13. smart typing pairs;
14. indentation;
15. `if` Live Template;
16. `function` Live Template;
17. `propertyFull` linked-variable Live Template;
18. Projects tree navigation and lazy/bounded source presentation;
19. overridden-script status/navigation;
20. View Assembly read-only in-memory editor and assembly grammar;
21. Creation Kit Wiki action behavior without launching a real browser in the test;
22. safe Generate Project boundaries and cancel behavior;
23. active-editor-aware status and richer tooltip;
24. compiler-missing status;
25. status click → Output without LSP restart;
26. missing status → Settings, explicit disable, and no restart while disabled;
27. cached `projectInfos` script navigator → exact LSP-reported file;
28. Rename of an external/Creation Kit symbol is blocked with an explicit user-visible error and no source changes;
29. Rename of a project-owned Papyrus symbol uses LSP semantics and changes its project references;
30. safe `.ppj` compile invokes bundled Pyro and produces a non-empty `.pex` only under project-local output while project and Creation Kit source files remain unchanged.
31. native `Papyrus Project` Run Configuration executes the same safe-Pyro pipeline through the IDE Run infrastructure, produces project-local `.pex` output, releases the shared execution gate, removes the validated snapshot, and leaves project/Creation Kit source unchanged.
32. standard `Build Project` invokes the Papyrus task runner only after explicit project-level `Papyrus (Pyro)` selection, then produces project-local `.pex` through the same safe pipeline while leaving sources unchanged.
33. `File | New Project` resolves CLion's live New Project action without hard-coding a product-specific ID and verifies that the real heavyweight New Project popup exposes `Papyrus` through the registered directory-project generator.
34. live `textDocument/syntaxTree` follows unsaved replacement and deletion through the real CLion `Document` → compatibility `didChange` bridge, then returns to the restored buffer.
35. live editor diagnostics move after an unsaved insertion, clear after an unsaved fix, reappear after an unsaved deletion, and restore to the original invalid fixture.
36. unresolved script status appears through the native editor notification and active-editor status-bar tooltip without an overriding-file action.
37. a real VFS `.psc` create/delete inside the project propagates through `workspace/didChangeWatchedFiles` → upstream `papyrus/projectsUpdated` → refreshed cached `projectInfos`.
38. invoking **Papyrus: Compile Project** without a selected PPJ discovers multiple real project-local PPJs and executes the explicitly selected upstream-style compile task through the same bounded Pyro service.
39. a real broken Papyrus source produces a compiler problem line, the project-bounded matcher resolves it, and navigation opens the exact project source location while no successful `.pex` is produced.

The suite is deliberately integration-heavy: a green run is the authoritative user-visible gate, not a substitute mock implementation.

## Safety model

External Papyrus inputs are treated as read-only by passive language tooling. In particular, the plugin must not silently modify:

- the embedded/read-only upstream VSIX payload and its extracted IDE-cache copy;
- Skyrim / Creation Kit installation files;
- import-only Papyrus dependency sources;
- MO2/mod-manager directories;
- compiler inputs.

Allowed writes are explicit and bounded:

- normal user editor changes to project-owned files;
- Papyrus Rename text edits only when **every** target is an existing writable `.psc` inside IDE project content and the canonical project root, and every edit is an exact replacement of the selected identifier with the requested new identifier; any unsafe/malformed target rejects the whole operation before edits are applied;
- explicit **Compile Project** output only under a validated project-local directory; the selected `.ppj` must be inside the project and may not enable package/zip/anonymize, build events, variables, or remote imports/folders;
- unsolicited server `workspace/applyEdit` requests are rejected by a client-side notifications guard rather than merely discouraged through capabilities;
- IDE settings/state;
- the IDE system/cache runtime staging directory;
- an explicitly requested **new** generated project child directory;
- build/test-owned directories and isolated test fixtures.

See `SAFETY_AUDIT.md` for the exact current boundaries.

## Configuration

Open **Settings → Languages & Frameworks → Papyrus** and configure:

- language-service enabled state;
- Skyrim SE/AE / Creation Kit install path;
- optional Papyrus compiler path override;
- Creation Kit INI paths;
- ambient project name;
- Papyrus flags file name.

If the configured game path is missing, the runtime resolver can fall back to the supported Bethesda Windows Registry install-path entry. Registry access is read-only.

## Offline development baseline

The current development build is intentionally tied to the local offline environment unless overridden by Gradle properties. The checked-in defaults currently expect:

- CLion 2026.2.x on platform branch 262; the current resolved installation is expected to be 2026.2.1 / CL-262.9437.136;
- Java target 25;
- local Kotlin compiler;
- the pinned tagged VSIX copied into `vendor/papyrus-lang/v3.3.0-prerelease.1/papyrus-lang-vscode.vsix`;
- local Skyrim SE/AE / Creation Kit installation;
- local Papyrus test INI;
- project-local offline Starter/Driver/JUnit JARs under `third_party/papyrus-test-deps/`.

This is a development/private-build baseline, not a Marketplace-ready distribution configuration.

IDE discovery is product-neutral inside the 262 branch. Resolution order is `-PpapyrusIdeHome`, legacy `-PpapyrusIdeaHome`, `PAPYRUS_IDE_HOME`, then installed JetBrains products. The default product is `CL` and default platform branch is `262`; patch updates inside 2026.2 are selected by the highest `buildNumber`. The Windows launch entry must be 64-bit and must resolve to a `*64.exe` launcher plus matching 64-bit `.vmoptions`. Use `gradlew.bat printIdeTarget` to see the exact resolved installation. A future platform branch such as 263 is intentionally not accepted silently; set `-PpapyrusIdeBranch=263` only after a compatibility audit.

## Vendored upstream and offline test dependencies

The source handoff archive intentionally omits large third-party binaries. Before building locally:

```text
vendor/papyrus-lang/v3.3.0-prerelease.1/papyrus-lang-vscode.vsix
```

must contain the exact **complete, unmodified** `v3.3.0-prerelease.1` VSIX with SHA-256. The full archive is retained, including Fallout 4/debug payloads and the complete Pyro distribution; only the bounded Pyro compile subset is currently active, so later scope expansion does not require a new vendor format:

```text
c4cf68d74471d4646b1c7dcff36f30293b507ebee215cc931cef051a0f8766db
```

Offline test JARs must be copied, preserving their existing `driver/` and `starter/` subdirectories, into:

```text
third_party/papyrus-test-deps/
```

Gradle no longer reads `Y:/dev/PapyrusTools/unpack/extension` and no longer requires `lib/papyrus-test-deps` inside the IDE installation. Test-dependency JARs are compile/test-only and are not packaged into the user plugin. The built plugin JAR does include the vendored VSIX so installed runtime use remains self-contained.

Papyrus-specific SVGs copied from the pinned VSIX are used where the IDE has no equivalent Papyrus icon; ordinary folders/debugger/general IDE concepts continue to use standard IDE presentation.

## Living documentation

These files are the current sources of truth and should be updated together when behavior changes:

- `README.md` — current architecture, supported behavior, and test model;
- `PORT_STATUS.md` — authoritative feature parity and coverage matrix;
- `ROADMAP.md` — only remaining work/decisions;
- `SAFETY_AUDIT.md` — current write/read/process safety boundaries;
- `HANDOFF.md` — compact continuation state for the next iteration;
- `src/main/resources/papyrus-welcome.md` — user-facing Getting Started help bundled into the plugin.

Historical per-version incident logs are intentionally no longer kept in these living docs. Source control, archived source ZIPs, patches, and test reports are the history.
