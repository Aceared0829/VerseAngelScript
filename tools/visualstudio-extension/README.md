# VerseAngelScript for Visual Studio 2026

A small, content-only VSIX for `.vas` files in Visual Studio 2026 (18.x, x64).
It uses Visual Studio's built-in TextMate and Language Configuration engines for:

- Syntax colors, including multiline triple-quoted strings and non-nesting comments
- Comment/uncomment commands
- Automatic bracket pairs, indentation and ordinary editor undo

This is basic editing support. It does not provide semantic completion, navigation,
compiler diagnostics, building, debugging, SDK generation or Unreal integration.
It does not register `.as`, `.cpp`, or the C++ language/content type.

## Build and test

Windows with Visual Studio 2026 and the **Visual Studio extension development**
workload is required. From the repository root in PowerShell:

```powershell
./tools/visualstudio-extension/scripts/test-vs2026.ps1
```

The script strictly discovers an 18.x/VSSDK installation, restores pinned packages,
builds the production VSIX, checks its contents against the canonical assets, and
runs seven IDE tests in an actual `devenv.exe` process. Microsoft's xUnit IDE harness
installs the VSIX in a separate `VASIntegration` experimental root. The tests assert
that both the process version/path and root suffix match the discovered instance.
They never install into your normal Visual Studio profile. The harness resets the
experimental root's settings; do not use that root for personal development.

The native suite covers classifications, Unicode/space paths, close/reopen,
triple-quoted strings, non-nesting comments, comment/uncomment and undo, bracket
pairing, newline indentation and `.cpp`/`.as` isolation. Commands go through the
real editor command chain. Direct buffer changes are not used to simulate command
success. Missing, skipped, empty or failing host results fail the script.

`TestResults/VS2026.trx`, the selected instance and isolated ActivityLogs are kept as
focused evidence. The dedicated CI workflow uses `windows-2025-vs2026`, with the
same strict runtime probe. A package build or Linux contract check is not evidence
that the native editor tests passed.

For a quick platform-independent registration check:

```sh
python tools/visualstudio-extension/test/check_package.py
```

The production VSIX is written beneath `bin/Release`. It contains no assemblies,
server process or test code. It is a development artifact, not a Marketplace or
GitHub release. Close Visual Studio before manually installing it with VSIXInstaller.

## Shared language assets

`../vscode-extension/syntaxes/vas.tmLanguage.json` and
`../vscode-extension/language-configuration.json` are linked as build content.
Do not copy or fork them here. CI verifies byte-for-byte parity in the built VSIX.
`VAS.pkgdef` registers `source.vas` with the built-in Language Configuration engine.
No custom MEF component or language-client assembly is necessary for this scope.

## References

- [Microsoft Language Configuration documentation](https://learn.microsoft.com/en-us/visualstudio/extensibility/language-configuration)
- [Microsoft's matching VSIX sample](https://github.com/microsoft/VSExtensibility/tree/main/LSP/Samples/Language%20Configuration%20Setup%20Example)
- [Microsoft test harness and public VSSDK feed](https://github.com/microsoft/vs-extension-testing)
- [Runner labels and installed software](https://github.com/actions/runner-images)

The test harness is pinned to `5.13.0-1.26502.3`, a published package whose
`VisualStudioVersion.VS18` support was checked in its actual assembly. The
production package uses `Microsoft.VSSDK.BuildTools` 18.5.40034; the 17.14.40265
Visual Studio SDK references are confined to the test project. Native execution
remains a required CI gate, rather than an assumption about package compatibility.
