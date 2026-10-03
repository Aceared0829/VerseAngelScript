using System;
using System.Collections;
using System.Collections.Generic;
using System.CodeDom.Compiler;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Automation.Peers;
using System.Windows.Automation.Provider;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using EnvDTE;
using Microsoft.CSharp;
using Microsoft.VisualStudio;
using Microsoft.VisualStudio.ComponentModelHost;
using Microsoft.VisualStudio.Editor;
using Microsoft.VisualStudio.OLE.Interop;
using Microsoft.VisualStudio.Settings;
using Microsoft.VisualStudio.Shell;
using Microsoft.VisualStudio.Shell.Interop;
using Microsoft.VisualStudio.Shell.Settings;
using Microsoft.VisualStudio.Text.Editor;
using Microsoft.VisualStudio.TextManager.Interop;
using Microsoft.VisualStudio.Threading;
using Microsoft.VisualStudio.Workspace.VSIntegration.Contracts;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;
using Xunit;
using Xunit.Harness;
using Process = System.Diagnostics.Process;
using Task = System.Threading.Tasks.Task;
using Window = System.Windows.Window;

namespace VerseAngelScript.VisualStudio.Tests
{
    // Every fact is executed by the VS18 harness after RequireExtension installs the
    // production VSIX. Tests invoke the registered command and actual WPF controls;
    // reflection reads state only, except the isolated native argv transport check.
    public sealed class ProjectBuildTests : IDisposable
    {
        private const string PackageId = "d3a6e112-5f40-4df1-8bb7-0b79f0e74226";
        private static readonly Guid CommandSet = new Guid("540e5ef6-e458-48d9-941d-990c7cb7da39");
        private const uint BuildCommand = 0x0100, CancelCommand = 0x0101;
        private const string SettingsCollection = "VerseAngelScript/Toolchain";
        private object package;
        private string packageAssemblySha256;
        private object Coordinator => Member(package, "Coordinator");
        private string Status => Convert.ToString(Member(Coordinator, "Status"));
        private bool Busy => Convert.ToBoolean(Member(Coordinator, "Busy"));
        private string previousCompiler;
        private bool hadCompiler;
        private string openedFolder;
        private RootEvents rootEvents;
        private bool packageClosed;
        private Window activeDialog;
        private readonly List<EnvDTE.Window> windows = new List<EnvDTE.Window>();
        private readonly List<Fixture> fixtures = new List<Fixture>();

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task SolutionCommandBuildsExplicitUnitWithNativeCompiler()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var fixture = CreateFixture("solution Unicode 漢字 😀 & literal $ (space)");
            await OpenSolutionAsync(fixture);
            ConfigureCompiler(NativeCompiler);
            Assert.False(Directory.Exists(fixture.OutputDirectory));
            await ChooseAndBuildAsync(fixture, 1);
            await WaitForAsync(() => !Busy && File.Exists(fixture.SecondOutput), "selected native unit output");
            Assert.StartsWith("Build succeeded", Status);
            Assert.False(File.Exists(fixture.FirstOutput));
            Assert.True(new FileInfo(fixture.SecondOutput).Length > 0);
            AssertReportDigest(fixture.Entry);
            Assert.Contains("alternate", fixture.LastUnitDetails);
            SaveEvidence(nameof(SolutionCommandBuildsExplicitUnitWithNativeCompiler),
                "solutionOpened", "registeredCommand", "explicitUnit", "nativeOutput", "sourceDigest", "outputCreationPreservesBuild");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task OpenFolderCommandBuildsExplicitUnitWithNativeCompiler()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var fixture = CreateFixture("folder Unicode 空间 & $ (literal)");
            await OpenFolderAsync(fixture);
            ConfigureCompiler(NativeCompiler);
            await ChooseAndBuildAsync(fixture, 0);
            await WaitForAsync(() => !Busy && File.Exists(fixture.FirstOutput), "Open Folder native build");
            Assert.False(File.Exists(fixture.SecondOutput));
            Assert.True(new FileInfo(fixture.FirstOutput).Length > 0);
            SaveEvidence(nameof(OpenFolderCommandBuildsExplicitUnitWithNativeCompiler),
                "folderOpened", "registeredCommand", "explicitUnit", "nativeOutput");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task ReadUnitsRequiresExplicitSelectionAndCancelIsPassive()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var fixture = CreateFixture("passive");
            fixture.CreateControlCompiler();
            ConfigureCompiler(fixture.Compiler);
            await OpenSolutionAsync(fixture);
            await Task.Delay(600); // Let passive shell/file notifications drain.
            QueryCommand(BuildCommand);
            var editor = OpenEditor(fixture.Entry);
            editor.View.TextBuffer.Insert(editor.View.TextBuffer.CurrentSnapshot.Length, "\r\n// edit only");
            await Task.Delay(300);
            Assert.Empty(fixture.Calls);
            CloseEditor(editor.Window);
            await DriveDialogAsync(fixture, dialog =>
            {
                Assert.False(Control<Button>(dialog, "BuildSelectedUnit").IsEnabled);
                Assert.Equal(-1, Control<ListBox>(dialog, "ProjectUnits").SelectedIndex);
                Invoke(Control<Button>(dialog, "CancelVasProject"));
                return Task.CompletedTask;
            });
            Assert.Empty(fixture.Calls);
            await DriveDialogAsync(fixture, async dialog =>
            {
                await ReadUnitsAsync(dialog);
                Assert.Equal(-1, Control<ListBox>(dialog, "ProjectUnits").SelectedIndex);
                Assert.False(Control<Button>(dialog, "BuildSelectedUnit").IsEnabled);
                var generation = Convert.ToInt64(Member(Coordinator, "Generation"));
                // A write alongside the compiler and a new unrelated project
                // directory must not invalidate the prepared descriptor.
                File.WriteAllText(Path.Combine(Path.GetDirectoryName(fixture.Compiler), "unrelated.txt"), "not an input");
                var notes = Path.Combine(fixture.Root, "notes"); Directory.CreateDirectory(notes);
                File.WriteAllText(Path.Combine(notes, "unrelated.txt"), "not an input");
                await Task.Delay(800); // Drain real native directory metadata events and the periodic validation.
                Assert.Equal(generation, Convert.ToInt64(Member(Coordinator, "Generation")));
                Assert.True(dialog.IsVisible, DiagnosticState());
                Assert.Equal(2, Control<ListBox>(dialog, "ProjectUnits").Items.Count);
                Assert.Equal(new[] { "describe" }, fixture.Calls);
                Assert.False(Directory.Exists(fixture.OutputDirectory));
                Invoke(Control<Button>(dialog, "CancelVasProject"));
            });
            await WaitForAsync(() => !Busy, "cancelled descriptor dialog");
            Assert.Equal(new[] { "describe" }, fixture.Calls);
            Assert.False(Directory.Exists(fixture.OutputDirectory));
            SaveEvidence(nameof(ReadUnitsRequiresExplicitSelectionAndCancelIsPassive),
                "noDefaultUnit", "buildInitiallyDisabled", "zeroPassiveProcesses", "zeroCancelProcesses", "zeroOutputDirectories", "unrelatedWritesPreserveSelection");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task LegacyManifestNeverSelectsEmbeddedTool()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var fixture = CreateFixture("legacy");
            fixture.CreateControlCompiler();
            File.WriteAllText(fixture.Manifest, new JObject {
                ["name"] = "Legacy", ["entry"] = "src/main 漢字 😀.vas", ["builderConfig"] = "host/default.txt",
                ["bytecodeOutput"] = "out/default.vasbc", ["builder"] = fixture.Compiler, ["runner"] = fixture.Compiler
            }.ToString(Formatting.None), new UTF8Encoding(false));
            await OpenSolutionAsync(fixture);
            ConfigureCompiler(NativeCompiler);
            await ChooseAndBuildAsync(fixture, 0);
            await WaitForAsync(() => !Busy && File.Exists(fixture.FirstOutput), "legacy native output");
            Assert.Empty(fixture.Calls);
            Assert.Contains("main", fixture.LastUnitDetails);
            Assert.True(ErrorTasks().Any(t => TaskText(t).IndexOf("legacy", StringComparison.OrdinalIgnoreCase) >= 0)
                || Status.IndexOf("legacy", StringComparison.OrdinalIgnoreCase) >= 0, "Native legacy warning must be visible.");
            SaveEvidence(nameof(LegacyManifestNeverSelectsEmbeddedTool),
                "legacyWarning", "explicitUnit", "embeddedToolNotRun", "nativeOutput");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task InvalidDescriptorNeverEnablesBuild()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var fixture = CreateFixture("bad descriptor");
            fixture.CreateControlCompiler();
            File.WriteAllText(fixture.DescriptorFile, "{\"protocol\":\"vas-project\",\"version\":999,\"success\":true}\n");
            await OpenSolutionAsync(fixture);
            ConfigureCompiler(fixture.Compiler);
            await DriveDialogAsync(fixture, async dialog =>
            {
                Invoke(Control<Button>(dialog, "ReadProjectUnits"));
                await WaitForAsync(() => fixture.Calls.Count == 1 && Control<TextBlock>(dialog, "ProjectBuildStatus").Text == "Unsupported VAS project descriptor protocol.", "rejected descriptor protocol");
                Assert.False(Control<Button>(dialog, "BuildSelectedUnit").IsEnabled);
                Assert.Empty(Control<ListBox>(dialog, "ProjectUnits").Items.Cast<object>());
                Assert.DoesNotContain("Select a compilation unit", Control<TextBlock>(dialog, "ProjectBuildStatus").Text);
                Invoke(Control<Button>(dialog, "CancelVasProject"));
            });
            Assert.Equal(new[] { "describe" }, fixture.Calls);
            Assert.False(Directory.Exists(fixture.OutputDirectory));
            SaveEvidence(nameof(InvalidDescriptorNeverEnablesBuild), "failedDescriptor", "buildDisabled", "noOutput");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task CompilerArgumentsPreserveUnicodeAndMetacharacters()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var root = CreateFixture("argv 漢字 😀 & $ (space)").Root;
            var native = Environment.GetEnvironmentVariable("VAS_NATIVE_ARGV_FIXTURE");
            Assert.True(File.Exists(native), "CMake native wide argv receiver is required.");
            var values = new[] { "", "plain", "漢字 😀", "a b", "literal & $ ; ` (x)", "a\"b", "a\\\\\"b", "ends in slash\\", "two slashes\\\\" };
            var result = await InvokeNativeProcessAsync(native, new[] { "argument-list" }.Concat(values).ToArray(), root);
            Assert.Equal(0, Convert.ToInt32(Member(result, "ExitCode")));
            var lines = Encoding.UTF8.GetString((byte[])Member(result, "Stdout")).TrimEnd('\n').Split('\n');
            Assert.Equal(values.Length.ToString(), lines[0]);
            Assert.Equal(values, lines.Skip(1).Select(value => Encoding.UTF8.GetString(Convert.FromBase64String(value))).ToArray());
            var nonzero = await InvokeNativeProcessAsync(native, new[] { "arguments", values[4] }, root);
            Assert.Equal(7, Convert.ToInt32(Member(nonzero, "ExitCode")));
            Assert.Equal(values[4] + "\n", Encoding.UTF8.GetString((byte[])Member(nonzero, "Stdout")));
            var stderr = Member(nonzero, "Stderr");
            Assert.Equal("diagnostic 漢字😀\n", stderr is byte[] bytes ? Encoding.UTF8.GetString(bytes) : Convert.ToString(stderr));
            SaveEvidence(nameof(CompilerArgumentsPreserveUnicodeAndMetacharacters),
                "nativeWideArgv", "literalArguments", "stderrSeparate", "exitCodePreserved");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task NativeDiagnosticsNavigateUtf8ByteColumnsWithSourceDigest()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var fixture = CreateFixture("native navigation");
            // The native callback's column is UTF-8 bytes; 😀 occupies four bytes
            // and two editor UTF-16 code units before the unknown identifier.
            const string source = "void main() { /* 😀 漢字 */ missing_native_symbol(); }\r\n";
            File.WriteAllText(fixture.Entry, source, new UTF8Encoding(false));
            await OpenSolutionAsync(fixture);
            ConfigureCompiler(NativeCompiler);
            await ChooseAndBuildAsync(fixture, 0);
            await WaitForAsync(() => !Busy && ErrorTasks().Any(t => TaskText(t).Contains("missing_native_symbol")), "native diagnostic Error List task");
            Assert.False(File.Exists(fixture.FirstOutput));
            AssertReportDigest(fixture.Entry);
            var task = ErrorTasks().First(t => TaskText(t).Contains("missing_native_symbol"));
            ErrorHandler.ThrowOnFailure(task.NavigateTo());
            await WaitForAsync(() =>
            {
                var currentView = TryActiveView();
                return currentView != null && currentView.TextBuffer.CurrentSnapshot.GetText() == source
                    && currentView.Caret.Position.BufferPosition.Position == source.IndexOf("missing_native_symbol", StringComparison.Ordinal);
            }, "native Error List navigation");
            var view = ActiveView();
            Assert.Equal(source, view.TextBuffer.CurrentSnapshot.GetText());
            Assert.Equal(source.IndexOf("missing_native_symbol", StringComparison.Ordinal), view.Caret.Position.BufferPosition.Position);
            SaveEvidence(nameof(NativeDiagnosticsNavigateUtf8ByteColumnsWithSourceDigest),
                "nativeCompileFailure", "sourceDigest", "errorListTask", "navigateToCalled", "astralUtf16Position");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task ChangedOrDigestlessDiagnosticsDoNotNavigate()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var fixture = CreateFixture("navigation freshness");
            const string source = "void main() { /* 😀 */ missing_native_symbol(); }\r\n";
            File.WriteAllText(fixture.Entry, source, new UTF8Encoding(false));
            await OpenSolutionAsync(fixture);
            ConfigureCompiler(NativeCompiler);
            await ChooseAndBuildAsync(fixture, 0);
            await WaitForAsync(() => !Busy && ErrorTasks().Any(t => TaskText(t).Contains("missing_native_symbol")), "source diagnostic");
            var stale = ErrorTasks().First(t => TaskText(t).Contains("missing_native_symbol"));
            File.WriteAllText(fixture.Entry, "// changed saved bytes\r\n" + source, new UTF8Encoding(false));
            var unrelated = OpenEditor(fixture.Unrelated);
            await AssertNavigationDoesNotMoveAsync(stale, unrelated.View);
            CloseEditor(unrelated.Window);

            fixture.CreateControlCompiler();
            fixture.WriteReport(digest: false);
            ConfigureCompiler(fixture.Compiler);
            await ChooseAndBuildAsync(fixture, 0);
            await WaitForAsync(() => !Busy && ErrorTasks().Any(t => TaskText(t).Contains("fixture_missing_symbol")), "digestless task");
            var digestless = ErrorTasks().First(t => TaskText(t).Contains("fixture_missing_symbol"));
            unrelated = OpenEditor(fixture.Unrelated);
            await AssertNavigationDoesNotMoveAsync(digestless, unrelated.View);
            SaveEvidence(nameof(ChangedOrDigestlessDiagnosticsDoNotNavigate),
                "changedSourceRejected", "digestlessRejected", "actualTaskNavigateTo");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task DirtyInputsAndAliasesBlockNativeBuild()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var fixture = CreateFixture("dirty inputs");
            fixture.CreateControlCompiler();
            await OpenSolutionAsync(fixture);
            ConfigureCompiler(fixture.Compiler);
            var hardLink = Path.Combine(fixture.Root, "hardlink.vas");
            Assert.True(CreateHardLink(hardLink, fixture.Entry, IntPtr.Zero), "NTFS hard-link fixture is required: " + Marshal.GetLastWin32Error());
            var junction = Path.Combine(fixture.Root, "junction");
            CreateJunction(junction, Path.GetDirectoryName(fixture.Entry));
            var aliases = new[] { fixture.Entry, fixture.Config, fixture.Manifest, hardLink, Path.Combine(junction, Path.GetFileName(fixture.Entry)) };
            foreach (var path in aliases)
            {
                var editor = OpenEditor(path);
                editor.View.TextBuffer.Insert(0, "// unsaved buffer only\r\n");
                var count = fixture.Calls.Count(call => call == "build");
                await DriveDialogAsync(fixture, async dialog =>
                {
                    Invoke(Control<Button>(dialog, "ReadProjectUnits"));
                    await WaitForAsync(() => Control<ListBox>(dialog, "ProjectUnits").Items.Count > 0
                        || Control<TextBlock>(dialog, "ProjectBuildStatus").Text.IndexOf("Reading native", StringComparison.Ordinal) < 0,
                        "dirty input preflight");
                    var list = Control<ListBox>(dialog, "ProjectUnits");
                    if (list.Items.Count > 0)
                    {
                        SelectUnit(list, 0);
                        Invoke(Control<Button>(dialog, "BuildSelectedUnit"));
                    }
                    else Invoke(Control<Button>(dialog, "CancelVasProject"));
                }, allowPreflightRejection: true);
                await WaitForAsync(() => !Busy, "dirty input build rejection");
                Assert.Equal(count, fixture.Calls.Count(call => call == "build"));
                Assert.False(Directory.Exists(fixture.OutputDirectory));
                CloseEditor(editor.Window);
            }
            SaveEvidence(nameof(DirtyInputsAndAliasesBlockNativeBuild), "dirtyEntryBlocked", "dirtyConfigBlocked", "dirtyManifestBlocked",
                "dirtyHardLinkBlocked", "dirtyJunctionBlocked", "noBuildProcesses");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task RepeatedBuildCancelAndCloseIgnoreLateResults()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var fixture = CreateFixture("lifecycle");
            fixture.CreateControlCompiler();
            fixture.WriteReport(digest: true);
            File.WriteAllText(fixture.WaitFile, "block build");
            await OpenSolutionAsync(fixture);
            ConfigureCompiler(fixture.Compiler);
            await ChooseAndBuildAsync(fixture, 0);
            await WaitForAsync(() => Busy && fixture.Calls.Contains("build"), "running control compiler");
            var count = fixture.Calls.Count;
            var repeatFlags = QueryCommand(BuildCommand);
            if ((repeatFlags & (uint)OLECMDF.OLECMDF_ENABLED) != 0) ExecuteCommand(BuildCommand);
            await Task.Delay(200);
            Assert.Equal(count, fixture.Calls.Count);
            Assert.DoesNotContain(Application.Current.Windows.Cast<Window>(), w => AutomationProperties.GetAutomationId(w) == "VasBuildProjectDialog");
            ExecuteCommand(CancelCommand);
            await WaitForAsync(() => !Busy && fixture.NoLiveProcesses(), "cancel command process exit");
            var cancelledStatus = Status;
            File.WriteAllText(fixture.ReleaseFile, "late result");
            await Task.Delay(350);
            Assert.Equal(cancelledStatus, Status);
            Assert.DoesNotContain(ErrorTasks(), task => TaskText(task).Contains("fixture_missing_symbol"));

            File.Delete(fixture.ReleaseFile);
            await ChooseAndBuildAsync(fixture, 0);
            await WaitForAsync(() => Busy && fixture.Calls.Count(call => call == "build") == 2, "second control compiler");
            await CloseSolutionAsync();
            await WaitForAsync(() => !Busy && fixture.NoLiveProcesses(), "solution close cancels compiler");
            var closedStatus = Status;
            File.WriteAllText(fixture.ReleaseFile, "late result after root closed");
            await Task.Delay(350);
            Assert.Equal(closedStatus, Status);
            Assert.DoesNotContain(ErrorTasks(), task => TaskText(task).Contains("fixture_missing_symbol"));

            File.Delete(fixture.ReleaseFile);
            await OpenFolderAsync(fixture);
            await ChooseAndBuildAsync(fixture, 0);
            await WaitForAsync(() => Busy && fixture.Calls.Count(call => call == "build") == 3, "Open Folder control compiler");
            await CloseSolutionAsync();
            await WaitForAsync(() => !Busy && fixture.NoLiveProcesses(), "native CloseFolder cancellation");
            var folderClosedStatus = Status;
            File.WriteAllText(fixture.ReleaseFile, "late result after folder closed");
            await Task.Delay(350);
            Assert.Equal(folderClosedStatus, Status);
            Assert.DoesNotContain(ErrorTasks(), task => TaskText(task).Contains("fixture_missing_symbol"));

            // Reopening the identical folder is a new native lifetime. Its new
            // explicit operation must survive completion notifications and still
            // be cancelled by the next real close.
            var previousGeneration = Convert.ToInt64(Member(Coordinator, "Generation"));
            File.Delete(fixture.ReleaseFile);
            await OpenFolderAsync(fixture);
            await ChooseAndBuildAsync(fixture, 0);
            await WaitForAsync(() => Busy && fixture.Calls.Count(call => call == "build") == 4, "same folder reopened with a fresh operation");
            Assert.True(Convert.ToInt64(Member(Coordinator, "Generation")) > previousGeneration);
            await CloseSolutionAsync();
            await WaitForAsync(() => !Busy && fixture.NoLiveProcesses(), "reopened folder close cancels fresh compiler");
            Assert.DoesNotContain(ErrorTasks(), task => TaskText(task).Contains("fixture_missing_symbol"));
            Assert.Equal(4, fixture.Calls.Count(call => call == "describe"));
            Assert.Equal(4, fixture.Calls.Count(call => call == "build"));
            Assert.False(Directory.Exists(fixture.OutputDirectory));

            // Establish that folder cleanup permits the next actual solution
            // lifetime, instead of deferring that evidence to another test.
            await OpenSolutionAsync(fixture);
            ConfigureCompiler(NativeCompiler);
            await ChooseAndBuildAsync(fixture, 1);
            await WaitForAsync(() => !Busy && Status.StartsWith("Build succeeded", StringComparison.Ordinal), "folder to solution native build");
            Assert.True(File.Exists(fixture.SecondOutput));
            AssertReportDigest(fixture.Entry);
            SaveEvidence(nameof(RepeatedBuildCancelAndCloseIgnoreLateResults),
                "repeatDisabled", "cancelCommand", "closeInvalidated", "lateResultIgnored", "processReleased", "sameRootFolderReopened", "folderToSolutionSettled");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task NativeIncludeObservationsRetainPartialDependencies()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var fixture = CreateFixture("native include graph");
            var first = Path.Combine(fixture.Root, "src", "first.vas");
            var retained = Path.Combine(fixture.Root, "src", "retained.vas");
            File.WriteAllText(first, "void first() {}\n"); File.WriteAllText(retained, "void retained() {}\n");
            File.WriteAllText(fixture.Entry, "#include \"first.vas\"\n#include \"retained.vas\"\nvoid main() {}\n", new UTF8Encoding(false));
            await OpenSolutionAsync(fixture); ConfigureCompiler(NativeCompiler);
            await ChooseAndBuildAsync(fixture, 0);
            await WaitForAsync(() => !Busy && File.Exists(fixture.FirstOutput), "native complete include graph");
            var complete = Member(Coordinator, "LastReport");
            Assert.True(Convert.ToBoolean(Member(complete, "DependenciesComplete")));
            AssertReportDigest(first); AssertReportDigest(retained);
            Assert.Contains(((IEnumerable)Member(complete, "Observations")).Cast<object>(), item => SamePath(Convert.ToString(Member(Member(item, "Identity"), "Display")), retained));

            File.WriteAllText(fixture.Entry, "#include \"first.vas\"\n#include \"missing.vas\"\nvoid main() {}\n", new UTF8Encoding(false));
            await ChooseAndBuildAsync(fixture, 0);
            await WaitForAsync(() => !Busy && Status.StartsWith("Build failed", StringComparison.Ordinal), "native partial include failure");
            Assert.False(Convert.ToBoolean(Member(Member(Coordinator, "LastReport"), "DependenciesComplete")));
            Assert.Contains(((IEnumerable)Member(Coordinator, "ObservedPaths")).Cast<string>(), path => SamePath(path, retained));
            var generation = Convert.ToInt64(Member(Coordinator, "Generation"));
            File.AppendAllText(retained, "// retained dependency changed\n");
            await WaitForAsync(() => Convert.ToInt64(Member(Coordinator, "Generation")) > generation, "previous complete dependency remains watched after partial discovery");
            Assert.DoesNotContain("succeeded", Status);
            SaveEvidence(nameof(NativeIncludeObservationsRetainPartialDependencies), "nativeIncludeDiscovery", "includeDigests", "partialDiscovery", "previousDependencyRetained", "retainedDependencyInvalidates");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task CanonicalMissingIncludesAndAncestorChangesInvalidate()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var missing = CreateFixture("canonical missing include");
            var target = Path.Combine(missing.Root, "real include target"); Directory.CreateDirectory(target);
            var alias = Path.Combine(missing.Root, "src", "include junction"); CreateJunction(alias, target);
            File.WriteAllText(missing.Entry, "#include \"include junction/missing.vas\"\nvoid main() {}\n", new UTF8Encoding(false));
            await OpenSolutionAsync(missing); ConfigureCompiler(NativeCompiler);
            await ChooseAndBuildAsync(missing, 0);
            await WaitForAsync(() => !Busy && Status.StartsWith("Build failed", StringComparison.Ordinal), "native missing include observation");
            var observation = ((IEnumerable)Member(Member(Coordinator, "LastReport"), "Observations")).Cast<object>();
            Assert.Contains(observation, item => SamePath(Convert.ToString(Member(Member(item, "Identity"), "Display")), Path.Combine(alias, "missing.vas")));
            var generation = Convert.ToInt64(Member(Coordinator, "Generation"));
            File.WriteAllText(Path.Combine(target, "missing.vas"), "void nowPresent() {}\n");
            await WaitForAsync(() => Convert.ToInt64(Member(Coordinator, "Generation")) > generation, "canonical missing alias creation invalidates result");

            foreach (var rename in new[] { true, false })
            {
                var fixture = CreateFixture(rename ? "ancestor rename" : "ancestor delete");
                var ancestor = Path.Combine(fixture.Root, "src", "ancestor"); Directory.CreateDirectory(ancestor);
                File.WriteAllText(Path.Combine(ancestor, "loaded.vas"), "void loaded() {}\n");
                File.WriteAllText(fixture.Entry, "#include \"ancestor/loaded.vas\"\nvoid main() {}\n", new UTF8Encoding(false));
                await OpenSolutionAsync(fixture);
                await ChooseAndBuildAsync(fixture, 0);
                await WaitForAsync(() => !Busy && File.Exists(fixture.FirstOutput), "native ancestor include build");
                generation = Convert.ToInt64(Member(Coordinator, "Generation"));
                if (rename) Directory.Move(ancestor, ancestor + " moved"); else Directory.Delete(ancestor, true);
                await WaitForAsync(() => Convert.ToInt64(Member(Coordinator, "Generation")) > generation, "include ancestor mutation invalidates result");
                Assert.DoesNotContain("succeeded", Status);
            }
            SaveEvidence(nameof(CanonicalMissingIncludesAndAncestorChangesInvalidate), "nativeMissingInclude", "canonicalAliasCreate", "ancestorRename", "ancestorDelete");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task FirstTraversalDirtyIncludeAliasBlocksPublication()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var fixture = CreateFixture("first traversal dirty include");
            var include = Path.Combine(fixture.Root, "src", "included.vas"); File.WriteAllText(include, "void included() {}\n");
            var alias = Path.Combine(fixture.Root, "dirty include alias.txt");
            Assert.True(CreateHardLink(alias, include, IntPtr.Zero), "NTFS hard-link fixture is required.");
            File.WriteAllText(fixture.Entry, "#include \"included.vas\"\nvoid main() {}\n", new UTF8Encoding(false));
            await OpenSolutionAsync(fixture); ConfigureCompiler(NativeCompiler);
            var dirty = OpenEditor(alias); dirty.View.TextBuffer.Insert(0, "// only in unsaved alias\r\n");
            await ChooseAndBuildAsync(fixture, 0);
            await WaitForAsync(() => !Busy, "first traversal dirty alias rejection");
            Assert.Contains("modified", Status.ToLowerInvariant());
            Assert.DoesNotContain(ErrorTasks(), task => TaskText(task).Contains("Script successfully built"));
            CloseEditor(dirty.Window);

            var unrelated = OpenEditor(fixture.Unrelated); unrelated.View.TextBuffer.Insert(0, "// unrelated dirty editor\r\n");
            await ChooseAndBuildAsync(fixture, 0);
            await WaitForAsync(() => !Busy && Status.StartsWith("Build succeeded", StringComparison.Ordinal), "unrelated dirty document permits native build");
            AssertReportDigest(include);
            Assert.True(File.Exists(fixture.FirstOutput));
            SaveEvidence(nameof(FirstTraversalDirtyIncludeAliasBlocksPublication), "firstTraversalObserved", "dirtyTxtHardLinkRejected", "noStalePublication", "unrelatedDirtyAllowed");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task MismatchedProofAndTruncatedReportsNeverPublish()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            foreach (var mismatch in new[] { true, false })
            {
                var fixture = CreateFixture(mismatch ? "mismatched proof" : "truncated report"); fixture.CreateControlCompiler();
                var lines = File.ReadAllLines(fixture.ReportFile).Select(JObject.Parse).ToArray();
                if (mismatch) lines[1]["sourceDigest"] = new string('0', 64);
                File.WriteAllText(fixture.ReportFile, string.Join("\n", (mismatch ? lines : lines.Take(3)).Select(line => line.ToString(Formatting.None))) + "\n", new UTF8Encoding(false));
                await OpenSolutionAsync(fixture); ConfigureCompiler(fixture.Compiler);
                await ChooseAndBuildAsync(fixture, 0);
                await WaitForAsync(() => !Busy, "controlled report rejection");
                Assert.DoesNotContain("succeeded", Status);
                Assert.DoesNotContain(ErrorTasks(), task => TaskText(task).Contains("fixture_missing_symbol"));
                if (mismatch) Assert.Contains("compiler-loaded bytes", Status);
                else Assert.Contains("Truncated", Status);
                Assert.Equal(new[] { "describe", "build" }, fixture.Calls);
            }
            SaveEvidence(nameof(MismatchedProofAndTruncatedReportsNeverPublish), "mismatchedDigestRejected", "truncatedTerminalRejected", "noDiagnosticPublication", "actualProcessCalls");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task BlockedBuildInputAndCompilerChangesInvalidate()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            foreach (var change in new[] { "source", "config", "manifest", "compiler" })
            {
                var fixture = CreateFixture("blocked change " + change); fixture.CreateControlCompiler(); File.WriteAllText(fixture.WaitFile, "wait");
                await OpenSolutionAsync(fixture); ConfigureCompiler(fixture.Compiler);
                await ChooseAndBuildAsync(fixture, 0);
                await WaitForAsync(() => Busy && fixture.Calls.Contains("build"), "blocked compiler before mutation");
                var generation = Convert.ToInt64(Member(Coordinator, "Generation"));
                if (change == "compiler") ConfigureCompiler(NativeCompiler);
                else
                {
                    var path = change == "source" ? fixture.Entry : change == "config" ? fixture.Config : fixture.Manifest;
                    File.AppendAllText(path, "\n");
                }
                await WaitForAsync(() => !Busy && fixture.NoLiveProcesses() && Convert.ToInt64(Member(Coordinator, "Generation")) > generation, "concurrent " + change + " change invalidates and stops process");
                var status = Status;
                File.WriteAllText(fixture.ReleaseFile, "late output"); await Task.Delay(250);
                Assert.Equal(status, Status);
                Assert.DoesNotContain(ErrorTasks(), task => TaskText(task).Contains("fixture_missing_symbol"));
                Assert.Equal(new[] { "describe", "build" }, fixture.Calls);
                Assert.False(Directory.Exists(fixture.OutputDirectory));
            }
            var racing = CreateFixture("concurrent watcher and explicit cancel");
            racing.CreateControlCompiler(); File.WriteAllText(racing.WaitFile, "wait");
            await OpenSolutionAsync(racing); ConfigureCompiler(racing.Compiler);
            var hostProcess = Process.GetCurrentProcess().Id;
            for (var cycle = 0; cycle < 8; cycle++)
            {
                File.Delete(racing.ReleaseFile);
                await ChooseAndBuildAsync(racing, 0);
                await WaitForAsync(() => Busy && racing.Calls.Count(call => call == "build") == cycle + 1, "blocked process for concurrent cancel cycle");
                var mutationStarted = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
                var mutation = Task.Run(() =>
                {
                    mutationStarted.SetResult(true);
                    // Actual file changes race native watcher cancellation against
                    // the UI command; no callback ordering is assumed or forced.
                    for (var edit = 0; edit < 4; edit++)
                    {
                        for (var attempt = 0; ; attempt++)
                        {
                            try { File.AppendAllText(racing.Entry, " "); break; }
                            catch (IOException) when (attempt < 100) { System.Threading.Thread.Sleep(5); }
                        }
                    }
                });
                await mutationStarted.Task;
                ExecuteCommand(CancelCommand);
                await mutation;
                await WaitForAsync(() => !Busy && racing.NoLiveProcesses(), "concurrent watcher/command cancellation owns and reaps process");
                File.WriteAllText(racing.ReleaseFile, "late output after concurrent cancellation");
                await Task.Delay(150);
                Assert.Equal(hostProcess, Process.GetCurrentProcess().Id);
                Assert.DoesNotContain("succeeded", Status);
                Assert.DoesNotContain(ErrorTasks(), item => TaskText(item).Contains("fixture_missing_symbol"));
                Assert.Equal(cycle + 1, racing.Calls.Count(call => call == "describe"));
                Assert.Equal(cycle + 1, racing.Calls.Count(call => call == "build"));
                Assert.Equal((cycle + 1) * 2, racing.Calls.Count);
                Assert.False(Directory.Exists(racing.OutputDirectory));
            }
            SaveEvidence(nameof(BlockedBuildInputAndCompilerChangesInvalidate), "sourceMutationCancels", "configMutationCancels", "manifestMutationCancels", "compilerSettingCancels", "lateOutputIgnored", "processesReaped", "concurrentWatcherCancelSafe");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task DescriptorCancellationReapsProcessBeforeRestart()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var fixture = CreateFixture("blocked descriptor cleanup"); fixture.CreateControlCompiler();
            File.WriteAllText(fixture.Compiler + ".wait-describe", "block descriptor");
            await OpenSolutionAsync(fixture); ConfigureCompiler(fixture.Compiler);
            await DriveDialogAsync(fixture, async dialog =>
            {
                Invoke(Control<Button>(dialog, "ReadProjectUnits"));
                await WaitForAsync(() => Busy && fixture.Calls.SequenceEqual(new[] { "describe" }) && !fixture.NoLiveProcesses(), "blocked descriptor process");
                ExecuteCommand(CancelCommand);
                // Stay in this UI callback: the async cleanup cannot publish a new
                // idle state before this immediate repeated command is rejected.
                Assert.True(Busy, "The descriptor still owns cleanup after cancellation.");
                var flags = QueryCommand(BuildCommand);
                if ((flags & (uint)OLECMDF.OLECMDF_ENABLED) != 0) ExecuteCommand(BuildCommand);
                Assert.Equal(new[] { "describe" }, fixture.Calls);
            });
            await WaitForAsync(() => !Busy && fixture.NoLiveProcesses(), "descriptor cancellation reaps owned process");
            Assert.Equal(new[] { "describe" }, fixture.Calls);
            File.Delete(fixture.Compiler + ".wait-describe");
            File.WriteAllText(fixture.ReleaseFile, "late descriptor");
            await DriveDialogAsync(fixture, async dialog =>
            {
                await ReadUnitsAsync(dialog);
                Assert.Equal(-1, Control<ListBox>(dialog, "ProjectUnits").SelectedIndex);
                Invoke(Control<Button>(dialog, "CancelVasProject"));
            });
            await WaitForAsync(() => !Busy && fixture.NoLiveProcesses(), "replacement descriptor cleanup");
            Assert.Equal(new[] { "describe", "describe" }, fixture.Calls);
            Assert.False(Directory.Exists(fixture.OutputDirectory));
            SaveEvidence(nameof(DescriptorCancellationReapsProcessBeforeRestart), "descriptorBlocked", "cancelCommand", "cleanupRetainsOwnership", "repeatIgnoredDuringCleanup", "processReaped", "restartAfterCleanup");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASIntegration", MaxAttempts = 1)]
        public async Task DirtyAliasChangedAfterReadBlocksImmediateBuild()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var fixture = CreateFixture("dirty alias during modal selection"); fixture.CreateControlCompiler();
            var alias = Path.Combine(fixture.Root, "open physical alias.txt");
            Assert.True(CreateHardLink(alias, fixture.Entry, IntPtr.Zero), "NTFS alias fixture is required.");
            await OpenSolutionAsync(fixture); ConfigureCompiler(fixture.Compiler);
            var editor = OpenEditor(alias);
            await DriveDialogAsync(fixture, async dialog =>
            {
                await ReadUnitsAsync(dialog);
                SelectUnit(Control<ListBox>(dialog, "ProjectUnits"), 0);
                editor.View.TextBuffer.Insert(0, "// modified after descriptor\r\n");
                // No sleep permits a timer-based preflight to hide a stale revision.
                Invoke(Control<Button>(dialog, "BuildSelectedUnit"));
            });
            await WaitForAsync(() => !Busy && fixture.NoLiveProcesses(), "immediate dirty alias build guard");
            Assert.Equal(new[] { "describe" }, fixture.Calls);
            Assert.Contains("modified", Status.ToLowerInvariant());
            Assert.False(Directory.Exists(fixture.OutputDirectory));
            SaveEvidence(nameof(DirtyAliasChangedAfterReadBlocksImmediateBuild), "descriptorRead", "rdtAliasChanged", "immediateBuildBlocked", "zeroBuildProcesses");
        }

        [IdeFact(MinVersion = VisualStudioVersion.VS18, MaxVersion = VisualStudioVersion.VS18, RootSuffix = "VASProjectDisposal", MaxAttempts = 1)]
        public async Task PackageDisposalCancelsRunningBuildAndLateResults()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await InitializeAsync();
            var fixture = CreateFixture("native package disposal"); fixture.CreateControlCompiler(); File.WriteAllText(fixture.WaitFile, "wait");
            await OpenSolutionAsync(fixture); ConfigureCompiler(fixture.Compiler);
            await ChooseAndBuildAsync(fixture, 0);
            await WaitForAsync(() => Busy && fixture.Calls.Contains("build"), "active process before native package close");
            // The shell's public package lifecycle invokes normal disposal. A distinct
            // native host suffix keeps this irreversible lifecycle test isolated.
            ErrorHandler.ThrowOnFailure(((IVsPackage)package).Close());
            packageClosed = true;
            await WaitForAsync(() => !Busy && fixture.NoLiveProcesses(), "package disposal stops process");
            var status = Status;
            Assert.Contains("disposed", status);
            File.WriteAllText(fixture.ReleaseFile, "late result after disposal"); await Task.Delay(350);
            Assert.Equal(status, Status);
            Assert.DoesNotContain(ErrorTasks(), item => TaskText(item).Contains("fixture_missing_symbol"));
            Assert.Equal(new[] { "describe", "build" }, fixture.Calls);
            SaveEvidence(nameof(PackageDisposalCancelsRunningBuildAndLateResults), "nativePackageClose", "processCancelled", "lateResultIgnored", "isolatedHostSuffix");
        }

        private async Task InitializeAsync()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            Assert.Equal("devenv", Process.GetCurrentProcess().ProcessName);
            var hostExe = Path.GetFullPath(Process.GetCurrentProcess().MainModule.FileName);
            Assert.Equal(18, FileVersionInfo.GetVersionInfo(hostExe).FileMajorPart);
            Assert.Equal(Path.GetFullPath(Environment.GetEnvironmentVariable("VAS_EXPECTED_DEVENV")), hostExe, ignoreCase: true);
            var shell = (IVsShell)Package.GetGlobalService(typeof(SVsShell));
            var id = new Guid(PackageId);
            ErrorHandler.ThrowOnFailure(shell.LoadPackage(ref id, out var loaded));
            package = loaded;
            var installedAssembly = package.GetType().Assembly;
            Assert.Equal("VerseAngelScript", installedAssembly.GetName().Name);
            var testDirectory = Path.GetDirectoryName(typeof(ProjectBuildTests).Assembly.Location);
            Assert.False(SamePath(Path.GetDirectoryName(installedAssembly.Location), testDirectory),
                "The package must load from the installed VSIX, not beside the test harness.");
            packageAssemblySha256 = Hash(installedAssembly.Location);
            using (var archive = ZipFile.OpenRead(Path.Combine(testDirectory, "VerseAngelScript.vsix")))
            {
                var payload = archive.Entries.Single(entry => entry.FullName == "VerseAngelScript.dll");
                using (var stream = payload.Open())
                using (var sha = SHA256.Create())
                    Assert.Equal(BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "").ToLowerInvariant(), packageAssemblySha256);
            }
            Assert.NotNull(Coordinator);
            Assert.True(File.Exists(NativeCompiler), "Real CMake-built native compiler is mandatory.");
            var store = Settings();
            hadCompiler = store.CollectionExists(SettingsCollection) && store.PropertyExists(SettingsCollection, "CompilerPath");
            previousCompiler = hadCompiler ? store.GetString(SettingsCollection, "CompilerPath") : null;
            rootEvents = new RootEvents();
            await CloseSolutionAsync();
        }

        private static string NativeCompiler => Environment.GetEnvironmentVariable("VAS_NATIVE_COMPILER");
        private static WritableSettingsStore Settings() => new ShellSettingsManager(ServiceProvider.GlobalProvider).GetWritableSettingsStore(SettingsScope.UserSettings);
        private static void ConfigureCompiler(string compiler)
        {
            var store = Settings();
            if (!store.CollectionExists(SettingsCollection)) store.CreateCollection(SettingsCollection);
            store.SetString(SettingsCollection, "CompilerPath", compiler);
        }

        private Fixture CreateFixture(string name)
        {
            var workspace = Environment.GetEnvironmentVariable("VAS_TEST_WORKSPACE");
            Assert.False(string.IsNullOrWhiteSpace(workspace));
            var fixture = new Fixture(Path.Combine(workspace, "project-build", name + " " + Guid.NewGuid().ToString("N")));
            fixtures.Add(fixture);
            return fixture;
        }

        private async Task OpenSolutionAsync(Fixture fixture)
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await CloseSolutionAsync();
            var solution = (IVsSolution)Package.GetGlobalService(typeof(SVsSolution));
            var opened = rootEvents.SolutionOpens;
            ErrorHandler.ThrowOnFailure(solution.OpenSolutionFile(0, fixture.Solution));
            await WaitForAsync(() =>
            {
                ThreadHelper.ThrowIfNotOnUIThread();
                ErrorHandler.ThrowOnFailure(solution.GetSolutionInfo(out var directory, out var file, out var options));
                return rootEvents.SolutionOpens > opened && SamePath(file, fixture.Solution);
            }, "native .sln open");
        }

        private async Task OpenFolderAsync(Fixture fixture)
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            await CloseSolutionAsync();
            var solution = (IVsSolution7)Package.GetGlobalService(typeof(SVsSolution));
            var opened = rootEvents.FolderOpens;
            solution.OpenFolder(fixture.Root);
            openedFolder = fixture.Root;
            await WaitForAsync(() =>
            {
                var components = (IComponentModel)Package.GetGlobalService(typeof(SComponentModel));
                return rootEvents.FolderOpens > opened && SamePath(rootEvents.LastOpenedFolder, fixture.Root) &&
                    SamePath(components.GetService<IVsFolderWorkspaceService>()?.CurrentWorkspace?.Location, fixture.Root);
            }, "native IVsSolution7.OpenFolder");
        }

        private static string CurrentFolder()
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            var components = (IComponentModel)Package.GetGlobalService(typeof(SComponentModel));
            return components.GetService<IVsFolderWorkspaceService>()?.CurrentWorkspace?.Location;
        }
        private static string CurrentSolution()
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            var solution = (IVsSolution)Package.GetGlobalService(typeof(SVsSolution));
            ErrorHandler.ThrowOnFailure(solution.GetSolutionInfo(out var directory, out var file, out var options));
            return file;
        }
        private async Task CloseSolutionAsync()
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            var dte = (DTE)Package.GetGlobalService(typeof(SDTE));
            dte.Documents.CloseAll(vsSaveChanges.vsSaveChangesNo);
            var folder = CurrentFolder() ?? openedFolder;
            if (folder != null)
            {
                var closed = rootEvents.FolderCloses;
                // This void method starts the native close. Its documented argument
                // is the folder's full path; completion arrives through Events7.
                ((IVsSolution7)Package.GetGlobalService(typeof(SVsSolution))).CloseFolder(folder);
                await WaitForAsync(() => rootEvents.FolderCloses > closed && SamePath(rootEvents.LastClosedFolder, folder) &&
                    CurrentFolder() == null, "native folder close completion and cleared workspace");
                openedFolder = null;
            }
            if (!string.IsNullOrEmpty(CurrentSolution()))
            {
                var closed = rootEvents.SolutionCloses;
                dte.Solution.Close(false);
                await WaitForAsync(() => rootEvents.SolutionCloses > closed && string.IsNullOrEmpty(CurrentSolution()), "native solution close completion");
            }
            await WaitForAsync(() => CurrentFolder() == null && string.IsNullOrEmpty(CurrentSolution()), "native root cleared before next open");
        }

        private async Task ChooseAndBuildAsync(Fixture fixture, int index)
        {
            await DriveDialogAsync(fixture, async dialog =>
            {
                await ReadUnitsAsync(dialog);
                var units = Control<ListBox>(dialog, "ProjectUnits");
                Assert.Equal(-1, units.SelectedIndex);
                Assert.False(Control<Button>(dialog, "BuildSelectedUnit").IsEnabled);
                SelectUnit(units, index);
                var details = Control<TextBox>(dialog, "SelectedUnitDetails").Text;
                Assert.Contains("Entry:", details);
                Assert.Contains("Host API:", details);
                Assert.Contains("Output:", details);
                fixture.LastUnitDetails = details;
                Assert.True(Control<Button>(dialog, "BuildSelectedUnit").IsEnabled);
                Invoke(Control<Button>(dialog, "BuildSelectedUnit"));
            });
        }

        private async Task DriveDialogAsync(Fixture fixture, Func<Window, Task> action, bool allowPreflightRejection = false)
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            var driver = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            // The real command opens a native modal DialogWindow. Its dispatcher
            // continues pumping, so UI automation runs inside that modal loop.
#pragma warning disable VSTHRD001 // Deliberately schedule into the native modal dispatcher; no thread switch is needed here.
            _ = Application.Current.Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() => { _ = DriveAsync(); }));
#pragma warning restore VSTHRD001
            ExecuteCommand(BuildCommand);
            await driver.Task;

            async Task DriveAsync()
            {
                await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
                Window dialog = null;
                try
                {
                    await WaitForAsync(() =>
                    {
                        dialog = Application.Current.Windows.Cast<Window>().FirstOrDefault(w => AutomationProperties.GetAutomationId(w) == "VasBuildProjectDialog");
                        return dialog != null || (allowPreflightRejection && !Busy && Status.IndexOf("modified", StringComparison.OrdinalIgnoreCase) >= 0);
                    }, "Build Project native dialog or explicit dirty-input rejection");
                    if (dialog == null) { driver.SetResult(false); return; }
                    activeDialog = dialog;
                    Assert.Equal("Microsoft.VisualStudio.PlatformUI.DialogWindow", dialog.GetType().BaseType.FullName);
                    Assert.True(SamePath(Control<TextBox>(dialog, "ProjectRoot").Text, fixture.Root));
                    Assert.True(SamePath(Control<TextBox>(dialog, "ProjectManifest").Text, fixture.Manifest));
                    Assert.True(Control<TextBox>(dialog, "ProjectRoot").IsReadOnly);
                    await action(dialog);
                    await WaitForAsync(() => !dialog.IsVisible, "native modal action closes the dialog");
                    driver.SetResult(true);
                }
                catch (Exception ex) { driver.TrySetException(ex); }
                finally { if (dialog != null && dialog.IsVisible) dialog.Close(); activeDialog = null; }
            }
        }

        private async Task ReadUnitsAsync(Window dialog)
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            Assert.Equal(-1, Control<ListBox>(dialog, "ProjectUnits").SelectedIndex);
            Invoke(Control<Button>(dialog, "ReadProjectUnits"));
            await WaitForAsync(() =>
            {
                ThreadHelper.ThrowIfNotOnUIThread();
                if (Control<ListBox>(dialog, "ProjectUnits").Items.Count > 0) return true;
                var pending = Member(dialog, "PendingRead") as JoinableTask;
                Assert.True(dialog.IsVisible && (pending == null || !pending.Task.IsCompleted), "Descriptor did not produce units. " + DiagnosticState());
                return false;
            }, "compiler-described unit list");
            Assert.False(Control<Button>(dialog, "ReadProjectUnits").IsEnabled);
        }

        private static void SelectUnit(ListBox list, int index)
        {
            Assert.InRange(index, 0, list.Items.Count - 1);
            list.UpdateLayout();
            list.ScrollIntoView(list.Items[index]);
            list.UpdateLayout();
            var item = (ListBoxItem)list.ItemContainerGenerator.ContainerFromIndex(index);
            Assert.NotNull(item);
            var parent = UIElementAutomationPeer.CreatePeerForElement(list) as SelectorAutomationPeer ?? new ListBoxAutomationPeer(list);
            var peer = new ListBoxItemAutomationPeer(list.Items[index], parent);
            var selection = (ISelectionItemProvider)peer.GetPattern(PatternInterface.SelectionItem);
            Assert.NotNull(selection);
            selection.Select();
            Assert.Equal(index, list.SelectedIndex);
        }

        private static void Invoke(Button button)
        {
            Assert.True(button.IsEnabled, "UI action must be enabled: " + AutomationProperties.GetAutomationId(button));
            var peer = UIElementAutomationPeer.CreatePeerForElement(button) ?? new ButtonAutomationPeer(button);
            ((IInvokeProvider)peer.GetPattern(PatternInterface.Invoke)).Invoke();
        }

        private static T Control<T>(DependencyObject root, string id) where T : DependencyObject
        {
            if (root is T match && AutomationProperties.GetAutomationId(root) == id) return match;
            for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
            {
                var child = TryControl<T>(VisualTreeHelper.GetChild(root, index), id);
                if (child != null) return child;
            }
            throw new InvalidOperationException("Missing native WPF control: " + id);
        }
        private static T TryControl<T>(DependencyObject root, string id) where T : DependencyObject
        {
            if (root is T match && AutomationProperties.GetAutomationId(root) == id) return match;
            for (var index = 0; index < VisualTreeHelper.GetChildrenCount(root); index++)
            {
                var child = TryControl<T>(VisualTreeHelper.GetChild(root, index), id);
                if (child != null) return child;
            }
            return null;
        }

        private static uint QueryCommand(uint id)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            var group = CommandSet;
            var commands = new[] { new OLECMD { cmdID = id } };
            var route = (IOleCommandTarget)Package.GetGlobalService(typeof(SUIHostCommandDispatcher));
            ErrorHandler.ThrowOnFailure(route.QueryStatus(ref group, 1, commands, IntPtr.Zero));
            Assert.True((commands[0].cmdf & (uint)OLECMDF.OLECMDF_SUPPORTED) != 0, "Production VSCT command is not registered.");
            return commands[0].cmdf;
        }
        private static void ExecuteCommand(uint id)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            QueryCommand(id);
            var group = CommandSet;
            var route = (IOleCommandTarget)Package.GetGlobalService(typeof(SUIHostCommandDispatcher));
            ErrorHandler.ThrowOnFailure(route.Exec(ref group, id, 0, IntPtr.Zero, IntPtr.Zero));
        }

        private async Task WaitForAsync(Func<bool> condition, string purpose)
        {
            var timer = Stopwatch.StartNew();
            do
            {
                await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
                if (condition()) return;
                await Task.Delay(50);
            } while (timer.Elapsed < TimeSpan.FromSeconds(30));
            Assert.Fail("Timed out waiting for " + purpose + ". " + DiagnosticState());
        }

        private string DiagnosticState()
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            var dialogStatus = activeDialog == null ? "none" : Control<TextBlock>(activeDialog, "ProjectBuildStatus").Text;
            var calls = string.Join("; ", fixtures.Select(f => Path.GetFileName(f.Root) + ": calls=[" + string.Join(",", f.Calls) + "], reaped=" + f.NoLiveProcesses()));
            var state = "Status=" + Status + "; Busy=" + Busy + "; Generation=" + Member(Coordinator, "Generation") +
                "; DialogVisible=" + activeDialog?.IsVisible + "; DialogStatus=" + dialogStatus +
                "; CurrentFolder=" + CurrentFolder() + "; CurrentSolution=" + CurrentSolution() +
                "; RootEvents=" + rootEvents?.History + "; " + calls;
            return state.Length <= 4096 ? state : state.Substring(0, 4096);
        }

        private static object Member(object instance, string name)
        {
            if (instance == null) return null;
            const BindingFlags flags = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;
            var type = instance.GetType();
            var property = type.GetProperty(name, flags);
            if (property != null) return property.GetValue(instance);
            var field = type.GetField(name, flags);
            if (field != null) return field.GetValue(instance);
            throw new MissingMemberException(type.FullName, name);
        }

        private async Task<object> InvokeNativeProcessAsync(string executable, string[] arguments, string root)
        {
            var type = package.GetType().Assembly.GetType("VerseAngelScript.VisualStudio.Build.NativeProcess", true);
            var method = type.GetMethod("RunAsync", BindingFlags.Static | BindingFlags.Public | BindingFlags.NonPublic);
            var task = (Task)method.Invoke(null, new object[] { executable, arguments, root, TimeSpan.FromSeconds(15), (long)(1024 * 1024), CancellationToken.None, null, null });
            await task;
            return Member(task, "Result");
        }

        private void AssertReportDigest(string entry)
        {
            var report = Member(Coordinator, "LastReport");
            Assert.NotNull(report);
            var sections = ((IEnumerable)Member(report, "LoadedSections")).Cast<object>();
            var observation = sections.Single(item => SamePath(Convert.ToString(Member(Member(item, "Identity"), "Display")), entry));
            var proof = Member(observation, "Proof");
            Assert.NotNull(proof);
            Assert.Equal(File.ReadAllBytes(entry).Length, Convert.ToInt32(Member(proof, "ByteLength")));
            Assert.Equal(Hash(entry), Convert.ToString(Member(proof, "Sha256")));
        }

        private IReadOnlyList<IVsTaskItem> ErrorTasks()
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            // Enumerate the actual Visual Studio Error List service, including its
            // registered production provider; do not reconstruct task DTOs.
            var list = (IVsTaskList)Package.GetGlobalService(typeof(SVsErrorList));
            Assert.NotNull(list);
            ErrorHandler.ThrowOnFailure(list.EnumTaskItems(out var enumerator));
            var tasks = new List<IVsTaskItem>();
            var item = new IVsTaskItem[1]; var fetched = new uint[1];
            while (enumerator.Next(1, item, fetched) == VSConstants.S_OK && fetched[0] == 1) tasks.Add(item[0]);
            return tasks;
        }
        private static string TaskText(IVsTaskItem item)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            ErrorHandler.ThrowOnFailure(item.get_Text(out var text));
            return text;
        }

        private sealed class OpenDocument
        {
            internal EnvDTE.Window Window;
            internal IWpfTextView View;
        }
        private OpenDocument OpenEditor(string path)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            var dte = (DTE)Package.GetGlobalService(typeof(SDTE));
            var window = dte.ItemOperations.OpenFile(path, EnvDTE.Constants.vsViewKindCode);
            window.Activate(); windows.Add(window);
            return new OpenDocument { Window = window, View = ActiveView() };
        }
        private static IWpfTextView ActiveView()
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            var view = TryActiveView();
            Assert.NotNull(view);
            return view;
        }
        private static IWpfTextView TryActiveView()
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            var text = (IVsTextManager)Package.GetGlobalService(typeof(SVsTextManager));
            if (ErrorHandler.Failed(text.GetActiveView(1, null, out var native)) || native == null) return null;
            var components = (IComponentModel)Package.GetGlobalService(typeof(SComponentModel));
            return components.GetService<IVsEditorAdaptersFactoryService>().GetWpfTextView(native);
        }
        private void CloseEditor(EnvDTE.Window window)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            window.Close(vsSaveChanges.vsSaveChangesNo); windows.Remove(window);
        }
        private static async Task AssertNavigationDoesNotMoveAsync(IVsTaskItem task, IWpfTextView before)
        {
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            var position = before.Caret.Position.BufferPosition.Position;
            task.NavigateTo(); // A refused/file-level navigation may return a failed HRESULT.
            await Task.Delay(500); // The production task schedules its navigation asynchronously.
            await ThreadHelper.JoinableTaskFactory.SwitchToMainThreadAsync();
            Assert.Same(before, ActiveView());
            Assert.Equal(position, before.Caret.Position.BufferPosition.Position);
        }

        private static bool SamePath(string left, string right) => left != null && right != null &&
            string.Equals(left.Replace('\\', '/').TrimEnd('/'), right.Replace('\\', '/').TrimEnd('/'), StringComparison.OrdinalIgnoreCase);
        private static string Hash(string path)
        {
            using (var sha = SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(File.ReadAllBytes(path))).Replace("-", "").ToLowerInvariant();
        }
        private void SaveEvidence(string name, params string[] checks)
        {
            var commandLine = (IVsAppCommandLine)Package.GetGlobalService(typeof(SVsAppCommandLine));
            ErrorHandler.ThrowOnFailure(commandLine.GetOption("RootSuffix", out var present, out var suffix));
            Assert.Equal(1, present);
            Assert.Equal(name == nameof(PackageDisposalCancelsRunningBuildAndLateResults) ? "VASProjectDisposal" : "VASIntegration", suffix);
            var path = Path.Combine(Environment.GetEnvironmentVariable("VAS_TEST_RESULTS"), "project-build", name + ".json");
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            File.WriteAllText(path, JsonConvert.SerializeObject(new {
                testName = name, hostMajor = FileVersionInfo.GetVersionInfo(Process.GetCurrentProcess().MainModule.FileName).FileMajorPart,
                processName = Process.GetCurrentProcess().ProcessName, rootSuffix = suffix,
                hostExe = Path.GetFullPath(Process.GetCurrentProcess().MainModule.FileName), packageAssemblySha256,
                compilerSha256 = Hash(NativeCompiler), checks = checks.ToDictionary(check => check, check => true)
            }, Formatting.Indented), new UTF8Encoding(false));
        }

        public void Dispose()
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            if (package == null) return;
            if (!packageClosed) ExecuteCommand(CancelCommand);
            foreach (var window in windows.ToArray()) { try { window.Close(vsSaveChanges.vsSaveChangesNo); } catch (COMException) { } }
            try { ThreadHelper.JoinableTaskFactory.Run(CloseSolutionAsync); }
            finally
            {
                try { rootEvents?.Dispose(); }
                finally
                {
                    try { foreach (var fixture in fixtures) fixture.ReleaseAndStop(); }
                    finally
                    {
                        var settings = Settings();
                        if (hadCompiler) settings.SetString(SettingsCollection, "CompilerPath", previousCompiler);
                        else if (settings.CollectionExists(SettingsCollection) && settings.PropertyExists(SettingsCollection, "CompilerPath")) settings.DeleteProperty(SettingsCollection, "CompilerPath");
                    }
                }
            }
        }

        private sealed class RootEvents : IVsSolutionEvents, IVsSolutionEvents7, IDisposable
        {
            private readonly IVsSolution solution;
            private readonly uint cookie;
            private readonly Queue<string> history = new Queue<string>();
            internal int FolderOpens, FolderCloses, SolutionOpens, SolutionCloses;
            internal string LastOpenedFolder, LastClosedFolder;
            internal string History => string.Join(" | ", history);
            internal RootEvents()
            {
                ThreadHelper.ThrowIfNotOnUIThread();
                solution = (IVsSolution)Package.GetGlobalService(typeof(SVsSolution));
                ErrorHandler.ThrowOnFailure(solution.AdviseSolutionEvents(this, out cookie));
            }
            private void Record(string value)
            {
                ThreadHelper.ThrowIfNotOnUIThread();
                if (history.Count == 12) history.Dequeue();
                history.Enqueue(value);
            }
            public void Dispose() { ThreadHelper.ThrowIfNotOnUIThread(); ErrorHandler.ThrowOnFailure(solution.UnadviseSolutionEvents(cookie)); }
            public void OnAfterOpenFolder(string path) { ThreadHelper.ThrowIfNotOnUIThread(); ++FolderOpens; LastOpenedFolder = path; Record("AfterOpenFolder:" + path); }
            public void OnBeforeCloseFolder(string path) { ThreadHelper.ThrowIfNotOnUIThread(); Record("BeforeCloseFolder:" + path); }
            public void OnQueryCloseFolder(string path, ref int cancel) { }
            public void OnAfterCloseFolder(string path) { ThreadHelper.ThrowIfNotOnUIThread(); ++FolderCloses; LastClosedFolder = path; Record("AfterCloseFolder:" + path); }
            public void OnAfterLoadAllDeferredProjects() { }
            public int OnAfterOpenSolution(object reserved, int isNew) { ThreadHelper.ThrowIfNotOnUIThread(); ++SolutionOpens; Record("AfterOpenSolution"); return VSConstants.S_OK; }
            public int OnBeforeCloseSolution(object reserved) { ThreadHelper.ThrowIfNotOnUIThread(); Record("BeforeCloseSolution"); return VSConstants.S_OK; }
            public int OnAfterCloseSolution(object reserved) { ThreadHelper.ThrowIfNotOnUIThread(); ++SolutionCloses; Record("AfterCloseSolution"); return VSConstants.S_OK; }
            public int OnAfterOpenProject(IVsHierarchy hierarchy, int added) => VSConstants.S_OK;
            public int OnQueryCloseProject(IVsHierarchy hierarchy, int removing, ref int cancel) => VSConstants.S_OK;
            public int OnBeforeCloseProject(IVsHierarchy hierarchy, int removed) => VSConstants.S_OK;
            public int OnAfterLoadProject(IVsHierarchy stub, IVsHierarchy real) => VSConstants.S_OK;
            public int OnQueryUnloadProject(IVsHierarchy real, ref int cancel) => VSConstants.S_OK;
            public int OnBeforeUnloadProject(IVsHierarchy real, IVsHierarchy stub) => VSConstants.S_OK;
            public int OnQueryCloseSolution(object reserved, ref int cancel) => VSConstants.S_OK;
        }

        private static void CreateJunction(string junction, string target)
        {
            using (var process = Process.Start(new ProcessStartInfo(Environment.GetEnvironmentVariable("ComSpec"),
                "/d /c mklink /J \"" + junction + "\" \"" + target + "\"") { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true }))
            {
                Assert.True(process.WaitForExit(10000)); Assert.Equal(0, process.ExitCode);
            }
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CreateHardLink(string fileName, string existingFileName, IntPtr securityAttributes);

        private sealed class Fixture
        {
            internal readonly string Root, Manifest, Entry, Config, FirstOutput, SecondOutput, OutputDirectory, Solution, Unrelated;
            internal readonly string Compiler, DescriptorFile, ReportFile, CallsFile, PidsFile, WaitFile, ReleaseFile;
            internal string LastUnitDetails;
            internal Fixture(string root)
            {
                Root = root; Manifest = Path.Combine(root, "vas-project.json");
                Entry = Path.Combine(root, "src", "main 漢字 😀.vas");
                Config = Path.Combine(root, "host", "default.txt");
                OutputDirectory = Path.Combine(root, "out");
                FirstOutput = Path.Combine(OutputDirectory, "default.vasbc"); SecondOutput = Path.Combine(OutputDirectory, "alternate.vasbc");
                Solution = Path.Combine(root, "Native Project.sln"); Unrelated = Path.Combine(root, "unrelated.vas");
                Compiler = Path.Combine(root, "control compiler", "fixture.exe");
                DescriptorFile = Compiler + ".descriptor"; ReportFile = Compiler + ".report"; CallsFile = Compiler + ".calls";
                PidsFile = Compiler + ".pids"; WaitFile = Compiler + ".wait"; ReleaseFile = Compiler + ".release";
                Directory.CreateDirectory(Path.GetDirectoryName(Entry)); Directory.CreateDirectory(Path.GetDirectoryName(Config));
                File.WriteAllText(Entry, "void main() { int value = 42; }\r\n", new UTF8Encoding(false));
                File.WriteAllText(Config, "// default host declarations\r\n", new UTF8Encoding(false));
                File.WriteAllText(Path.Combine(root, "host", "alternate.txt"), "// alternate host declarations\r\n", new UTF8Encoding(false));
                File.WriteAllText(Unrelated, "// unrelated editor target\r\nvoid unrelated() {}\r\n", new UTF8Encoding(false));
                File.WriteAllText(Solution, "Microsoft Visual Studio Solution File, Format Version 12.00\r\n# Visual Studio Version 18\r\nVisualStudioVersion = 18.0.0.0\r\nMinimumVisualStudioVersion = 10.0.40219.1\r\nGlobal\r\nEndGlobal\r\n", new UTF8Encoding(false));
                var units = new JArray();
                foreach (var id in new[] { "default", "alternate" })
                    units.Add(new JObject { ["id"] = id, ["entry"] = "src/main 漢字 😀.vas", ["hostApi"] = new JObject { ["config"] = "host/" + id + ".txt" }, ["output"] = "out/" + id + ".vasbc" });
                File.WriteAllText(Manifest, new JObject { ["schemaVersion"] = 1, ["name"] = "Native VS18 fixture", ["compilationUnits"] = units }.ToString(Formatting.None), new UTF8Encoding(false));
            }

            internal IReadOnlyList<string> Calls => ReadLines(CallsFile);
            private static string[] ReadLines(string path)
            {
                if (!File.Exists(path)) return new string[0];
                using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                using (var reader = new StreamReader(stream, Encoding.UTF8))
                    return reader.ReadToEnd().Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
            }
            internal bool NoLiveProcesses()
            {
                foreach (var id in ReadLines(PidsFile))
                {
                    try { using (var process = Process.GetProcessById(int.Parse(id))) if (!process.HasExited) return false; }
                    catch (ArgumentException) { }
                }
                return true;
            }
            internal void ReleaseAndStop()
            {
                if (!Directory.Exists(Path.GetDirectoryName(Compiler))) return;
                File.WriteAllText(ReleaseFile, "cleanup");
                foreach (var id in ReadLines(PidsFile))
                {
                    try
                    {
                        using (var process = Process.GetProcessById(int.Parse(id)))
                        {
                            if (!process.HasExited && SamePath(process.MainModule.FileName, Compiler)) { process.Kill(); process.WaitForExit(5000); }
                        }
                    }
                    catch (ArgumentException) { }
                    catch (InvalidOperationException) { }
                }
            }
            internal void CreateControlCompiler()
            {
                Directory.CreateDirectory(Path.GetDirectoryName(Compiler));
                using (var provider = new CSharpCodeProvider())
                {
                    var parameters = new CompilerParameters(new[] { "System.dll", "System.Core.dll" }, Compiler) {
                        GenerateExecutable = true, GenerateInMemory = false, TreatWarningsAsErrors = true, CompilerOptions = "/platform:x64 /optimize+"
                    };
                    var results = provider.CompileAssemblyFromSource(parameters, ControlCompilerSource);
                    Assert.False(results.Errors.HasErrors, string.Join("\n", results.Errors.Cast<CompilerError>().Select(error => error.ToString())));
                }
                var units = new JArray();
                foreach (var id in new[] { "default", "alternate" })
                    units.Add(new JObject { ["id"] = id, ["entry"] = Wire(Entry), ["hostApi"] = new JObject { ["config"] = Wire(Path.Combine(Root, "host", id + ".txt")) }, ["output"] = Wire(Path.Combine(OutputDirectory, id + ".vasbc")) });
                File.WriteAllText(DescriptorFile, new JObject {
                    ["protocol"] = "vas-project", ["version"] = 1, ["success"] = true,
                    ["project"] = Wire(Manifest), ["projectRoot"] = Wire(Root), ["projectSchemaVersion"] = 1,
                    ["legacyProject"] = false, ["name"] = "Controlled compiler fixture", ["compilationUnits"] = units,
                    ["warnings"] = new JArray(), ["errors"] = new JArray()
                }.ToString(Formatting.None) + "\n", new UTF8Encoding(false));
                WriteReport(digest: true);
            }
            internal void WriteReport(bool digest)
            {
                var start = Event("start", 1);
                start["compiler"] = "vasbuild"; start["compilerVersion"] = "native-test-control";
                start["cwd"] = Wire(Root); start["config"] = Wire(Config); start["entry"] = Wire(Entry); start["output"] = Wire(FirstOutput);
                start["project"] = Wire(Manifest); start["projectSchemaVersion"] = 1; start["unit"] = "default"; start["legacyProject"] = false;
                start["positionEncoding"] = "utf-8-bytes"; start["positionBase"] = 1;
                var section = Event("section_loaded", 2); section["section"] = Wire(Entry); section["utf8Valid"] = true;
                if (digest)
                {
                    section["sourceDigestVersion"] = 1; section["sourceDigestAlgorithm"] = "sha256";
                    section["sourceByteLength"] = File.ReadAllBytes(Entry).Length; section["sourceDigest"] = Hash(Entry);
                }
                var diagnostic = Event("diagnostic", 3); diagnostic["severity"] = "error"; diagnostic["message"] = "fixture_missing_symbol";
                diagnostic["section"] = Wire(Entry); diagnostic["row"] = 1; diagnostic["column"] = 1;
                var result = Event("result", 4); result["success"] = false; result["phase"] = "compile"; result["dependenciesComplete"] = true;
                File.WriteAllText(ReportFile, string.Join("\n", new[] { start, section, diagnostic, result }.Select(value => value.ToString(Formatting.None))) + "\n", new UTF8Encoding(false));
            }
            private static JObject Event(string type, int seq) => new JObject {
                ["protocol"] = "vasbuild", ["version"] = 1, ["type"] = type, ["seq"] = seq,
                ["invalidUtf8Fields"] = new JArray(), ["rawBytes"] = new JObject()
            };
            private static string Wire(string path) => path.Replace('\\', '/');

            // Controlled failures only. Happy paths and source digest/navigation
            // positive evidence always use the repository's CMake-built vasbuild.
            private const string ControlCompilerSource = @"
using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text;
using System.Threading;
public static class ControlledCompiler
{
    public static int Main(string[] args)
    {
        string self = Assembly.GetExecutingAssembly().Location;
        bool describe = args.Length == 2 && args[0] == ""--describe-project=json"";
        bool build = args.Length == 5 && args[0] == ""--report=jsonl"" && args[1] == ""--project"" && args[3] == ""--unit"";
        File.AppendAllText(self + "".pids"", Process.GetCurrentProcess().Id.ToString() + ""\n"");
        File.AppendAllText(self + "".calls"", (describe ? ""describe"" : build ? ""build"" : ""unexpected"") + ""\n"");
        using (var log = new StreamWriter(self + "".argv"", true, new UTF8Encoding(false)))
        {
            foreach (string arg in args) log.WriteLine(Convert.ToBase64String(Encoding.UTF8.GetBytes(arg)));
        }
        if (!describe && !build) return 3;
        if ((build && File.Exists(self + "".wait"")) || (describe && File.Exists(self + "".wait-describe"")))
        {
            var deadline = DateTime.UtcNow.AddSeconds(45);
            while (!File.Exists(self + "".release""))
            {
                if (DateTime.UtcNow >= deadline) return 4;
                Thread.Sleep(25);
            }
        }
        byte[] bytes = File.ReadAllBytes(self + (describe ? "".descriptor"" : "".report""));
        using (var output = Console.OpenStandardOutput()) { output.Write(bytes, 0, bytes.Length); output.Flush(); }
        return describe ? 0 : 1;
    }
}";
        }
    }
}
