using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.ComponentModel.Composition;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.VisualStudio;
using Microsoft.VisualStudio.Editor;
using Microsoft.VisualStudio.Language.Intellisense;
using Microsoft.VisualStudio.OLE.Interop;
using Microsoft.VisualStudio.Shell;
using Microsoft.VisualStudio.Text;
using Microsoft.VisualStudio.Text.Classification;
using Microsoft.VisualStudio.Text.Editor;
using Microsoft.VisualStudio.TextManager.Interop;
using Microsoft.VisualStudio.Utilities;
using Microsoft.VisualStudio.Threading;

namespace VerseAngelScript.VisualStudio.Editor
{
    [Export(typeof(EditorWorkspace))]
    internal sealed class EditorWorkspace
    {
        [Import] internal ITextDocumentFactoryService Documents = null;
        private readonly ConcurrentDictionary<string, DocumentState> states = new ConcurrentDictionary<string, DocumentState>(StringComparer.OrdinalIgnoreCase);
        internal DocumentState Get(ITextBuffer buffer)
        {
            if (!Documents.TryGetTextDocument(buffer, out var document) || !document.FilePath.EndsWith(".vas", StringComparison.OrdinalIgnoreCase)) return null;
            return states.GetOrAdd(document.FilePath, file => new DocumentState(buffer, file, this));
        }
        internal Dictionary<string, string> OpenSources() => states.ToDictionary(p => p.Key, p => p.Value.Buffer.CurrentSnapshot.GetText(), StringComparer.OrdinalIgnoreCase);
        internal void Changed() { foreach (var state in states.Values) state.Refresh(); }
        internal void Remove(DocumentState state) => states.TryRemove(state.File, out _);
    }

    internal sealed class DocumentState
    {
        internal readonly ITextBuffer Buffer;
        internal readonly string File;
        internal IReadOnlyList<Source> Graph = new List<Source>();
        internal event EventHandler Updated;
        private readonly EditorWorkspace workspace;
        private CancellationTokenSource pending;
        private JoinableTask ready;
        private JoinableTask diagnosticTask;
        private int generation;
        internal ITextSnapshot AnalyzedSnapshot;
        internal IReadOnlyList<SyntaxIssue> CompilerProblems = Array.Empty<SyntaxIssue>();
        private DateTime requested;

        internal DocumentState(ITextBuffer buffer, string file, EditorWorkspace workspace)
        {
            Buffer = buffer; File = file; this.workspace = workspace;
            buffer.Changed += OnChanged;
            if (workspace.Documents.TryGetTextDocument(buffer, out var document)) workspace.Documents.TextDocumentDisposed += OnDisposed;
            Refresh();
        }
        private void OnDisposed(object sender, TextDocumentEventArgs args)
        {
            if (args.TextDocument.TextBuffer != Buffer) return;
            Buffer.Changed -= OnChanged; pending?.Cancel(); pending?.Dispose(); workspace.Remove(this);
            workspace.Documents.TextDocumentDisposed -= OnDisposed;
            workspace.Changed();
        }
        private void OnChanged(object sender, TextContentChangedEventArgs args) { workspace.Changed(); }
        internal void EnsureFresh() { if (DateTime.UtcNow - requested > TimeSpan.FromMilliseconds(500)) Refresh(); }
        internal async Task WaitAsync() { if (ready != null) await ready.JoinAsync(); }
        internal void Refresh()
        {
            pending?.Cancel(); pending?.Dispose(); var cancellation = new CancellationTokenSource(); pending = cancellation;
            var token = cancellation.Token;
            int revision = ++generation; var snapshot = Buffer.CurrentSnapshot; string text = snapshot.GetText();
            var open = workspace.OpenSources(); open[File] = text; requested = DateTime.UtcNow;
            ready = ThreadHelper.JoinableTaskFactory.RunAsync(async () =>
            {
                try
                {
                    await TaskScheduler.Default;
                    await Task.Delay(75, token);
                    var loaded = new Dictionary<string, Source>(StringComparer.OrdinalIgnoreCase);
                    Func<string, Source> read = file =>
                    {
                        token.ThrowIfCancellationRequested();
                        if (loaded.TryGetValue(file, out var prior)) return prior;
                        Source source = null;
                        if (open.TryGetValue(file, out string unsaved)) source = LanguageModel.Parse(unsaved, file);
                        else if (System.IO.File.Exists(file) && new FileInfo(file).Length <= 4 * 1024 * 1024) source = LanguageModel.Parse(System.IO.File.ReadAllText(file), file);
                        loaded[file] = source; return source;
                    };
                    var graph = LanguageModel.Graph(LanguageModel.Parse(text, File), read, token);
                    await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync(token);
                    if (revision != generation || snapshot != Buffer.CurrentSnapshot) return;
                    Graph = graph; CompilerProblems = Array.Empty<SyntaxIssue>(); AnalyzedSnapshot = snapshot; Updated?.Invoke(this, EventArgs.Empty);
                    string compiler = LiveCompiler.ConfiguredCompiler();
                    if (!string.IsNullOrWhiteSpace(compiler)) diagnosticTask = ThreadHelper.JoinableTaskFactory.RunAsync(async () => {
                        try {
                            await TaskScheduler.Default; await Task.Delay(400, token);
                            var problems = await LiveCompiler.InspectAsync(compiler, graph, token);
                            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync(token);
                            if (revision != generation || snapshot != Buffer.CurrentSnapshot) return;
                            CompilerProblems = problems.ToArray(); Updated?.Invoke(this, EventArgs.Empty);
                        } catch (OperationCanceledException) { }
                    });
                }
                catch (OperationCanceledException) { }
                catch (Exception e) when (e is IOException || e is UnauthorizedAccessException || e is ArgumentException || e is System.Text.RegularExpressions.RegexMatchTimeoutException) { }
            });
        }
        internal Source Current => Graph.FirstOrDefault();
        internal List<Symbol> Definitions(int offset)
        {
            if (Current == null || AnalyzedSnapshot != Buffer.CurrentSnapshot) return new List<Symbol>();
            var symbols = LanguageModel.Resolve(Graph, Current, offset);
            if (symbols.Count > 0) return symbols;
            // PREPROCESSOR leaves also permit macro definition navigation.
            string text = Current.Text; int left = Math.Min(offset, text.Length), right = left;
            while (left > 0 && (char.IsLetterOrDigit(text[left - 1]) || text[left - 1] == '_')) left--;
            while (right < text.Length && (char.IsLetterOrDigit(text[right]) || text[right] == '_')) right++;
            string name = text.Substring(left, right - left);
            return Graph.SelectMany(m => m.Symbols).Where(s => s.Kind == "macro" && s.Name == name).ToList();
        }
        internal List<Symbol> Completions(int offset)
        {
            if (Current == null || AnalyzedSnapshot != Buffer.CurrentSnapshot) return new List<Symbol>();
            var symbols = Graph.SelectMany(m => m.Symbols.Where(s => m != Current ? s.Exported : s.Exported || s.Start <= offset && s.ScopeStart <= offset && offset <= s.ScopeEnd)).ToList();
            var match = System.Text.RegularExpressions.Regex.Match(Current.Text.Substring(0, Math.Min(offset, Current.Text.Length)), "([\\p{L}_][\\p{L}\\p{N}_]*(?:::[\\p{L}_][\\p{L}\\p{N}_]*)*)(::|\\.)[\\p{L}\\p{N}_]*$");
            if (!match.Success) return symbols;
            string owner = match.Groups[1].Value;
            if (match.Groups[2].Value == ".")
            {
                var receiver = LanguageModel.Resolve(Graph, Current, match.Index);
                if (receiver.Count != 1 || string.IsNullOrEmpty(receiver[0].Type)) return new List<Symbol>();
                string type = receiver[0].Type, qualified = receiver[0].Container + "::" + type;
                owner = symbols.FirstOrDefault(s => (s.Kind == "class" || s.Kind == "interface") && (s.QualifiedName == type || s.QualifiedName == qualified))?.QualifiedName ?? type;
            }
            if (match.Groups[2].Value == "::" && !owner.Contains("::")) {
                var identities = LanguageModel.Resolve(Graph, Current, match.Index).Where(s => new[] { "class", "interface", "enum", "namespace" }.Contains(s.Kind)).Select(s => s.QualifiedName).Distinct().ToList();
                if (identities.Count == 1) owner = identities[0];
            }
            return symbols.Where(s => (s.Container == owner || s.Kind == "enumMember" && s.Type == owner) && s.Exported).ToList();
        }
    }

    [Export(typeof(ICompletionSourceProvider)), Name("VAS completions"), ContentType("text")]
    internal sealed class CompletionProvider : ICompletionSourceProvider
    {
        [Import] internal EditorWorkspace Workspace = null;
        [Import] internal ISignatureHelpBroker Signatures = null;
        public ICompletionSource TryCreateCompletionSource(ITextBuffer buffer)
        {
            var state = Workspace.Get(buffer); return state == null ? null : new CompletionSource(state, Signatures);
        }
    }
    internal sealed class CompletionSource : ICompletionSource
    {
        private readonly DocumentState state;
        private readonly ISignatureHelpBroker signatures;
        internal CompletionSource(DocumentState state, ISignatureHelpBroker signatures) { this.state = state; this.signatures = signatures; }
        public void AugmentCompletionSession(ICompletionSession session, IList<CompletionSet> sets)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            state.EnsureFresh(); var snapshot = state.Buffer.CurrentSnapshot; var point = session.GetTriggerPoint(snapshot); if (point == null) return;
            int offset = point.Value.Position, start = offset; string text = snapshot.GetText();
            if (!LanguageModel.IsCode(text, offset)) return;
            while (start > 0 && (char.IsLetterOrDigit(text[start - 1]) || text[start - 1] == '_')) start--;
            int following = offset; while (following < text.Length && char.IsWhiteSpace(text[following])) following++;
            bool addCall = following == text.Length || text[following] != '(';
            var symbols = state.Completions(offset); var completions = symbols.GroupBy(s => s.Name).Select(g => new Completion(g.Key,
                g.Key + (addCall && g.Any(s => s.Kind == "function" || s.Kind == "method") ? "()" : ""),
                string.Join("\n", g.Select(s => (string.IsNullOrEmpty(s.Detail) ? s.Kind + " " + s.QualifiedName : s.Detail) + " — " + Path.GetFileName(s.File))), null, null)).ToList();
            if (start == 0 || text[start - 1] != '.' && text[start - 1] != ':') foreach (string name in LanguageModel.Keywords.Concat(LanguageModel.Runtime)) if (!completions.Any(c => c.DisplayText == name))
                completions.Add(new Completion(name, name + (addCall && LanguageModel.Runtime.Contains(name) ? "()" : ""), null, null, null));
            sets.Add(new CompletionSet("VAS", "VAS", snapshot.CreateTrackingSpan(start, offset - start, SpanTrackingMode.EdgeInclusive), completions.OrderBy(c => c.DisplayText), null));
            // Native IntelliSense may consume Tab before the view command filter.
            // Put call syntax in InsertionText so every native commit route agrees.
            if (!session.Properties.ContainsProperty(typeof(CompletionSource))) {
                session.Properties.AddProperty(typeof(CompletionSource), true);
                session.Committed += (sender, args) => {
                    var set = session.SelectedCompletionSet; var selected = set?.SelectionStatus.Completion;
                    if (set?.Moniker != "VAS" || selected == null || !selected.InsertionText.EndsWith("()", StringComparison.Ordinal)) return;
                    var functions = symbols.Where(s => s.Name == selected.DisplayText && (s.Kind == "function" || s.Kind == "method")).ToList();
                    bool arguments = functions.Count == 0 || functions.Any(s => CallHelp.Parameters(s.Detail).Count > 0);
                    if (arguments) {
                        int end = set.ApplicableTo.GetEndPoint(state.Buffer.CurrentSnapshot).Position;
                        session.TextView.Caret.MoveTo(new SnapshotPoint(state.Buffer.CurrentSnapshot, Math.Max(0, end - 1)));
                        var committed = state.Buffer.CurrentSnapshot;
                        ThreadHelper.JoinableTaskFactory.RunAsync(async () => {
                            await state.WaitAsync(); await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
                            if (!session.TextView.IsClosed && committed == state.Buffer.CurrentSnapshot) signatures.TriggerSignatureHelp(session.TextView);
                        }).FileAndForget("VAS/CompletionParameters");
                    }
                };
            }
        }
        public void Dispose() { }
    }

    [Export(typeof(IAsyncQuickInfoSourceProvider)), Name("VAS quick info"), ContentType("text")]
    internal sealed class QuickInfoProvider : IAsyncQuickInfoSourceProvider
    {
        [Import] internal EditorWorkspace Workspace = null;
        public IAsyncQuickInfoSource TryCreateQuickInfoSource(ITextBuffer buffer)
        { var state = Workspace.Get(buffer); return state == null ? null : new QuickInfoSource(state); }
    }
    internal sealed class QuickInfoSource : IAsyncQuickInfoSource
    {
        private readonly DocumentState state;
        internal QuickInfoSource(DocumentState state) { this.state = state; }
        public async Task<QuickInfoItem> GetQuickInfoItemAsync(IAsyncQuickInfoSession session, CancellationToken cancellation)
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync(cancellation);
            state.EnsureFresh(); await state.WaitAsync();
            cancellation.ThrowIfCancellationRequested();
            var point = session.GetTriggerPoint(state.Buffer.CurrentSnapshot); if (point == null) return null;
            var word = state.Current?.Tokens.Concat(state.Current.DirectiveWords).FirstOrDefault(t => t.Start <= point.Value.Position && point.Value.Position <= t.End && t.Kind == "id");
            if (word == null || state.AnalyzedSnapshot != state.Buffer.CurrentSnapshot) return null;
            var symbols = state.Definitions(point.Value.Position); if (symbols.Count == 0) return null;
            var applicable = state.Buffer.CurrentSnapshot.CreateTrackingSpan(word.Start, word.End - word.Start, SpanTrackingMode.EdgeInclusive);
            return new QuickInfoItem(applicable, string.Join("\n", symbols.Select(s => (string.IsNullOrEmpty(s.Detail) ? (s.Type ?? s.Kind) + " " + s.QualifiedName : s.Detail) + " — " + Path.GetFileName(s.File))));
        }
        public void Dispose() { }
    }

    [Export(typeof(IVsTextViewCreationListener)), Name("VAS commands"), ContentType("text"), TextViewRole(PredefinedTextViewRoles.Editable)]
    internal sealed class ViewListener : IVsTextViewCreationListener
    {
        [Import] internal IVsEditorAdaptersFactoryService Adapters = null;
        [Import] internal ICompletionBroker Completion = null;
        [Import] internal ISignatureHelpBroker Signatures = null;
        [Import] internal EditorWorkspace Workspace = null;
        public void VsTextViewCreated(IVsTextView adapter)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            var view = Adapters.GetWpfTextView(adapter); if (view == null) return;
            var state = Workspace.Get(view.TextBuffer); if (state != null) new Commands(adapter, view, state, Completion, Signatures);
        }
    }
    internal sealed class Commands : IOleCommandTarget
    {
        private readonly IVsTextView adapter; private readonly IWpfTextView view; private readonly DocumentState state; private readonly ICompletionBroker broker;
        private readonly ISignatureHelpBroker signatures;
        private IOleCommandTarget next; private ICompletionSession session;
        private readonly EventHandler analyzed;
        internal Commands(IVsTextView adapter, IWpfTextView view, DocumentState state, ICompletionBroker broker, ISignatureHelpBroker signatures)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            this.adapter = adapter; this.view = view; this.state = state; this.broker = broker;
            this.signatures = signatures;
            analyzed = (sender, args) => { if (!view.IsClosed && session != null && !session.IsDismissed) session.Recalculate(); };
            state.Updated += analyzed;
            ErrorHandler.ThrowOnFailure(adapter.AddCommandFilter(this, out next));
            view.Closed += (sender, args) => { state.Updated -= analyzed; session?.Dismiss(); adapter.RemoveCommandFilter(this); };
        }
        public int QueryStatus(ref Guid group, uint count, OLECMD[] commands, IntPtr text)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            int result = next.QueryStatus(ref group, count, commands, text);
            for (int i = 0; i < count; i++) if (group == VSConstants.GUID_VSStandardCommandSet97 && commands[i].cmdID == (uint)VSConstants.VSStd97CmdID.GotoDefn || group == VSConstants.VSStd2K && new[] { (uint)VSConstants.VSStd2KCmdID.AUTOCOMPLETE, (uint)VSConstants.VSStd2KCmdID.COMPLETEWORD, (uint)VSConstants.VSStd2KCmdID.SHOWMEMBERLIST, (uint)VSConstants.VSStd2KCmdID.PARAMINFO }.Contains(commands[i].cmdID))
                commands[i].cmdf = (uint)(OLECMDF.OLECMDF_SUPPORTED | OLECMDF.OLECMDF_ENABLED);
            return commands.Take((int)count).Any(c => (c.cmdf & (uint)OLECMDF.OLECMDF_SUPPORTED) != 0) ? VSConstants.S_OK : result;
        }
        public int Exec(ref Guid group, uint id, uint options, IntPtr input, IntPtr output)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            if (group == VSConstants.GUID_VSStandardCommandSet97 && id == (uint)VSConstants.VSStd97CmdID.GotoDefn)
            {
                int offset = view.Caret.Position.BufferPosition.Position;
                ThreadHelper.JoinableTaskFactory.RunAsync(async () =>
                {
                    state.EnsureFresh(); await state.WaitAsync(); await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
                    var include = state.Current?.Includes.FirstOrDefault(i => i.Start <= offset && offset < i.End);
                    Symbol target;
                    if (include != null)
                    {
                        // Resolve only files already in the analyzed closure: never perform disk IO on the UI thread.
                        var candidates = state.Graph.Where(s => s.File.Replace('\\', '/').EndsWith("/" + include.Path.Replace('\\', '/'), StringComparison.OrdinalIgnoreCase)).ToList();
                        if (candidates.Count != 1) return; target = new Symbol { File = candidates[0].File, Start = 0 };
                    }
                    else { var targets = state.Definitions(offset); if (targets.Count != 1) return; target = targets[0]; }
                    VsShellUtilities.OpenDocument(ServiceProvider.GlobalProvider, target.File, VSConstants.LOGVIEWID_Code, out _, out _, out var frame);
                    var targetView = VsShellUtilities.GetTextView(frame);
                    if (targetView != null)
                    {
                        var source = state.Graph.FirstOrDefault(s => s.File == target.File); string preceding = source?.Text.Substring(0, Math.Min(target.Start, source.Text.Length)) ?? "";
                        int line = preceding.Count(c => c == '\n'), last = preceding.LastIndexOf('\n'); targetView.SetCaretPos(line, preceding.Length - last - 1); targetView.CenterLines(line, 1);
                    }
                }).FileAndForget("VAS/GoToDefinition");
                return VSConstants.S_OK;
            }
            if (group == VSConstants.VSStd2K)
            {
                if (new[] { (uint)VSConstants.VSStd2KCmdID.AUTOCOMPLETE, (uint)VSConstants.VSStd2KCmdID.COMPLETEWORD, (uint)VSConstants.VSStd2KCmdID.SHOWMEMBERLIST }.Contains(id)) { StartCompletion(); return VSConstants.S_OK; }
                if (id == (uint)VSConstants.VSStd2KCmdID.PARAMINFO) { StartSignature(); return VSConstants.S_OK; }
                if (session != null && !session.IsDismissed && (id == (uint)VSConstants.VSStd2KCmdID.TAB || id == (uint)VSConstants.VSStd2KCmdID.RETURN) && session.SelectedCompletionSet?.SelectionStatus.IsSelected == true) {
                    session.Commit();
                    StartSignature();
                    return VSConstants.S_OK;
                }
            }
            int result = next.Exec(ref group, id, options, input, output);
            if (group == VSConstants.VSStd2K && id == (uint)VSConstants.VSStd2KCmdID.TYPECHAR)
            {
                int caret = view.Caret.Position.BufferPosition.Position; string text = view.TextBuffer.CurrentSnapshot.GetText();
                if (caret > 0 && (text[caret - 1] == '(' || text[caret - 1] == ',')) StartSignature();
                else if (caret > 0 && (char.IsLetter(text[caret - 1]) || text[caret - 1] == '_' || text[caret - 1] == '.' || text[caret - 1] == ':')) StartCompletion();
                else if (session != null && !session.IsDismissed) session.Filter();
            }
            return result;
        }
        private void StartCompletion()
        {
            if (!LanguageModel.IsCode(view.TextBuffer.CurrentSnapshot.GetText(), view.Caret.Position.BufferPosition.Position)) return;
            if (state.AnalyzedSnapshot == state.Buffer.CurrentSnapshot) { Complete(); return; }
            var snapshot = state.Buffer.CurrentSnapshot; int offset = view.Caret.Position.BufferPosition.Position;
            ThreadHelper.JoinableTaskFactory.RunAsync(async () =>
            {
                await state.WaitAsync(); await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
                if (!view.IsClosed && state.Buffer.CurrentSnapshot == snapshot && view.Caret.Position.BufferPosition.Position == offset) Complete();
            }).FileAndForget("VAS/Completion");
        }
        private void StartSignature() {
            var snapshot = view.TextBuffer.CurrentSnapshot; int offset = view.Caret.Position.BufferPosition.Position;
            ThreadHelper.JoinableTaskFactory.RunAsync(async () => {
                await state.WaitAsync(); await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
                if (!view.IsClosed && state.Buffer.CurrentSnapshot == snapshot && view.Caret.Position.BufferPosition.Position == offset) signatures.TriggerSignatureHelp(view);
            }).FileAndForget("VAS/Parameters");
        }
        private void Complete() { if (session == null || session.IsDismissed) session = broker.TriggerCompletion(view); else session.Filter(); }
    }
}
