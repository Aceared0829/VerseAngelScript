using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Threading;
using Microsoft.VisualStudio;
using Microsoft.VisualStudio.ComponentModelHost;
using Microsoft.VisualStudio.Shell;
using Microsoft.VisualStudio.Shell.Interop;
using Microsoft.VisualStudio.Threading;
using Microsoft.VisualStudio.Workspace.VSIntegration.Contracts;
using Task = System.Threading.Tasks.Task;

namespace VerseAngelScript.VisualStudio.Build
{
    // Every process is reachable only through the two explicit native dialog actions.
    // UI/RDT queries stay on the UI thread; bytes, aliases, hashes and waits do not.
    internal sealed class BuildCoordinator : IDisposable
    {
        private const int MaxInputBytes = 16 * 1024 * 1024;
        private readonly VasPackage package;
        private readonly CancellationToken lifetime;
        private readonly JoinableTaskFactory factory;
        private readonly DispatcherTimer timer;
        private readonly Dictionary<string, HashSet<string>> dependencies = new Dictionary<string, HashSet<string>>(StringComparer.Ordinal);
        private readonly HostEvents hostEvents;
        private readonly IVsOutputWindowPane output;
        private volatile Session current;
        private long generation;
        private volatile bool disposed;
        private bool flowRunning;
        private bool checking;
        internal ErrorListProvider ErrorProvider { get; }
        internal string Status { get; private set; } = "Ready";
        internal bool Busy { get; private set; }
        internal Report LastReport => current?.Report;
        internal long Generation => Interlocked.Read(ref generation);
        internal string[] ObservedPaths => current == null ? new string[0] : current.Paths();

        internal BuildCoordinator(VasPackage package, CancellationToken lifetime)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            this.package = package; this.lifetime = lifetime; factory = ThreadHelper.JoinableTaskFactory;
            ErrorProvider = new ErrorListProvider(package) { ProviderName = "VAS Project Build", ProviderGuid = new Guid("cd1d85e7-b4e0-45f0-9e2a-f2409bad5348") };
            var window = (IVsOutputWindow)Package.GetGlobalService(typeof(SVsOutputWindow));
            var paneId = new Guid("46b8683a-bd9b-411f-9594-7718b1f56661");
            ErrorHandler.ThrowOnFailure(window.CreatePane(ref paneId, "VAS Project Build", 1, 0));
            ErrorHandler.ThrowOnFailure(window.GetPane(ref paneId, out output));
            hostEvents = new HostEvents(WorkspaceChanged, DocumentsChanged);
            timer = new DispatcherTimer(TimeSpan.FromMilliseconds(350), DispatcherPriority.Background, (s, e) => factory.RunAsync(CheckCurrentAsync).FileAndForget("VerseAngelScript/InputCheck"), Dispatcher.CurrentDispatcher);
            timer.Start();
        }

        internal async Task ShowBuildAsync()
        {
            await factory.SwitchToMainThreadAsync();
            if (disposed || flowRunning) return;
            Cancel("Preparing a new build");
            var view = CaptureView();
            if (string.IsNullOrEmpty(view.Root)) { SetStatus("Open a solution or folder containing vas-project.json first."); return; }
            var session = new Session(Generation, view.Root, Path.Combine(view.Root, "vas-project.json"), view.Compiler, lifetime);
            current = session; session.DirtyMonikers = view.Dirty; Busy = true; flowRunning = true; ErrorProvider.Tasks.Clear();
            try
            {
                // Existence checks do not launch a process or create output directories.
                await Task.Run(() =>
                {
                    NativeProcess.ValidateCompiler(session.Compiler);
                    Track(session, session.Compiler, true);
                    Track(session, session.Manifest, true);
                    Validate(session, view.Dirty);
                    Watch(session);
                });
                Guard(session);
                await factory.SwitchToMainThreadAsync();
                session.Dialog = new BuildDialog(session.Root, session.Manifest, session.Compiler,
                    () => DescribeAsync(session), () => CancelIfCurrent(session, "Build cancelled"), factory);
                var dialog = session.Dialog;
                var accepted = dialog.ShowModal();
                if (dialog.PendingRead != null) await dialog.PendingRead.JoinAsync();
                if (accepted != true || dialog.SelectedUnit == null) return;
                Guard(session);
                session.Descriptor = session.Dialog.Descriptor;
                session.Unit = session.Dialog.SelectedUnit;
                session.Dialog = null;
                await BuildAsync(session);
            }
            catch (Exception ex) { await FailAsync(session, ex); }
            finally
            {
                await factory.SwitchToMainThreadAsync();
                flowRunning = false; Busy = false;
                session.Dialog = null;
                if (current != session) session.Cancel.Dispose();
            }
        }

        private async Task<Descriptor> DescribeAsync(Session session)
        {
            await factory.SwitchToMainThreadAsync();
            var view = CaptureView(); RequireView(session, view);
            SetStatus("Reading VAS project units");
            var descriptor = await Task.Run(async () =>
            {
                Validate(session, view.Dirty);
                var result = await NativeProcess.RunAsync(session.Compiler,
                    new[] { "--describe-project=json", session.Manifest }, session.Root, TimeSpan.FromSeconds(30), 16 * 1024 * 1024,
                    session.Token, () => GuardForProcess(session));
                var parsed = Protocol.Describe(result.Stdout, result.ExitCode);
                if (!SamePath(parsed.Project, session.Manifest) || !SamePath(parsed.Root, session.Root))
                    throw new IOException("The compiler described a different project.");
                foreach (var unit in parsed.Units) { WatchCandidate(session, unit.Config); WatchCandidate(session, unit.Entry); }
                Validate(session, view.Dirty); Watch(session);
                return parsed;
            });
            await factory.SwitchToMainThreadAsync();
            RequireView(session, CaptureView()); Guard(session);
            SetStatus("Select an explicit VAS compilation unit");
            return descriptor;
        }

        private async Task BuildAsync(Session session)
        {
            await factory.SwitchToMainThreadAsync();
            var view = CaptureView(); RequireView(session, view);
            SetStatus("Building VAS unit " + session.Unit.Id);
            var report = new Report(session.Descriptor, session.Unit);
            session.Report = report;
            var key = session.Manifest + "\0" + session.Unit.Id;
            var previous = dependencies.TryGetValue(key, out var known) ? known.ToArray() : new string[0];
            List<PublishedDiagnostic> diagnostics = null;
            try
            {
                diagnostics = await Task.Run(async () =>
                {
                    foreach (var path in previous) Track(session, path, false);
                    // Recheck selected inputs after the modal selection; missing dormant
                    // units remain valid, but selected files must be readable now.
                    Track(session, session.Unit.Config, true);
                    Track(session, session.Unit.Entry, true);
                    Validate(session, view.Dirty); Watch(session);
                    var result = await NativeProcess.RunAsync(session.Compiler,
                        new[] { "--report=jsonl", "--project", session.Manifest, "--unit", session.Unit.Id },
                        session.Root, TimeSpan.FromMinutes(2), 64L * 1024 * 1024, session.Token,
                        () => GuardForProcess(session), line =>
                        {
                            report.AcceptLine(line);
                            var observation = report.LastObservation;
                            if (observation != null)
                            {
                                if (!observation.Identity.Bindable)
                                    throw new IOException("Compiler-observed path cannot be verified by the Unicode editor.");
                                Track(session, FileIdentity.ResolveObservedPath(report.Cwd, observation.Identity), false);
                                Watch(session);
                            }
                        });
                    report.Finish(result.ExitCode);
                    Validate(session, view.Dirty);
                    var bound = new Dictionary<string, FileSnapshot>(StringComparer.OrdinalIgnoreCase);
                    foreach (var section in report.LoadedSections)
                    {
                        if (!section.Identity.Bindable || section.Proof == null) continue;
                        var snapshot = FileIdentity.Observe(FileIdentity.ResolveObservedPath(report.Cwd, section.Identity), MaxInputBytes);
                        if (!snapshot.MatchesProof(section.Proof))
                            throw new IOException("Saved source does not match the compiler-loaded bytes; build again: " + section.Identity.Display);
                        if (section.Utf8Valid) bound[ResolveSection(report.Cwd, section.Identity.Display)] = snapshot;
                    }
                    var items = new List<PublishedDiagnostic>();
                    foreach (var diagnostic in report.Diagnostics)
                    {
                        FileSnapshot snapshot = null; int line = 0, column = 0;
                        if (diagnostic.Bindable && diagnostic.Row > 0 && diagnostic.Column > 0)
                        {
                            var path = ResolveSection(report.Cwd, diagnostic.Section);
                            if (path != null && bound.TryGetValue(path, out var source) && source.TryPosition(diagnostic.Row, diagnostic.Column, out line, out column)) snapshot = source;
                        }
                        items.Add(new PublishedDiagnostic(diagnostic, snapshot, line, column));
                    }
                    session.NavigationSnapshots = bound.Values.ToArray();
                    Validate(session, view.Dirty);
                    if (!string.IsNullOrWhiteSpace(result.Stderr)) session.Stderr = result.Stderr;
                    return items;
                });
                await factory.SwitchToMainThreadAsync();
                RequireView(session, CaptureView());
                var dirty = CaptureView().Dirty;
                await Task.Run(() => Validate(session, dirty));
                await factory.SwitchToMainThreadAsync();
                Guard(session);
                MergeDependencies(key, report, report.DependenciesComplete);
                Publish(session, diagnostics);
                SetStatus((report.Success ? "Build succeeded" : "Build failed (" + report.Phase + ")") + " — " + session.Unit.Id + (string.IsNullOrEmpty(session.Stderr) ? "" : "\nCompiler stderr: " + session.Stderr));
            }
            catch (Exception ex)
            {
                await factory.SwitchToMainThreadAsync();
                MergeDependencies(key, report, false);
                await FailAsync(session, ex);
            }
        }

        private void MergeDependencies(string key, Report report, bool complete)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            if (complete || !dependencies.TryGetValue(key, out var set)) dependencies[key] = set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var observation in report.Observations)
                if (observation.Identity.Bindable) set.Add(observation.Identity.Display);
        }

        private void Publish(Session session, IEnumerable<PublishedDiagnostic> diagnostics)
        {
            ThreadHelper.ThrowIfNotOnUIThread(); GuardPublication(session);
            ErrorProvider.SuspendRefresh();
            try
            {
                ErrorProvider.Tasks.Clear();
                foreach (var item in diagnostics)
                {
                    var task = new ErrorTask
                    {
                        Category = TaskCategory.BuildCompile,
                        ErrorCategory = item.Diagnostic.Severity == "error" ? TaskErrorCategory.Error : item.Diagnostic.Severity == "warning" ? TaskErrorCategory.Warning : TaskErrorCategory.Message,
                        Text = item.Diagnostic.Message + (item.Snapshot == null && !string.IsNullOrEmpty(item.Diagnostic.Section) ? " [" + item.Diagnostic.Section + "]" : ""),
                        Document = item.Snapshot?.Path ?? string.Empty, Line = item.Line, Column = item.Column
                    };
                    if (item.Snapshot != null) task.Navigate += (s, e) => factory.RunAsync(() => NavigateAsync(session, item)).FileAndForget("VerseAngelScript/Navigate");
                    ErrorProvider.Tasks.Add(task);
                }
            }
            finally { ErrorProvider.ResumeRefresh(); }
            ErrorProvider.Show();
        }

        private async Task NavigateAsync(Session session, PublishedDiagnostic item)
        {
            try
            {
                await factory.SwitchToMainThreadAsync();
                var view = CaptureView(); RequireView(session, view);
                await Task.Run(() => { Validate(session, view.Dirty); if (!FileIdentity.Matches(item.Snapshot)) throw new IOException("Source changed; build again before navigating."); });
                await factory.SwitchToMainThreadAsync();
                Guard(session); RequireView(session, CaptureView());
                VsShellUtilities.OpenDocument(package, item.Snapshot.Path, VSConstants.LOGVIEWID_Code, out var hierarchy, out var id, out var frame, out var textView);
                ErrorHandler.ThrowOnFailure(textView.GetBuffer(out var buffer));
                ErrorHandler.ThrowOnFailure(buffer.GetLastLineIndex(out var lastLine, out var lastColumn));
                ErrorHandler.ThrowOnFailure(buffer.GetLineText(0, 0, lastLine, lastColumn, out var editorText));
                var expectedText = new System.Text.UTF8Encoding(false, true).GetString(item.Snapshot.Bytes);
                if (expectedText.Length != 0 && expectedText[0] == '\uFEFF') expectedText = expectedText.Substring(1);
                if (!string.Equals(editorText, expectedText, StringComparison.Ordinal))
                    throw new IOException("The open editor does not match the verified compiler input; save and build again.");
                GuardPublication(session);
                ErrorHandler.ThrowOnFailure(textView.SetCaretPos(item.Line, item.Column));
                ErrorHandler.ThrowOnFailure(textView.CenterLines(item.Line, 1));
                ErrorHandler.ThrowOnFailure(frame.Show());
            }
            catch (Exception ex) { await FailAsync(session, ex); }
        }

        private async Task CheckCurrentAsync()
        {
            await factory.SwitchToMainThreadAsync();
            if (checking || disposed || current == null) return;
            var session = current; checking = true;
            try
            {
                var view = CaptureView(); RequireView(session, view);
                await Task.Run(() => Validate(session, view.Dirty));
            }
            catch (Exception ex) { await FailAsync(session, ex); }
            finally { checking = false; }
        }

        private void WorkspaceChanged() { ThreadHelper.ThrowIfNotOnUIThread(); Cancel("Workspace changed; build again"); }
        private void DocumentsChanged()
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            var session = current;
            if (disposed || session == null) return;
            var dirty = CaptureView().Dirty;
            if (new HashSet<string>(session.DirtyMonikers, StringComparer.OrdinalIgnoreCase).SetEquals(dirty)) return;
            session.DirtyMonikers = dirty;
            var revision = Interlocked.Increment(ref session.DocumentRevision);
            session.DirtyCheck.Reset();
            factory.RunAsync(async () =>
            {
                try
                {
                    await Task.Run(() => Validate(session, dirty));
                    if (Interlocked.Read(ref session.DocumentRevision) == revision)
                    {
                        Interlocked.Exchange(ref session.VerifiedDocumentRevision, revision);
                        session.DirtyCheck.Set();
                    }
                }
                catch (Exception ex) { await FailAsync(session, ex); }
            }).FileAndForget("VerseAngelScript/DirtyInputCheck");
        }

        internal void Cancel(string reason)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            Interlocked.Increment(ref generation);
            var session = current; current = null;
            session?.Cancel.Cancel(); session?.StopWatching();
            if (session?.Dialog != null)
            {
                session.Dialog.Invalidate(reason);
                if (session.Dialog.IsVisible) session.Dialog.DialogResult = false;
            }
            Busy = flowRunning; ErrorProvider.Tasks.Clear(); SetStatus(reason);
            if (!flowRunning) session?.Cancel.Dispose();
        }
        private void CancelIfCurrent(Session session, string reason) { ThreadHelper.ThrowIfNotOnUIThread(); if (current == session) Cancel(reason); }
        private async Task FailAsync(Session session, Exception ex)
        {
            await factory.SwitchToMainThreadAsync();
            if (current != session || disposed) return;
            CancelIfCurrent(session, ex is OperationCanceledException ? "Build cancelled" : ex.Message);
        }
        private void SetStatus(string status)
        {
            ThreadHelper.ThrowIfNotOnUIThread(); Status = status;
            output.OutputStringThreadSafe(status + Environment.NewLine);
        }
        private void Guard(Session session)
        {
            session.Token.ThrowIfCancellationRequested();
            if (disposed || session.Generation != Generation || current != session) throw new OperationCanceledException();
        }
        private void GuardForProcess(Session session)
        {
            Guard(session);
            var deadline = System.Diagnostics.Stopwatch.StartNew();
            while (Interlocked.Read(ref session.DocumentRevision) != Interlocked.Read(ref session.VerifiedDocumentRevision))
            {
                Guard(session);
                if (deadline.Elapsed > TimeSpan.FromSeconds(10)) throw new IOException("Timed out verifying modified editor inputs.");
                session.DirtyCheck.Wait(50, session.Token);
            }
            Guard(session);
        }
        private void GuardPublication(Session session)
        {
            Guard(session);
            if (Interlocked.Read(ref session.DocumentRevision) != Interlocked.Read(ref session.VerifiedDocumentRevision))
                throw new IOException("Editor inputs changed during publication; build again.");
        }
        private void RequireView(Session session, ViewState view)
        {
            Guard(session);
            if (!SamePath(session.Root, view.Root) || !string.Equals(session.Compiler, view.Compiler, StringComparison.Ordinal))
                throw new IOException("Workspace or compiler setting changed; build again.");
        }

        private ViewState CaptureView()
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            string root = null;
            var solution = (IVsSolution)Package.GetGlobalService(typeof(SVsSolution));
            if (ErrorHandler.Succeeded(solution.GetSolutionInfo(out var directory, out var file, out var options)) && !string.IsNullOrEmpty(file)) root = directory;
            if (root == null)
            {
                var component = (IComponentModel)Package.GetGlobalService(typeof(SComponentModel));
                root = component.GetService<IVsFolderWorkspaceService>()?.CurrentWorkspace?.Location;
            }
            var rdt = (IVsRunningDocumentTable)Package.GetGlobalService(typeof(SVsRunningDocumentTable));
            var rdt4 = (IVsRunningDocumentTable4)rdt;
            ErrorHandler.ThrowOnFailure(rdt.GetRunningDocumentsEnum(out var documents));
            var cookie = new uint[1]; var dirty = new List<string>();
            while (documents.Next(1, cookie, out var fetched) == VSConstants.S_OK && fetched == 1)
                if (rdt4.IsDocumentDirty(cookie[0])) { var path = rdt4.GetDocumentMoniker(cookie[0]); if (!string.IsNullOrEmpty(path)) dirty.Add(path); }
            return new ViewState(root, package.ReadCompilerPath(), dirty.ToArray());
        }

        private void Track(Session session, string path, bool required)
        {
            Guard(session);
            lock (session.Sync) if (session.Snapshots.ContainsKey(path)) return;
            FileSnapshot snapshot = null;
            try { snapshot = FileIdentity.Observe(path, MaxInputBytes); }
            catch (FileNotFoundException) { if (required) throw; }
            catch (DirectoryNotFoundException) { if (required) throw; }
            var aliases = FileIdentity.WatchAliases(path);
            lock (session.Sync)
            {
                Guard(session);
                if (session.Snapshots.ContainsKey(path)) return;
                if (session.Snapshots.Count >= 4096 || (snapshot != null && session.TotalBytes + snapshot.Length > 64L * 1024 * 1024))
                    throw new IOException("VAS saved-input observation limit exceeded.");
                session.Snapshots.Add(path, snapshot);
                session.PathAliases[path] = aliases.ToArray();
                if (snapshot != null) session.TotalBytes += snapshot.Length;
                foreach (var alias in aliases) session.Aliases.Add(alias);
            }
        }
        private void WatchCandidate(Session session, string path)
        {
            // Descriptor enumeration never requires dormant unit contents to be
            // available. Observe their path aliases for changes during selection.
            IReadOnlyList<string> aliases;
            try { aliases = FileIdentity.WatchAliases(path); }
            catch (IOException) { aliases = new[] { Path.GetFullPath(path) }; }
            catch (UnauthorizedAccessException) { aliases = new[] { Path.GetFullPath(path) }; }
            lock (session.Sync)
            {
                Guard(session);
                foreach (var alias in aliases) session.Aliases.Add(alias);
            }
        }
        private void Validate(Session session, IEnumerable<string> dirty)
        {
            Guard(session);
            KeyValuePair<string, FileSnapshot>[] snapshots;
            lock (session.Sync) snapshots = session.Snapshots.ToArray();
            foreach (var pair in snapshots)
            {
                Guard(session);
                if (pair.Value == null ? File.Exists(pair.Key) || Directory.Exists(pair.Key) : !FileIdentity.Matches(pair.Value))
                    throw new IOException("Saved input changed; build again: " + pair.Key);
                string[] aliases;
                lock (session.Sync) aliases = session.PathAliases[pair.Key];
                if (!new HashSet<string>(aliases, StringComparer.OrdinalIgnoreCase).SetEquals(FileIdentity.WatchAliases(pair.Key)))
                    throw new IOException("Input path alias changed; build again: " + pair.Key);
            }
            foreach (var source in session.NavigationSnapshots)
                if (!FileIdentity.Matches(source)) throw new IOException("Compiler-observed source changed; build again: " + source.Path);
            foreach (var path in dirty)
            {
                if (snapshots.Any(pair => SamePath(pair.Key, path))) throw new IOException("Save the modified VAS input before building: " + path);
                foreach (var pair in snapshots)
                {
                    if (pair.Value == null) continue;
                    bool same;
                    try { same = FileIdentity.SameFile(pair.Key, path); }
                    catch (FileNotFoundException) { continue; }
                    catch (DirectoryNotFoundException) { continue; }
                    if (same) throw new IOException("Save the modified physical input alias before building: " + path);
                }
            }
            Guard(session);
        }

        private void Watch(Session session)
        {
            Guard(session);
            string[] aliases;
            lock (session.Sync) aliases = session.Aliases.ToArray();
            var directories = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var alias in aliases)
            {
                var directory = Path.GetDirectoryName(alias);
                while (!string.IsNullOrEmpty(directory) && !Directory.Exists(directory)) directory = Path.GetDirectoryName(directory);
                if (string.IsNullOrEmpty(directory)) throw new IOException("Cannot watch input ancestor: " + alias);
                directories.Add(directory);
                // A watcher inside a renamed/deleted directory need not receive its
                // own parent event. Watch the existing ancestor's parent as well.
                var parent = Path.GetDirectoryName(directory);
                if (!string.IsNullOrEmpty(parent)) directories.Add(parent);
            }
            foreach (var directory in directories)
            {
                Guard(session);
                lock (session.Sync) if (session.Watchers.ContainsKey(directory)) continue;
                var watcher = new FileSystemWatcher(directory) { IncludeSubdirectories = true, NotifyFilter = NotifyFilters.FileName | NotifyFilters.DirectoryName | NotifyFilters.LastWrite | NotifyFilters.Size | NotifyFilters.CreationTime };
                FileSystemEventHandler changed = (s, e) => InputEvent(session, e.FullPath);
                watcher.Changed += changed; watcher.Created += changed; watcher.Deleted += changed;
                watcher.Renamed += (s, e) => { InputEvent(session, e.OldFullPath); InputEvent(session, e.FullPath); };
                watcher.Error += (s, e) => InvalidateFromWorker(session, "Input watcher lost events; build again.");
                try
                {
                    watcher.EnableRaisingEvents = true;
                    lock (session.Sync)
                    {
                        Guard(session);
                        if (session.Watchers.Count >= 256) throw new IOException("Too many input watch directories; build cancelled.");
                        session.Watchers.Add(directory, watcher);
                    }
                }
                catch { watcher.Dispose(); throw; }
            }
        }
        private void InputEvent(Session session, string path)
        {
            bool relevant;
            lock (session.Sync) relevant = session.Aliases.Any(alias => SamePath(alias, path) || alias.Replace('\\', '/').StartsWith(path.Replace('\\', '/').TrimEnd('/') + "/", StringComparison.OrdinalIgnoreCase));
            if (relevant) InvalidateFromWorker(session, "Saved input changed; build again.");
        }
        private void InvalidateFromWorker(Session session, string reason)
        {
            if (session.Generation != Generation) return;
            // Advance immediately on the notifying thread, before any queued UI publication.
            if (Interlocked.CompareExchange(ref generation, session.Generation + 1, session.Generation) != session.Generation) return;
            session.Cancel.Cancel();
            factory.RunAsync(() => InvalidateOnUiAsync(session, reason)).FileAndForget("VerseAngelScript/InvalidateInput");
        }
        private async Task InvalidateOnUiAsync(Session session, string reason)
        {
            await factory.SwitchToMainThreadAsync();
            if (current == session) Cancel(reason);
        }
        private static string ResolveSection(string cwd, string section)
        {
            if (string.IsNullOrEmpty(section)) return null;
            try { return Path.GetFullPath(Path.IsPathRooted(section) ? section : Path.Combine(cwd, section)).Replace('\\', '/'); }
            catch (Exception ex) when (ex is ArgumentException || ex is NotSupportedException) { return null; }
        }
        private static bool SamePath(string a, string b) => a != null && b != null && string.Equals(a.Replace('\\', '/').TrimEnd('/'), b.Replace('\\', '/').TrimEnd('/'), StringComparison.OrdinalIgnoreCase);

        public void Dispose()
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            if (disposed) return;
            Cancel("VAS package disposed"); disposed = true; timer.Stop(); hostEvents.Dispose(); ErrorProvider.Dispose();
        }

        private sealed class ViewState
        {
            internal readonly string Root, Compiler; internal readonly string[] Dirty;
            internal ViewState(string root, string compiler, string[] dirty) { Root = root; Compiler = compiler; Dirty = dirty; }
        }
        private sealed class PublishedDiagnostic
        {
            internal readonly Diagnostic Diagnostic; internal readonly FileSnapshot Snapshot; internal readonly int Line, Column;
            internal PublishedDiagnostic(Diagnostic diagnostic, FileSnapshot snapshot, int line, int column) { Diagnostic = diagnostic; Snapshot = snapshot; Line = line; Column = column; }
        }
        private sealed class Session
        {
            internal readonly object Sync = new object();
            internal readonly long Generation;
            internal readonly string Root, Manifest, Compiler;
            internal readonly CancellationTokenSource Cancel;
            internal readonly CancellationToken Token;
            internal readonly Dictionary<string, FileSnapshot> Snapshots = new Dictionary<string, FileSnapshot>(StringComparer.OrdinalIgnoreCase);
            internal readonly Dictionary<string, string[]> PathAliases = new Dictionary<string, string[]>(StringComparer.OrdinalIgnoreCase);
            internal readonly HashSet<string> Aliases = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            internal readonly Dictionary<string, FileSystemWatcher> Watchers = new Dictionary<string, FileSystemWatcher>(StringComparer.OrdinalIgnoreCase);
            internal long TotalBytes, DocumentRevision, VerifiedDocumentRevision;
            internal string[] DirtyMonikers = new string[0];
            internal readonly ManualResetEventSlim DirtyCheck = new ManualResetEventSlim(true);
            internal BuildDialog Dialog; internal Descriptor Descriptor; internal CompilationUnit Unit; internal Report Report;
            internal FileSnapshot[] NavigationSnapshots = new FileSnapshot[0]; internal string Stderr;
            internal Session(long generation, string root, string manifest, string compiler, CancellationToken lifetime)
            { Generation = generation; Root = root; Manifest = manifest; Compiler = compiler; Cancel = CancellationTokenSource.CreateLinkedTokenSource(lifetime); Token = Cancel.Token; }
            internal string[] Paths() { lock (Sync) return Snapshots.Keys.ToArray(); }
            internal void StopWatching()
            {
                FileSystemWatcher[] watchers;
                lock (Sync) { watchers = Watchers.Values.ToArray(); Watchers.Clear(); }
                foreach (var watcher in watchers) watcher.Dispose();
            }
        }
    }
}
