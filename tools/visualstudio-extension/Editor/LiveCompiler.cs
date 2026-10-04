using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.VisualStudio.Settings;
using Microsoft.VisualStudio.Shell;
using Microsoft.VisualStudio.Shell.Settings;
using VerseAngelScript.VisualStudio.Build;

namespace VerseAngelScript.VisualStudio.Editor
{
    internal static class LiveCompiler
    {
        private static readonly SemaphoreSlim Gate = new SemaphoreSlim(1);
        internal static string ConfiguredCompiler() {
            ThreadHelper.ThrowIfNotOnUIThread();
            var store = new ShellSettingsManager(ServiceProvider.GlobalProvider).GetReadOnlySettingsStore(SettingsScope.UserSettings);
            return store.CollectionExists(BuildOptions.Collection) && store.PropertyExists(BuildOptions.Collection, "LiveDiagnostics") && store.GetBoolean(BuildOptions.Collection, "LiveDiagnostics")
                && store.PropertyExists(BuildOptions.Collection, "CompilerPath") ? store.GetString(BuildOptions.Collection, "CompilerPath") : "";
        }
        internal static async Task<List<SyntaxIssue>> InspectAsync(string compiler, IReadOnlyList<Source> graph, CancellationToken cancellation) {
            var issues = new List<SyntaxIssue>(); if (string.IsNullOrWhiteSpace(compiler) || graph.Count == 0) return issues;
            await Gate.WaitAsync(cancellation); string temporary = null;
            try {
                compiler = NativeProcess.ValidateCompiler(compiler); var source = graph[0];
                string root = Path.GetDirectoryName(source.File), config = null;
                for (int depth = 0; root != null && depth < 16; depth++, root = Path.GetDirectoryName(root)) {
                    config = Path.Combine(root, ".vas", "vasbuild.config.txt"); if (File.Exists(config)) break;
                }
                if (root == null || config == null || !File.Exists(config) || graph.Any(s => !s.File.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))) return issues;
                temporary = Path.Combine(Path.GetTempPath(), "vas-vs-diagnostics-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(temporary);
                foreach (var model in graph) {
                    cancellation.ThrowIfCancellationRequested(); string target = Path.Combine(temporary, model.File.Substring(root.Length + 1));
                    Directory.CreateDirectory(Path.GetDirectoryName(target)); File.WriteAllText(target, model.Text, new UTF8Encoding(false));
                }
                string entry = Path.Combine(temporary, source.File.Substring(root.Length + 1)); var roots = new List<string>();
                for (string parent = Path.GetDirectoryName(entry); parent != null && parent.StartsWith(temporary, StringComparison.OrdinalIgnoreCase); parent = Path.GetDirectoryName(parent)) roots.Add(parent);
                var environment = new Dictionary<string, string> { ["VAS_INCLUDE_PATH"] = string.Join(Path.PathSeparator.ToString(), roots) };
                var result = await NativeProcess.RunAsync(compiler, new[] { config, entry, Path.Combine(temporary, "diagnostics.vasbc") }, temporary,
                    TimeSpan.FromSeconds(10), 2 * 1024 * 1024, cancellation, environment: environment);
                string output = Encoding.UTF8.GetString(result.Stdout) + result.Stderr;
                foreach (string line in output.Split('\n')) {
                    var match = Regex.Match(line.TrimEnd('\r'), "^(.*) \\((\\d+),\\s*(\\d+)\\)\\s*:\\s*ERR\\s*:\\s*(.+)$");
                    if (!match.Success || !string.Equals(Path.GetFullPath(match.Groups[1].Value), entry, StringComparison.OrdinalIgnoreCase)
                        || !int.TryParse(match.Groups[2].Value, out int row) || !int.TryParse(match.Groups[3].Value, out int column)) continue;
                    int start = Offset(source.Text, row, column); if (start < 0) continue;
                    int end = start; while (end < source.Text.Length && (char.IsLetterOrDigit(source.Text[end]) || source.Text[end] == '_')) end++;
                    if (end == start && end < source.Text.Length) end += char.IsHighSurrogate(source.Text[end]) && end + 1 < source.Text.Length ? 2 : 1;
                    issues.Add(new SyntaxIssue { Start = start, End = end, Message = match.Groups[4].Value });
                }
                return issues;
            } catch (Exception e) when (e is IOException || e is UnauthorizedAccessException || e is ArgumentException || e is InvalidOperationException || e is TimeoutException) { return issues; }
            finally {
                if (temporary != null && string.Equals(Path.GetDirectoryName(Path.GetFullPath(temporary)), Path.GetFullPath(Path.GetTempPath()).TrimEnd(Path.DirectorySeparatorChar), StringComparison.OrdinalIgnoreCase)
                    && Path.GetFileName(temporary).StartsWith("vas-vs-diagnostics-", StringComparison.Ordinal)) {
                    try { Directory.Delete(temporary, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
                }
                Gate.Release();
            }
        }
        private static int Offset(string text, int row, int column) {
            if (row < 1 || column < 1) return -1; int start = 0;
            for (int line = 1; line < row; line++) { int newline = text.IndexOf('\n', start); if (newline < 0) return -1; start = newline + 1; }
            int offset = start, bytes = 0;
            while (offset < text.Length && text[offset] != '\n' && bytes < column - 1) {
                int width = char.IsHighSurrogate(text[offset]) && offset + 1 < text.Length && char.IsLowSurrogate(text[offset + 1]) ? 2 : 1;
                int count = Encoding.UTF8.GetByteCount(text.Substring(offset, width)); if (bytes + count > column - 1) break;
                bytes += count; offset += width;
            }
            return offset;
        }
    }
}
