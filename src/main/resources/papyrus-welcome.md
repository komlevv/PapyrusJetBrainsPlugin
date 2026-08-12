# Papyrus for CLion / JetBrains IDEs

This plugin provides Papyrus language tooling for **Skyrim Special Edition / Anniversary Edition** using the tagged `papyrus-lang` language server, the VSIX TextMate grammars, and native IntelliJ Platform APIs.

## Configuration

Open **Settings | Languages & Frameworks | Papyrus** to configure:

- whether the Papyrus language service is enabled;
- the Skyrim SE/AE / Creation Kit installation directory;
- an optional Papyrus compiler path override;
- Creation Kit INI files;
- the ambient project name;
- the Papyrus flags file name.

If the configured Skyrim path is unavailable, the plugin can use the supported Windows Registry install-path entry as a read-only fallback. If the game/compiler cannot be resolved, the Papyrus status item shows the missing state and opens this Settings page when clicked.

Disabling the language service is always an explicit Settings choice. It stops Papyrus LSP clients and prevents them from restarting while disabled; it does not modify game, bundled-VSIX, compiler, or source files.

## Editor features

Papyrus `.psc` and `.ppj` files use the pinned VSIX bundled inside the plugin together with the IDE's native LSP client. The VSIX is extracted automatically into the IDE's system cache; no external VSIX path is required.

Current editor support includes:

- syntax highlighting;
- completion;
- diagnostics;
- go to declaration;
- Find Usages / references;
- Quick Definition;
- Quick Documentation / hover;
- Parameter Info / signature help;
- File Structure / document symbols;
- folding;
- line comments;
- smart typing pairs;
- Papyrus indentation;
- common Papyrus Live Templates.

**Refactor | Rename** uses the Papyrus language server for semantic symbol/reference discovery, but the IDE applies edits only after a strict project-bound safety check. For an allowed project symbol, `Rename Papyrus Symbol` shows the current name in the **New name** field and applies the operation only after **Rename** is confirmed. The new name must be a valid non-keyword identifier, and every server-proposed edit must be an exact replacement of that identifier. Creation Kit/game sources, import-only dependency sources, remote sources, vendor/cache files, read-only files, and any rename containing an unsafe target are rejected before any edits are applied. `ScriptName` rename is intentionally blocked for now because a safe script rename must also coordinate the `.psc` file name. Every blocked user rename shows an explicit reason and the target path when available; blocked operations never apply partial edits.

Generic LSP Code Actions remain disabled. The pinned tagged server does not currently register a Code Action handler.

## Papyrus Projects

Open **Papyrus Projects** from the right tool-window bar.

The **Projects** tab shows language-server project data with explicit:

- `Sources`;
- `Imports`;
- source/import names and paths;
- `[remote]` markers for remote imports;
- scripts resolved by the language server.

Source/import nodes are lazy. Large script sets use bounded grouping instead of materializing one very large flat Swing subtree.

Use **Navigate...** to search the cached language-server project snapshot by script identifier, project, include, or path without expanding the tree. Opening a result navigates to the exact file path reported by the language server.

## Script status

For `.psc` files, the editor can show whether the current script is resolved and, when duplicate definitions exist, which file wins. The overridden-script notification can navigate to the winning file.

## Language service status and output

When a Papyrus file is active, the status bar shows the compact Papyrus lifecycle/readiness state, for example:

- `Papyrus: running`;
- `Papyrus: starting`;
- `Papyrus: compiler missing`;
- `Papyrus: game missing`.

Hover the status for read-only details such as current file, workspace root, server identity/version when reported, and cached `.psc` resolution state.

Click a running/starting/error state to open **Papyrus Projects | Output**, a bounded in-memory Papyrus transcript. Compile Project also writes its captured Pyro/compiler stdout/stderr to this same Output tab. Missing-game/compiler states open **Settings | Languages & Frameworks | Papyrus**.

## Actions

### Search Creation Kit Wiki

**Papyrus: Search Creation Kit Wiki** searches the selected Papyrus identifier (or the applicable identifier at the caret) after explicit user invocation.

### View Assembly

**Papyrus: View Assembly** requests assembly from the language server and opens it as a **read-only in-memory** `.disassemble.pas` editor using the VSIX Papyrus Assembly grammar. It does not create a `.pas` file in the source project.

### Create a Papyrus project

Use **File | New Project | Papyrus** for the native IDE project-creation flow. It uses the IDE's normal Name/Location/Git wizard and creates `skyrimse.ppj`, `Source/Scripts`, and ready-to-use Papyrus `.run` configurations. A project created this way is opted into **Build system: Papyrus (Pyro)** automatically. Existing projects are never switched automatically.

**Papyrus: Generate Skyrim SE Project Files** remains an explicit alternative write action. It creates a **new** direct child project directory selected by the user, never overwrites an existing target, rejects unsafe folder names, and rejects targets inside the configured Skyrim installation.

### Compile Project

Papyrus project compilation has three IDE entry points that share the same safety preflight and bundled Pyro command: **Papyrus: Compile Project** (a selected `.ppj` compiles directly; otherwise real project-local `.ppj` files are discovered and offered as `Compile Project (<relative ppj>)` choices), **Run | Edit Configurations... | Papyrus Project** for Run/Stop/Rerun, and optional **Build | Build Project / Ctrl+F9** integration. Build Project is not changed globally: ordinary projects use the native IDE build behavior by default. Select **Settings | Build, Execution, Deployment | Build Tools | Papyrus | Build system: Papyrus (Pyro)** to opt a project in. Newly generated Papyrus projects already store this selection and include a ready-to-use `Papyrus: Compile Project` `.run` configuration.

The preflight parses PPJ XML with DTDs and external entities disabled; project files that attempt to declare a DOCTYPE or resolve external XML entities are rejected before Pyro starts. Current support is intentionally limited to Skyrim SE/AE compilation: the `.ppj` and its Output must be inside the IDE project, and the validated absolute Output path is forced on the Pyro command line. Pyro receives a temporary same-directory snapshot of the already-validated PPJ content with the XML declaration removed for compatibility; the original PPJ is not modified and the snapshot is deleted after compilation. Existing `.pex` build outputs in that directory may be replaced by an explicit build. Creation Kit/compiler/import locations are read-only inputs.

The compiler rejects projects that enable anonymization, package/ZIP creation, Variables, any pre/post build/import/compile/package/zip/anonymize event, remote HTTP(S) imports/folders, or expandable Output/import/folder paths. Those advanced Pyro behaviors are not part of the enabled subset, and task discovery exposes only which project-local PPJ to compile. The explicit action and Build Project bridge write process text to **Papyrus Projects | Output**; the Run Configuration uses the IDE's standard Run console. Both console surfaces recognize Papyrus compiler `file(line,column): message` output and make only canonical project-local source paths clickable. Only one Papyrus build may run per IDE project at a time.

### Getting Started Help

**Papyrus: Show Getting Started Help** opens this bundled help document. The plugin intentionally does **not** auto-open Welcome on first install/update.

## Features intentionally unavailable

The current safety build does not expose:

- unrestricted Pyro task/package/zip/anonymize/event/remote features beyond the bounded Compile Project subset;
- debugger-support installation;
- executable Papyrus debugger attach/breakpoints;
- generic LSP Code Actions.

Fallout 4 and Skyrim LE are also outside the current game scope.

## External-input safety

The bundled VSIX, Skyrim/Creation Kit installation, compiler inputs, and import-only Papyrus dependency locations are treated as read-only inputs. Explicit Papyrus builds, whether started by the Compile Project action, the Papyrus Project Run Configuration, or an opted-in Build Project action, may write only compiled output under the validated project-local Output directory.

The plugin may extract the SHA-pinned bundled VSIX, normalize required empty runtime directories in that cache copy, and create a content-fingerprinted copy of the upstream host runtime under the IDE's system/cache directory when required for runtime dependency loading. This avoids modifying the original vendor payload or game installation.
