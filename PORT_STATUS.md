# Papyrus JetBrains Plugin port status — 0.2.147 source

Current status: **ALPHA**. Active compatibility target: **CLion 2026.2.x / IntelliJ Platform 262**; verified target: **CLion 2026.2.1 / CL-262.9437.136**.

The current source is **0.2.147**. The latest authoritative Windows gate remains **0.2.146: 59/59 UNIT + 16/16 PAPYRUS-LANG + 39/39 REAL CLION UI = 114/114**. 0.2.147 fixes the background script-status read-access assertion on IntelliJ Platform 262; it has not yet replaced 0.2.146 as the verified baseline. Feature scope and safety boundaries are unchanged.

## Baseline

- Plugin source version: **0.2.147**.
- 0.2.126 is the all-24 plugin-source inspection cleanup; expected runtime behavior is unchanged.
- Authoritative Windows gate: user-verified **0.2.146 — 59/59 UNIT, 16/16 PAPYRUS-LANG, 39/39 REAL CLION UI = 114/114**.
- Hardened LSP-semantic **Refactor | Rename** is VERIFIED at the 0.2.112 baseline.
- Tagged `v3.3.0-prerelease.1` safe SSE/AE client parity is effectively complete. Bounded Pyro compile, the native Papyrus Project Run Configuration, and opt-in IDE Build Project integration are VERIFIED. 0.2.126 keeps the active target on CLion 2026.2.1, generalizes IDE discovery, and replaces the Java-only New Project language generator with the platform directory-project generator.
- **Papyrus: Compile Project**, **Run Configuration: Papyrus Project**, and the opt-in **Build Project** bridge use the same restricted compile subset: project-owned `.ppj`, SSE/AE only, project-local output forced by the client, and no package/zip/anonymize/events/remotes/variables. 0.2.134 adds only project-local PPJ discovery/selection and project-local compiler hyperlinks; it does not expose raw Pyro task-definition options.
- Active target: **CLion 2026.2.1 / CL-262.9437.136**; compatibility lock: platform branch **262**.
- Upstream reference: **`joelday/papyrus-lang v3.3.0-prerelease.1`**.
- Active game scope: **Skyrim Special Edition / Anniversary Edition**.

- 0.2.115 full gate verified the bundled-Pyro XML-declaration compatibility snapshot and the project-local write boundary end-to-end.

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
