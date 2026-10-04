# VerseAngelScript for Visual Studio 2026

A VSIX for `.vas` editing and explicit native project builds in Visual Studio 2026
(18.x, x64). Canonical TextMate assets provide syntax colors, comments, bracket
pairs, indentation and ordinary editor undo. Read-only MEF providers add
import/include F12, native declaration/member/macro completion, async hover and
type/function/macro semantic colors. Source graph scans run in the background. The extension does not claim `.as`,
`.cpp`, or the C++ language/content type.

中文安装、源码打包、示例与三端能力矩阵见 [安装与使用指南](../../docs/ide-setup.md)。

## Build a project

1. Set **Tools → Options → VerseAngelScript → Toolchain → Compiler executable** to
   an absolute `vasbuild.exe` path. This is a user setting, independent of the
   project. Manifest `builder` and `runner` metadata never select a program.
2. Open a solution or use **Open Folder**. Place `vas-project.json` directly in
   the solution directory or open folder. The active editor does not select the
   entry, host API, or compilation unit.
3. Invoke **Tools → Build VAS Project**. The native dialog shows the actual root,
   manifest and configured executable. **Read Project Units** explicitly runs one
   compiler descriptor command. Cancelling before this action executes no tool.
4. Select a unit from the returned native descriptor. No unit is selected by
   default, even for a one-unit project. Review its entry, host API and output,
   then choose **Build Selected Unit** to run one build. Cancelling this stage
   executes no build. **Tools → Cancel VAS Build** stops an active operation.

The compiler owns the shared [project](../../docs/vas-project.md) and
[JSONL report](../../docs/vas-build-report.md) contracts. Enumeration and selection
create no output directories. Only a successful native compilation may create
its configured output. Opening, indexing and editing do not start a project build. An independent current-file live checker runs only when explicitly enabled in user Toolchain settings. A repeated Build command while an operation is active is
ignored; cancel it before starting another.

Compiler errors, warnings and information appear in the native Error List and
build status in the **VAS Project Build** Output pane. A diagnostic navigates only
when a supported compiler-provided loaded-source SHA-256 proof matches a stable
saved file observation and the native editor's text matches those bytes. Native
UTF-8 byte columns are converted to UTF-16 caret columns, including supplementary
characters and an initial UTF-8 BOM. Unknown positions, invalid UTF-8/NUL/lone-CR
source, missing or unsupported proofs remain non-navigable messages. No range or
source position is guessed from line numbers alone.

Saved manifest, compiler, selected entry/config and compiler-observed include
inputs are guarded against changes and dirty physical aliases, including hardlinks
and junctions. Missing include candidates retain watch-only canonical aliases
through their nearest existing ancestors. Partial discovery preserves prior
per-unit dependencies. Cancellation, root close/reopen, compiler setting changes,
input changes and disposal prevent late diagnostics from replacing current work.

The checks are observations, not an adversarial filesystem sandbox or an atomic
snapshot of a concurrently changing tree. The source digest proves the exact
native loaded bytes, not the original native file ID. A same-byte replacement
before the first matching client observation is indistinguishable. The client
uses 16 MiB per saved file, 64 MiB total tracked bytes, 4,096 tracked paths, 256
watch directories, 16 MiB descriptors, 64 MiB reports, 1 MiB report lines and
100,000 records. Descriptor/build deadlines are 30/120 seconds. Cancellation
terminates the direct owned compiler process; process-tree termination is not
claimed. The compiler's JSONL flushing behavior is unchanged.

## Native trust boundary

The two explicit dialog actions authorize only their current descriptor/build
operation. They do not establish stored workspace trust or automatically approve
future execution. No supported public native trust-state query was found in the
pinned VS18-compatible SDK packages. Synchronization with Visual Studio's native
workspace trust state remains unverified. Opening a solution or folder is not
proof of native trust. The extension uses no reconstructed private COM interface,
registry trust heuristic or `Microsoft.Internal` API.

Conditional macro activation and complete compiler type binding are not inferred.
This module does not provide Run Project, semantic diagnostics, LSP, debugger/DAP,
SDK generation, Unreal integration, or C++ feature parity.

## Build and verify

Windows with Visual Studio 2026, C++ tools and the **Visual Studio extension
development** workload is required:

```powershell
./tools/visualstudio-extension/scripts/test-vs2026.ps1
```

The script discovers an actual 18.x/VSSDK installation, builds `vasbuild` and the
native wide-argument fixture, runs compiler contract tests, builds the production
VSIX and validates its exact payload. Microsoft's pinned xUnit IDE harness installs
that VSIX through `RequireExtension` into the separate `VASIntegration` root and
runs real `devenv.exe` tests. A separate `VASProjectDisposal` root isolates the
package-close lifecycle case. Both the host executable and root suffix are asserted.
The harness resets experimental-root settings; do not use it for personal work.

The original seven native editor cases remain mandatory alongside eighteen
project cases (25 required native cases). Project cases exercise
solution/Open Folder discovery, actual registered commands and dialogs, explicit
nondefault selection, real compiler output/Error List/navigation, dirty aliases,
proof failures, cancellation, repeated operations and stale-result guards. XML
and per-case evidence gates reject missing, skipped, duplicate or failed cases.
`TestResults/VS2026.trx`, compiler/host identities, project evidence and focused
ActivityLogs are retained by the `windows-2025-vs2026` workflow. A successful Linux
compile or protocol test is never evidence of native Visual Studio execution.

For platform-independent packaging/evidence checks:

```sh
python tools/visualstudio-extension/test/check_package.py
python tools/visualstudio-extension/test/test_validation.py
```

The development VSIX is produced under `bin/Release`. Its executable payload is
limited to `VerseAngelScript.dll` and Newtonsoft.Json 13.0.3, alongside generated
package registration and canonical editor assets. SDK, harness and other runtime
DLLs, test code, PDBs, compiler executables and release ZIPs must not ship.
There is no version bump, Marketplace publication or release in this module.

## Shared assets and supported APIs

`../vscode-extension/syntaxes/vas.tmLanguage.json` and
`../vscode-extension/language-configuration.json` are linked, not copied/forked.
Package validation checks byte-for-byte parity. `VAS.pkgdef` registers the built-in
Language Configuration engine; the executable package adds only explicit build
commands, options, dialogs and Error List integration. No `ILanguageClient` is
registered without a server.

Pinned dependencies include VSSDK BuildTools 18.5.40034, SDK 17.14.40265,
Interop 18.0.42421 and Workspace/Workspace.VSIntegration 17.12.19. The public
`IVsFolderWorkspaceService.CurrentWorkspace` supplies Open Folder location;
`IVsSolution.GetSolutionInfo` supplies a solution location. Native dialogs use
`DialogWindow.ShowModal`, and diagnostics use `ErrorListProvider`/`ErrorTask`.
The test harness remains 5.13.0-1.26502.3. Its omitted runtime dependencies are
explicitly pinned and checked before host launch; they are test-only.

References: [language configuration](https://learn.microsoft.com/en-us/visualstudio/extensibility/language-configuration),
[native DialogWindow](https://learn.microsoft.com/en-us/dotnet/api/microsoft.visualstudio.platformui.dialogwindow?view=visualstudiosdk-2022),
[native trust behavior](https://learn.microsoft.com/en-us/visualstudio/ide/trust-settings?view=visualstudio),
[Microsoft test harness](https://github.com/microsoft/vs-extension-testing),
[runner images](https://github.com/actions/runner-images).

## Editing diagnostics (0.3.0)

Native function completion inserts call parentheses and Signature Help displays source parameters. Structural syntax errors have red squiggles without a compiler. Set the user Compiler executable and enable **Live diagnostics** to check unsaved source/dependency snapshots after a debounce. The nearest project ancestor must contain `.vas/vasbuild.config.txt`; checks publish current-file errors and reject stale snapshots. See [language service details](../../docs/ide-language-support.md).
