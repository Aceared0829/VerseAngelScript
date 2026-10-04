using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel.Composition;
using System.Linq;
using Microsoft.VisualStudio.Language.Intellisense;
using Microsoft.VisualStudio.Text;
using Microsoft.VisualStudio.Utilities;

namespace VerseAngelScript.VisualStudio.Editor
{
    internal sealed class CallSite { internal string Name; internal int NameOffset, Open, Parameter; }
    internal static class CallHelp
    {
        internal static CallSite Find(string text, int offset) {
            var source = LanguageModel.Parse(text, ""); var stack = new Stack<CallSite>(); Token previous = null;
            foreach (var token in source.Tokens) {
                if (token.Start >= offset) break;
                if (new[] { "(", "[", "{" }.Contains(token.Text)) stack.Push(new CallSite { Name = token.Text == "(" && previous?.Kind == "id" ? previous.Text : "", NameOffset = previous?.Start ?? 0, Open = token.Start });
                else if (new[] { ")", "]", "}" }.Contains(token.Text)) { if (stack.Count > 0) stack.Pop(); }
                else if (token.Text == "," && stack.Count > 0) stack.Peek().Parameter++;
                previous = token;
            }
            return stack.FirstOrDefault(c => c.Name.Length > 0 && !new[] { "if", "for", "while", "switch", "catch" }.Contains(c.Name));
        }
        internal static List<Span> Parameters(string label) {
            var tokens = LanguageModel.Parse(label, "").Tokens; var ranges = new List<Span>();
            int open = tokens.FindIndex(t => t.Text == "("), depth = 0;
            if (open < 0) return ranges;
            int begin = tokens[open].End;
            foreach (var token in tokens.Skip(open + 1)) {
                if (new[] { "(", "[", "{", "<" }.Contains(token.Text)) depth++;
                else if (token.Text == ")" && depth == 0 || token.Text == "," && depth == 0) {
                    int start = begin, end = token.Start;
                    while (start < end && char.IsWhiteSpace(label[start])) start++;
                    while (end > start && char.IsWhiteSpace(label[end - 1])) end--;
                    if (end > start) ranges.Add(new Span(start, end - start));
                    begin = token.End; if (token.Text == ")") break;
                } else if (new[] { ")", "]", "}", ">" }.Contains(token.Text)) depth--;
            }
            return ranges;
        }
    }
    [Export(typeof(ISignatureHelpSourceProvider)), Name("VAS parameters"), ContentType("text")]
    internal sealed class SignatureProvider : ISignatureHelpSourceProvider {
        [Import] internal EditorWorkspace Workspace = null;
        public ISignatureHelpSource TryCreateSignatureHelpSource(ITextBuffer buffer) { var state = Workspace.Get(buffer); return state == null ? null : new SignatureSource(state); }
    }
    internal sealed class SignatureSource : ISignatureHelpSource {
        private readonly DocumentState state;
        internal SignatureSource(DocumentState state) { this.state = state; }
        public void AugmentSignatureHelpSession(ISignatureHelpSession session, IList<ISignature> signatures) {
            var snapshot = state.Buffer.CurrentSnapshot; var point = session.GetTriggerPoint(snapshot); if (point == null || snapshot != state.AnalyzedSnapshot) return;
            var call = CallHelp.Find(snapshot.GetText(), point.Value.Position); if (call == null) return;
            var declarations = LanguageModel.Resolve(state.Graph, state.Current, call.NameOffset).Where(s => s.Kind == "function" || s.Kind == "method").Select(s => s.Detail).Distinct().ToList();
            if (declarations.Count == 0 && (call.Name == "print" || call.Name == "println")) declarations.Add(call.Name + "(const string &in Format, const ?&in Arguments...)");
            var ours = declarations.Select(label => new Signature(state.Buffer, label, snapshot.CreateTrackingSpan(call.Open + 1, point.Value.Position - call.Open - 1, SpanTrackingMode.EdgeInclusive), call.Parameter)).ToList();
            foreach (var signature in ours) signatures.Add(signature);
            EventHandler dismissed = null;
            dismissed = (sender, args) => { foreach (var signature in ours) signature.Dispose(); session.Dismissed -= dismissed; };
            session.Dismissed += dismissed;
        }
        public ISignature GetBestMatch(ISignatureHelpSession session) {
            var call = CallHelp.Find(state.Buffer.CurrentSnapshot.GetText(), session.TextView.Caret.Position.BufferPosition.Position);
            return session.Signatures.FirstOrDefault(s => s.Parameters.Count > (call?.Parameter ?? 0)) ?? session.Signatures.FirstOrDefault();
        }
        public void Dispose() { }
    }
    internal sealed class Signature : ISignature, IDisposable {
        private readonly ITextBuffer buffer;
        public string Content { get; }
        public string PrettyPrintedContent => Content;
        public string Documentation => "VAS function parameters";
        public ITrackingSpan ApplicableToSpan { get; }
        public ReadOnlyCollection<IParameter> Parameters { get; }
        public IParameter CurrentParameter { get; private set; }
        public event EventHandler<CurrentParameterChangedEventArgs> CurrentParameterChanged;
        internal Signature(ITextBuffer buffer, string label, ITrackingSpan span, int index) {
            this.buffer = buffer; Content = label; ApplicableToSpan = span;
            Parameters = CallHelp.Parameters(label).Select(p => (IParameter)new Parameter(this, label.Substring(p.Start, p.Length), p)).ToList().AsReadOnly();
            CurrentParameter = index < Parameters.Count ? Parameters[index] : null;
            buffer.Changed += Changed;
        }
        private void Changed(object sender, TextContentChangedEventArgs args) {
            int offset = ApplicableToSpan.GetEndPoint(args.After).Position;
            var call = CallHelp.Find(args.After.GetText(), offset); int index = call?.Parameter ?? -1;
            var next = index >= 0 && index < Parameters.Count ? Parameters[index] : null;
            if (next != CurrentParameter) { var before = CurrentParameter; CurrentParameter = next; CurrentParameterChanged?.Invoke(this, new CurrentParameterChangedEventArgs(before, next)); }
            if (call == null) buffer.Changed -= Changed;
        }
        public void Dispose() { buffer.Changed -= Changed; }
    }
    internal sealed class Parameter : IParameter {
        public ISignature Signature { get; }
        public string Name { get; }
        public string Documentation => Name;
        public Span Locus { get; }
        public Span PrettyPrintedLocus => Locus;
        internal Parameter(ISignature signature, string name, Span span) { Signature = signature; Name = name; Locus = span; }
    }
}
