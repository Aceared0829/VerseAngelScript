# Native VAS project contract v1

`vasbuild` owns project interpretation. IDEs can ask the compiler to describe a
manifest, choose an explicit compilation unit, and consume the existing native
build event stream. Neither command launches an IDE, build script, runner, shell,
or a binary named in the manifest.

```text
vasbuild --describe-project=json <manifest>
vasbuild --report=jsonl --project <manifest> --unit <id>
```

The switches and argument order above are exact. Unit selection is mandatory,
even for a one-unit project. There is no default-unit or working-directory search.
Relative manifest arguments are resolved against the process working directory;
all manifest-owned paths are resolved against the manifest's directory.

The original positional forms remain supported, including their established path
semantics and default CLI behavior:

```text
vasbuild <host-interface config> <entry.vas> <bytecode output>
vasbuild --report=jsonl <host-interface config> <entry.vas> <bytecode output>
```

## Manifest schema

A UTF-8 JSON manifest explicitly declares schema version 1:

```json
{
  "schemaVersion": 1,
  "name": "Example",
  "compilationUnits": [
    {
      "id": "game",
      "entry": "src/main.vas",
      "hostApi": { "config": ".vas/game-host.txt" },
      "output": "out/game.vasbc"
    },
    {
      "id": "editor",
      "entry": "src/main.vas",
      "hostApi": { "config": ".vas/editor-host.txt" },
      "output": "out/editor.vasbc"
    }
  ]
}
```

- `schemaVersion` is the integer `1`; other versions and types are rejected
- `name` is optional, may be empty, and is at most 256 UTF-8 bytes
- `compilationUnits` contains 1 through 256 objects in manifest order
- Each unit requires exactly `id`, `entry`, `hostApi`, and `output`
- `id` is case-sensitive and matches `[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}`;
  duplicate IDs are invalid
- `hostApi` requires exactly `config`. This is a host API declaration file used
  for compilation, not executable host implementations or a project settings file
- Units may share an entry and use different host API configurations. Their output
  paths must be distinct, using the platform's path case-comparison convention
- Entry paths must end with lowercase `.vas`. Output names have no mandatory
  extension; `.vasbc` is conventional
- Unknown object properties, missing required properties, wrong types, duplicate
  JSON keys (including equivalent escaped key spellings), and malformed JSON fail
  the entire project. An invalid unselected unit is still an invalid manifest

The whole manifest is at most 1 MiB (1,048,576 bytes), including whitespace.
Container nesting is at most 16. Input bytes must be valid UTF-8. JSON strings and
keys may not contain NUL, C0/C1 controls, or DEL, including escaped controls. JSON
Unicode escapes must represent Unicode scalar values, with valid surrogate pairs
when required. Limits count UTF-8 bytes, not characters or UTF-16 code units.

### Portable manifest paths

`entry`, `hostApi.config`, and `output` are nonempty, manifest-relative paths of
at most 4096 UTF-8 bytes. They use forward slashes and must be portable across the
supported hosts. The compiler rejects:

- Absolute paths, drive-qualified paths, UNC paths, colons, or backslashes
- Empty, `.` or `..` components, including doubled or trailing slashes
- NUL/control characters, `<`, `>`, `"`, `|`, `?`, or `*`
- Components ending in a dot or space
- DOS device components such as `CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`, and
  `LPT1`–`LPT9`, including mixed case and filename extensions. Console device
  aliases and the Windows-reserved superscript digit spellings are rejected too

Spaces, valid Unicode, and shell metacharacters such as `$`, `;`, `&`, parentheses,
and backticks are ordinary filename characters. There is no tilde, environment,
wildcard, command, or shell expansion. Use an argument array when launching the
compiler rather than constructing a shell command string.

These restrictions apply to manifest-owned paths. They do not replace the native
script loader's include-resolution or include-once behavior.

## Read-only descriptor

`--describe-project=json` writes exactly one UTF-8 JSON object followed by LF to
stdout and returns zero on success. It emits no ordinary text diagnostics or
usage text on stdout or stderr. Nonzero exit or a missing/malformed response means
failure. A failed stdout write/flush can prevent a complete response.

A successful result has this shape (absolute paths are examples):

```json
{
  "protocol": "vas-project",
  "version": 1,
  "success": true,
  "project": "/work/example/vas-project.json",
  "projectRoot": "/work/example",
  "projectSchemaVersion": 1,
  "legacyProject": false,
  "name": "Example",
  "compilationUnits": [
    {
      "id": "game",
      "entry": "/work/example/src/main.vas",
      "hostApi": { "config": "/work/example/.vas/game-host.txt" },
      "output": "/work/example/out/game.vasbc"
    }
  ],
  "warnings": [],
  "errors": []
}
```

The actual wire object is on one physical line; indentation above is illustrative.
`project`, `projectRoot`, and every unit path use absolute, lexically normalized
forward-slash identities, including on Windows. `projectRoot` is the lexical
parent directory of `project`. These are not symlink-resolved identities. Missing
`name` is `null`. The descriptor does not require entry/config files or output
parents to exist and does not read unit contents, create a compiler engine,
compile, run commands, create output directories, or rewrite the manifest.

On failure, `success` is false, `compilationUnits` is empty, and `errors` contains
one or more objects with these fields:

| Field | Meaning |
| --- | --- |
| `code` | Error category, e.g. `project_json`, `project_schema`, or `project_path` |
| `field` | JSON pointer when known; the empty string represents the root/unknown field |
| `message` | Human-readable explanation, not a stable parsing interface |
| `section` | Manifest or other relevant path, or the empty string |
| `row`, `column` | One-based UTF-8 byte positions when the parser provides them; otherwise `0` |
| `byteOffset` | One-based raw manifest byte offset when known; otherwise `null` |

Parser positions count LF-separated rows and byte columns. An unexpected EOF can
point one byte beyond the file. Schema, duplicate-key, resource, encoding, and
other file-level errors need not have source positions. Do not manufacture a
source range from zero/unknown locations. The known categories are
`project_arguments`, `project_io`, `project_limit`, `project_encoding`,
`project_json`, `project_schema`, `project_path`, `project_unit`, `project_input`,
and `project_output`; only relevant categories appear in a descriptor.

`project`/`projectRoot` may be null if lookup could not establish them.
`projectSchemaVersion` is `1` for a successfully validated v1 manifest and `null`
for a legacy or invalid manifest. `legacyProject` is a boolean; failure may occur
before the schema shape is known. Consumers should rely on `success` before using
units and tolerate unknown additive response fields, while rejecting unsupported
protocol versions.

## Selected-unit build and filesystem safety

The compiler validates the whole manifest, then selects an exact unit ID. It
checks file availability only for the selected unit. A well-formed dormant unit
may refer to missing files without blocking another unit's build.

The selected config and entry must be regular files whose real filesystem targets
are inside the real manifest root. In-root symlinks are accepted; links escaping
the root are rejected. A config availability/containment failure ends in the
`config` phase; an entry availability/containment failure ends in `load`. Discovery
is incomplete in either case. The compiler then uses its normal config reader,
script builder, include callback, and bytecode writer.

Output handling occurs only after successful compilation:

- The output's lexical location and real parent must remain inside the manifest
  root. Missing directories are created only at this point
- A final output symlink/reparse point, directory, device, FIFO, multiply-linked
  file, or stdout alias is rejected
- The manifest, every declared unit's config and entry, and every source section actually
  loaded by the native include loader are protected from overwrite, including
  filesystem-identity aliases. Missing unselected inputs remain allowed
- Existing outputs of other units may not identify the same filesystem file
- The opened output handle is checked again before truncating/writing bytecode

Argument, config, load, and compile failures preserve any previous output and do
not create its missing parent directories. Output validation failures also avoid
truncating a rejected destination. A later write/flush failure may leave partial
bytecode; this is not an atomic-replacement or fsync durability guarantee.

The include graph is observed from the real compiler callbacks. Existing include
traversal can resolve beyond the manifest root; project mode does not invent a
sandbox for script includes or pre-scan their text. Actual loaded files remain
protected from output overwrite. Callers must not concurrently replace files,
retarget links, or mutate the project tree during a build. These checks prevent
accidental unsafe output; they are not an adversarial filesystem-race sandbox.

## Legacy adaptation

An unversioned object may contain only the existing legacy fields:

```json
{
  "name": "Old project",
  "entry": "src/main.vas",
  "builderConfig": ".vas/vasbuild.config.txt",
  "bytecodeOutput": "out/main.vasbc",
  "builder": ".vas/bin/vasbuild.exe",
  "runner": ".vas/bin/vasrun.exe"
}
```

`entry`, `builderConfig`, and `bytecodeOutput` are required and obey the same
portable path rules. `name`, `builder`, and `runner` are optional strings.
`builder`/`runner` are ignored metadata, not paths to validate or commands to run.
The adapter exposes one unit named `main`, `legacyProject: true`, and
`projectSchemaVersion: null`. A descriptor emits exactly one warning with code
`legacy_project`; a build emits one corresponding warning diagnostic. Adaptation
never rewrites the file and does not promise to use a legacy configured tool.

## Relationship to build report v1

The JSONL event protocol remains `vasbuild`, version `1`. Project-mode `start`
records add `project`, `projectSchemaVersion`, `unit`, and `legacyProject`; the
selected `config`, `entry`, and `output` match the descriptor's absolute
forward-slash paths. Existing diagnostics, section loads, include attempts/results,
sequence numbers, and terminal results remain the native observations documented
in [vas-build-report.md](vas-build-report.md).

Manifest or unit-selection failures terminate in `arguments` with
`dependenciesComplete: false`. Later phases retain their existing meaning. No
IDE should replace an earlier dependency graph with a partial discovery or treat a
successful descriptor as a successful compilation.

## Conformance tests

The native CTest cases are `vasbuild_project_descriptor`,
`vasbuild_project_validation`, `vasbuild_project_build`, `vasbuild_project_safety`,
and `vasbuild_project_includes`. They use the real compiler and shared fixtures
under `tests/vasbuild/fixtures/project-contract`, with isolated temporary projects.
They exercise strict schema/JSON/resource limits, UTF-8 byte positions, Unicode
and literal metacharacter paths, working-directory independence, per-unit host
selection, legacy equivalence, read-only description, output preservation,
filesystem containment, and parity with native include events.

Portable cases run on each native CTest host. Symlink/hard-link cases run only when
the filesystem permits creating those fixtures; FIFO coverage is POSIX-only.
Passing on one host is not evidence of a Windows or macOS execution run.

### Standalone build compatibility

The project parser and compiler additions require C++11. The standalone GNU make
route retains a C++11 build. The pinned parser's minimum supported Microsoft
compiler is Visual Studio 2015 with Build Tools 14.0.25123.0 or newer; historical
v110/Visual Studio 2008 routes are not supported by this project feature. Use the
root CMake build with a supported compiler, or retarget the standalone `.vcxproj`
to a supported toolset. Repository checks on Linux do not establish that MSVC,
Windows, or macOS builds were run locally.
