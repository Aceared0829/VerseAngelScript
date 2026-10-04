using System;
using System.Diagnostics;
using Process = System.Diagnostics.Process;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Runtime.CompilerServices;
using System.Xml.Linq;
using System.Text;
using System.Threading.Tasks;
using EnvDTE;
using Microsoft.VisualStudio;
using Microsoft.VisualStudio.ComponentModelHost;
using Microsoft.VisualStudio.Editor;
using Microsoft.VisualStudio.Language.Intellisense;
using Microsoft.VisualStudio.OLE.Interop;
using Microsoft.VisualStudio.Shell;
using Microsoft.VisualStudio.Shell.Interop;
using Microsoft.VisualStudio.Text;
using Microsoft.VisualStudio.Text.Classification;
using Microsoft.VisualStudio.Text.BraceCompletion;
using Microsoft.VisualStudio.Text.Editor;
using Microsoft.VisualStudio.TextManager.Interop;
using Microsoft.VisualStudio.Text.Tagging;
using Microsoft.VisualStudio.Settings;
using Microsoft.VisualStudio.Shell.Settings;
using Xunit;
using Xunit.Harness;

[assembly: RequireExtension("VerseAngelScript.vsix")]
[assembly: CollectionBehavior(DisableTestParallelization = true)]

namespace VerseAngelScript.VisualStudio.Tests
{
    // These are IDE facts, never ordinary unit facts. The harness deploys the actual
    // production VSIX and executes this assembly inside an isolated devenv process.
    public sealed class EditorTests : IDisposable
    {
        private const string Source = "// Unicode 注释\r\nvoid Main() {\r\n    int count = 42;\r\n    string title = \"你好 VAS\";\r\n}\r\n";

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task TypingCompletionParameterHelpAndLiveErrors()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            const string collection = "VerseAngelScript/Toolchain";
            var settings = new ShellSettingsManager(ServiceProvider.GlobalProvider).GetWritableSettingsStore(SettingsScope.UserSettings);
            bool hadCompiler = settings.CollectionExists(collection) && settings.PropertyExists(collection, "CompilerPath");
            bool hadLive = settings.CollectionExists(collection) && settings.PropertyExists(collection, "LiveDiagnostics");
            string previousCompiler = hadCompiler ? settings.GetString(collection, "CompilerPath") : "";
            bool previousLive = hadLive && settings.GetBoolean(collection, "LiveDiagnostics");
            if (!settings.CollectionExists(collection)) settings.CreateCollection(collection);
            settings.SetString(collection, "CompilerPath", Environment.GetEnvironmentVariable("VAS_NATIVE_COMPILER")); settings.SetBoolean(collection, "LiveDiagnostics", true);
            string entry = WriteFixture("editing/editor.vas", "void Main() {}");
            string examples = Environment.GetEnvironmentVariable("VAS_LANGUAGE_EXAMPLES");
            string hostConfig = Path.GetFullPath(Path.Combine(examples, "../../sdk/samples/asbuild/bin/config.txt"));
            string config = Path.Combine(Path.GetDirectoryName(entry), ".vas", "vasbuild.config.txt"); Directory.CreateDirectory(Path.GetDirectoryName(config)); File.Copy(hostConfig, config, true);
            try {
                using (var editor = Open(entry)) {
                    var components = (IComponentModel)Package.GetGlobalService(typeof(SComponentModel));
                    var completions = components.GetService<ICompletionBroker>(); var signatures = components.GetService<ISignatureHelpBroker>();
                    using (var errors = components.GetService<IViewTagAggregatorFactoryService>().CreateTagAggregator<IErrorTag>(editor.View)) {
                        Action<string> replace = text => {
                            foreach (var completion in completions.GetSessions(editor.View)) completion.Dismiss();
                            foreach (var signature in signatures.GetSessions(editor.View)) signature.Dismiss();
                            using (var edit = editor.View.TextBuffer.CreateEdit()) { edit.Replace(0, editor.Text.Length, text); edit.Apply(); } editor.End();
                        };
                        Func<string, Task> waitError = async message => {
                            for (int attempt = 0; attempt < 100; attempt++) {
                                var span = new SnapshotSpan(editor.View.TextBuffer.CurrentSnapshot, 0, editor.Text.Length);
                                if (errors.GetTags(span).Any(t => (t.Tag.ToolTipContent?.ToString() ?? "").Contains(message))) return;
                                await Task.Delay(100);
                            }
                            Assert.Fail("Expected live squiggle: " + message);
                        };
                        string prefix = "int CalculateScore(int Left, int Right) { return Left + Right; }\nvoid Main() { Calcu";
                        replace(prefix); await Task.Delay(250); await editor.ExecuteCommandAsync("Edit.ListMembers");
                        var session = completions.GetSessions(editor.View).Single(); var set = session.CompletionSets.Single(s => s.Moniker == "VAS");
                        var item = set.Completions.Single(c => c.DisplayText == "CalculateScore");
                        session.SelectedCompletionSet = set; set.SelectionStatus = new CompletionSelectionStatus(item, true, true);
                        editor.Command(VSConstants.VSStd2KCmdID.TAB);
                        Assert.EndsWith("CalculateScore()", editor.Text); Assert.Equal(')', editor.Text[editor.View.Caret.Position.BufferPosition.Position]);
                        await Task.Delay(250); await editor.ExecuteCommandAsync("Edit.ParameterInfo");
                        var hint = signatures.GetSessions(editor.View).FirstOrDefault(s => s.Signatures.Any(signature => signature.Content.Contains("CalculateScore")));
                        Assert.NotNull(hint); Assert.Equal(2, hint.Signatures.First(s => s.Content.Contains("CalculateScore")).Parameters.Count);
                        replace("void Main() {"); await waitError("Expected '}'");
                        replace("void Main() { int Value = MissingEditorName; }"); await waitError("MissingEditorName");
                        replace("void Main() { int Value = 1 return; }"); await waitError("Expected");
                        replace("void Main() { int Value = 1; }"); await Task.Delay(800);
                        Assert.Empty(errors.GetTags(new SnapshotSpan(editor.View.TextBuffer.CurrentSnapshot, 0, editor.Text.Length)));
                        File.WriteAllText(Path.Combine(Environment.GetEnvironmentVariable("VAS_TEST_RESULTS"), "vs-editing.json"),
                            "{\"host\":\"VS18\",\"nativeFunctionCompletion\":true,\"parameterHelp\":true,\"structuralErrors\":true,\"nativeCompilerErrors\":true,\"missingSemicolon\":true,\"clearedErrorsAfterFix\":true}");
                    }
                }
            } finally {
                if (hadCompiler) settings.SetString(collection, "CompilerPath", previousCompiler); else settings.DeleteProperty(collection, "CompilerPath");
                if (hadLive) settings.SetBoolean(collection, "LiveDiagnostics", previousLive); else settings.DeleteProperty(collection, "LiveDiagnostics");
            }
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task ArenaModulesCompleteNavigateAndHaveSemanticColors()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            string examples = Environment.GetEnvironmentVariable("VAS_LANGUAGE_EXAMPLES");
            Assert.True(Directory.Exists(examples), "Use the actual arena example as the editor fixture.");
            string entry = null;
            foreach (string file in Directory.GetFiles(examples, "*.vas", SearchOption.AllDirectories))
            {
                string relative = file.Substring(examples.TrimEnd('\\', '/').Length + 1);
                string copied = WriteFixture("arena/" + relative, File.ReadAllText(file));
                if (relative == "main.vas") entry = copied;
            }
            Assert.NotNull(entry);
            using (var editor = Open(entry))
            {
                await AssertClassificationAsync(editor, "RunDemo", "VAS function");
                var components = (IComponentModel)Package.GetGlobalService(typeof(SComponentModel));
                var broker = components.GetService<ICompletionBroker>();
                int call = editor.Text.IndexOf("RunDemo(", StringComparison.Ordinal);
                editor.View.Caret.MoveTo(new SnapshotPoint(editor.View.TextBuffer.CurrentSnapshot, call));
                await editor.ExecuteCommandAsync("Edit.ListMembers");
                var session = broker.GetSessions(editor.View).Single();
                // TextMate can contribute separate lexical word guesses. Verify the native VAS declaration set.
                var names = session.CompletionSets.Single(set => set.Moniker == "VAS").Completions.Select(c => c.DisplayText).ToList();
                Assert.Contains("RunDemo", names);
                Assert.Contains("RunBatch", names);
                Assert.DoesNotContain("VAS_ARENA_MAX_ROUNDS", names);
                session.Dismiss();
                editor.View.Caret.MoveTo(new SnapshotPoint(editor.View.TextBuffer.CurrentSnapshot, editor.Text.IndexOf("return 0;", StringComparison.Ordinal)));
                await editor.ExecuteCommandAsync("Edit.ListMembers");
                session = broker.GetSessions(editor.View).Single();
                names = session.CompletionSets.Single(set => set.Moniker == "VAS").Completions.Select(c => c.DisplayText).ToList();
                Assert.Contains("VAS_ARENA_MAX_ROUNDS", names);
                Assert.Contains("println", names);
                session.Dismiss();
                editor.View.Caret.MoveTo(new SnapshotPoint(editor.View.TextBuffer.CurrentSnapshot, call));
                await editor.ExecuteCommandAsync("Edit.GoToDefinition");
                for (int attempt = 0; attempt < 100 && !editor.Dte.ActiveDocument.FullName.EndsWith("Demo.vas", StringComparison.OrdinalIgnoreCase); attempt++) await Task.Delay(100);
                Assert.EndsWith("Demo.vas", editor.Dte.ActiveDocument.FullName);
                string demoPath = editor.Dte.ActiveDocument.FullName;
                using (var demo = Open(demoPath))
                {
                    int declaration = demo.Text.IndexOf("RunDemo(", StringComparison.Ordinal);
                    using (var change = demo.View.TextBuffer.CreateEdit()) { change.Replace(declaration, 7, "RunChangedDemo"); change.Apply(); }
                    editor.View.Caret.MoveTo(new SnapshotPoint(editor.View.TextBuffer.CurrentSnapshot, call));
                    for (int attempt = 0; attempt < 100 && editor.HasClassification("RunDemo", "VAS function"); attempt++) await Task.Delay(100);
                    AssertNoClassification(editor, "RunDemo", "VAS function");
                    await editor.ExecuteCommandAsync("Edit.ListMembers");
                    session = broker.GetSessions(editor.View).Single();
                    names = session.CompletionSets.Single(set => set.Moniker == "VAS").Completions.Select(c => c.DisplayText).ToList();
                    Assert.Contains("RunChangedDemo", names);
                    Assert.DoesNotContain("RunDemo", names);
                    session.Dismiss();
                }
                await AssertClassificationAsync(editor, "RunDemo", "VAS function");
                using (var config = Open(Path.Combine(Path.GetDirectoryName(entry), "Arena", "Config.vas")))
                    await AssertClassificationAsync(config, "VAS_ARENA_MAX_ROUNDS", "VAS macro");
                using (var combatant = Open(Path.Combine(Path.GetDirectoryName(entry), "Arena", "Combatant.vas"))) {
                    int valueUse = combatant.Text.IndexOf("Warrior, 90", StringComparison.Ordinal);
                    combatant.View.Caret.MoveTo(new SnapshotPoint(combatant.View.TextBuffer.CurrentSnapshot, valueUse));
                    await combatant.ExecuteCommandAsync("Edit.GoToDefinition");
                    for (int attempt = 0; attempt < 100 && combatant.View.Caret.Position.BufferPosition.Position >= valueUse; attempt++) await Task.Delay(100);
                    Assert.True(combatant.View.Caret.Position.BufferPosition.Position < valueUse, "Enum value F12 must navigate to its declaration.");
                }
                string results = Environment.GetEnvironmentVariable("VAS_TEST_RESULTS");
                File.WriteAllText(Path.Combine(results, "vs-language.json"), "{\"host\":\"VS18\",\"fixtureFiles\":9,\"semanticColors\":true,\"completion\":true,\"macroCompletion\":true,\"f12\":true,\"unsavedInvalidation\":true,\"enumNavigation\":true}");
            }
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task RunsInsideVisualStudio2026ExperimentalHost()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            Assert.Equal("devenv", Process.GetCurrentProcess().ProcessName);
            var version = FileVersionInfo.GetVersionInfo(Process.GetCurrentProcess().MainModule.FileName);
            Assert.Equal(18, version.FileMajorPart);
            Assert.Equal(Path.GetFullPath(Environment.GetEnvironmentVariable("VAS_EXPECTED_DEVENV")),
                Path.GetFullPath(Process.GetCurrentProcess().MainModule.FileName), ignoreCase: true);
            var commandLine = (IVsAppCommandLine)Package.GetGlobalService(typeof(SVsAppCommandLine));
            ErrorHandler.ThrowOnFailure(commandLine.GetOption("RootSuffix", out var present, out var suffix));
            Assert.Equal(1, present);
            Assert.Equal("VASIntegration", suffix);
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task ClassifiesUnicodeSpacePathAndReopens()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            var path = WriteFixture("Unicode 空间/classification 示例.vas", Source);
            for (var attempt = 0; attempt < 2; attempt++)
            {
                using (var editor = Open(path))
                {
                    Assert.Equal(Source, editor.Text);
                    await AssertClassificationAsync(editor, "void", "keyword");
                    await AssertClassificationAsync(editor, "42", "number");
                    await AssertClassificationAsync(editor, "你好 VAS", "string");
                    await AssertClassificationAsync(editor, "Unicode 注释", "comment");
                    Assert.False(editor.View.TextBuffer.ContentType.IsOfType("C/C++"));
                }
            }
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task TripleStringsAndNonNestingCommentsUseCanonicalGrammar()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            const string text = "string value = \"\"\"first\r\nraw \\q 你好\r\nlast\"\"\";\r\n/* outer /* inner */ int after = 73;\r\n";
            using (var editor = Open(WriteFixture("grammar.vas", text)))
            {
                await AssertClassificationAsync(editor, "raw \\q 你好", "string");
                await AssertClassificationAsync(editor, "outer", "comment");
                await AssertClassificationAsync(editor, "int", "keyword");
                await AssertClassificationAsync(editor, "73", "number");
                AssertNoClassification(editor, "after", "comment");
            }
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task CommentAndUncommentAreRealUndoableCommands()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            const string text = "int count = 42;";
            using (var editor = Open(WriteFixture("comments.vas", text)))
            {
                await AssertClassificationAsync(editor, "int", "keyword");
                editor.SelectAll();
                await editor.ExecuteCommandAsync("Edit.CommentSelection");
                var commented = editor.Text;
                Assert.StartsWith("//", commented);
                Assert.Equal(text, commented.Substring(2).TrimStart(' ', '\t'));
                await editor.ExecuteCommandAsync("Edit.Undo");
                Assert.Equal(text, editor.Text);
                editor.SelectAll();
                await editor.ExecuteCommandAsync("Edit.CommentSelection");
                editor.SelectAll();
                await editor.ExecuteCommandAsync("Edit.UncommentSelection");
                Assert.Equal(text, editor.Text);
                await editor.ExecuteCommandAsync("Edit.Undo");
                Assert.Equal(commented, editor.Text);
            }
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task BracePairingAndTypingAreUndoable()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            const string original = "// pairs\r\n";
            using (var editor = Open(WriteFixture("pairs.vas", original)))
            {
                await AssertClassificationAsync(editor, "pairs", "comment");
                var option = DefaultTextViewOptions.BraceCompletionEnabledOptionName;
                var previous = editor.View.Options.GetOptionValue<bool>(option);
                try
                {
                    // Exercise the supported editor preference in this test view only.
                    // The production content-only VSIX never changes user preferences.
                    editor.View.Options.SetOptionValue(option, false);
                    editor.End();
                    editor.Type('{');
                    Assert.Equal(original + "{", editor.Text);
                    await editor.ExecuteCommandAsync("Edit.Undo");
                    Assert.Equal(original, editor.Text);

                    editor.View.Options.SetOptionValue(option, true);
                    var components = (IComponentModel)Package.GetGlobalService(typeof(SComponentModel));
                    var manager = components.GetService<IBraceCompletionManagerFactory>().TryGetBraceCompletionManager(editor.View);
                    File.WriteAllText(Path.Combine(Environment.GetEnvironmentVariable("VAS_TEST_RESULTS"), "brace-editor-options.txt"),
                        $"CommandRoute=SUIHostCommandDispatcher\r\nBraceCompletionEnabled={editor.View.Options.GetOptionValue<bool>(option)}\r\nManagerEnabled={manager?.Enabled.ToString() ?? "missing"}\r\nContentType={editor.View.TextBuffer.ContentType.TypeName}");
                    editor.End();
                    editor.Type('{');
                    Assert.Equal(original + "{}", editor.Text);
                    Assert.Equal(editor.Text.Length - 1, editor.View.Caret.Position.BufferPosition.Position);
                    // Default brace completion commits its closing character in a
                    // separate transaction after typing (BraceCompletionDefaultSession.Start).
                    await editor.ExecuteCommandAsync("Edit.Undo");
                    Assert.Equal(original + "{", editor.Text);
                    await editor.ExecuteCommandAsync("Edit.Undo");
                    Assert.Equal(original, editor.Text);
                    await editor.ExecuteCommandAsync("Edit.Redo");
                    Assert.Equal(original + "{", editor.Text);
                    await editor.ExecuteCommandAsync("Edit.Redo");
                    Assert.Equal(original + "{}", editor.Text);
                    await editor.ExecuteCommandAsync("Edit.Undo");
                    Assert.Equal(original + "{", editor.Text);
                    await editor.ExecuteCommandAsync("Edit.Undo");
                    Assert.Equal(original, editor.Text);
                    editor.End();
                    editor.Type('(');
                    Assert.Equal(original + "()", editor.Text);
                    editor.Type(')');
                    Assert.Equal(original + "()", editor.Text);
                    Assert.Equal(editor.Text.Length, editor.View.Caret.Position.BufferPosition.Position);
                }
                finally { editor.View.Options.SetOptionValue(option, previous); }
            }
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task NewlineIndentsAndUndoRestoresText()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            const string text = "void Main() {";
            using (var editor = Open(WriteFixture("indent.vas", text)))
            {
                await AssertClassificationAsync(editor, "void", "keyword");
                editor.View.Options.SetOptionValue(DefaultOptions.IndentSizeOptionId, 4);
                editor.End();
                editor.Command(VSConstants.VSStd2KCmdID.RETURN);
                // VS can represent automatic indentation as virtual space until typing.
                var virtualPoint = editor.View.Caret.Position.VirtualBufferPosition;
                var line = virtualPoint.Position.GetContainingLine();
                var tabSize = editor.View.Options.GetOptionValue(DefaultOptions.TabSizeOptionId);
                var prefix = line.GetText().Substring(0, virtualPoint.Position.Position - line.Start.Position);
                Assert.Equal(4, IndentationColumns(prefix, tabSize) + virtualPoint.VirtualSpaces);
                editor.Type('x');
                var snapshot = editor.View.TextBuffer.CurrentSnapshot;
                Assert.Equal(2, snapshot.LineCount);
                Assert.Equal(text, snapshot.GetLineFromLineNumber(0).GetText());
                var typedLine = snapshot.GetLineFromLineNumber(1).GetText();
                Assert.Equal("x", typedLine.TrimStart(' ', '\t'));
                Assert.Equal(4, IndentationColumns(typedLine.Substring(0, typedLine.Length - 1), tabSize));
                await editor.ExecuteCommandAsync("Edit.Undo");
                // Undo may combine typing with the newline; restore using real undo only.
                for (var i = 0; i < 3 && editor.Text != text; i++)
                    await editor.ExecuteCommandAsync("Edit.Undo");
                Assert.Equal(text, editor.Text);
            }
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task CppAndAsAreNotClaimedByVasGrammar()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            using (var editor = Open(WriteFixture("control.cpp", "// native C++\r\nint value = 42;\r\nmixin Foo;")))
            {
                Assert.True(editor.View.TextBuffer.ContentType.IsOfType("C/C++"), editor.View.TextBuffer.ContentType.TypeName);
                await AssertClassificationAsync(editor, "int", "keyword");
                AssertNoClassification(editor, "mixin", "keyword");
            }
            using (var editor = Open(WriteFixture("control.as", "mixin Foo;\r\nfuncdef void Callback();")))
            {
                await Task.Delay(500);
                AssertNoClassification(editor, "mixin", "keyword");
                AssertNoClassification(editor, "funcdef", "keyword");
                Assert.False(editor.View.TextBuffer.ContentType.IsOfType("C/C++"));
            }
        }

        public void Dispose()
        {
            // Same in-memory log interface used by Microsoft's integration harness.
            // It only reads this isolated IDE process, never the runner's profile.
            ThreadHelper.ThrowIfNotOnUIThread();
            var service = (IActivityLogDumper)Package.GetGlobalService(typeof(SVsActivityLog));
            var buffer = service.GetActivityLogBuffer();
            var end = buffer.LastIndexOf('>');
            Assert.True(end >= 0, "The isolated IDE did not provide its ActivityLog buffer.");
            var log = XElement.Parse("<entries>" + buffer.Substring(0, end + 1) + "</entries>");
            var directory = Environment.GetEnvironmentVariable("VAS_TEST_RESULTS");
            Directory.CreateDirectory(directory);
            log.Save(Path.Combine(directory, "VAS-ActivityLog-" + Guid.NewGuid().ToString("N") + ".xml"));
            var errors = log.Elements("entry").Where(entry =>
                string.Equals((string)entry.Element("type"), "Error", StringComparison.OrdinalIgnoreCase)
                && (entry.Value.IndexOf("VerseAngelScript", StringComparison.OrdinalIgnoreCase) >= 0
                    || entry.Value.IndexOf("VAS.pkgdef", StringComparison.OrdinalIgnoreCase) >= 0));
            Assert.Empty(errors);
        }

        [ComImport, Guid("4F111D70-F291-428D-8E40-CB1D4B1A7BDE"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IActivityLogDumper
        {
            [MethodImpl(MethodImplOptions.InternalCall)]
            [return: MarshalAs(UnmanagedType.BStr)]
            string GetActivityLogBuffer();
        }

        private static int IndentationColumns(string whitespace, int tabSize)
        {
            Assert.True(tabSize > 0);
            var columns = 0;
            foreach (var character in whitespace)
            {
                Assert.True(character == ' ' || character == '\t', "Indentation must contain whitespace only.");
                columns = character == '\t' ? columns + tabSize - columns % tabSize : columns + 1;
            }
            return columns;
        }

        private static string WriteFixture(string name, string text)
        {
            var root = Environment.GetEnvironmentVariable("VAS_TEST_WORKSPACE");
            Assert.False(string.IsNullOrWhiteSpace(root), "VAS_TEST_WORKSPACE must point to an isolated test directory.");
            var path = Path.Combine(root, name.Replace('/', Path.DirectorySeparatorChar));
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            File.WriteAllText(path, text, new UTF8Encoding(false));
            return path;
        }

        private static Editor Open(string path)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            var dte = (DTE)Package.GetGlobalService(typeof(SDTE));
            var window = dte.ItemOperations.OpenFile(path, EnvDTE.Constants.vsViewKindCode);
            window.Activate();
            var manager = (IVsTextManager)Package.GetGlobalService(typeof(SVsTextManager));
            ErrorHandler.ThrowOnFailure(manager.GetActiveView(1, null, out var nativeView));
            var components = (IComponentModel)Package.GetGlobalService(typeof(SComponentModel));
            var view = components.GetService<IVsEditorAdaptersFactoryService>().GetWpfTextView(nativeView);
            Assert.NotNull(view);
            return new Editor(dte, window, view, components.GetService<IClassifierAggregatorService>().GetClassifier(view.TextBuffer));
        }

        private static async Task AssertClassificationAsync(Editor editor, string token, string classification)
        {
            for (var attempt = 0; attempt < 100; attempt++)
            {
                if (editor.HasClassification(token, classification))
                    return;
                await Task.Delay(100);
            }
            Assert.Fail($"Expected {classification} for '{token}'. Actual: {editor.Classifications(token)}; content type: {editor.View.TextBuffer.ContentType.TypeName}");
        }

        private static void AssertNoClassification(Editor editor, string token, string classification)
        {
            Assert.False(editor.HasClassification(token, classification), $"Unexpected {classification} for '{token}': {editor.Classifications(token)}");
        }

        private sealed class Editor : IDisposable
        {
            public DTE Dte { get; }
            public IWpfTextView View { get; }
            private readonly Window window;
            private readonly IOleCommandTarget commands;
            private readonly IClassifier classifier;
            public string Text => View.TextBuffer.CurrentSnapshot.GetText();

            public Editor(DTE dte, Window window, IWpfTextView view, IClassifier classifier)
            {
                ThreadHelper.ThrowIfNotOnUIThread();
                Dte = dte;
                this.window = window;
                // Use the full IDE command route, as Microsoft's VS18 harness does.
                // The native view's terminal command target can bypass editor handlers.
                commands = (IOleCommandTarget)Package.GetGlobalService(typeof(SUIHostCommandDispatcher));
                View = view;
                this.classifier = classifier;
            }

            private SnapshotSpan Token(string token)
            {
                var snapshot = View.TextBuffer.CurrentSnapshot;
                var position = snapshot.GetText().IndexOf(token, StringComparison.Ordinal);
                Assert.True(position >= 0, $"Missing token: {token}");
                return new SnapshotSpan(snapshot, position, token.Length);
            }

            public bool HasClassification(string token, string type) => classifier.GetClassificationSpans(Token(token))
                .Any(span => span.ClassificationType.IsOfType(type));

            public string Classifications(string token) => string.Join(", ", classifier.GetClassificationSpans(Token(token))
                .Select(span => span.ClassificationType.Classification + ":" + span.Span.GetText()));

            public void SelectAll() => View.Selection.Select(new SnapshotSpan(View.TextBuffer.CurrentSnapshot, 0, Text.Length), false);
            public void End() { View.Selection.Clear(); View.Caret.MoveTo(new SnapshotPoint(View.TextBuffer.CurrentSnapshot, Text.Length)); }

            public async Task ExecuteCommandAsync(string name)
            {
                await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
                window.Activate();
                View.VisualElement.Focus();
                var command = Dte.Commands.Item(name, 0);
                var textManager = (IVsTextManager)Package.GetGlobalService(typeof(SVsTextManager));
                var components = (IComponentModel)Package.GetGlobalService(typeof(SComponentModel));
                var adapters = components.GetService<IVsEditorAdaptersFactoryService>();
                var elapsed = Stopwatch.StartNew();
                string state;
                do
                {
                    await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
                    if (!View.HasAggregateFocus) { Dte.MainWindow.Activate(); window.Activate(); View.VisualElement.Focus(); }
                    var activeResult = textManager.GetActiveView(1, null, out var activeNativeView);
                    var active = ErrorHandler.Succeeded(activeResult) && activeNativeView != null
                        && ReferenceEquals(View, adapters.GetWpfTextView(activeNativeView));
                    var focused = View.HasAggregateFocus;
                    var available = command.IsAvailable;
                    state = $"activeView={active}; focused={focused}; available={available}; activeViewHResult=0x{activeResult:X8}";
                    if (active && focused && available)
                    {
                        // No await or other command between readiness and the single
                        // mutation. Never retry a command that may have already executed.
                        Dte.ExecuteCommand(name);
                        return;
                    }
                    // Yield for normal frame activation/command-context notifications;
                    // this loop observes readiness and never executes a mutation.
                    await Task.Delay(50);
                }
                while (elapsed.Elapsed < TimeSpan.FromSeconds(10));
                Assert.Fail($"Editor command '{name}' did not become ready: {state}");
            }

            public void Command(VSConstants.VSStd2KCmdID command)
            {
                ThreadHelper.ThrowIfNotOnUIThread();
                window.Activate();
                var group = VSConstants.VSStd2K;
                ErrorHandler.ThrowOnFailure(commands.Exec(ref group, (uint)command, 0, IntPtr.Zero, IntPtr.Zero));
            }

            public void Type(char character)
            {
                ThreadHelper.ThrowIfNotOnUIThread();
                window.Activate();
                var input = Marshal.AllocCoTaskMem(32);
                try
                {
                    Marshal.GetNativeVariantForObject((ushort)character, input);
                    var group = VSConstants.VSStd2K;
                    ErrorHandler.ThrowOnFailure(commands.Exec(ref group, (uint)VSConstants.VSStd2KCmdID.TYPECHAR, 0, input, IntPtr.Zero));
                }
                finally { Marshal.FreeCoTaskMem(input); }
            }

            public void Dispose()
            {
                ThreadHelper.ThrowIfNotOnUIThread();
                (classifier as IDisposable)?.Dispose();
                window.Close(vsSaveChanges.vsSaveChangesNo);
            }
        }
    }
}
