# vasbuild compilation report v1

`vasbuild --report=jsonl <host-interface config> <entry.vas> <bytecode output>`

The optional flag must be the first argument and must be followed by exactly the
three positional arguments. It selects one UTF-8 JSON Lines stream on stdout.
Without that exact first flag, the existing command line, text diagnostics,
stdout/stderr routing and tolerance of trailing arguments are unchanged. Report
mode does not interpret `vas-project.json`, add include paths/defines, or change
which source files the compiler sees. The config describes host API declarations;
it is not a project file and does not provide runnable implementations.

## Framing and completion

Each stdout line is a complete JSON object, terminated by LF, with no BOM or
unescaped line breaks. Output is flushed after every record. In report mode all
ordinary compiler/tool diagnostics are records on stdout; stderr is reserved for
abnormal runtime/platform failures and is not another protocol channel. On Windows
stdout uses binary mode, including when attached to a console; IDEs should use a
pipe. Never merge stderr into the JSON stream.

All records contain:

| Field | Value |
| --- | --- |
| `protocol` | `"vasbuild"` |
| `version` | integer `1` |
| `type` | record type below |
| `seq` | consecutive integer starting at `1` for each process |
| `invalidUtf8Fields` | array of this record's string field names containing invalid input bytes |
| `rawBytes` | object mapping those field names to lowercase hex of their **entire original byte string** |

For valid text the last two fields are `[]` and `{}`. There are no timestamps,
process IDs, source text, environment variables, semantic symbols, fabricated
ranges or diagnostic codes. Unknown additive fields should be ignored; consumers
must reject unsupported protocol versions rather than guessing their meaning.

An ordinary invocation emits one `start`, zero or more event records, then exactly
one `result` as its final record, even when argument validation or compilation
fails. `success: true` means configuration, loading, compilation, bytecode writing
and the final bytecode close/flush succeeded. It does not promise fsync-level
persistence or a compatible runtime host. A failed output may leave a partial
bytecode file; consumers must not execute it.

Consumers must validate framing, sequence, version, one final `result`, and process
exit status. Treat success as established **only** after both `success: true` and a
zero exit. Missing/truncated/malformed terminal data, a killed process, a broken
pipe, stdout write/flush failure, or failed Windows argument decoding means an
incomplete invocation, never a successful empty result. No terminal record can be
promised when the output channel itself fails. A detected output-channel failure
produces a nonzero exit; POSIX may terminate via SIGPIPE. The report is attempted
before any compilation or bytecode output, so a failed first write stops early.

Report mode requires an ordinary regular bytecode file distinct from stdout.
Existing devices, terminals and FIFOs are rejected, including `/dev/tty`,
`/dev/stdout`, Windows `NUL` and `CONOUT$`. A regular-file destination that identifies
stdout (including a hard link) is also rejected before truncation or bytecode
writes. The actual write handle is checked before truncation as well as probing
the path, so a failed metadata-only probe cannot allow a console alias through.
This avoids device aliases whose filesystem identity differs from stdout. The default CLI's output destinations remain unchanged. Callers must not
concurrently replace or retarget output paths during compilation. Use a separate
ordinary file for bytecode and a pipe for the report.

## Records

### `start`

Additional fields: `compiler: "vasbuild"`, `compilerVersion` (the linked
AngelScript library version), `cwd`, `config`, `entry`, `output`,
`positionEncoding: "utf-8-bytes"`, `positionBase: 1`.

Paths describe this invocation, not a workspace-wide project. `cwd` is the actual
native working directory; other paths are absolute. Missing, empty or unresolvable
paths are `null`; a directory that cannot be queried makes `cwd` null. Invalid
argument-count reports describe supplied tuple slots where available but do not
use them for compilation.

`entry` uses exactly the file builder's normalization: forward slashes, lexical
`/./` and `/../` removal, no symlink resolution. Config/output use native absolute
path resolution without changing how those paths are opened. In particular POSIX
config/output paths retain backslashes and dot segments, because simplifying a
path across a symlink could name a different file; Windows paths can contain native
backslash separators. These details are deliberate and must not be replaced with
an IDE's speculative path normalization. The `entry` normalization is also used
for include `resolved` and loaded `section` names. It preserves the pre-existing
builder behavior, including its platform-specific include-once comparison rules.

### `diagnostic`

Additional fields: `severity` (`error`, `warning`, `information`), `message`,
`section`, `row`, `column`.

The section and signed integer positions are the exact callback values. A section
can be a normalized loaded path, an original relative config/output/entry argument,
or an empty engine-generated section; do not assume every section is a file.
Resolve file-valued relative sections against `start.cwd`, and do not attach empty
sections to an arbitrary editor document.

Positive source positions are one-based byte positions in the compiler's source
section, not Unicode code-point counts, UTF-16 columns, display columns or ranges.
A tab counts as one byte; a supplementary Unicode character normally counts as four.
Rows follow LF boundaries. Zero is unknown/file-level and must remain unknown,
including config records with a known row and column zero. Do not subtract one
from zero or manufacture an end position. Convert to editor coordinates only with
the exact corresponding source bytes. Invalid UTF-8 source cannot be mapped to a
Unicode document without an explicit consumer policy.

### `section_loaded`

Additional fields: `section` and `utf8Valid` (whether the source bytes form valid
UTF-8, independent of filename encoding).

This means the real native loader successfully read this file's complete bytes.
It is emitted **before** `AddSectionFromMemory` preprocesses includes. It does not
mean that preprocessing, module acceptance or compilation succeeded. An entry or
include whose descendants later fail still has a `section_loaded` record. An empty
file is loaded too. Repeated/cyclic includes skipped by the builder do not produce
another load record. No source contents are transmitted.

### `include_attempt`

Additional fields: `from` (the including normalized section), `requested` (the
include token contents given to the actual callback), and `resolved` (the loader's
normalized path, or null when it cannot be resolved).

The sequence number identifies this attempt. Events are generated by the real
preprocessor callback, **not** a regex scan. Both single and double quotes work;
comments and inactive `#if` blocks produce no attempts. Includes resolve relative
to the including section unless absolute. The callback has no reliable line/column
for a directive; this record intentionally has no invented location.

### `include_result`

Additional fields: `attemptSeq` and `status`:

- `loaded`: the new section's load/preprocessing and all nested include callbacks
  returned successfully; it still may fail subsequent compilation
- `skipped`: include-once/cycle detection found the section already seen
- `failed`: resolving/reading/preprocessing this include or a nested include failed
- `rejected`: the include did not have the required lowercase `.vas` extension

Each ordinary attempt has one result. Nested events appear between a parent's
attempt and result, so results need not match the most recent attempt. A parent
whose bytes were loaded can finish `failed` because a descendant failed.

### `result`

Additional fields: `success` (boolean), `phase` and `dependenciesComplete` (boolean).
Phases are `arguments`, `engine`, `config`, `load`, `compile`, `output`. `phase`
names the last phase reached; a successful build ends at `output`.

`dependenciesComplete` becomes true only after the entry loader and all observed
include callbacks return without aborting. It stays false for argument/config/
entry-load errors or an aborted include traversal. A later compile or output error
can therefore have `dependenciesComplete: true`. It describes completed discovery
for this invocation, not successful compilation. Even a complete discovery cannot
include syntactically invalid directives never accepted by the preprocessor,
inactive branches, future edits, or runtime-loaded files. Config is a separate
input identified by `start.config`, not a source-section load event.

An incomplete discovery is a **partial observation**, not an authoritative empty
or replacement dependency graph. Later includes may never have been attempted,
even in an already loaded parent. Consumers should conservatively retain previous
watch dependencies when a refresh is partial. A complete list is the set of
`section_loaded` identities; include attempts additionally expose unresolved or
rejected candidate dependencies that an IDE may want to watch.

## Invalid UTF-8 policy

The compiler's existing acceptance of bytes is unchanged. Every JSON string is
valid UTF-8. Each invalid byte is replaced with U+FFFD in its display field, that
field is listed in `invalidUtf8Fields`, and its complete original bytes are retained
as lowercase hexadecimal in `rawBytes`. Valid Unicode, literal U+FFFD, quotes,
backslashes and control characters round-trip unambiguously. There is no locale-
encoding guess and no silent Latin-1 conversion. Windows native UTF-16 arguments
that cannot be converted to Unicode fail before entering the reporting pipeline.

Never use the replacement display value of a listed path field as an identity:
two distinct invalid-byte filenames can display identically. A byte-aware POSIX
consumer can use `rawBytes[field]` for lossless identity; a Unicode-only IDE must
treat that path as non-bindable and show a file-level message. Apply the same rule
to `cwd`, `config`, `entry`, `output`, `section`, `from`, `requested`, and `resolved`
where present. Source `utf8Valid: false` is separate from string-field encoding and
does not put source bytes in `rawBytes`.

## Example

For a simple successful source (paths abbreviated here):

```jsonl
{"protocol":"vasbuild","version":1,"type":"start","seq":1,"compiler":"vasbuild","compilerVersion":"2.39.0 WIP","positionEncoding":"utf-8-bytes","positionBase":1,"cwd":"/work","config":"/work/host.txt","entry":"/work/main.vas","output":"/work/main.vasbc","invalidUtf8Fields":[],"rawBytes":{}}
{"protocol":"vasbuild","version":1,"type":"diagnostic","seq":2,"severity":"information","section":"host.txt","row":0,"column":0,"message":"Configuration successfully registered","invalidUtf8Fields":[],"rawBytes":{}}
{"protocol":"vasbuild","version":1,"type":"section_loaded","seq":3,"section":"/work/main.vas","utf8Valid":true,"invalidUtf8Fields":[],"rawBytes":{}}
{"protocol":"vasbuild","version":1,"type":"diagnostic","seq":4,"severity":"information","section":"main.vas","row":0,"column":0,"message":"Script successfully built","invalidUtf8Fields":[],"rawBytes":{}}
{"protocol":"vasbuild","version":1,"type":"diagnostic","seq":5,"severity":"information","section":"main.vasbc","row":0,"column":0,"message":"Bytecode successfully saved","invalidUtf8Fields":[],"rawBytes":{}}
{"protocol":"vasbuild","version":1,"type":"result","seq":6,"success":true,"phase":"output","dependenciesComplete":true,"invalidUtf8Fields":[],"rawBytes":{}}
```
