# Validated IDE install artifacts

Publish existing CI-built Rider ZIP, VS Code VSIX, and Visual Studio 2026 VSIX
as ordinary GitHub Actions artifacts with seven-day retention and unchanged
workflow permissions and audience. No Releases, Marketplace, version changes,
signing, credentials, new binaries, or semantic prerequisite changes are included.

Each job stages exactly one validated package plus `manifest.json` and
`SHA256SUMS`. The name identifies the full actual checkout commit, runner OS and
architecture, IDE version tested by this job, and declared installation range.
VS Code also includes the requested matrix version, including `stable`, so each
OS/runtime matrix job remains distinct. The manifest records the actual checkout
commit and tree separately from the PR head; a PR merge checkout must never be
labelled as bytes built from the PR head alone. Package SHA256 binds the manifest
to the unchanged staged bytes. Declared installation compatibility is distinct
from native validation scope; one job does not establish all-platform support.

Uploads run only after all preceding validation succeeds:

- Rider: Gradle `test buildPlugin`, required trust results, and required project
  results; inspect the fresh `build/distributions/*.zip`, never the tracked ZIP
- VS Code: compiler conformance, extension unit/TextMate/native project tests,
  VSIX packaging, and that matrix job's native Extension Host tests
- Visual Studio 2026: existing package boundary, compiler conformance, managed
  core/native host tests, selected VS18 identity, loaded production assembly,
  and required native evidence; bind upload to `production-package.json` SHA256

Reject missing, ambiguous, malformed, duplicate, traversal, symlink, or unexpected
package contents. Never stage test logs, workspaces, host profiles, private files,
SDK/harness assemblies, or an entire build directory. Reuse the existing VS2026
package checker. Validate production source/resource parity where feasible. An
unknown new package layout must fail closed until reviewed against actual CI
output. Report package-inspection/helper tests separately from native IDE checks.

Evidence inspected before implementation: the locally available VS Code package,
current VS Code CI package listing (19 files), historical tracked Rider ZIP layout
only, and VS2026 native CI logs selecting `bin/Release/net472/VerseAngelScript.vsix`
with strict package evidence. Fresh Rider/VS2026 bytes require the first CI run;
the historical tracked Rider ZIP is not current delivery or validation evidence.
