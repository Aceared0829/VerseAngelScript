using System;
using System.Collections.Generic;
using System.ComponentModel.Composition;
using System.Linq;
using System.Windows.Media;
using Microsoft.VisualStudio.Text;
using Microsoft.VisualStudio.Text.Classification;
using Microsoft.VisualStudio.Utilities;

namespace VerseAngelScript.VisualStudio.Editor
{
    internal static class ColorNames
    {
        internal const string Type = "VAS type", Function = "VAS function", Macro = "VAS macro";
        [Export(typeof(ClassificationTypeDefinition)), Name(Type), BaseDefinition("identifier")]
        internal static ClassificationTypeDefinition TypeDefinition = null;
        [Export(typeof(ClassificationTypeDefinition)), Name(Function), BaseDefinition("identifier")]
        internal static ClassificationTypeDefinition FunctionDefinition = null;
        [Export(typeof(ClassificationTypeDefinition)), Name(Macro), BaseDefinition("preprocessor keyword")]
        internal static ClassificationTypeDefinition MacroDefinition = null;
    }
    [Export(typeof(EditorFormatDefinition)), ClassificationType(ClassificationTypeNames = ColorNames.Type), Name(ColorNames.Type), UserVisible(true), Order(After = Priority.Default)]
    internal sealed class TypeFormat : ClassificationFormatDefinition
    { public TypeFormat() { DisplayName = ColorNames.Type; ForegroundColor = Color.FromRgb(78, 201, 176); } }
    [Export(typeof(EditorFormatDefinition)), ClassificationType(ClassificationTypeNames = ColorNames.Function), Name(ColorNames.Function), UserVisible(true), Order(After = Priority.Default)]
    internal sealed class FunctionFormat : ClassificationFormatDefinition
    { public FunctionFormat() { DisplayName = ColorNames.Function; ForegroundColor = Color.FromRgb(176, 144, 40); } }
    [Export(typeof(EditorFormatDefinition)), ClassificationType(ClassificationTypeNames = ColorNames.Macro), Name(ColorNames.Macro), UserVisible(true), Order(After = Priority.Default)]
    internal sealed class MacroFormat : ClassificationFormatDefinition
    { public MacroFormat() { DisplayName = ColorNames.Macro; ForegroundColor = Color.FromRgb(177, 112, 193); } }

    [Export(typeof(IClassifierProvider)), Name("VAS semantic colors"), ContentType("text")]
    internal sealed class SemanticColorProvider : IClassifierProvider
    {
        [Import] internal EditorWorkspace Workspace = null;
        [Import] internal IClassificationTypeRegistryService Registry = null;
        public IClassifier GetClassifier(ITextBuffer buffer)
        { var state = Workspace.Get(buffer); return state == null ? null : buffer.Properties.GetOrCreateSingletonProperty(() => new SemanticColors(state, Registry)); }
    }
    internal sealed class SemanticColors : IClassifier
    {
        private readonly DocumentState state;
        private readonly IClassificationTypeRegistryService registry;
        internal SemanticColors(DocumentState state, IClassificationTypeRegistryService registry)
        { this.state = state; this.registry = registry; state.Updated += (sender, args) => ClassificationChanged?.Invoke(this, new ClassificationChangedEventArgs(new SnapshotSpan(state.Buffer.CurrentSnapshot, 0, state.Buffer.CurrentSnapshot.Length))); }
        public event EventHandler<ClassificationChangedEventArgs> ClassificationChanged;
        public IList<ClassificationSpan> GetClassificationSpans(SnapshotSpan span)
        {
            var result = new List<ClassificationSpan>(); var source = state.Current;
            if (source == null || state.AnalyzedSnapshot != span.Snapshot) return result;
            var names = state.Graph.SelectMany(m => m.Symbols).Where(s => new[] { "class", "interface", "enum", "namespace", "function", "method", "macro" }.Contains(s.Kind)).GroupBy(s => s.Name).ToDictionary(g => g.Key, g => g.Select(s => s.Kind).Distinct().Count() == 1 ? g.First().Kind : "");
            foreach (var token in source.Tokens.Where(t => t.Kind == "id" && t.Start >= span.Start.Position && t.End <= span.End.Position))
            {
                if (LanguageModel.Keywords.Contains(token.Text) || !names.TryGetValue(token.Text, out string kind)) continue;
                if (kind == "function" || kind == "method")
                { int next = token.End; while (next < source.Text.Length && char.IsWhiteSpace(source.Text[next])) next++; if (next == source.Text.Length || source.Text[next] != '(') continue; }
                string color = kind == "macro" ? ColorNames.Macro : kind == "function" || kind == "method" ? ColorNames.Function : ColorNames.Type;
                result.Add(new ClassificationSpan(new SnapshotSpan(span.Snapshot, token.Start, token.End - token.Start), registry.GetClassificationType(color)));
            }
            foreach (var word in source.DirectiveWords.Where(t => t.Start >= span.Start.Position && t.End <= span.End.Position))
                if (names.TryGetValue(word.Text, out string kind) && kind == "macro") result.Add(new ClassificationSpan(new SnapshotSpan(span.Snapshot, word.Start, word.End - word.Start), registry.GetClassificationType(ColorNames.Macro)));
            return result;
        }
    }
}
