# Papyrus JetBrains Plugin port status — 0.2.164 source

Current status: **ALPHA**. Active compatibility target: **CLion 2026.2.x / IntelliJ Platform 262**; verified target: **CLion 2026.2.1 / CL-262.9437.136**.

The current source is **0.2.164**. The latest user-confirmed green Windows baseline is **0.2.163: 75/75 UNIT + 20/20 PAPYRUS-LANG + 48/48 REAL CLION UI**. The expanded 0.2.148 investigation gate is **59 UNIT + 20 PAPYRUS-LANG + 45 REAL CLION UI**; its three physical Ctrl+B scenarios intentionally reproduced the CLion/Rider shortcut-dispatch defect. The user's 0.2.150 gate kept 59/59 UNIT and 20/20 PAPYRUS-LANG green but failed exactly the three physical Ctrl+B scenarios because the editor-local `AnActionWrapper` was performed without navigation. 0.2.151 replaced that wrapper execution strategy and the user has since confirmed the resulting Ctrl+B path works in real CLion. The diagnostic 0.2.152 removal of the interceptor failed; 0.2.153 retains 0.2.151 and targets imported-source LSP continuity.


## 0.2.164 Projects Refresh busy state

The Projects toolbar disables `Refresh` immediately after a user click and changes its text to `Refreshing...` for the full `VALIDATING` / `RELOADING` / `SYNCHRONIZING` lifecycle. Terminal success and validation/server error states restore the normal `Refresh` label and enabled state. The service-level lock remains the authoritative duplicate-request guard, so disabling the Swing control is not relied upon for correctness.

## 0.2.158 guarded PPJ reload lifecycle

PPJ files are no longer native LSP documents. Opening a PPJ may still start the Papyrus client, but IntelliJ does not automatically send PPJ `didOpen`/`didChange`/`didSave`, preventing a malformed editor save from directly invoking papyrus-lang's destructive `ReloadProjects` path. The Projects Refresh button now discovers project-local PPJs, validates their XML, `@Variable` expansion, local imports, and source folders, and only then sends one explicit `textDocument/didSave` trigger. Completion is driven by the upstream `papyrus/projectsUpdated` notification, not a guessed timer.

The Projects service keeps its previous successful snapshot during dirty, validating, reloading, and error states. Project-local PPJ VFS changes mark the view dirty rather than reloading automatically; created/deleted PSC source-tree changes are converted to the same guarded reload path instead of direct `workspace/didChangeWatchedFiles`, but those automatic source-tree reloads never restart a still-busy LSP process. The Projects tab now displays the exact phase and actionable details such as the PPJ file, original Import/Folder value, resolved missing path, XML parse failure, unresolved variable, or server synchronization failure. A reload remains `RELOADING` until the real `papyrus/projectsUpdated` event arrives; there is no guessed timeout. If the user explicitly presses Refresh again while that event is still outstanding, current PPJs are validated again and only then is the Papyrus LSP restarted through the public client-manager lifecycle. Expected gate: **72 UNIT + 20 PAPYRUS-LANG + 48 REAL CLION UI**.

## Baseline

- Plugin source version: **0.2.164**.
- 0.2.126 is the all-24 plugin-source inspection cleanup; expected runtime behavior is unchanged.
- Authoritative Windows gate: user-verified **0.2.146 — 59/59 UNIT, 16/16 PAPYRUS-LANG, 39/39 REAL CLION UI = 114/114**.
- Hardened LSP-semantic **Refactor | Rename** is VERIFIED at the 0.2.112 baseline.
- Tagged `v3.3.0-prerelease.1` safe SSE/AE client parity is effectively complete. Bounded Pyro compile, the native Papyrus Project Run Configuration, and opt-in IDE Build Project integration are VERIFIED. 0.2.126 keeps the active target on CLion 2026.2.1, generalizes IDE discovery, and replaces the Java-only New Project language generator with the platform directory-project generator.
- **Papyrus: Compile Project**, **Run Configuration: Papyrus Project**, and the opt-in **Build Project** bridge use the same restricted compile subset: project-owned `.ppj`, SSE/AE only, project-local output forced by the client, and no package/zip/anonymize/events/remotes/variables. 0.2.134 adds only project-local PPJ discovery/selection and project-local compiler hyperlinks; it does not expose raw Pyro task-definition options.
- Active target: **CLion 2026.2.1 / CL-262.9437.136**; compatibility lock: platform branch **262**.
- Upstream reference: **`joelday/papyrus-lang v3.3.0-prerelease.1`**.
- Active game scope: **Skyrim Special Edition / Anniversary Edition**.

- 0.2.115 full gate verified the bundled-Pyro XML-declaration compatibility snapshot and the project-local write boundary end-to-end.



## 0.2.156 External Libraries naming

The user confirmed `Papyrus Imports` is now visible under External Libraries. 0.2.156 keeps the 0.2.155 source-only library model and import-to-import navigation unchanged, but decorates exact managed import-root directory nodes with the same `PapyrusProjectsPresentation.formatIncludeLabel(...)` text used by the Papyrus Projects tool window. This replaces ambiguous leaf-only names such as `Scripts`, `scripts`, and `Source` with labels such as `Data: Scripts`, `racemenu: scripts`, and `PapyrusUtil AE SE - Scripting Utility Functions: Source`.

Papyrus project files commonly list their own source directory in both `<Imports>` and `<Folders>`; the upstream `skyrimse.ppj` does this for `.\Source\Scripts`. Such local source/import overlap is now excluded from the managed external library, so a project-local `src: src` entry is not duplicated under External Libraries. Expected gate: **62 UNIT + 20 PAPYRUS-LANG + 47 REAL CLION UI**.

## 0.2.155 source-only library visibility

**Status: IMPLEMENTED, pending Windows acceptance gate.**

Real use of 0.2.154 confirmed that `project -> import -> import` Go To Declaration works and that the import files are correctly classified as library sources, but the `External Libraries` node remained empty. IntelliJ's default `LibraryType.DEFAULT_EXTERNAL_ROOT_TYPES` contains only `OrderRootType.CLASSES`; therefore an ordinary named library with only `SOURCES` roots is intentionally filtered out of the External Libraries project-view node.

0.2.155 keeps the same source-only module library and import-only Go To Declaration bridge, but assigns the managed library a dedicated `PapyrusImportLibraryType`. Its external root types are exactly `OrderRootType.SOURCES`. Existing plugin-managed 0.2.154 libraries are retagged in place on the next synchronization; import directories are not duplicated as `CLASSES` and are not promoted back to project content. The real-CLion import test now also requires the managed library type to report `SOURCES` as an external root. Expected gate for 0.2.155: **61 UNIT + 20 PAPYRUS-LANG + 47 REAL CLION UI**.

## 0.2.154 Papyrus imports as External Libraries

**Status: IMPLEMENTED, pending Windows acceptance gate.**

0.2.153 proved the import-to-import failure and restored navigation by promoting Papyrus import directories to IntelliJ content roots. Real use confirmed the navigation fix, but those dependency directories then appeared as top-level project folders. 0.2.154 corrects the project model: local, non-remote Papyrus imports from `papyrus/projectInfos` are attached to the owning module as source roots of one managed module library named `Papyrus Imports`, so they are dependencies rather than project content. Runtime use confirmed the declaration chain but also showed that IntelliJ does not render an ordinary source-only library in the **External Libraries** node; 0.2.156 fixes that visibility rule with a dedicated library type.

Platform 262 native LSP intentionally rejects library-source documents because they are not `ProjectFileIndex.isInContent(...)`. To preserve `Ctrl+B` / Go To Declaration from an imported `.psc`, 0.2.154 registers a narrow public `GotoDeclarationHandler`: it returns `null` for project-content files so native LSP remains authoritative there, and only for Papyrus import library sources does it send `textDocument/definition` directly to the already-running papyrus-lang client and convert the result to PSI navigation targets. This also avoids duplicate declaration targets between the bridge and native LSP.

No 0.2.153 content-root migration is performed. Users who tested 0.2.153 must remove those dependency content roots manually once; 0.2.154 will not adopt or delete them. The existing 47 REAL CLION UI gate now requires vanilla `Quest.psc` / `Form.psc` to be library sources and explicitly **not** project content before exercising both direct Go To Declaration and physical Ctrl+B.

## 0.2.153 imported-source content roots

**Status: IMPLEMENTED, pending Windows acceptance gate.**

Real use exposed a second Definition boundary after the 0.2.151 shortcut fix: `project.psc -> RaceMenuBase.psc` worked, but `RaceMenuBase.psc -> Quest.psc` failed when both targets were Papyrus imports. Platform 262 native LSP rejects files outside `ProjectFileIndex.isInContent(...)` before it asks `PapyrusLspClientDescriptor.isSupportedFile(...)`. An LSP Definition response can still open an external target, which explains why the first hop succeeds, but once that imported file is the active document the native LSP feature pipeline no longer owns it.

0.2.153 mirrors **local, non-remote Papyrus import directories reported by `papyrus/projectInfos`** into the IntelliJ module content model. Nested/duplicate import roots are collapsed. Existing user/IDE content roots are never adopted as Papyrus-owned. Only roots actually added by the plugin are persisted in the plugin project state and are eligible for later removal when the import graph changes or Papyrus support is disabled. Root changes use the public `ModuleRootModificationUtil` / `ModifiableRootModel` APIs. Platform 262 already listens for `ContentRootEntity` changes and re-processes open files, so existing LSP clients receive the normal `didOpen` path once an imported editor becomes project content.

The 0.2.151 editor-local Ctrl+B interceptor is retained unchanged. The diagnostic 0.2.152 no-interceptor experiment failed in user testing and is not the baseline. New real-CLion acceptance coverage verifies both direct `GotoDeclaration` and physical Ctrl+B from imported vanilla `Quest.psc` to imported vanilla `Form.psc`. Expected expanded gate: **60 UNIT + 20 PAPYRUS-LANG + 47 REAL CLION UI**.

## 0.2.151 Ctrl+B direct-action re-entry

**Status: USER-CONFIRMED for the reported Ctrl+B behavior; full expanded gate status is separate.**

0.2.150 successfully moved physical Ctrl+B ahead of Rider's backend composite action: the dispatch trace now shows the Papyrus editor-local wrapper being selected and performed. The same gate also proved that `ActionUtil.wrap(GotoDeclaration)` is not sufficient in this CLion/Rider shortcut context because no navigation follows. 0.2.151 keeps the editor-local custom shortcut, but its local `DumbAwareAction` calls public `ActionManager.tryToExecute(...)` on the registered platform `GotoDeclaration` action with a `null` input event, the editor content component as context, and no keyboard-specific action place. With `now=false`, Platform 262 waits for focus to settle, rebuilds the `DataContext` from the editor component, updates the exact supplied action without the input-event dispatcher, and performs it with `inputEvent=null`. This happens after keyboard shortcut arbitration has completed and does not replace the global keymap, hard-code Ctrl+B, invoke Rider internals, or change LSP Definition semantics.

## 0.2.150 Ctrl+B shortcut routing

**Status: IMPLEMENTED, pending Windows acceptance gate.**

The language server and direct platform `GotoDeclaration` action are already covered independently. The remaining real-IDE defect was isolated to CLion/Rider keyboard shortcut arbitration: physical Ctrl+B selected a Rider backend composite action instead of the working platform declaration action. 0.2.150 attaches an editor-local `ActionUtil.wrap(GotoDeclaration)` wrapper through the public `EditorFactoryListener` / `AnAction.registerCustomShortcutSet` APIs. The wrapper reuses the platform `GotoDeclaration` shortcut set and preserves the platform action lifecycle instead of manually forwarding `actionPerformed`. No global keymap entry is replaced or hard-coded. The three physical shortcut scenarios (local member, local script type, vanilla `Quest`) are the acceptance gate.

## Status vocabulary

- **VERIFIED** — implemented and exercised by an authoritative test appropriate to the feature, normally including the real target IDE for user-visible behavior.
- **IMPLEMENTED** — production implementation exists, but there is no dedicated authoritative runtime scenario for that exact surface.
- **INDIRECT** — behavior is exercised inside another real-IDE scenario rather than by a dedicated scenario.
- **INTENTIONAL DIFFERENCE** — upstream behavior exists, but the user explicitly chose a different IDE behavior.
- **SAFETY HOLD** — intentionally disabled/not registered because it can write, execute, install, or apply broad workspace edits outside proven boundaries.
- **OUT OF SCOPE** — upstream behavior belongs to Fallout 4 or Skyrim LE and is not part of the current SSE/AE target.
- **JETBRAINS-IDE-ONLY** — useful native polish beyond strict upstream client parity.

Coverage notation:

- **U** — unit/contract test;
- **S** — raw tagged `papyrus-lang` black-box test;
- **I#** — real target-IDE UI scenario/order number (CLion for the active target).

No source-line/branch percentage is available because JaCoCo or equivalent instrumentation is not configured.

## Parity summary

### SSE/AE read-only/editor parity

**High / effectively complete for the currently targeted safe surface.** Completion, diagnostics, navigation, references, Rename, hover, signature help, document symbols, TextMate behavior, folding, comments, pairs, indentation, common snippets/templates, project inspection, script status, assembly viewing, status UX, and project generation all have current implementation and feature-oriented verification. 0.2.115 verifies the bounded compile subset; 0.2.116 exposes it through native IDE Run Configuration infrastructure without claiming unrestricted Pyro parity.

### Safe SSE/AE VSIX client parity

The safe client surface is implemented with JetBrains-IDE-native adaptations. The only deliberate safe UX difference currently recorded is:

- automatic first-install/update Welcome → **INTENTIONAL DIFFERENCE / skipped by user**;
- manual Getting Started action remains **IMPLEMENTED**.

### Full upstream extension parity

**Not claimed by design.** Full parity would also require:

- unrestricted Pyro task/build features (package/zip/anonymize/events/remotes/custom task options);
- debugger support installation;
- debugger attach/breakpoints;
- Fallout 4 support;
- Skyrim LE support;
- their game-specific generation/settings/debug surfaces.

Those are either safety-held or outside current scope and must not be included in a misleading single “100% upstream parity” number. Tagged `papyrus-lang v3.3.0-prerelease.1` does not register a Code Action handler, so generic platform LSP Code Actions are defensively disabled rather than counted as a missing tagged-upstream feature.

## Feature and coverage matrix

### 1. Language and editor surface

| Feature                                      | Upstream basis                                | IDE state                                                                                                                                                                                                                                              | Status                 | Coverage                           |
| -------------------------------------------- | --------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------- | ---------------------------------- |
| `.psc` Papyrus language/TextMate grammar     | VSIX language + grammar                       | Pinned VSIX embedded in plugin JAR, extracted to IDE cache, loaded through TextMate                                                                                                                                                                    | VERIFIED               | I1, I11, 0.2.112 full gate         |
| `.ppj` Papyrus Project language/grammar      | VSIX `papyrus-project` language               | Tagged VSIX bundle; LSP language id `papyrus-project`                                                                                                                                                                                                  | IMPLEMENTED / INDIRECT | I23 opens real `.ppj`              |
| `.disassemble.pas` assembly language/grammar | VSIX `papyrus-assembly` language + grammar    | Tagged VSIX grammar used by in-memory assembly editor                                                                                                                                                                                                  | VERIFIED               | U assembly snapshot, I20           |
| Completion                                   | LSP                                           | Native IDE completion + Papyrus prefix customization                                                                                                                                                                                                   | VERIFIED               | S, I2                              |
| Diagnostics                                  | LSP                                           | Native diagnostics customization                                                                                                                                                                                                                       | VERIFIED               | S, I3, I35                         |
| Definition / Go to Declaration               | LSP                                           | Native navigation                                                                                                                                                                                                                                      | VERIFIED               | S, I4                              |
| References / Find Usages                     | LSP dynamic registration                      | Compatibility adaptation for IntelliJ Platform 262 + native Find Usages                                                                                                                                                                                | VERIFIED               | S, I6                              |
| Refactor / Rename                            | upstream `textDocument/rename`                | Custom IDE Rename handler asks LSP for semantic edits, preflights definition ownership, validates exact project-bound identifier replacements, rejects unsolicited `workspace/applyEdit`, then applies one IDE undoable command; LSP waits run off EDT | VERIFIED               | U + S + I28-I29, 0.2.112 full gate |
| Hover / Quick Documentation                  | LSP                                           | Native Quick Documentation                                                                                                                                                                                                                             | VERIFIED               | S, I8                              |
| Signature Help / Parameter Info              | LSP                                           | Native Parameter Info                                                                                                                                                                                                                                  | VERIFIED               | S, I9                              |
| Document Symbols / File Structure            | LSP                                           | Native File Structure                                                                                                                                                                                                                                  | VERIFIED               | S, I10                             |
| Quick Definition / Peek analogue             | Definition data                               | IDE implementation-view adapter                                                                                                                                                                                                                        | VERIFIED               | I7                                 |
| Folding                                      | upstream language behavior + platform adapter | Papyrus folding builder                                                                                                                                                                                                                                | VERIFIED               | I5                                 |
| Line comments                                | VSIX language configuration                   | Native comment action via TextMate configuration                                                                                                                                                                                                       | VERIFIED               | I12                                |
| Auto-close/surround pairs                    | VSIX language configuration                   | Native smart typing                                                                                                                                                                                                                                    | VERIFIED               | I13                                |
| Indentation                                  | VSIX language configuration                   | IDE enter handler matching upstream rules                                                                                                                                                                                                              | VERIFIED               | U exact-rule tests, I14            |
| Common Papyrus snippets                      | VSIX `snippets/papyrus/papyrus.json`          | IDE Live Templates                                                                                                                                                                                                                                     | VERIFIED               | U pinned snapshot, I15-I17         |
| Fallout 4 snippet set                        | VSIX `papyrus-fallout4.json`                  | Not part of SSE/AE target                                                                                                                                                                                                                              | OUT OF SCOPE           | —                                  |
| Open-buffer document sync                    | LSP text sync                                 | Native open/close + compatibility didChange bridge                                                                                                                                                                                                     | VERIFIED directly      | S + I34 + I35                      |
| Watched workspace/project changes            | upstream client watcher behavior              | VFS watcher bridge + project cache invalidation                                                                                                                                                                                                        | VERIFIED directly      | I37 real VFS create/delete         |

### 2. Upstream custom protocol and Papyrus client UX

| Feature                                  | Upstream basis                                                        | IDE state                                                                                                                                                    | Status                        | Coverage                                                                                       |
| ---------------------------------------- | --------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------- | ---------------------------------------------------------------------------------------------- |
| `papyrus/projectInfos`                   | custom request                                                        | Project service/cache + Projects tree + navigator                                                                                                            | VERIFIED                      | S incl. precedence/remote-cache, I18, I27, I37                                                 |
| `textDocument/scriptInfo`                | custom request                                                        | script-status service/editor notification                                                                                                                    | VERIFIED                      | S incl. duplicate-identifier precedence, I19, I23, I36                                         |
| `textDocument/assembly`                  | custom request                                                        | View Assembly action                                                                                                                                         | VERIFIED                      | S, I20                                                                                         |
| `textDocument/syntaxTree`                | custom request                                                        | typed client mapping; no user-visible tree UI; used as a direct live-buffer probe for the compatibility bridge                                               | VERIFIED protocol + real IDE  | S, I34                                                                                         |
| `papyrus/projectsUpdated`                | custom notification                                                   | invalidates Projects and script-status caches                                                                                                                | VERIFIED end-to-end           | I37 VFS create/delete → server notification → refreshed cached `projectInfos`                  |
| Projects view                            | VS Code `papyrus-projects` view                                       | `Papyrus Projects` tool window                                                                                                                               | VERIFIED                      | I18                                                                                            |
| Source/import provenance                 | upstream projectInfos fields                                          | explicit `Sources` / `Imports` hierarchy                                                                                                                     | JETBRAINS-IDE-ONLY / VERIFIED | U presentation, S local source/import precedence, I18                                          |
| Large-source bounded presentation        | not an upstream UI requirement                                        | lazy include/group presentation                                                                                                                              | JETBRAINS-IDE-ONLY / VERIFIED | U threshold, I18                                                                               |
| Script navigator                         | not in tagged VSIX                                                    | cached-projectInfos `Navigate...` dialog                                                                                                                     | JETBRAINS-IDE-ONLY / VERIFIED | U model, I27                                                                                   |
| Cached remote import resolution          | upstream projectInfos/source resolver                                 | reads already-cached remote source metadata/files; no download action is exposed                                                                             | VERIFIED read-only            | S pre-populated isolated remote cache                                                          |
| Unresolved script status                 | upstream scriptInfo/CodeLens UX                                       | native editor notification + active-editor status tooltip                                                                                                    | VERIFIED                      | I36                                                                                            |
| Overridden script status                 | upstream scriptInfo/CodeLens UX                                       | editor notification + winning-file navigation                                                                                                                | VERIFIED                      | I19                                                                                            |
| Search Creation Kit Wiki                 | upstream command                                                      | IDE action                                                                                                                                                   | VERIFIED                      | U action rules, I21                                                                            |
| View Assembly                            | upstream command                                                      | read-only in-memory IDE editor                                                                                                                               | VERIFIED                      | I20                                                                                            |
| Generate Skyrim SE/AE Project            | upstream command/templates                                            | bounded IDE project generation action                                                                                                                        | VERIFIED                      | U generator, I22                                                                               |
| File → New Project → Papyrus             | platform `DirectoryProjectGenerator` using the same upstream template | CLion-compatible New Project entry; creates `skyrimse.ppj`, `Source/Scripts`, `.run` configs and opts the created project into Papyrus build                 | VERIFIED 0.2.123              | U generator/descriptor; I33 verifies `Papyrus` in the real CLion heavyweight New Project popup |
| Compile Skyrim SE/AE Project             | upstream Pyro task provider                                           | restricted bundled-Pyro action; validates `.ppj`, runs the validated snapshot, and forces project-local output                                               | VERIFIED 0.2.115              | U compile-safety contracts, I30                                                                |
| Workspace-style PPJ task discovery       | upstream `PyroTaskProvider` workspace task discovery                  | scans real project-local `.ppj` files without following links; selected task reuses the bounded compile service                                              | VERIFIED 0.2.137              | U task discovery, I38                                                                          |
| Papyrus compiler problem matcher         | upstream `$PapyrusCompiler` problem matcher                           | raw upstream and observed Pyro `COMPILATION FAILED:` envelope are normalized; physical target remains canonical/project-local and VFS refresh stays deferred | VERIFIED 0.2.138              | U parser, I39                                                                                  |
| Native Papyrus Project Run Configuration | VS Code Pyro task execution adapted to IDE Run infrastructure         | standard Run/Stop/Rerun + Run console, reusing the same safe compile service and execution gate                                                              | VERIFIED 0.2.116              | U shared-gate contract, I31                                                                    |
| Build Project / Ctrl+F9                  | IntelliJ Platform `ProjectTaskRunner` extension                       | project-level opt-in: IDE default is untouched; `Papyrus (Pyro)` routes module build tasks to the same safe compile service                                  | VERIFIED 0.2.117              | U opt-in/default contracts, I32                                                                |
| Manual Getting Started                   | upstream `papyrus.showWelcome` command                                | bundled read-only Getting Started editor                                                                                                                     | IMPLEMENTED                   | descriptor registration; no dedicated I scenario                                               |
| Automatic Welcome                        | upstream `WelcomeHandler`                                             | deliberately not auto-opened                                                                                                                                 | INTENTIONAL DIFFERENCE        | user decision                                                                                  |

### 3. Language-service startup/status/configuration

| Feature                                          | Upstream basis                             | IDE state                                                                  | Status                        | Coverage                                                 |
| ------------------------------------------------ | ------------------------------------------ | -------------------------------------------------------------------------- | ----------------------------- | -------------------------------------------------------- |
| SSE/AE language-service enable/disable           | upstream per-game enabled setting          | explicit global SSE/AE checkbox                                            | VERIFIED                      | U readiness, I26                                         |
| SSE/AE install path configuration                | upstream setting                           | Settings → Languages & Frameworks → Papyrus                                | VERIFIED indirectly           | U resolver, I26 settings surface                         |
| Windows Registry install-path fallback           | upstream Windows discovery semantics       | read-only Bethesda Registry resolver                                       | VERIFIED                      | U resolver                                               |
| Creation Kit INI loading                         | upstream launch configuration              | ordered configured INI loader                                              | VERIFIED                      | U loader + running server gate                           |
| compiler path resolution/missing state           | upstream launch behavior                   | explicit override + detected compiler + readiness classification           | VERIFIED                      | U resolver, I24                                          |
| game-missing status                              | upstream status item                       | Papyrus status bar                                                         | IMPLEMENTED / unit-verified   | U status/readiness; no dedicated I game-missing scenario |
| compiler-missing status                          | upstream status item                       | Papyrus status bar                                                         | VERIFIED                      | U status, I24, I26                                       |
| running/starting/error status                    | upstream language service status           | active-editor-aware status bar                                             | VERIFIED                      | U status, I23                                            |
| richer tooltip                                   | not strict upstream parity                 | file/root/server/script-state details                                      | JETBRAINS-IDE-ONLY / VERIFIED | U tooltip, I23                                           |
| status click → output                            | upstream OutputChannel command             | bounded `Papyrus Projects` Output tab transcript                           | VERIFIED adaptation           | I25                                                      |
| locate-or-disable game UX                        | upstream QuickPick command                 | missing-state click → Settings; explicit disable checkbox                  | VERIFIED adaptation           | I26                                                      |
| bundled VSIX extraction + immutable host staging | JetBrains IDE/Windows packaging adaptation | SHA-pinned VSIX → versioned IDE cache → content-fingerprinted host runtime | VERIFIED                      | U extractor + U stager + 0.2.112 full gate               |

### 4. Upstream surfaces intentionally not active

| Upstream surface                                                 | Current state                               | Reason                                                                                                                                |
| ---------------------------------------------------------------- | ------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| Unrestricted Pyro task engine / package / zip / events / remotes | SAFETY HOLD                                 | can execute PPJ-defined events, fetch remotes, and write archive/temp/package outputs outside the bounded compile subset              |
| Install SSE debugger support                                     | SAFETY HOLD                                 | writes into game/mod-manager locations                                                                                                |
| Attach Papyrus debugger                                          | SAFETY HOLD                                 | process/network/debugger lifecycle not safety-gated                                                                                   |
| Papyrus breakpoints / executable debugger integration            | SAFETY HOLD                                 | same debugger boundary                                                                                                                |
| Generic platform LSP Rename applier                              | DISABLED BY DESIGN                          | replaced by the guarded Papyrus Rename handler so unsafe edits produce an explicit error instead of a silent generic failure          |
| LSP Code Actions                                                 | DEFENSIVELY DISABLED / NOT IN TAGGED SERVER | tagged `v3.3.0-prerelease.1` registers no Code Action handler; generic future server actions remain disabled until separately audited |
| Fallout 4 language/client/debug/generation settings              | OUT OF SCOPE                                | current target is SSE/AE                                                                                                              |
| Skyrim LE language/client/generation settings                    | OUT OF SCOPE                                | current target is SSE/AE                                                                                                              |

`PapyrusProject` is now an executable safe build Run Configuration. The separate inert `PapyrusAttach` configuration still exists only to preserve a future debugger integration shape; it always rejects execution and is not counted as debugger parity.

## Active parity coverage burn-down

Debugger is explicitly excluded. The active non-debugger burn-down has no remaining stage after the verified 0.2.138 baseline. Any next feature work must come from optional hardening/release readiness or an explicit decision to enter a safety-held area.

## Test coverage summary

### Current counts

- 0.2.112 result: BUILD PASS + **40/40** unit/contract + **11/11** exact-upstream raw server + **29/29** real IDEA UI = **80/80**. Hardened Safe Rename is VERIFIED at this baseline.
- Current authoritative gate: **0.2.146 — 59 + 16 + 39 = 114/114**. Earlier milestone runs remain available in source-control/test-report history rather than being maintained as current status.

### Coverage level by active surface

- Core user-visible editor/LSP features: **strong**, with dedicated real-IDE scenarios for completion, diagnostics, definition, references, hover, signature help, structure, folding, TextMate, comments, pairs, indentation, and representative templates.
- Active custom actions/integration: **strong** for Wiki Search, View Assembly, Generate Project, bounded Compile Project, native Papyrus Project Run Configuration, opt-in Build Project routing, project-local PPJ task discovery/selection, and compiler-problem navigation. 0.2.126 carries forward verification of the `Papyrus` directory-project generator in CLion's real heavyweight New Project popup; **manual Welcome lacks a dedicated runtime scenario**.
- Projects/status UX: **strong**, including source/import provenance, cached-remote metadata/resolution, VFS-driven project refresh, tree navigation, unresolved/overridden status, active-editor status, output click, disable/re-enable behavior, and navigator.
- Startup/config resolution: **strong unit coverage plus real running-server/UI evidence**; Registry fallback specifically is unit-covered rather than a dedicated real-IDE Registry scenario.
- Protocol compatibility: **all four tracked custom request families are black-box verified**; `syntaxTree` now also has a typed client mapping used to prove live IDE-buffer convergence without adding a user-visible Syntax Tree tool window.
- Write-capable active behavior: **Generate Project, Safe Rename, bounded Compile Project, native Run Configuration, and opt-in Build Project are verified**. The CLion-compatible New Project runtime reuses the bounded generator and opts in only the project it creates; unrestricted Pyro package/zip/anonymize/events/remotes and all debugger writes remain disabled.

## Known coverage gaps that are not current regressions

- No line/branch coverage metric.
- No dedicated real-IDE test for manual Getting Started action.
- No dedicated real-IDE test that forces Registry autodetection as the only valid game path.
- No dedicated notification-only test for `papyrus/projectsUpdated`; cache invalidation is covered indirectly.
- No deterministic performance benchmark/scale suite beyond current bounded presentation/model unit contracts. Such a suite is JetBrains-IDE-specific and is **not present in tagged upstream**.

These are candidates for hardening, not evidence that the corresponding implemented feature is broken.

- CLion LSP compatibility: `plugin.xml` declares both `com.intellij.modules.lsp` and `com.intellij.modules.ultimate`.


## 0.2.163 PPJ snapshot workspace

- 0.2.161 is the user-confirmed green baseline.
- PPJ validation failures now state the failure cause explicitly and wrap inside the Projects window.
- papyrus-lang receives only private immutable validated PPJ snapshots, never the editable PPJ.
- Cold start with an invalid PPJ falls back to the persisted last validated snapshot, or to a safe empty PPJ workspace when no fallback exists.
- Editable PPJ revision tracking prevents a late edit during server restart from being incorrectly reported as READY.
- Expected gate: 75 UNIT + 20 PAPYRUS-LANG + 48 REAL CLION UI.
