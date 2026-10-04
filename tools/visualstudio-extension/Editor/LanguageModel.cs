using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading;
using Newtonsoft.Json.Linq;

namespace VerseAngelScript.VisualStudio.Editor
{
    // Read-only editor declarations. Conditional activation/expansion and overload
    // validation remain compiler-owned; uncertain definitions are returned as candidates.
    internal sealed class Token
    {
        internal string Text, Kind;
        internal int Start, End;
    }
    internal sealed class Symbol
    {
        internal string Name, Kind, File, Container, Type, Detail;
        internal int Start, End, ScopeStart, ScopeEnd;
        internal bool Exported;
        internal string QualifiedName => string.IsNullOrEmpty(Container) ? Name : Container + "::" + Name;
    }
    internal sealed class Include
    {
        internal string Path, Kind;
        internal int Start, End;
    }
    internal sealed class Source
    {
        internal string Text, File;
        internal readonly List<Token> Tokens = new List<Token>();
        internal readonly List<Token> DirectiveWords = new List<Token>();
        internal readonly List<Symbol> Symbols = new List<Symbol>();
        internal readonly List<Include> Includes = new List<Include>();
        internal readonly List<SyntaxIssue> Problems = new List<SyntaxIssue>();
    }
    internal sealed class SyntaxIssue { internal int Start, End; internal string Message; }
    internal static class LanguageModel
    {
        internal static readonly HashSet<string> Keywords = new HashSet<string>(("abstract auto bool break case cast class const continue default do double else enum explicit external false final float for foreach from funcdef get if import in inout int int8 int16 int32 int64 interface is mixin namespace null out override private protected property public return set shared string super switch this true try typedef uint uint8 uint16 uint32 uint64 void while").Split(' '));
        internal static readonly string[] Runtime = { "print", "println", "getCommandLineArgs", "getSystemTime", "assert", "formatInt", "formatUInt", "formatFloat", "parseInt", "parseUInt", "parseFloat" };
        private static readonly HashSet<string> Modifiers = new HashSet<string>(new[] { "const", "private", "protected", "public", "shared", "external", "explicit", "import" });
        private static readonly HashSet<string> NonTypes = new HashSet<string>(new[] { "return", "new", "throw", "case", "else", "break", "continue", "namespace", "class", "interface", "enum" });
        private static readonly Regex Lexer = new Regex("\\s+|//[^\\r\\n]*|/\\*[\\s\\S]*?(?:\\*/|$)|\"\"\"[\\s\\S]*?(?:\"\"\"|$)|\"(?:\\\\[\\s\\S]|[^\"\\\\])*(?:\"|$)|'(?:\\\\[\\s\\S]|[^'\\\\])*(?:'|$)|0[xX][\\da-fA-F](?:[\\da-fA-F]|'(?=[\\da-fA-F]))*|0[bB][01](?:[01]|'(?=[01]))*|0[oO][0-7](?:[0-7]|'(?=[0-7]))*|0[dD]\\d(?:\\d|'(?=\\d))*|(?:\\d(?:\\d|'(?=\\d))*(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?[fF]?|[\\p{L}_][\\p{L}\\p{N}_]*|::|==|!=|<=|>=|&&|\\|\\||[\\s\\S]", RegexOptions.Compiled, TimeSpan.FromSeconds(1));
        private sealed class Scope
        {
            internal string Kind, Name;
            internal int Start, End;
        }
        internal static bool IsCode(string text, int offset) {
            foreach (Match match in Lexer.Matches(text)) {
                if (match.Index > offset) break;
                if (match.Index <= offset && offset < match.Index + match.Length) return !(match.Value.StartsWith("//") || match.Value.StartsWith("/*") || match.Value[0] == '"' || match.Value[0] == '\'');
                if (offset == text.Length && match.Index + match.Length == offset) {
                    if (match.Value.StartsWith("//") || match.Value.StartsWith("/*") && !match.Value.EndsWith("*/")) return false;
                    if (match.Value[0] == '"' || match.Value[0] == '\'') return match.Value.Length > 1 && match.Value[match.Value.Length - 1] == match.Value[0];
                }
            }
            return true;
        }
        internal static Source Parse(string text, string file)
        {
            var model = new Source { Text = text, File = file };
            if (text.Length > 4 * 1024 * 1024) return model;
            for (var match = Lexer.Match(text); match.Success; match = match.NextMatch())
            {
                string value = match.Value;
                if (value.StartsWith("/*") && !value.EndsWith("*/")) model.Problems.Add(new SyntaxIssue { Start = match.Index, End = match.Index + 2, Message = "Unterminated block comment" });
                if (value[0] == '"' || value[0] == '\'') {
                    string delimiter = value.StartsWith("\"\"\"") ? "\"\"\"" : value.Substring(0, 1); int slashes = 0;
                    if (delimiter.Length == 1) for (int i = value.Length - 2; i >= 0 && value[i] == '\\'; i--) slashes++;
                    if (value.Length < delimiter.Length * 2 || !value.EndsWith(delimiter) || slashes % 2 != 0)
                        model.Problems.Add(new SyntaxIssue { Start = match.Index, End = match.Index + delimiter.Length, Message = "Unterminated string literal" });
                }
                if (char.IsWhiteSpace(value[0]) || value.StartsWith("//") || value.StartsWith("/*")) continue;
                if (value == "#")
                {
                    int stop = text.IndexOf('\n', match.Index); if (stop < 0) stop = text.Length;
                    string directive = text.Substring(match.Index, stop - match.Index).TrimEnd('\r');
                    foreach (Match word in Lexer.Matches(directive)) {
                        var token = ToToken(word);
                        if (token.Kind == "id") { token.Start += match.Index; token.End += match.Index; model.DirectiveWords.Add(token); }
                    }
                    var include = Regex.Match(directive, "^#include\\s*(?:\"([^\"\\r\\n]+)\"|'([^'\\r\\n]+)'|<([^>\\r\\n]+)>)");
                    if (include.Success) model.Includes.Add(new Include { Path = include.Groups[1].Success ? include.Groups[1].Value : include.Groups[2].Success ? include.Groups[2].Value : include.Groups[3].Value, Kind = include.Groups[3].Success ? "system" : "quoted", Start = match.Index, End = match.Index + include.Length });
                    var macro = Regex.Match(directive, "^#define[ \\t]+([A-Za-z_]\\w*)(.*)");
                    if (macro.Success) model.Symbols.Add(new Symbol { Name = macro.Groups[1].Value, Kind = "macro", File = file, Start = match.Index + macro.Groups[1].Index, End = match.Index + macro.Groups[1].Index + macro.Groups[1].Length, Container = "", ScopeEnd = text.Length, Exported = true, Detail = directive });
                    match = Lexer.Match(text, stop); if (!match.Success) break;
                    // The next match starts at the end of this directive (usually a newline).
                    if (!char.IsWhiteSpace(match.Value[0])) model.Tokens.Add(ToToken(match));
                    continue;
                }
                model.Tokens.Add(ToToken(match));
            }
            var t = model.Tokens; var pairs = new Dictionary<int, int>(); var brackets = new Stack<int>();
            for (int i = 0; i < t.Count; i++)
            {
                if (new[] { "(", "[", "{" }.Contains(t[i].Text)) brackets.Push(i);
                else if (new[] { ")", "]", "}" }.Contains(t[i].Text)) {
                    if (brackets.Count == 0 || "([{".IndexOf(t[brackets.Peek()].Text) != ")]}".IndexOf(t[i].Text))
                        model.Problems.Add(new SyntaxIssue { Start = t[i].Start, End = t[i].End, Message = "Unexpected '" + t[i].Text + "'" });
                    else pairs[brackets.Pop()] = i;
                }
            }
            if (Regex.IsMatch(text, "(?m)^\\s*#(?:if|ifdef|ifndef)\\b")) model.Problems.RemoveAll(p => p.Message.StartsWith("Unexpected"));
            else foreach (int left in brackets) model.Problems.Add(new SyntaxIssue { Start = t[left].Start, End = t[left].End, Message = "Expected '" + ")]}"["([{".IndexOf(t[left].Text)] + "'" });
            var scopes = new List<Scope>(); var bodies = new Dictionary<int, Scope>(); var declarations = new HashSet<int>();
            Func<string> container = () => string.Join("::", scopes.Where(s => new[] { "namespace", "class", "interface", "enum" }.Contains(s.Kind)).Select(s => s.Name));
            Func<bool> inFunction = () => scopes.Any(s => s.Kind == "function");
            Func<int, string> at = i => i >= 0 && i < t.Count ? t[i].Text : "";
            Func<int, string, Symbol> add = (i, kind) =>
            {
                declarations.Add(i); var scope = scopes.LastOrDefault();
                var symbol = new Symbol { Name = t[i].Text, Kind = kind, File = file, Start = t[i].Start, End = t[i].End, Container = container(), ScopeStart = scope?.Start ?? 0, ScopeEnd = scope?.End ?? text.Length, Exported = !inFunction(), Detail = "" };
                model.Symbols.Add(symbol); return symbol;
            };
            Func<int, string> typeBefore = i =>
            {
                int j = i - 1;
                while (new[] { "@", "&", "const", "in", "out", "inout" }.Contains(at(j))) j--;
                if (at(j) == ">") { int depth = 1; while (--j >= 0 && depth > 0) { if (at(j) == ">") depth++; if (at(j) == "<") depth--; } }
                if (j < 0 || t[j].Kind != "id" || NonTypes.Contains(at(j))) return null;
                int end = j; while (j >= 2 && at(j - 1) == "::" && t[j - 2].Kind == "id") j -= 2;
                string name = string.Concat(t.Skip(j).Take(end - j + 1).Select(x => x.Text));
                int head = j - 1; while (head >= 0 && Modifiers.Contains(at(head))) head--;
                return head >= 0 && !new[] { ";", "{", "}", "(", "," }.Contains(at(head)) ? null : name;
            };
            for (int i = 0; i < t.Count; i++)
            {
                var token = t[i]; string next = at(i + 1);
                if (token.Text == "{") { scopes.Add(bodies.TryGetValue(i, out var body) ? body : new Scope { Kind = "block", Name = "", Start = token.Start, End = pairs.TryGetValue(i, out int end) ? t[end].End : text.Length }); continue; }
                if (token.Text == "}") { if (scopes.Count > 0) scopes.RemoveAt(scopes.Count - 1); continue; }
                if (token.Text == "import" && scopes.Count == 0)
                {
                    int j = i + 1; var parts = new List<string>();
                    while (j < t.Count && t[j].Kind == "id" && !Keywords.Contains(at(j))) { parts.Add(at(j++)); if (at(j) != ".") break; j++; }
                    if (parts.Count > 0 && at(j) == ";") { model.Includes.Add(new Include { Path = string.Join("/", parts) + ".vas", Kind = "module", Start = token.Start, End = t[j].End }); i = j; continue; }
                }
                if (new[] { "namespace", "class", "interface", "enum" }.Contains(token.Text) && i + 1 < t.Count && t[i + 1].Kind == "id")
                {
                    int j = i + 2; while (j < t.Count && at(j) != "{" && at(j) != ";") j++;
                    var type = add(i + 1, token.Text);
                    if (at(j) == "{") bodies[j] = new Scope { Kind = token.Text, Name = type.Name, Start = t[j].Start, End = pairs.TryGetValue(j, out int end) ? t[end].End : text.Length };
                    continue;
                }
                if (token.Kind != "id" || Keywords.Contains(token.Text) || declarations.Contains(i)) continue;
                if (next == "(" && pairs.TryGetValue(i + 1, out int close) && !inFunction())
                {
                    int tail = close + 1; while (new[] { "const", "override", "final", "property" }.Contains(at(tail))) tail++;
                    string type = typeBefore(i); var owner = scopes.LastOrDefault();
                    bool ctor = owner?.Name == token.Text && new[] { "class", "interface" }.Contains(owner.Kind);
                    if ((type != null || ctor) && (new[] { "{", ";" }.Contains(at(tail)) || at(tail) == "from" && tail + 1 < t.Count && t[tail + 1].Kind == "string" && at(tail + 2) == ";"))
                    {
                        int start = at(tail) == "{" ? t[tail].Start : text.Length, end = pairs.TryGetValue(tail, out int bodyEnd) ? t[bodyEnd].End : start;
                        int partStart = i + 2, depth = 0; var parameters = new List<string>();
                        for (int j = partStart; j <= close; j++)
                        {
                            if (new[] { "(", "[", "<" }.Contains(at(j))) depth++;
                            if (new[] { ")", "]", ">" }.Contains(at(j)) && j != close) depth--;
                            if (j == close || at(j) == "," && depth == 0)
                            {
                                var part = t.Skip(partStart).Take(j - partStart).ToList();
                                if (part.Count > 0) parameters.Add(text.Substring(part[0].Start, part.Last().End - part[0].Start));
                                var before = part.TakeWhile(x => x.Text != "=").ToList(); var name = before.LastOrDefault(x => x.Kind == "id" && !Keywords.Contains(x.Text));
                                if (name != null && before.IndexOf(name) > 0) { int atName = t.IndexOf(name); var parameter = add(atName, "parameter"); parameter.Type = typeBefore(atName); parameter.ScopeStart = start; parameter.ScopeEnd = end; parameter.Exported = false; }
                                partStart = j + 1;
                            }
                        }
                        var fn = add(i, scopes.Any(s => s.Kind == "class" || s.Kind == "interface") ? "method" : "function"); fn.Type = type; fn.Detail = (type ?? "") + " " + token.Text + "(" + string.Join(", ", parameters) + ")";
                        if (at(tail) == "{") bodies[tail] = new Scope { Kind = "function", Name = fn.Name, Start = start, End = end };
                        i = close; continue;
                    }
                }
                string variableType = typeBefore(i);
                if (variableType != null && new[] { "=", ";", ",", "[" }.Contains(next)) { var variable = add(i, !inFunction() && scopes.Any(s => s.Kind == "class" || s.Kind == "interface") ? "property" : "variable"); variable.Type = variableType; }
                else if (scopes.LastOrDefault()?.Kind == "enum" && new[] { "=", ",", "}" }.Contains(next)) { var value = add(i, "enumMember"); value.Type = container(); value.Container = string.Join("::", scopes.Where(s => new[] { "namespace", "class", "interface" }.Contains(s.Kind)).Select(s => s.Name)); }
            }
            return model;
        }
        private static Token ToToken(Match match) => new Token { Text = match.Value, Start = match.Index, End = match.Index + match.Length, Kind = match.Value[0] == '"' || match.Value[0] == '\'' ? "string" : char.IsLetter(match.Value[0]) || match.Value[0] == '_' ? "id" : char.IsDigit(match.Value[0]) ? "number" : "punctuation" };

        internal static List<Symbol> Resolve(IReadOnlyList<Source> graph, Source model, int offset)
        {
            var word = model.Tokens.FirstOrDefault(t => t.Kind == "id" && t.Start <= offset && offset <= t.End);
            if (word == null) return new List<Symbol>();
            var own = model.Symbols.FirstOrDefault(s => s.Start == word.Start); if (own != null) return new List<Symbol> { own };
            var all = graph.SelectMany(m => m.Symbols.Where(s => m == model || s.Exported)).ToList();
            var locals = model.Symbols.Where(s => s.Name == word.Text && !s.Exported && s.Start <= offset && s.ScopeStart <= offset && offset <= s.ScopeEnd).OrderBy(s => s.ScopeEnd - s.ScopeStart).ThenByDescending(s => s.Start).ToList();
            if (locals.Count > 0) return locals.Take(1).ToList();
            var t = model.Tokens; int index = t.IndexOf(word);
            if (index >= 2 && t[index - 1].Text == ".")
            {
                var receiver = Resolve(graph, model, t[index - 2].Start);
                if (receiver.Count != 1 || string.IsNullOrEmpty(receiver[0].Type)) return new List<Symbol>();
                string type = receiver[0].Type, qualified = receiver[0].Container + "::" + type;
                return all.Where(s => s.Name == word.Text && (s.Container == type || s.Container == qualified)).ToList();
            }
            string qualifier = ""; int j = index;
            while (j >= 2 && t[j - 1].Text == "::" && t[j - 2].Kind == "id") { qualifier = t[j - 2].Text + (qualifier.Length == 0 ? "" : "::" + qualifier); j -= 2; }
            if (qualifier.Length > 0) {
                string lexical = model.Symbols.Where(s => s.Start <= offset && s.ScopeStart <= offset && offset <= s.ScopeEnd).OrderByDescending(s => s.Container?.Length ?? 0).FirstOrDefault()?.Container ?? "";
                var owners = new List<string> { qualifier };
                for (;;) { if (lexical.Length > 0) owners.Add(lexical + "::" + qualifier); int last = lexical.LastIndexOf("::", StringComparison.Ordinal); if (last < 0) break; lexical = lexical.Substring(0, last); }
                foreach (string scope in owners) { var matches = all.Where(s => s.Name == word.Text && (s.Container == scope || s.Kind == "enumMember" && s.Type == scope)).ToList(); if (matches.Count > 0) return matches; }
                return new List<Symbol>();
            }
            var candidates = all.Where(s => s.Name == word.Text && s.Exported).ToList();
            string owner = model.Symbols.Where(s => s.Start <= offset && s.ScopeStart <= offset && offset <= s.ScopeEnd).OrderByDescending(s => s.Container?.Length ?? 0).FirstOrDefault()?.Container ?? "";
            for (;;) { var selected = candidates.Where(s => s.Container == owner).ToList(); if (selected.Count > 0) return selected; if (owner.Length == 0) break; int last = owner.LastIndexOf("::", StringComparison.Ordinal); owner = last < 0 ? "" : owner.Substring(0, last); }
            return new List<Symbol>();
        }

        internal static List<string> SearchRoots(string file)
        {
            var roots = new List<string>(); string original = Path.GetDirectoryName(file), directory = original;
            for (int depth = 0; directory != null && depth < 16; depth++, directory = Path.GetDirectoryName(directory))
            {
                roots.Add(directory);
                string manifest = Path.Combine(directory, "vas-project.json");
                if (System.IO.File.Exists(manifest))
                {
                    try { if (new FileInfo(manifest).Length < 1024 * 1024) foreach (var unit in JObject.Parse(System.IO.File.ReadAllText(manifest))["compilationUnits"] ?? new JArray()) if (unit["entry"]?.Type == JTokenType.String) roots.Add(Path.GetDirectoryName(Path.GetFullPath(Path.Combine(directory, (string)unit["entry"])))); } catch (Exception e) when (e is IOException || e is Newtonsoft.Json.JsonException || e is ArgumentException) { }
                    break;
                }
                if (Directory.Exists(Path.Combine(directory, ".git")) || System.IO.File.Exists(Path.Combine(directory, ".git"))) break;
            }
            foreach (string root in (Environment.GetEnvironmentVariable("VAS_INCLUDE_PATH") ?? "").Split(Path.PathSeparator).Where(x => x.Length > 0)) roots.Add(Path.GetFullPath(Path.Combine(original, root)));
            return roots.Distinct(StringComparer.OrdinalIgnoreCase).ToList();
        }
        internal static Source Dependency(string file, Include include, IReadOnlyList<string> roots, Func<string, Source> read)
        {
            if (Path.IsPathRooted(include.Path) || include.Path.Contains(":")) return null;
            string relative = include.Path.Replace('\\', Path.DirectorySeparatorChar).Replace('/', Path.DirectorySeparatorChar);
            if (include.Kind != "system") { var local = read(Path.GetFullPath(Path.Combine(Path.GetDirectoryName(file), relative))); if (local != null) return local; }
            var matches = roots.Select(root => Path.GetFullPath(Path.Combine(root, relative))).Distinct(StringComparer.OrdinalIgnoreCase).Select(read).Where(m => m != null).ToList();
            return matches.Count == 1 ? matches[0] : null;
        }
        internal static List<Source> Graph(Source source, Func<string, Source> read, CancellationToken cancellation)
        {
            var roots = SearchRoots(source.File); var graph = new List<Source>(); var queue = new Queue<Source>(); queue.Enqueue(source); var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase); int bytes = 0;
            while (queue.Count > 0 && graph.Count < 256)
            {
                cancellation.ThrowIfCancellationRequested(); var model = queue.Dequeue();
                if (!visited.Add(model.File)) continue; bytes += model.Text.Length; if (bytes > 16 * 1024 * 1024) break; graph.Add(model);
                foreach (var include in model.Includes) { var dependency = Dependency(model.File, include, roots, read); if (dependency != null) queue.Enqueue(dependency); }
            }
            return graph;
        }
    }
}
