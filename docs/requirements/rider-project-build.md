# Rider explicit project build

## Requirement and scope

Add `VAS.BuildProject` under Build for the root `vas-project.json`, with explicit
compilation-unit selection and a separately chosen application-local native
compiler. The compiler owns all manifest semantics and host API selection.
Existing Build Current / Run Current remain unchanged. Opening a project,
indexing, editing, or saving settings must not invoke project tools.

## Contract and safety

- Describe with `--describe-project=json <manifest>`; build only with
  `--report=jsonl --project <manifest> --unit <id>` and argument arrays
- No manifest/project-shared executable trust, shell, guessing manifests,
  implicit output directories, active-file fallback, or automatic builds
- Validate bounded UTF-8 descriptor/report framing, version, identities,
  sequence, completion and exit status; reject incompatible compilers
- Trusted, live project and explicitly configured absolute compiler are required
- Use saved inputs only; reject dirty source/manifest/config aliases
- Cancel processes on cancellation/disposal/new invocation and suppress stale
  results on input, compiler setting, or trust changes
- Show project diagnostics separately, with precise byte-to-UTF-16 locations
  only when the source snapshot and native identity are verified
- Keep dependency observations scoped by manifest + unit; incomplete discovery
  retains previous observations and never becomes an empty authoritative graph

## Acceptance

Pure JVM tests cover malformed/oversized/unsupported protocols, diagnostics,
process limits/cancellation and dependency lifecycle. Real Rider Build 262 tests
must exercise the registered action, actual compiler, explicit selection,
legacy manifest, Unicode/metacharacter paths, dirty/trust/cancellation/stale
results, and navigation independently of active editor. CI must fail when required
native cases are missing, skipped or fail. Linux JVM/compiler checks do not count
as a native Rider host run; disclose unavailable verification explicitly.
