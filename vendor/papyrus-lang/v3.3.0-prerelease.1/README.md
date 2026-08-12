# papyrus-lang VSIX vendor slot

This source tree expects the exact upstream release artifact:

- project: `joelday/papyrus-lang`
- tag: `v3.3.0-prerelease.1`
- file: `papyrus-lang-vscode.vsix`
- SHA-256: `c4cf68d74471d4646b1c7dcff36f30293b507ebee215cc931cef051a0f8766db`

Copy the VSIX into this directory before building:

```text
vendor/papyrus-lang/v3.3.0-prerelease.1/papyrus-lang-vscode.vsix
```

Keep the VSIX complete and unchanged; do not strip Fallout 4, debugger, Pyro, or other payloads. The full archive is retained so additional upstream-supported game/features can be enabled later without redesigning vendor packaging.

The VSIX is intentionally omitted from source handoff archives because it is a large upstream binary artifact. Gradle verifies the pinned SHA-256, embeds the unchanged VSIX into the plugin JAR, and extracts it into an IDE system-cache directory at runtime. The release archive has no ZIP entry for the empty `extension/pyro/remote` directory, so build/runtime extraction explicitly normalizes that required directory without modifying this vendored VSIX.

`LICENSE.txt` is copied from the upstream VSIX and must remain with this vendor slot.
