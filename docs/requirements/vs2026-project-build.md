# Visual Studio 2026 explicit project build

## Scope

Add registered Build VAS Project and Cancel VAS Build commands to the existing
canonical `.vas` editor VSIX. Resolve the actual solution directory or supported
Open Folder workspace root, require its explicit root `vas-project.json`, and use
an independently configured absolute user-level native executable. Keep compiler
manifest semantics and diagnostics in the shared native contracts.

A native dialog displays the root, compiler and manifest before **Read Project
Units** executes a descriptor. A second explicit action in that dialog builds the
selected unit after showing entry/config/output. There is no preselected unit,
active-editor fallback, execution on open/edit/settings, output-directory creation
during selection, or persistent trust flag.

## Safety and lifecycle

- Validate bounded UTF-8 descriptor/JSONL syntax, typed values, version, sequence,
  invocation identities, terminal data and process exit status
- Drain stdout/stderr concurrently under byte limits and deadlines using net472
  process APIs, strict Windows CRT argument transport and no shell
- Kill only the owned direct process on cancellation; do not claim Job Object or
  descendant termination without that implementation and native test evidence
- Do all file reads, hashing, alias resolution and process waits off the UI thread
- Guard saved manifest/compiler/selected config/entry and observed source inputs
  against dirty RDT physical aliases, replacement, canonical retargeting and change
- Keep per-manifest/unit observations across partial traversals, including missing
  candidate canonical watch aliases built from an existing ancestor plus suffix
- Verify ancestor directory metadata notifications against saved inputs and aliases;
  output or unrelated sibling writes preserve a prepared operation, while input
  writes, ancestor rename/delete and missing-candidate creation invalidate it
- Suppress late publication/navigation after cancel, dispose, root close/reopen,
  new generation, changed compiler or changed saved/dirty inputs
- Publish genuine structured diagnostics in native Error List; map UTF-8 byte
  positions to UTF-16 only for current matching native loaded-source proofs and
  verified saved file identity/editor text. Missing/invalid evidence never gains
  guessed navigation
- Treat loaded-source digest as content evidence; it is neither the compiler's
  file ID nor an atomic native filesystem snapshot

No public native workspace trust query has been verified in the pinned packages.
The explicit operation flow is useful without inventing a trust API, but does not
claim native trust-state synchronization. Where the host presents an actual
native MOTW trust prompt, cancellation must produce no VAS execution. Failure to
produce that native prompt is an unverified case, never a fabricated trust pass.

## Acceptance and release boundary

Retain all seven native editor host cases. Add mandatory real VS18 cases for
solution and `IVsSolution7.OpenFolder`, registered command invocation, actual
DialogWindow stages/cancellation, explicit nondefault units, real compiler
errors/warnings/output, native Error List navigation including astral Unicode,
physical dirty aliases, missing/mismatched proofs, input/root/compiler changes,
repeated/cancelled/disposed operations and late-result suppression. The native
cases also require output-directory creation and unrelated compiler-sidecar writes
to preserve valid work, without weakening ancestor or missing-include invalidation.
The native wide-argv executable validates argc/Unicode/quotes independently of the client.
Strict result/evidence gates reject missing, skipped, failed or undiscovered
required host tests. Compile-only/Linux evidence is reported separately.

The VSIX keeps canonical grammar/config byte parity and an exact assembly allowlist:
production VerseAngelScript.dll plus Newtonsoft.Json 13.0.3, with generated package
registration. No SDK/test/PDB/compiler binaries, serverless ILanguageClient,
Marketplace changes, releases, ZIPs or version bumps. Run Project, semantic LSP,
debugger and full C++ parity remain outside this module.
