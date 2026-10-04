using System;
using System.Collections.Generic;
using System.ComponentModel.Composition;
using System.Linq;
using Microsoft.VisualStudio.Text;
using Microsoft.VisualStudio.Text.Adornments;
using Microsoft.VisualStudio.Text.Tagging;
using Microsoft.VisualStudio.Utilities;

namespace VerseAngelScript.VisualStudio.Editor
{
    [Export(typeof(ITaggerProvider)), ContentType("text"), TagType(typeof(ErrorTag))]
    internal sealed class SyntaxErrorProvider : ITaggerProvider
    {
        [Import] internal EditorWorkspace Workspace = null;
        public ITagger<T> CreateTagger<T>(ITextBuffer buffer) where T : ITag
        {
            var state = Workspace.Get(buffer);
            return state == null ? null : buffer.Properties.GetOrCreateSingletonProperty(() => new SyntaxErrors(state)) as ITagger<T>;
        }
    }
    internal sealed class SyntaxErrors : ITagger<ErrorTag>
    {
        private readonly DocumentState state;
        internal SyntaxErrors(DocumentState state) {
            this.state = state;
            state.Updated += (sender, args) => TagsChanged?.Invoke(this, new SnapshotSpanEventArgs(new SnapshotSpan(state.Buffer.CurrentSnapshot, 0, state.Buffer.CurrentSnapshot.Length)));
        }
        public event EventHandler<SnapshotSpanEventArgs> TagsChanged;
        public IEnumerable<ITagSpan<ErrorTag>> GetTags(NormalizedSnapshotSpanCollection spans) {
            if (spans.Count == 0 || state.AnalyzedSnapshot != spans[0].Snapshot || state.Current == null) yield break;
            foreach (var problem in state.Current.Problems.Concat(state.CompilerProblems)) {
                var span = new SnapshotSpan(state.AnalyzedSnapshot, problem.Start, problem.End - problem.Start);
                if (spans.Any(s => s.IntersectsWith(span))) yield return new TagSpan<ErrorTag>(span, new ErrorTag(PredefinedErrorTypeNames.SyntaxError, problem.Message));
            }
        }
    }
}
