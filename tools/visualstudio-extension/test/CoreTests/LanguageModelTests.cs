using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Diagnostics;
using Newtonsoft.Json;
using VerseAngelScript.VisualStudio.Editor;

internal static class LanguageModelTests
{
    internal static void Measure()
    {
        var directory = new DirectoryInfo(Environment.CurrentDirectory);
        while (directory != null && !Directory.Exists(Path.Combine(directory.FullName, "examples", "arena"))) directory = directory.Parent;
        if (directory == null) throw new Exception("Arena example not found.");
        string entry = Path.Combine(directory.FullName, "examples", "arena", "main.vas");
        var durations = new List<double>(); int symbols = 0;
        for (int repeat = 0; repeat < 31; repeat++)
        {
            var timer = Stopwatch.StartNew(); var cache = new Dictionary<string, Source>(StringComparer.OrdinalIgnoreCase);
            Func<string, Source> read = file => { if (cache.TryGetValue(file, out var prior)) return prior; var model = File.Exists(file) ? LanguageModel.Parse(File.ReadAllText(file), file) : null; cache[file] = model; return model; };
            var graph = LanguageModel.Graph(read(entry), read, CancellationToken.None);
            if (graph.Count != 9) throw new Exception("Arena graph changed during measurement.");
            symbols = graph.Sum(m => m.Symbols.Count); durations.Add(timer.Elapsed.TotalMilliseconds);
        }
        durations.Sort();
        string json = JsonConvert.SerializeObject(new { files = 9, symbols, samples = 31, uncachedMedianMs = durations[15], uncachedP95Ms = durations[29], scope = "source model/import graph only; excludes IDE startup/rendering; filesystem cache may be warm" }, Formatting.Indented);
        string output = Path.Combine(directory.FullName, "out", "verification", "ide", "vs-model-performance.json");
        Directory.CreateDirectory(Path.GetDirectoryName(output)); File.WriteAllText(output, json); Console.WriteLine(json);
    }

    internal static void Run(Action<bool, string> check)
    {
        var model = LanguageModel.Parse("/* import Wrong; */ string Text = \"import Fake; class Spoof {}\";\n#define CODE class Forged {}\nint Value = 0xAB'CD;\nimport Real.Library;\nimport int External(int Value) from \"Legacy\";", "main.vas");
        check(model.Includes.Select(i => i.Path).SequenceEqual(new[] { "Real/Library.vas" }), "Only genuine module imports are dependencies");
        check(model.Symbols.Any(s => s.Name == "External" && s.Kind == "function"), "Legacy function imports remain declarations");
        check(model.Symbols.Any(s => s.Name == "CODE" && s.Kind == "macro"), "Macro declaration remains indexed");
        check(LanguageModel.Parse("void Main() {", "main.vas").Problems.Any(p => p.Message == "Expected '}'"), "Missing closing brace is diagnosed");
        check(LanguageModel.Parse("void Main(] {}", "main.vas").Problems.Any(p => p.Message == "Unexpected ']'"), "Mismatched brackets are diagnosed");
        check(LanguageModel.Parse("string Text = \"unfinished", "main.vas").Problems.Any(p => p.Message.Contains("string")), "Unterminated string is diagnosed");
        check(LanguageModel.Parse("void Main() { string Text = \"} )\"; int Value = 0xAB'CD; /* ] */ }", "main.vas").Problems.Count == 0, "Protected tokens do not manufacture syntax errors");
        check(!model.Symbols.Any(s => new[] { "Spoof", "Forged", "AB", "CD" }.Contains(s.Name)), "Strings, macro bodies and numbers cannot forge symbols");
        var library = LanguageModel.Parse("namespace Arena { class FHero { int Health; void Heal(int Amount) {} } int Add(int A, int B) { return A+B; } }", "library.vas");
        var source = LanguageModel.Parse("import Arena.Library;\nvoid Main() { Arena::FHero Hero; int Value = 1; { int Value = 2; print(Value); } print(Value); Hero.Heal(3); Arena::Add(1,2); }", "main.vas");
        var graph = new List<Source> { source, library };
        check(LanguageModel.Resolve(graph, source, source.Text.IndexOf("Heal(3)", StringComparison.Ordinal)).Single().QualifiedName == "Arena::FHero::Heal", "Typed member navigation");
        check(LanguageModel.Resolve(graph, source, source.Text.IndexOf("Add(1,2)", StringComparison.Ordinal)).Single().QualifiedName == "Arena::Add", "Namespace navigation");
        check(LanguageModel.Resolve(graph, source, source.Text.IndexOf("print(Value)", StringComparison.Ordinal) + 6).Single().Start == source.Text.IndexOf("Value = 2", StringComparison.Ordinal), "Nested local shadows outer declaration");
        check(LanguageModel.Resolve(graph, source, source.Text.LastIndexOf("print(Value)", StringComparison.Ordinal) + 6).Single().Start == source.Text.IndexOf("Value = 1", StringComparison.Ordinal), "Outer local restores after nested scope");
        check(library.Symbols.Any(s => s.Name == "Amount" && s.Kind == "parameter"), "Parameters retain their own scope");
        var roles = LanguageModel.Parse("namespace Arena { enum ERole { Warrior, Medic } void Main() { print(Warrior); print(ERole::Warrior); } }", "roles.vas");
        check(LanguageModel.Resolve(new List<Source> { roles }, roles, roles.Text.IndexOf("Warrior);", StringComparison.Ordinal)).Single().Kind == "enumMember", "Enum values export to their namespace");
        check(LanguageModel.Resolve(new List<Source> { roles }, roles, roles.Text.LastIndexOf("Warrior);", StringComparison.Ordinal)).Single().Kind == "enumMember", "Explicit relative enum qualification");
        var directory = new DirectoryInfo(Environment.CurrentDirectory);
        while (directory != null && !Directory.Exists(Path.Combine(directory.FullName, "examples", "arena"))) directory = directory.Parent;
        if (directory == null) throw new Exception("Run core tests from the repository tree to verify the real arena example.");
        string entry = Path.Combine(directory.FullName, "examples", "arena", "main.vas");
        var cache = new Dictionary<string, Source>(StringComparer.OrdinalIgnoreCase);
        Func<string, Source> read = file => { if (cache.TryGetValue(file, out var prior)) return prior; var result = File.Exists(file) ? LanguageModel.Parse(File.ReadAllText(file), file) : null; cache[file] = result; return result; };
        var main = read(entry); var arena = LanguageModel.Graph(main, read, CancellationToken.None);
        check(arena.Count == 9, "Real nine-file arena dependency closure");
        check(Path.GetFileName(LanguageModel.Resolve(arena, main, main.Text.IndexOf("RunDemo(", StringComparison.Ordinal)).Single().File) == "Demo.vas", "Cross-module RunDemo definition");
        check(arena.SelectMany(m => m.Symbols).Any(s => s.Name == "VAS_ARENA_MAX_ROUNDS" && s.Kind == "macro"), "Transitive exported macro visible");
        check(arena.Single(m => m.File.EndsWith("Report.vas", StringComparison.Ordinal)).Symbols.Any(s => s.Name == "PrintStandings"), "Generic/handle parameters preserve function indexing");
    }
}
