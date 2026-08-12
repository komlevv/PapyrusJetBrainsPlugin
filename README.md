> **Status: ALPHA** — active development; behavior and packaging may still change before the first stable release.
> **Target IDE:** CLion **2026.2.x** on IntelliJ Platform **262**. Verified target: **CLion 2026.2.1 / CL-262.9437.136**.
> **Platform:** Windows.

# Papyrus Language for JetBrains IDEs

Papyrus language support for **Skyrim Special Edition / Anniversary Edition** in CLion.

The plugin uses the upstream `joelday/papyrus-lang v3.3.0-prerelease.1` language server and TextMate grammars and bundles the required upstream runtime. Users do **not** need to install the VS Code extension or configure a separate VSIX path.

## Supported scope

- **Skyrim Special Edition / Anniversary Edition** is the active game target.
- **Fallout 4** and **Skyrim Legendary Edition** are not currently supported.
- Papyrus debugging is not currently available.
- Generic LSP Code Actions are disabled.
- Advanced/unrestricted Pyro packaging, events, variables, remotes, and similar task features are intentionally disabled.

## Features

### Editing and navigation

- `.psc` syntax highlighting;
- completion, including member completion after `.`;
- diagnostics;
- Go to Declaration / Definition;
- Find Usages;
- Quick Definition;
- Quick Documentation / hover;
- Parameter Info / signature help;
- File Structure / document symbols;
- code folding;
- line comments;
- smart typing pairs;
- Papyrus indentation rules;
- common Papyrus Live Templates.

### Refactoring

**Refactor → Rename** uses Papyrus semantic information while keeping writes restricted to project-owned source files.

Rename is rejected when a target is external, read-only, import-only, remote, part of the Creation Kit/game installation, part of the bundled runtime/cache, malformed, or otherwise outside the project write boundary. `ScriptName` rename is currently disabled.

### Papyrus Projects

The **Papyrus Projects** tool window provides:

- Sources and Imports groups;
- source/import provenance;
- lazy loading for large projects;
- remote-import marking;
- navigation to scripts;
- project script search through **Navigate...**;
- overridden and unresolved script status;
- project output with clickable Papyrus compiler diagnostics.

The status bar follows the active Papyrus editor and exposes relevant language-service and project state.

### Actions

Available Papyrus actions include:

- **Papyrus: Search Creation Kit Wiki**;
- **Papyrus: View Assembly** — opens a read-only `.disassemble.pas` editor;
- **Papyrus: Generate Skyrim SE Project Files**;
- **Papyrus: Compile Project**;
- **Papyrus: Show Getting Started Help**.

### Project creation, build, and run

**File → New Project → Papyrus** creates a Skyrim SE/AE Papyrus project layout.

Project compilation uses the bundled Pyro toolchain with a restricted safety profile:

- the selected `.ppj` must belong to the project;
- compiler output is forced into a project-local directory;
- Skyrim / Creation Kit files are treated as read-only inputs;
- package/zip/anonymize, build events, variables, and remote inputs are rejected.

A native **Papyrus Project** Run Configuration is available through the normal Run/Stop/Rerun UI.

**Build Project / Ctrl+F9** uses Papyrus only when the project explicitly selects **Papyrus (Pyro)** under Build Tools. Existing projects otherwise keep the IDE's normal build behavior.

## Installation

Install the plugin ZIP through:

**Settings → Plugins → ⚙ → Install Plugin from Disk**

Select the Papyrus plugin ZIP and restart the IDE when prompted.

No separate `papyrus-lang` or VSIX installation is required.

## Configuration

Open **Settings → Languages & Frameworks → Papyrus**.

Available settings include:

- language-service enabled state;
- Skyrim SE/AE / Creation Kit install path;
- optional Papyrus compiler path override;
- Creation Kit INI paths;
- ambient project name;
- Papyrus flags file name.

If the configured game path is missing, the plugin can use the supported Bethesda Windows Registry install-path entry as a read-only fallback.

## Safety

Passive language tooling treats external Papyrus inputs as read-only. The plugin does not silently rewrite Skyrim / Creation Kit files, import-only dependency sources, MO2/mod-manager directories, or the bundled upstream runtime.

Writes are limited to explicit user actions such as normal editing of project files, validated project-owned Rename operations, project-local compile output, plugin settings/cache data, and explicitly generated new project directories.

For the exact write/read/process boundaries, see [`SAFETY_AUDIT.md`](SAFETY_AUDIT.md).

## Current limitations

- Windows only.
- CLion 2026.2.x / IntelliJ Platform 262 is the current target.
- Skyrim SE/AE only.
- No Papyrus debugger.
- No Fallout 4 or Skyrim LE support yet.
- Generic LSP Code Actions are disabled.
- Advanced Pyro task/package/remote features are intentionally restricted.

## Upstream

The plugin currently tracks:

`joelday/papyrus-lang v3.3.0-prerelease.1`

The pinned upstream VSIX is bundled as part of the plugin runtime and provides the language server, TextMate grammars, Pyro runtime, and related Papyrus resources used by the plugin.

## Project documentation

Developer and implementation details are kept outside this README:

- [`PORT_STATUS.md`](PORT_STATUS.md) — current feature/parity matrix;
- [`ROADMAP.md`](ROADMAP.md) — remaining product work and decisions;
- [`SAFETY_AUDIT.md`](SAFETY_AUDIT.md) — write/read/process safety boundaries;
- [`HANDOFF.md`](HANDOFF.md) — developer continuation state, build/test history, implementation notes, and environment details;
- [`INSPECTION_AUDIT.md`](INSPECTION_AUDIT.md) — IDE inspection/code-quality tracking.
