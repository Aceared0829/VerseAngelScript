using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace VerseAngelScript.VisualStudio.Build
{
    internal sealed class ProtocolException : IOException
    {
        internal ProtocolException(string message) : base(message) { }
        internal ProtocolException(string message, Exception inner) : base(message, inner) { }
    }

    internal sealed class CompilationUnit
    {
        public string Id { get; }
        public string Entry { get; }
        public string Config { get; }
        public string Output { get; }
        internal CompilationUnit(string id, string entry, string config, string output)
        { Id = id; Entry = entry; Config = config; Output = output; }
        public override string ToString() { return Id; }
    }

    internal sealed class Descriptor
    {
        public string Project { get; }
        public string Root { get; }
        public int? SchemaVersion { get; }
        public bool Legacy { get; }
        public IReadOnlyList<CompilationUnit> Units { get; }
        public IReadOnlyList<string> Warnings { get; }
        internal Descriptor(string project, string root, int? schema, bool legacy,
            IList<CompilationUnit> units, IList<string> warnings)
        { Project = project; Root = root; SchemaVersion = schema; Legacy = legacy;
          Units = new List<CompilationUnit>(units).AsReadOnly(); Warnings = new List<string>(warnings).AsReadOnly(); }
    }

    internal sealed class Diagnostic
    {
        public string Severity { get; }
        public string Message { get; }
        public string Section { get; }
        public int Row { get; }
        public int Column { get; }
        public bool Bindable { get; }
        internal Diagnostic(string severity, string message, string section, int row, int column, bool bindable)
        { Severity = severity; Message = message; Section = section; Row = row; Column = column; Bindable = bindable; }
    }

    internal sealed class PathIdentity : IEquatable<PathIdentity>
    {
        public string Display { get; }
        public string RawHex { get; }
        public bool Bindable { get { return RawHex == null && !string.IsNullOrEmpty(Display) && Display.IndexOf('\0') < 0; } }
        internal PathIdentity(string display, string rawHex = null) { Display = display; RawHex = rawHex; }
        public bool Equals(PathIdentity other) { return other != null && Display == other.Display && RawHex == other.RawHex; }
        public override bool Equals(object other) { return Equals(other as PathIdentity); }
        public override int GetHashCode() { return (Display ?? "").GetHashCode() ^ (RawHex ?? "").GetHashCode(); }
    }

    internal sealed class SourceProof
    {
        public int ByteLength { get; }
        public string Sha256 { get; }
        internal SourceProof(int length, string sha256) { ByteLength = length; Sha256 = sha256; }
    }

    internal sealed class Observation
    {
        public PathIdentity Identity { get; }
        public bool Loaded { get; }
        public bool Utf8Valid { get; }
        public SourceProof Proof { get; }
        internal Observation(PathIdentity identity, bool loaded, bool utf8Valid, SourceProof proof)
        { Identity = identity; Loaded = loaded; Utf8Valid = utf8Valid; Proof = proof; }
    }

    /// <summary>Compiler responses only. This consumer never parses a project manifest.</summary>
    internal static class Protocol
    {
        public const int MaxDescriptorBytes = 16 * 1024 * 1024;
        public const int MaxRecordBytes = 1024 * 1024;
        public const int MaxReportBytes = 64 * 1024 * 1024;
        public const int MaxEvents = 100000;
        private static readonly UTF8Encoding Utf8 = new UTF8Encoding(false, true);

        public static Descriptor Describe(byte[] stdout, int exitCode)
        {
            Require(stdout != null && stdout.Length > 1 && stdout.Length <= MaxDescriptorBytes,
                "Missing or oversized VAS project descriptor.");
            Require(stdout[stdout.Length - 1] == 10, "Truncated VAS project descriptor (missing LF).");
            var o = ParseLine(stdout, stdout.Length - 1);
            Require(String(o, "protocol") == "vas-project" && Integer(o, "version") == 1,
                "Unsupported VAS project descriptor protocol.");
            bool success = Boolean(o, "success"), legacy = Boolean(o, "legacyProject");
            Require((exitCode == 0) == success, "VAS descriptor disagrees with compiler exit status.");
            int? schema = NullableInteger(o, "projectSchemaVersion");
            string project = NullableString(o, "project"), root = NullableString(o, "projectRoot");
            NullableString(o, "name");
            Require(project == null || AbsoluteIdentity(project), "Invalid VAS project identity.");
            Require(root == null || AbsoluteIdentity(root), "Invalid VAS root identity.");
            var units = Array(o, "compilationUnits");
            var warnings = new List<string>();
            int legacyWarnings = 0;
            foreach (var element in Array(o, "warnings"))
            {
                var warning = Object(element);
                if (String(warning, "code") == "legacy_project") ++legacyWarnings;
                warnings.Add(String(warning, "message"));
            }
            var errors = new List<string>();
            foreach (var element in Array(o, "errors"))
            {
                var error = Object(element);
                String(error, "code"); String(error, "section");
                string field = String(error, "field"), message = String(error, "message");
                Require(Integer(error, "row") >= 0 && Integer(error, "column") >= 0, "Invalid descriptor error position.");
                var offset = Required(error, "byteOffset");
                Require(offset.Type == JTokenType.Null || PositiveLong(offset), "Invalid descriptor error byte offset.");
                errors.Add((field.Length == 0 ? "project" : field) + ": " + message);
            }
            if (!success)
            {
                Require(units.Count == 0 && errors.Count > 0 && schema == null, "Invalid failed VAS descriptor.");
                throw new ProtocolException(string.Join(Environment.NewLine, errors));
            }
            Require(project != null && root != null && Parent(project) == root, "VAS project root differs from manifest parent.");
            Require(errors.Count == 0 && (legacy ? schema == null : schema == 1), "Invalid VAS project metadata.");
            Require(units.Count >= 1 && units.Count <= 256, "Invalid VAS compilation unit count.");
            Require(!legacy || (units.Count == 1 && legacyWarnings == 1), "Invalid legacy VAS descriptor.");
            var ids = new HashSet<string>(StringComparer.Ordinal);
            var outputs = new HashSet<string>(WindowsIdentity(root) ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal);
            var decoded = new List<CompilationUnit>();
            foreach (var element in units)
            {
                var unit = Object(element);
                string id = String(unit, "id"), entry = String(unit, "entry"),
                    config = String(Object(Required(unit, "hostApi")), "config"), output = String(unit, "output");
                Require(Regex.IsMatch(id, @"\A[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}\z") && ids.Add(id), "Invalid or duplicate VAS unit ID.");
                foreach (var path in new[] { entry, config, output })
                    Require(AbsoluteIdentity(path) && Within(root, path), "VAS unit path must be absolute and inside the project root.");
                Require(entry.EndsWith(".vas", StringComparison.Ordinal), "VAS entry must end with lowercase .vas.");
                Require(outputs.Add(output), "Duplicate VAS output identity.");
                Require(!legacy || id == "main", "Invalid legacy VAS unit ID.");
                decoded.Add(new CompilationUnit(id, entry, config, output));
            }
            return new Descriptor(project, root, schema, legacy, decoded, warnings);
        }

        internal static JObject ParseLine(byte[] bytes, int length)
        {
            Require(length > 0, "Empty VAS protocol record.");
            for (int i = 0; i < length; ++i)
                Require(bytes[i] != 10 && bytes[i] != 13, "Invalid VAS protocol line framing.");
            try
            {
                string line = Utf8.GetString(bytes, 0, length);
                Require(line[0] != '\ufeff', "Unexpected BOM in VAS protocol.");
                // Newtonsoft intentionally accepts JavaScript extensions. Validate RFC 8259
                // syntax first, including escaped Unicode scalars and duplicate decoded keys.
                new StrictJson(line).Validate();
                using (var reader = new JsonTextReader(new StringReader(line)))
                {
                    reader.DateParseHandling = DateParseHandling.None;
                    reader.MaxDepth = 64;
                    var token = JToken.ReadFrom(reader, new JsonLoadSettings { DuplicatePropertyNameHandling = DuplicatePropertyNameHandling.Error });
                    Require(!reader.Read(), "Trailing VAS JSON data.");
                    return Object(token);
                }
            }
            catch (DecoderFallbackException e) { throw new ProtocolException("Invalid UTF-8 in VAS protocol.", e); }
            catch (JsonException e) { throw new ProtocolException("Malformed VAS protocol JSON.", e); }
        }

        internal static JToken Required(JObject o, string name)
        { var value = o[name]; Require(value != null, "Missing VAS field: " + name); return value; }
        internal static JObject Object(JToken value)
        { Require(value is JObject, "Expected VAS JSON object."); return (JObject)value; }
        internal static JArray Array(JObject o, string name)
        { var value = Required(o, name); Require(value is JArray, "Expected VAS array: " + name); return (JArray)value; }
        internal static string String(JObject o, string name) { return String(Required(o, name)); }
        internal static string String(JToken token)
        { Require(token.Type == JTokenType.String, "Expected VAS string."); return (string)token; }
        internal static string NullableString(JObject o, string name)
        { var value = Required(o, name); return value.Type == JTokenType.Null ? null : String(value); }
        internal static bool Boolean(JObject o, string name)
        { var value = Required(o, name); Require(value.Type == JTokenType.Boolean, "Expected VAS boolean: " + name); return (bool)value; }
        internal static int Integer(JObject o, string name)
        {
            var value = Required(o, name); int result;
            Require(value.Type == JTokenType.Integer && int.TryParse(value.ToString(), NumberStyles.AllowLeadingSign,
                CultureInfo.InvariantCulture, out result), "Expected bounded VAS integer: " + name);
            return int.Parse(value.ToString(), CultureInfo.InvariantCulture);
        }
        internal static int? NullableInteger(JObject o, string name)
        { return Required(o, name).Type == JTokenType.Null ? (int?)null : Integer(o, name); }
        private static bool PositiveLong(JToken value)
        { long number; return value.Type == JTokenType.Integer && long.TryParse(value.ToString(), out number) && number > 0; }
        internal static bool NativeAbsolute(string path)
        { return !string.IsNullOrEmpty(path) && (path.StartsWith("/", StringComparison.Ordinal) || path.StartsWith(@"\\", StringComparison.Ordinal)
            || (path.Length >= 3 && IsLetter(path[0]) && path[1] == ':' && (path[2] == '/' || path[2] == '\\'))); }
        internal static bool AbsoluteIdentity(string path)
        {
            if (!NativeAbsolute(path) || path.IndexOf('\\') >= 0 || path.Any(c => c < 32 || (c >= 127 && c <= 159))) return false;
            int start = path.StartsWith("//", StringComparison.Ordinal) ? 2 : path[0] == '/' ? 1 : 3;
            if (start == path.Length) return start != 2;
            string[] pieces = path.Substring(start).Split('/');
            return (start != 2 || pieces.Length >= 2) && pieces.All(p => p.Length > 0 && p != "." && p != "..");
        }
        private static bool IsLetter(char c) { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'; }
        private static bool WindowsIdentity(string p) { return p.StartsWith("//", StringComparison.Ordinal) || p.Length >= 3 && p[1] == ':'; }
        private static bool Within(string root, string path) { return path.StartsWith(root.EndsWith("/", StringComparison.Ordinal) ? root : root + "/", StringComparison.Ordinal) && path != root; }
        private static string Parent(string path)
        { int slash = path.LastIndexOf('/'); return slash == 0 ? "/" : slash == 2 && path[1] == ':' ? path.Substring(0, 3) : path.Substring(0, slash); }
        internal static void Require(bool value, string message) { if (!value) throw new ProtocolException(message); }

        internal static int ScalarLength(byte[] bytes, int offset)
        {
            int first = bytes[offset];
            if (first < 128) return 1;
            int length = first >= 0xc2 && first <= 0xdf ? 2 : first >= 0xe0 && first <= 0xef ? 3 : first >= 0xf0 && first <= 0xf4 ? 4 : 0;
            if (length == 0 || bytes.Length - offset < length) return 0;
            for (int j = 1; j < length; ++j) if ((bytes[offset + j] & 0xc0) != 0x80) return 0;
            int second = bytes[offset + 1];
            return first == 0xe0 && second < 0xa0 || first == 0xed && second >= 0xa0 || first == 0xf0 && second < 0x90 || first == 0xf4 && second >= 0x90 ? 0 : length;
        }
        internal static HashSet<string> EncodingMetadata(JObject record)
        {
            var invalid = new HashSet<string>(StringComparer.Ordinal);
            var raw = Object(Required(record, "rawBytes"));
            foreach (var fieldValue in Array(record, "invalidUtf8Fields"))
            {
                string field = String(fieldValue), hex = String(raw, field), display = String(record, field);
                Require(invalid.Add(field) && hex.Length > 0 && hex.Length % 2 == 0 && Regex.IsMatch(hex, @"\A[0-9a-f]+\z"), "Invalid VAS raw-byte encoding metadata.");
                var bytes = new byte[hex.Length / 2];
                for (int i = 0; i < bytes.Length; ++i) bytes[i] = byte.Parse(hex.Substring(i * 2, 2), NumberStyles.HexNumber, CultureInfo.InvariantCulture);
                var decoded = new StringBuilder(); bool bad = false;
                for (int i = 0; i < bytes.Length;)
                {
                    int size = ScalarLength(bytes, i);
                    if (size == 0) { bad = true; decoded.Append('\ufffd'); ++i; }
                    else { decoded.Append(Utf8.GetString(bytes, i, size)); i += size; }
                }
                Require(bad && decoded.ToString() == display, "VAS raw bytes differ from invalid UTF-8 display.");
            }
            Require(raw.Properties().Select(p => p.Name).ToHashSetCompat().SetEquals(invalid), "VAS raw-byte fields differ from invalid-field list.");
            return invalid;
        }
        private static HashSet<string> ToHashSetCompat(this IEnumerable<string> source) { return new HashSet<string>(source, StringComparer.Ordinal); }
        internal static PathIdentity Identity(JObject record, string field, HashSet<string> invalid)
        { return new PathIdentity(String(record, field), invalid.Contains(field) ? String(Object(Required(record, "rawBytes")), field) : null); }

        private sealed class StrictJson
        {
            private readonly string text; private int at;
            internal StrictJson(string value) { text = value; }
            internal void Validate() { Value(0); Space(); Require(at == text.Length, "Trailing VAS JSON data."); }
            private void Space() { while (at < text.Length && (text[at] == ' ' || text[at] == '\t')) ++at; }
            private bool Take(char c) { Space(); if (at < text.Length && text[at] == c) { ++at; return true; } return false; }
            private void Need(char c) { Require(Take(c), "Malformed VAS JSON syntax."); }
            private void Value(int depth)
            {
                Require(depth <= 64, "VAS JSON nesting limit exceeded."); Space();
                Require(at < text.Length, "Truncated VAS JSON.");
                if (Take('{'))
                {
                    var names = new HashSet<string>(StringComparer.Ordinal);
                    if (Take('}')) return;
                    do { string name = Text(); Require(names.Add(name), "Duplicate VAS JSON key."); Need(':'); Value(depth + 1); } while (Take(','));
                    Need('}'); return;
                }
                if (Take('['))
                { if (Take(']')) return; do { Value(depth + 1); } while (Take(',')); Need(']'); return; }
                if (text[at] == '"') { Text(); return; }
                foreach (string literal in new[] { "true", "false", "null" })
                    if (text.Length - at >= literal.Length && string.CompareOrdinal(text, at, literal, 0, literal.Length) == 0) { at += literal.Length; return; }
                int start = at;
                if (at < text.Length && text[at] == '-') ++at;
                Require(at < text.Length, "Invalid VAS JSON number.");
                if (text[at] == '0') ++at;
                else { Require(text[at] >= '1' && text[at] <= '9', "Invalid VAS JSON value."); Digits(); }
                if (at < text.Length && text[at] == '.') { ++at; int begin = at; Digits(); Require(at > begin, "Invalid VAS JSON fraction."); }
                if (at < text.Length && (text[at] == 'e' || text[at] == 'E'))
                { ++at; if (at < text.Length && (text[at] == '+' || text[at] == '-')) ++at; int begin = at; Digits(); Require(at > begin, "Invalid VAS JSON exponent."); }
                Require(at > start, "Invalid VAS JSON value.");
            }
            private void Digits() { while (at < text.Length && text[at] >= '0' && text[at] <= '9') ++at; }
            private string Text()
            {
                Need('"'); var value = new StringBuilder(); bool closed = false;
                while (at < text.Length)
                {
                    char ch = text[at++];
                    if (ch == '"') { closed = true; break; }
                    Require(ch >= 32, "Control byte in VAS JSON string.");
                    if (ch == '\\')
                    {
                        Require(at < text.Length, "Truncated VAS JSON escape."); ch = text[at++];
                        if (ch == 'u')
                        {
                            Require(text.Length - at >= 4, "Truncated VAS Unicode escape."); int scalar;
                            Require(int.TryParse(text.Substring(at, 4), NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture, out scalar), "Invalid VAS Unicode escape.");
                            ch = (char)scalar; at += 4;
                        }
                        else
                        {
                            int escape = "\"\\/bfnrt".IndexOf(ch);
                            Require(escape >= 0, "Invalid VAS JSON escape."); ch = "\"\\/\b\f\n\r\t"[escape];
                        }
                    }
                    value.Append(ch);
                }
                Require(closed, "Unterminated VAS JSON string.");
                for (int i = 0; i < value.Length; ++i)
                {
                    if (char.IsHighSurrogate(value[i])) Require(++i < value.Length && char.IsLowSurrogate(value[i]), "Invalid VAS Unicode scalar.");
                    else Require(!char.IsLowSurrogate(value[i]), "Invalid VAS Unicode scalar.");
                }
                return value.ToString();
            }
        }
    }

    /// <summary>Serialized, bounded incremental decoder. Failure retains partial observations.</summary>
    internal sealed class Report
    {
        private readonly Descriptor descriptor; private readonly CompilationUnit unit;
        private readonly List<Diagnostic> diagnostics = new List<Diagnostic>();
        private readonly List<Observation> observations = new List<Observation>();
        private readonly HashSet<int> attempts = new HashSet<int>();
        private long bytes; private int sequence;
        private bool terminal, terminalSuccess, terminalDependencies, finished;
        public IReadOnlyList<Diagnostic> Diagnostics { get { return diagnostics.AsReadOnly(); } }
        public IReadOnlyList<Observation> Observations { get { return observations.AsReadOnly(); } }
        public IReadOnlyList<Observation> LoadedSections { get { return observations.Where(o => o.Loaded).ToList().AsReadOnly(); } }
        public Observation LastObservation { get; private set; }
        public string Cwd { get; private set; }
        public string Phase { get; private set; }
        public string Failure { get; private set; }
        public bool Complete { get { return finished && Failure == null; } }
        public bool Success { get { return Complete && terminalSuccess; } }
        public bool DependenciesComplete { get { return Complete && terminalDependencies; } }
        public Report(Descriptor descriptor, CompilationUnit unit)
        {
            Protocol.Require(descriptor != null && unit != null && descriptor.Units.Contains(unit), "Selected VAS unit is not in this descriptor.");
            this.descriptor = descriptor; this.unit = unit;
        }
        public void Fail(string failure) { if (Failure == null) Failure = failure ?? "Incomplete VAS invocation."; finished = false; }
        public void AcceptLine(byte[] raw)
        {
            LastObservation = null;
            if (Failure != null) throw new ProtocolException(Failure);
            try
            {
                Protocol.Require(!terminal && !finished, "VAS report contains data after terminal result.");
                Protocol.Require(raw != null && raw.Length > 0 && raw.Length <= Protocol.MaxRecordBytes, "Empty or oversized VAS report record.");
                bytes += raw.Length + 1L;
                Protocol.Require(bytes <= Protocol.MaxReportBytes && sequence < Protocol.MaxEvents, "VAS report resource limit exceeded.");
                var o = Protocol.ParseLine(raw, raw.Length);
                Protocol.Require(Protocol.String(o, "protocol") == "vasbuild" && Protocol.Integer(o, "version") == 1, "Unsupported VAS build report protocol.");
                int next = Protocol.Integer(o, "seq");
                Protocol.Require(next == sequence + 1, "VAS report sequence is not consecutive.");
                var invalid = Protocol.EncodingMetadata(o); string type = Protocol.String(o, "type");
                Protocol.Require(sequence != 0 || type == "start", "VAS report is missing its start record.");
                switch (type)
                {
                    case "start": Start(o, invalid); break;
                    case "diagnostic":
                        string severity = Protocol.String(o, "severity"), section = Protocol.String(o, "section");
                        Protocol.Require(new[] { "error", "warning", "information" }.Contains(severity), "Invalid VAS diagnostic severity.");
                        diagnostics.Add(new Diagnostic(severity, Protocol.String(o, "message"), section, Protocol.Integer(o, "row"),
                            Protocol.Integer(o, "column"), section.Length > 0 && section.IndexOf('\0') < 0 && !invalid.Contains("section")));
                        break;
                    case "section_loaded":
                        var identity = Protocol.Identity(o, "section", invalid); bool utf8 = Protocol.Boolean(o, "utf8Valid");
                        LastObservation = new Observation(identity, true, utf8, Proof(o)); observations.Add(LastObservation); break;
                    case "include_attempt":
                        Protocol.String(o, "from"); Protocol.String(o, "requested"); attempts.Add(next);
                        if (Protocol.NullableString(o, "resolved") != null)
                        { LastObservation = new Observation(Protocol.Identity(o, "resolved", invalid), false, false, null); observations.Add(LastObservation); }
                        break;
                    case "include_result":
                        string status = Protocol.String(o, "status");
                        Protocol.Require(new[] { "loaded", "skipped", "failed", "rejected" }.Contains(status) && attempts.Remove(Protocol.Integer(o, "attemptSeq")), "Invalid or duplicate VAS include result."); break;
                    case "result":
                        bool success = Protocol.Boolean(o, "success"), dependencies = Protocol.Boolean(o, "dependenciesComplete");
                        string phase = Protocol.String(o, "phase");
                        Protocol.Require(new[] { "arguments", "engine", "config", "load", "compile", "output" }.Contains(phase)
                            && attempts.Count == 0 && (!success || phase == "output" && dependencies)
                            && (!dependencies || phase == "compile" || phase == "output"), "Invalid VAS terminal result.");
                        terminal = true; terminalSuccess = success; terminalDependencies = dependencies; Phase = phase; break;
                    default: throw new ProtocolException("Unknown VAS report record type: " + type);
                }
                sequence = next;
            }
            catch (ProtocolException e) { Fail(e.Message); throw; }
        }
        private void Start(JObject o, HashSet<string> invalid)
        {
            Protocol.Require(sequence == 0 && Protocol.String(o, "compiler") == "vasbuild" && Protocol.String(o, "positionEncoding") == "utf-8-bytes"
                && Protocol.Integer(o, "positionBase") == 1, "Invalid VAS start record.");
            Protocol.String(o, "compilerVersion");
            Equal(o, "project", descriptor.Project, invalid); Equal(o, "unit", unit.Id, invalid); Equal(o, "entry", unit.Entry, invalid);
            Equal(o, "config", unit.Config, invalid); Equal(o, "output", unit.Output, invalid);
            Protocol.Require(Protocol.NullableInteger(o, "projectSchemaVersion") == descriptor.SchemaVersion && Protocol.Boolean(o, "legacyProject") == descriptor.Legacy, "VAS project schema changed before build.");
            string cwd = Protocol.NullableString(o, "cwd");
            Protocol.Require(Protocol.NativeAbsolute(cwd) && cwd.IndexOf('\0') < 0 && !invalid.Contains("cwd"), "VAS report has no usable working directory."); Cwd = cwd;
        }
        private static void Equal(JObject o, string field, string expected, HashSet<string> invalid)
        { Protocol.Require(Protocol.String(o, field) == expected && !invalid.Contains(field), "VAS build " + field + " differs from selected descriptor."); }
        private static SourceProof Proof(JObject o)
        {
            string[] fields = { "sourceDigestVersion", "sourceDigestAlgorithm", "sourceByteLength", "sourceDigest" };
            if (!fields.Any(f => o[f] != null)) return null;
            int version = Protocol.Integer(o, "sourceDigestVersion");
            if (version != 1) return null;
            string algorithm = Protocol.String(o, "sourceDigestAlgorithm");
            int length = Protocol.Integer(o, "sourceByteLength"); string digest = Protocol.String(o, "sourceDigest");
            Protocol.Require(length >= 0, "Invalid VAS source proof length.");
            if (algorithm != "sha256") return null;
            Protocol.Require(Regex.IsMatch(digest, @"\A[0-9a-f]{64}\z"), "Invalid VAS source SHA-256 proof.");
            return new SourceProof(length, digest);
        }
        public void Finish(int exitCode)
        {
            if (Failure != null) throw new ProtocolException(Failure);
            try
            {
                Protocol.Require(!finished && sequence > 0 && terminal, "Truncated or already-finished VAS report.");
                Protocol.Require((exitCode == 0) == terminalSuccess, "VAS report result disagrees with compiler exit status."); finished = true;
            }
            catch (ProtocolException e) { Fail(e.Message); throw; }
        }
    }
}
