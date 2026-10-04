using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using VerseAngelScript.VisualStudio.Build;

internal static class Program
{
    private static int checks;
    private static readonly UTF8Encoding Utf8 = new UTF8Encoding(false, true);
    private static readonly string Root = Environment.OSVersion.Platform == PlatformID.Win32NT ? "C:/project" : "/project";
    private static int Main(string[] args)
    {
        if (args.Length >= 2 && args[0] == "--fixture") return Fixture(args[1]);
        try
        {
            DescriptorTests(); ReportTests(); PositionTests(); CompilerPathTests(); InputEventTests(); WorkspaceEventTests(); ProcessTests();
            FixtureMutationTests.Run(Check);
            string native = Environment.GetEnvironmentVariable("VAS_NATIVE_ARGV_FIXTURE");
            if (!string.IsNullOrEmpty(native)) NativeArguments(native);
            else if (Environment.OSVersion.Platform == PlatformID.Win32NT) throw new Exception("Windows core gate requires VAS_NATIVE_ARGV_FIXTURE (tests/vasbuild/rider_argv_fixture.cpp).");
            else Console.WriteLine("SKIP native argv fixture: VAS_NATIVE_ARGV_FIXTURE not configured.");
            if (Environment.OSVersion.Platform == PlatformID.Win32NT) WindowsIdentityTests();
            else Console.WriteLine("SKIP Windows physical handles/hardlinks/junctions: non-Windows host.");
            string compiler = Environment.GetEnvironmentVariable("VASBUILD_EXECUTABLE") ?? Environment.GetEnvironmentVariable("VAS_NATIVE_COMPILER");
            if (!string.IsNullOrEmpty(compiler)) NativeCompilerTest(compiler);
            Console.WriteLine("PASS " + checks + " VAS core assertions"); return 0;
        }
        catch (Exception e) { Console.Error.WriteLine(e); return 1; }
    }
    private static void Check(bool value, string message) { ++checks; if (!value) throw new Exception(message); }
    private static void Reject(Action action, string message)
    {
        ++checks; try { action(); } catch (IOException) { return; } catch (OperationCanceledException) { return; }
        throw new Exception("Accepted invalid case: " + message);
    }
    private static JObject DescriptorJson()
    {
        return new JObject { ["protocol"] = "vas-project", ["version"] = 1, ["success"] = true,
            ["project"] = Root + "/vas-project.json", ["projectRoot"] = Root, ["projectSchemaVersion"] = 1,
            ["legacyProject"] = false, ["name"] = null,
            ["compilationUnits"] = new JArray(new JObject { ["id"] = "game", ["entry"] = Root + "/main.vas",
                ["hostApi"] = new JObject { ["config"] = Root + "/host.txt" }, ["output"] = Root + "/out/main.vasbc" }),
            ["warnings"] = new JArray(), ["errors"] = new JArray() };
    }
    private static byte[] Json(JObject value) { return Utf8.GetBytes(value.ToString(Formatting.None)); }
    private static byte[] DescriptorBytes(JObject value) { return Utf8.GetBytes(value.ToString(Formatting.None) + "\n"); }
    private static Descriptor Descriptor() { return Protocol.Describe(DescriptorBytes(DescriptorJson()), 0); }
    private static void CompilerPathTests()
    {
        foreach (var value in new[] { "/tools/vasbuild.exe", @"\tools\vasbuild.exe", "C:vasbuild.exe", "vasbuild.exe", "//server", @"\\.\pipe\vasbuild.exe" })
            Check(!NativeProcess.WindowsFullyQualifiedPath(value), "Reject drive-relative Windows compiler setting: " + value);
        foreach (var value in new[] { @"C:\tools\vasbuild.exe", "D:/tools/vasbuild.exe", @"\\server\share\vasbuild.exe", @"\\?\C:\tools\vasbuild.exe", @"\\?\UNC\server\share\vasbuild.exe" })
            Check(NativeProcess.WindowsFullyQualifiedPath(value), "Accept fully qualified Windows compiler setting: " + value);
        if (Environment.OSVersion.Platform == PlatformID.Win32NT)
        {
            foreach (var value in new[] { "/tools/vasbuild.exe", @"\tools\vasbuild.exe", "C:vasbuild.exe" })
                Reject(() => NativeProcess.ValidateCompiler(value), "No current-drive/compiler path inference");
            var executable = Path.GetFullPath(Assembly.GetExecutingAssembly().Location);
            var before = Environment.CurrentDirectory;
            try
            {
                Environment.CurrentDirectory = Path.GetDirectoryName(executable);
                if (executable.Length > 2 && executable[1] == ':')
                {
                    // This is an existing PE executable on the current drive. An
                    // existence failure cannot accidentally satisfy this regression.
                    var relativeToDrive = executable.Substring(2).Replace('\\', '/');
                    Check(File.Exists(Path.GetFullPath(relativeToDrive)), "Root-relative fixture resolves to existing executable");
                    Reject(() => NativeProcess.ValidateCompiler(relativeToDrive), "Reject existing current-drive-rooted executable");
                }
            }
            finally { Environment.CurrentDirectory = before; }
        }
    }
    private static void DescriptorTests()
    {
        Check(Descriptor().Units[0].Config == Root + "/host.txt", "Native descriptor config identity");
        var response = DescriptorJson(); response["newField"] = new JObject { ["newVersion"] = 1 };
        Check(Protocol.Describe(DescriptorBytes(response), 0).Units.Count == 1, "Additive descriptor field");
        string good = Utf8.GetString(DescriptorBytes(DescriptorJson()));
        foreach (string bad in new[] { good.TrimEnd('\n'), "\ufeff" + good, good + "\n", good.Replace("\n", "\r\n"),
            good.Replace("\"version\":1", "\"version\":1.0"), good.Replace("\"version\":1", "\"version\":1e0"),
            good.Replace("\"version\":1", "\"version\":01"), good.Replace("\"version\":1", "\"version\":1,\"ver\\u0073ion\":1"),
            good.Replace("\"name\":null", "\"name\":\"\\ud800\""), good.Replace("\"name\":null", "\"name\":undefined"),
            good.Replace("\"name\":null", "name:null"), good.Replace("\"name\":null", "'name':null"),
            good.Replace("\"name\":null", "\"name\":/*comment*/null"), good.Replace("\"errors\":[]", "\"errors\":[],"),
            good.Replace("\"errors\":[]", "\"errors\":[,]"), good.Replace("\"version\":1", "\"version\":2") })
            Reject(() => Protocol.Describe(Utf8.GetBytes(bad), 0), "strict descriptor framing/JSON");
        Reject(() => Protocol.Describe(DescriptorBytes(DescriptorJson()), 1), "descriptor exit mismatch");
        response = DescriptorJson(); response["compilationUnits"][0]["entry"] = Root + "/../escape.vas";
        Reject(() => Protocol.Describe(DescriptorBytes(response), 0), "descriptor path escape");
        response = DescriptorJson(); ((JArray)response["compilationUnits"]).Add(response["compilationUnits"][0].DeepClone());
        Reject(() => Protocol.Describe(DescriptorBytes(response), 0), "duplicate unit");
        byte[] invalid = DescriptorBytes(DescriptorJson()); invalid[1] = 0xff;
        Reject(() => Protocol.Describe(invalid, 0), "strict UTF8");
        Reject(() => Protocol.Describe(new byte[Protocol.MaxDescriptorBytes + 1], 0), "descriptor byte bound");
        response = DescriptorJson(); JToken nested = new JValue(0); for (int i = 0; i < 70; ++i) nested = new JArray(nested); response["future"] = nested;
        Reject(() => Protocol.Describe(DescriptorBytes(response), 0), "JSON depth bound");
        response = DescriptorJson(); response["success"] = false; response["projectSchemaVersion"] = null; response["compilationUnits"] = new JArray();
        response["errors"] = new JArray(new JObject { ["code"] = "project_json", ["field"] = "", ["message"] = "bad project", ["section"] = "", ["row"] = 0, ["column"] = 0, ["byteOffset"] = null });
        Reject(() => Protocol.Describe(DescriptorBytes(response), 2), "failed native descriptor surfaces error");
    }
    private static void InputEventTests()
    {
        const string source = "C:/project/src/main.vas";
        foreach (var change in new[] { WatcherChangeTypes.Changed, WatcherChangeTypes.Created, WatcherChangeTypes.Deleted, WatcherChangeTypes.Renamed })
        {
            Check(InputEvents.Classify(source, @"c:\project\src\main.vas", change) == InputEventEffect.Invalidate, "Exact input event invalidates: " + change);
            Check(InputEvents.Classify(source, "C:/project/src/unrelated.txt", change) == InputEventEffect.None, "Sibling event preserves input: " + change);
            Check(InputEvents.Classify(source, "C:/project/out", change) == InputEventEffect.None, "Output event preserves input: " + change);
            Check(InputEvents.Classify(source, "C:/proj", change) == InputEventEffect.None, "Path prefix without boundary is unrelated: " + change);
            Check(InputEvents.Classify(source, "C:/project/src", change) == (change == WatcherChangeTypes.Changed ? InputEventEffect.Verify : InputEventEffect.Invalidate), "Ancestor metadata verifies; structural events invalidate: " + change);
        }
        Check(InputEvents.Classify("C:/project/tool/vasbuild.exe", "C:/project/tool/vasbuild.exe.calls", WatcherChangeTypes.Created) == InputEventEffect.None, "Compiler sidecar creation is unrelated");
        Check(InputEvents.Classify("C:/project/tool/vasbuild.exe", "C:/project/tool", WatcherChangeTypes.Changed) == InputEventEffect.Verify, "Compiler sidecar parent metadata requires identity verification");
        Check(InputEvents.Classify("C:/real/nested/missing.vas", "C:/real/nested", WatcherChangeTypes.Created) == InputEventEffect.Invalidate, "Missing canonical suffix ancestor creation invalidates");
        Check(InputEvents.Classify("C:/real/nested/missing.vas", "C:/real/nested/missing.vas", WatcherChangeTypes.Created) == InputEventEffect.Invalidate, "Missing canonical include creation invalidates");
    }
    private static void WorkspaceEventTests()
    {
        Check(!WorkspaceEvents.Invalidates(false, "C:/project", @"c:\project\"), "Same-root open completion preserves prepared operation");
        Check(!WorkspaceEvents.Invalidates(false, "C:/project", "C:/project"), "Repeated same-root completion preserves prepared operation");
        Check(WorkspaceEvents.Invalidates(true, "C:/project", "C:/project"), "Closing invalidates before same-root reopen");
        Check(WorkspaceEvents.Invalidates(true, "C:/project", null), "Closing invalidates when native root already cleared");
        Check(WorkspaceEvents.Invalidates(false, "C:/project", null), "Completed close invalidates stale visible root");
        Check(WorkspaceEvents.Invalidates(false, "C:/project", "C:/other"), "Different-root completion invalidates");
        Check(!WorkspaceEvents.Invalidates(false, null, "C:/project"), "Passive opening has no operation to invalidate");
        Check(!WorkspaceEvents.Invalidates(true, null, null), "Repeated close has no old operation to invalidate");
    }
    private static JObject Event(string type, int seq)
    { return new JObject { ["protocol"] = "vasbuild", ["version"] = 1, ["type"] = type, ["seq"] = seq, ["invalidUtf8Fields"] = new JArray(), ["rawBytes"] = new JObject() }; }
    private static JObject Start()
    {
        var o = Event("start", 1); o["compiler"] = "vasbuild"; o["compilerVersion"] = "test"; o["cwd"] = Root;
        o["project"] = Root + "/vas-project.json"; o["projectSchemaVersion"] = 1; o["unit"] = "game"; o["legacyProject"] = false;
        o["entry"] = Root + "/main.vas"; o["config"] = Root + "/host.txt"; o["output"] = Root + "/out/main.vasbc";
        o["positionEncoding"] = "utf-8-bytes"; o["positionBase"] = 1; return o;
    }
    private static JObject Result(int seq, bool success = true, bool complete = true, string phase = "output")
    { var o = Event("result", seq); o["success"] = success; o["dependenciesComplete"] = complete; o["phase"] = phase; return o; }
    private static Report Started()
    { var d = Descriptor(); var r = new Report(d, d.Units[0]); r.AcceptLine(Json(Start())); return r; }
    private static void ReportTests()
    {
        var report = Started(); report.AcceptLine(Json(Result(2)));
        Check(!report.Success && !report.DependenciesComplete, "Exit gates success and discovery"); report.Finish(0);
        Check(report.Success && report.DependenciesComplete, "Success after exit"); Reject(() => report.AcceptLine(Json(Result(3))), "trailing report");
        Check(!report.Success, "Trailing record poisons prior success");
        report = Started(); Reject(() => report.Finish(0), "missing result");
        report = Started(); report.AcceptLine(Json(Result(2))); Reject(() => report.Finish(-1), "signed nonzero exit");
        report = Started(); Reject(() => report.AcceptLine(Json(Result(3))), "sequence gap");
        report = Started(); Reject(() => report.AcceptLine(Json(Start())), "duplicate start");
        var d = Descriptor(); report = new Report(d, d.Units[0]); Reject(() => report.AcceptLine(Json(Result(1))), "missing start");
        report = new Report(d, d.Units[0]); var start = Start(); start["unit"] = "other"; Reject(() => report.AcceptLine(Json(start)), "wrong selected unit");
        report = Started(); var loaded = Event("section_loaded", 2); loaded["section"] = Root + "/main.vas"; loaded["utf8Valid"] = true;
        report.AcceptLine(Json(loaded)); Check(report.LoadedSections.Count == 1 && report.LastObservation.Proof == null, "Old compiler without proof");
        var include = Event("include_attempt", 3); include["from"] = Root + "/main.vas"; include["requested"] = "missing.vas"; include["resolved"] = Root + "/missing.vas";
        report.AcceptLine(Json(include)); var includeResult = Event("include_result", 4); includeResult["attemptSeq"] = 3; includeResult["status"] = "failed";
        report.AcceptLine(Json(includeResult)); report.AcceptLine(Json(Result(5, false, false, "load"))); report.Finish(1);
        Check(report.Complete && !report.Success && !report.DependenciesComplete && report.Observations.Count == 2, "Partial observations retained");
        report = Started(); report.AcceptLine(Json(loaded)); Reject(() => report.AcceptLine(new byte[] { 0xff }), "malformed later report");
        Check(report.Observations.Count == 1 && !report.Complete, "Malformed stream retains previous dependencies");
        loaded["sourceDigestVersion"] = 1; loaded["sourceDigestAlgorithm"] = "sha256"; loaded["sourceByteLength"] = 0;
        loaded["sourceDigest"] = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        report = Started(); report.AcceptLine(Json(loaded)); Check(report.LastObservation.Proof.ByteLength == 0, "Empty SHA proof accepted");
        foreach (string field in new[] { "sourceDigestVersion", "sourceDigestAlgorithm", "sourceByteLength", "sourceDigest" })
        { var bad = (JObject)loaded.DeepClone(); bad.Remove(field); report = Started(); Reject(() => report.AcceptLine(Json(bad)), "partial digest group"); }
        foreach (var badValue in new JToken[] { new JValue(-1), new JValue(2147483648L), new JValue(1.0), new JValue("1") })
        { var bad = (JObject)loaded.DeepClone(); bad["sourceByteLength"] = badValue; report = Started(); Reject(() => report.AcceptLine(Json(bad)), "invalid proof length/type"); }
        var badDigest = (JObject)loaded.DeepClone(); badDigest["sourceDigest"] = loaded["sourceDigest"].ToString().ToUpperInvariant();
        report = Started(); Reject(() => report.AcceptLine(Json(badDigest)), "uppercase digest rejected");
        loaded["sourceDigestVersion"] = 2; report = Started(); report.AcceptLine(Json(loaded)); Check(report.LastObservation.Proof == null, "Future digest gives no proof");
        loaded.Remove("sourceDigestVersion"); loaded.Remove("sourceDigestAlgorithm"); loaded.Remove("sourceByteLength"); loaded.Remove("sourceDigest");
        loaded["section"] = "\ufffd\ufffd"; loaded["invalidUtf8Fields"] = new JArray("section"); loaded["rawBytes"] = new JObject { ["section"] = "c0af" };
        report = Started(); report.AcceptLine(Json(loaded)); Check(!report.LastObservation.Identity.Bindable && report.LastObservation.Identity.RawHex == "c0af", "Invalid filenames never bind");
        loaded["rawBytes"]["section"] = "efbfbd"; report = Started(); Reject(() => report.AcceptLine(Json(loaded)), "valid replacement mislabeled invalid");
        report = Started(); var diagnostic = Event("diagnostic", 2); diagnostic["severity"] = "error"; diagnostic["message"] = "error";
        diagnostic["section"] = ""; diagnostic["row"] = 0; diagnostic["column"] = 0;
        report.AcceptLine(Json(diagnostic)); Check(!report.Diagnostics[0].Bindable && report.Diagnostics[0].Row == 0, "Unknown location stays file-level");
        report = Started(); var huge = new byte[Protocol.MaxRecordBytes + 1]; Reject(() => report.AcceptLine(huge), "record byte bound");
        report = Started(); for (int i = 2; i <= Protocol.MaxEvents; ++i) { diagnostic["seq"] = i; report.AcceptLine(Json(diagnostic)); }
        Reject(() => report.AcceptLine(Json(Result(Protocol.MaxEvents + 1))), "event count bound");
        report = Started(); var bulk = Event("diagnostic", 2); bulk["severity"] = "information"; bulk["section"] = "";
        bulk["row"] = 0; bulk["column"] = 0; bulk["message"] = new string('x', 1000000);
        for (int i = 2; i <= 68; ++i) { bulk["seq"] = i; report.AcceptLine(Json(bulk)); }
        bulk["seq"] = 69; Reject(() => report.AcceptLine(Json(bulk)), "report aggregate byte bound");
        report = Started(); include["seq"] = 2; report.AcceptLine(Json(include));
        Reject(() => report.AcceptLine(Json(Result(3))), "unmatched include attempt");
    }
    private static FileSnapshot Snapshot(byte[] bytes) { return new FileSnapshot("source", "source", "test", DateTime.UtcNow, "test", bytes); }
    private static void PositionTests()
    {
        var s = Snapshot(Utf8.GetBytes("\ufeff\t漢😀x\r\nlast")); int line, column;
        Check(s.TryPosition(1, 12, out line, out column) && line == 0 && column == 4, "BOM tab astral byte position");
        Check(!s.TryPosition(1, 10, out line, out column), "Reject multibyte interior");
        Check(s.TryPosition(2, 5, out line, out column) && line == 1 && column == 4, "CRLF and EOF mapping");
        Check(!s.TryPosition(0, 1, out line, out column) && !s.TryPosition(1, 0, out line, out column), "Unknown positions stay unknown");
        Check(!s.TryPosition(3, 1, out line, out column), "Unknown row rejected");
        Check(!Snapshot(new byte[] { 0xff }).TryPosition(1, 1, out line, out column), "Invalid source UTF8 rejected");
        Check(!Snapshot(Utf8.GetBytes("a\0b")).TryPosition(1, 1, out line, out column), "NUL source rejected");
        Check(!Snapshot(Utf8.GetBytes("a\rb")).TryPosition(1, 1, out line, out column), "Lone CR rejected");
        s = Snapshot(new byte[0]); Check(s.MatchesProof(new SourceProof(0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")), "Exact empty-byte digest");
        Check(!s.MatchesProof(null) && !s.MatchesProof(new SourceProof(1, s.Sha256)), "No inferred proof");
    }
    private static string[] ChildArguments(string mode)
    {
        string assembly = Assembly.GetExecutingAssembly().Location;
        return string.Equals(Path.GetFileNameWithoutExtension(Process.GetCurrentProcess().MainModule.FileName), "dotnet", StringComparison.OrdinalIgnoreCase)
            ? new[] { assembly, "--fixture", mode } : new[] { "--fixture", mode };
    }
    private static ProcessResult Child(string mode, int milliseconds = 10000, long limit = 8 * 1024 * 1024, CancellationToken token = default(CancellationToken), Action<byte[]> sink = null)
    {
        return NativeProcess.RunAsync(Process.GetCurrentProcess().MainModule.FileName, ChildArguments(mode), Path.GetTempPath(),
            TimeSpan.FromMilliseconds(milliseconds), limit, token, null, sink).GetAwaiter().GetResult();
    }
    private static void ProcessTests()
    {
        Check(NativeProcess.QuoteArgument("") == "\"\"", "Empty quoted token");
        Check(NativeProcess.QuoteArgument("a\\\"b\\") == "\"a\\\\\\\"b\\\\\"", "Interior quotes and trailing slash encoding");
        var result = Child("dual-pipe"); Check(result.Stdout.Length == 512 * 1024 && result.Stderr.Length == 16 * 1024, "Concurrent stdout/stderr drains");
        result = Child("utf8"); Check(Utf8.GetString(result.Stdout) == "漢😀\n", "Byte pipes preserve UTF8");
        Reject(() => Child("unterminated", sink: bytes => { }), "truncated JSONL framing");
        Reject(() => Child("dual-pipe", limit: 1024), "stdout total bound");
        Reject(() => Child("stderr-flood"), "stderr total bound");
        Reject(() => Child("stderr-invalid"), "strict stderr UTF8 after retained prefix");
        result = Child("stderr-split"); Check(result.Stderr.Length == 16383, "Retained stderr ends before split scalar");
        Reject(() => Child("long-line", sink: bytes => { }), "line bound");
        Reject(() => Child("utf8", sink: bytes => { throw new ProtocolException("fixture rejection"); }), "sink exception");
        var watch = Stopwatch.StartNew(); Reject(() => Child("retained-pipe", milliseconds: 200), "retained pipe cannot establish completion");
        Check(watch.Elapsed < TimeSpan.FromSeconds(5), "Retained pipe cleanup remains bounded");
        watch.Restart(); Reject(() => Child("hang", milliseconds: 100), "timeout"); Check(watch.Elapsed < TimeSpan.FromSeconds(5), "Bounded timeout cleanup");
        using (var cancel = new CancellationTokenSource(100)) { watch.Restart(); Reject(() => Child("hang", token: cancel.Token), "cancel"); Check(watch.Elapsed < TimeSpan.FromSeconds(5), "Bounded cancel cleanup"); }
    }
    private static int Fixture(string mode)
    {
        using (var output = Console.OpenStandardOutput()) using (var error = Console.OpenStandardError())
        {
            switch (mode)
            {
                case "dual-pipe": var chunk = Enumerable.Repeat((byte)'a', 4096).ToArray(); for (int i = 0; i < 128; ++i) { error.Write(chunk, 0, chunk.Length); output.Write(chunk, 0, chunk.Length); } return 0;
                case "utf8": var bytes = Utf8.GetBytes("漢😀\n"); output.Write(bytes, 0, bytes.Length); return 0;
                case "unterminated": output.WriteByte((byte)'x'); return 0;
                case "stderr-invalid": var prefix = new byte[20 * 1024]; error.Write(prefix, 0, prefix.Length); error.WriteByte(0xff); return 0;
                case "stderr-split": var retained = Enumerable.Repeat((byte)'a', 16383).ToArray(); error.Write(retained, 0, retained.Length); var scalar = Utf8.GetBytes("😀"); error.Write(scalar, 0, 1); error.Flush(); Thread.Sleep(30); error.Write(scalar, 1, 3); return 0;
                case "stderr-flood": var flood = new byte[8192]; for (int i = 0; i < 600; ++i) error.Write(flood, 0, flood.Length); return 0;
                case "long-line": var line = new byte[Protocol.MaxRecordBytes + 1]; output.Write(line, 0, line.Length); return 0;
                case "retained-pipe":
                    var child = new ProcessStartInfo { FileName = Process.GetCurrentProcess().MainModule.FileName,
                        Arguments = NativeProcess.Arguments(ChildArguments("short-hang")), UseShellExecute = false, CreateNoWindow = true };
                    Process.Start(child).Dispose(); return 0;
                case "short-hang": Thread.Sleep(5000); return 0;
                case "hang": Thread.Sleep(30000); return 0;
                default: return 2;
            }
        }
    }
    private static void NativeArguments(string executable)
    {
        string[] values = { "", "plain", "with spaces", "漢😀", "literal & ; $ ` ( )", "a\\", "a\\\"b", "\"quoted\"", "\\\\server\\share\\" };
        var args = new List<string> { "argument-list" }; args.AddRange(values);
        var result = NativeProcess.RunAsync(NativeProcess.ValidateCompiler(executable), args, Path.GetTempPath(), TimeSpan.FromSeconds(10), 1024 * 1024,
            CancellationToken.None).GetAwaiter().GetResult();
        string[] lines = Utf8.GetString(result.Stdout).Split('\n');
        Check(result.ExitCode == 0 && lines[0] == values.Length.ToString(), "Native argv count");
        for (int i = 0; i < values.Length; ++i) Check(Utf8.GetString(Convert.FromBase64String(lines[i + 1])) == values[i], "Native argv exact round trip " + i);
    }
    private static void WindowsIdentityTests()
    {
        string root = Path.Combine(Path.GetTempPath(), "vas-identity-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(root);
        try
        {
            string source = Path.Combine(root, "source.vas"), alias = Path.Combine(root, "hardlink.vas"); File.WriteAllText(source, "void main() {}", Utf8);
            Check(CreateHardLink(alias, source, IntPtr.Zero), "Windows hardlink fixture");
            var snapshot = FileIdentity.Observe(source); Check(FileIdentity.SameFile(source, alias), "Physical hardlink identity");
            Check(snapshot.PhysicalKey == FileIdentity.Observe(alias).PhysicalKey && snapshot.Matches(), "Stable handle snapshots");
            File.WriteAllText(alias, "void main() { }", Utf8); Check(!snapshot.Matches(), "Hardlink byte mutation invalidates");
            snapshot = FileIdentity.Observe(source); File.Delete(source); File.WriteAllBytes(source, snapshot.Bytes);
            Check(!snapshot.Matches() && !FileIdentity.SameFile(source, alias), "Same-byte replacement changes physical identity");
            string target = Path.Combine(root, "target"), junction = Path.Combine(root, "junction"); Directory.CreateDirectory(target);
            var command = new ProcessStartInfo { FileName = Environment.GetEnvironmentVariable("ComSpec"), Arguments = "/d /c mklink /J \"" + junction + "\" \"" + target + "\"", UseShellExecute = false, CreateNoWindow = true };
            using (var p = Process.Start(command)) { p.WaitForExit(); Check(p.ExitCode == 0, "Junction fixture"); }
            string missing = Path.Combine(junction, "nested", "missing.vas"); var aliases = FileIdentity.WatchAliases(missing);
            Check(aliases.Any(p => string.Equals(p, Path.Combine(target, "nested", "missing.vas"), StringComparison.OrdinalIgnoreCase)), "Missing canonical alias retains whole suffix");
            Check(!aliases.Any(p => string.Equals(p, Path.Combine(target, "nested", "sibling.vas"), StringComparison.OrdinalIgnoreCase)), "Missing alias excludes sibling");
            Directory.Delete(junction);
        }
        finally { Directory.Delete(root, true); }
    }
    private static void NativeCompilerTest(string executable)
    {
        string root = Path.Combine(Path.GetTempPath(), "vas-native-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(root);
        try
        {
            string manifest = Path.Combine(root, "vas-project.json");
            File.WriteAllText(Path.Combine(root, "main.vas"), "void main() {}\n", Utf8); File.WriteAllText(Path.Combine(root, "host.txt"), "", Utf8);
            File.WriteAllText(manifest, "{\"schemaVersion\":1,\"compilationUnits\":[{\"id\":\"game\",\"entry\":\"main.vas\",\"hostApi\":{\"config\":\"host.txt\"},\"output\":\"out/main.vasbc\"}]}", Utf8);
            var described = NativeProcess.RunAsync(NativeProcess.ValidateCompiler(executable), new[] { "--describe-project=json", manifest }, root,
                TimeSpan.FromSeconds(20), Protocol.MaxDescriptorBytes, CancellationToken.None).GetAwaiter().GetResult();
            var d = Protocol.Describe(described.Stdout, described.ExitCode); var report = new Report(d, d.Units[0]);
            var built = NativeProcess.RunAsync(executable, new[] { "--report=jsonl", "--project", manifest, "--unit", "game" }, root,
                TimeSpan.FromSeconds(20), Protocol.MaxReportBytes, CancellationToken.None, null, report.AcceptLine).GetAwaiter().GetResult();
            report.Finish(built.ExitCode); Check(report.Success && report.DependenciesComplete && File.Exists(Path.Combine(root, "out", "main.vasbc")), "Real native compiler round trip");
            Check(report.LoadedSections.Count == 1 && Snapshot(File.ReadAllBytes(Path.Combine(root, "main.vas"))).MatchesProof(report.LoadedSections[0].Proof), "Real compiler digest equals exact loaded bytes");
        }
        finally { Directory.Delete(root, true); }
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true, EntryPoint = "CreateHardLinkW")]
    [return: MarshalAs(UnmanagedType.Bool)] private static extern bool CreateHardLink(string name, string existing, IntPtr security);
}
