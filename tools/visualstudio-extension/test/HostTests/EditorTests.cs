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
using Microsoft.VisualStudio.OLE.Interop;
using Microsoft.VisualStudio.Shell;
using Microsoft.VisualStudio.Shell.Interop;
using Microsoft.VisualStudio.Text;
using Microsoft.VisualStudio.Text.Classification;
using Microsoft.VisualStudio.Text.BraceCompletion;
using Microsoft.VisualStudio.Text.Editor;
using Microsoft.VisualStudio.TextManager.Interop;
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
                editor.Dte.ExecuteCommand("Edit.CommentSelection");
                var commented = editor.Text;
                Assert.StartsWith("//", commented);
                Assert.Equal(text, commented.Substring(2).TrimStart(' ', '\t'));
                editor.Dte.ExecuteCommand("Edit.Undo");
                Assert.Equal(text, editor.Text);
                editor.SelectAll();
                editor.Dte.ExecuteCommand("Edit.CommentSelection");
                editor.SelectAll();
                editor.Dte.ExecuteCommand("Edit.UncommentSelection");
                Assert.Equal(text, editor.Text);
                editor.Dte.ExecuteCommand("Edit.Undo");
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
                    editor.Dte.ExecuteCommand("Edit.Undo");
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
                    editor.Dte.ExecuteCommand("Edit.Undo");
                    Assert.Equal(original + "{", editor.Text);
                    editor.Dte.ExecuteCommand("Edit.Undo");
                    Assert.Equal(original, editor.Text);
                    editor.Dte.ExecuteCommand("Edit.Redo");
                    Assert.Equal(original + "{", editor.Text);
                    editor.Dte.ExecuteCommand("Edit.Redo");
                    Assert.Equal(original + "{}", editor.Text);
                    editor.Dte.ExecuteCommand("Edit.Undo");
                    Assert.Equal(original + "{", editor.Text);
                    editor.Dte.ExecuteCommand("Edit.Undo");
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
                editor.Dte.ExecuteCommand("Edit.Undo");
                // Undo may combine typing with the newline; restore using real undo only.
                for (var i = 0; i < 3 && editor.Text != text; i++)
                    editor.Dte.ExecuteCommand("Edit.Undo");
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
