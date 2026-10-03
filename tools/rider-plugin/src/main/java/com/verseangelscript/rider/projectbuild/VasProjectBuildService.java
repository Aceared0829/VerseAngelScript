package com.verseangelscript.rider.projectbuild;

import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.ide.trustedProjects.TrustedProjectsListener;
import com.intellij.ide.trustedProjects.TrustedProjectsLocator;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.newvfs.BulkFileListener;
import com.intellij.openapi.vfs.newvfs.events.VFileEvent;
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent;
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent;
import com.verseangelscript.rider.build.VasToolchainSettings;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit-only, saved-input project builds. Never used by the external annotator. */
@Service(Service.Level.PROJECT)
public final class VasProjectBuildService implements Disposable {
    public interface Interaction {
        String select(VasProjectProtocol.Descriptor descriptor);
        void show(Outcome outcome);
    }
    public record Location(Path file, int offset, VasProjectInputs.Snapshot snapshot) {}
    public record Item(VasProjectProtocol.Diagnostic diagnostic, Location location) {}
    public record Outcome(long generation, String unit, String status, boolean success, List<Item> items) {}
    private record Selection(VasProjectProtocol.Descriptor descriptor, Map<String,String> inputStamps) {}
    private final Project project;
    private final Object stateLock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, Set<Path>> dependencies = new ConcurrentHashMap<>();
    private volatile Session current;
    private volatile Outcome latest;
    private volatile Interaction interaction;
    private volatile boolean disposed;
    private volatile Runnable filesystemObserver = () -> {};
    void installFilesystemObserver(Runnable observer, Disposable lifetime) {
        filesystemObserver = observer;
        com.intellij.openapi.util.Disposer.register(lifetime, () -> filesystemObserver = () -> {});
    }
    private void filesystemWork() {
        if (ApplicationManager.getApplication().isDispatchThread())
            throw new IllegalStateException("VAS filesystem validation must run off the EDT.");
        filesystemObserver.run();
    }
    private volatile java.util.function.Consumer<List<String>> launchObserver = ignored -> {};
    void installLaunchObserver(java.util.function.Consumer<List<String>> observer, Disposable lifetime) {
        launchObserver = observer;
        com.intellij.openapi.util.Disposer.register(lifetime, () -> launchObserver = ignored -> {});
    }
    private void beforeProcess(Session session, List<String> arguments) {
        guard(session, false);
        launchObserver.accept(arguments);
        guard(session, false);
    }

    public VasProjectBuildService(Project project) {
        this.project = project;
        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(TrustedProjectsListener.TOPIC,
            new TrustedProjectsListener() {
                @Override public void onProjectUntrusted(@NotNull TrustedProjectsLocator.LocatedProject locatedProject) {
                    // Path-level trust updates may not carry a Project instance.
                    if (!project.isDisposed() && !TrustedProjects.isProjectTrusted(project))
                        invalidate("Project trust was revoked. Trust the project and build again.");
                }
            });
        EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(@NotNull DocumentEvent event) {
                VirtualFile file = FileDocumentManager.getInstance().getFile(event.getDocument());
                if (file != null) changed(Path.of(file.getPath()), false);
            }
        }, this);
        project.getMessageBus().connect(this).subscribe(VirtualFileManager.VFS_CHANGES, new BulkFileListener() {
            @Override public void before(@NotNull List<? extends VFileEvent> events) {
                // Rename/move events must be checked while their old directory
                // identity still exists, including parents of observed sources.
                for (VFileEvent event : events) vfsChanged(event);
            }
            @Override public void after(@NotNull List<? extends VFileEvent> events) {
                for (VFileEvent event : events) vfsChanged(event);
            }
        });
    }

    public static VasProjectBuildService getInstance(Project project) { return project.getService(VasProjectBuildService.class); }
    public Outcome latest() { return latest; }
    public long inputRevision(Outcome outcome) {
        Session session = current;
        return session != null && outcome.generation() == session.id && active(session) && latest == outcome ? session.inputEvents.get() : -1;
    }
    public boolean isCurrent(Outcome outcome) {
        Session session = current;
        if (session == null || outcome.generation() != session.id || latest != outcome || !active(session)) return false;
        try { guard(session, true); return latest == outcome && active(session); }
        catch (RuntimeException exception) { invalidate(session, exception.getMessage()); return false; }
    }
    public void setInteraction(Interaction value) { interaction = value; }
    public Set<Path> dependencies(String manifest, String unit) { return Set.copyOf(dependencies.getOrDefault(key(manifest, unit), Set.of())); }
    private static String key(String manifest, String unit) { return manifest + "\0" + unit; }

    public void start() {
        if (disposed || project.isDisposed() || project.getBasePath() == null) return;
        Session session = new Session(sequence.incrementAndGet(), Path.of(project.getBasePath()).toAbsolutePath().normalize().resolve("vas-project.json"),
            VasToolchainSettings.getInstance().compilerPath);
        session.aliasChecks = new VasProjectChangeQueue(1024,
            runnable -> ApplicationManager.getApplication().executeOnPooledThread(runnable),
            () -> active(session), path -> {
                boolean discovery = Boolean.TRUE.equals(session.changeKinds.remove(path));
                if (discovery) {
                    // VFS can discover a file already read by the native compiler.
                    // Keep results only after revalidating all saved bytes off-thread.
                    try { guard(session, true); }
                    catch (RuntimeException exception) { invalidate(session, exception.getMessage()); }
                } else if (inputAlias(session, path)) invalidate(session, "Inputs changed; build again");
            }, () -> {
                session.changeKinds.clear();
                invalidate(session, "Too many pending input changes. Build again.");
            });
        synchronized (stateLock) {
            Session old = current;
            if (old != null) old.cancelled = true;
            current = session;
        }
        publishProgress(session, new Outcome(session.id, "", "Reading project…", false, List.of()));
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Describe VAS project", true) {
            Selection selection;
            @Override public void run(@NotNull ProgressIndicator indicator) {
                try {
                    session.indicator = indicator;
                    Path compiler = VasProjectProcess.validateNativeCompiler(session.compilerSetting);
                    session.compilerStamp = VasProjectInputs.stamp(compiler);
                    session.compiler = compiler;
                    guard(session, false);
                    capture(session, session.manifest);
                    if (!Files.isRegularFile(session.manifest)) throw new IOException("No root vas-project.json. Build Project requires an explicit manifest.");
                    beforeProcess(session, List.of("--describe-project=json", session.manifest.toString()));
                    var output = VasProjectProcess.run(session.compiler, List.of("--describe-project=json", session.manifest.toString()),
                        session.manifest.getParent(), 30_000, 16L * 1024 * 1024, () -> guardState(session), null);
                    var descriptor = VasProjectProtocol.describe(output.stdout(), output.exitCode());
                    if (!same(Path.of(descriptor.project()), session.manifest) || !same(Path.of(descriptor.root()), session.manifest.getParent()))
                        throw new IOException("Compiler described a different VAS project.");
                    Map<String,String> stamps = new HashMap<>();
                    for (var unit : descriptor.units()) {
                        stamps.put(unit.config(), VasProjectInputs.stamp(Path.of(unit.config())));
                        stamps.put(unit.entry(), VasProjectInputs.stamp(Path.of(unit.entry())));
                    }
                    guard(session, true);
                    selection = new Selection(descriptor, Map.copyOf(stamps));
                } catch (Exception exception) { failed(session, exception); }
            }
            @Override public void onSuccess() {
                if (selection == null || !active(session)) return;
                try {
                    guardState(session);
                    String chosen = ui().select(selection.descriptor());
                    if (chosen == null) { invalidate(session, "Build cancelled"); return; }
                    var unit = selection.descriptor().units().stream().filter(value -> value.id().equals(chosen)).findFirst()
                        .orElseThrow(() -> new IllegalStateException("Select an explicit VAS compilation unit."));
                    guardState(session);
                    session.unit = unit;
                    session.descriptor = selection.descriptor();
                    build(session, selection);
                } catch (Exception exception) { failed(session, exception); }
            }
            @Override public void onCancel() { invalidate(session, "Build cancelled"); }
        });
    }

    private void build(Session session, Selection selection) {
        publishProgress(session, new Outcome(session.id, session.unit.id(), "Building…", false, List.of()));
        ProgressManager.getInstance().run(new Task.Backgroundable(project, "Build VAS unit " + session.unit.id(), true) {
            @Override public void run(@NotNull ProgressIndicator indicator) {
                session.indicator = indicator;
                var report = new VasProjectProtocol.Report(session.descriptor, session.unit);
                try {
                    for (String file : List.of(session.unit.config(), session.unit.entry())) {
                        Path path = Path.of(file);
                        if (!Objects.equals(selection.inputStamps().get(file), VasProjectInputs.stamp(path)))
                            throw new IOException("VAS input changed after unit selection. Build again: " + file);
                        capture(session, path);
                    }
                    guard(session, true);
                    session.startedMillis = System.currentTimeMillis();
                    beforeProcess(session, List.of("--report=jsonl", "--project", session.manifest.toString(), "--unit", session.unit.id()));
                    var output = VasProjectProcess.run(session.compiler,
                        List.of("--report=jsonl", "--project", session.manifest.toString(), "--unit", session.unit.id()),
                        Path.of(session.descriptor.root()), 120_000, 64L * 1024 * 1024, () -> guardState(session), line -> {
                            report.acceptLine(line);
                            observe(session, report);
                        });
                    report.finish(output.exitCode());
                    guard(session, true);
                    mergeDependencies(session, report, report.dependenciesComplete());
                    List<Item> items = new ArrayList<>();
                    for (var diagnostic : report.diagnostics()) items.add(new Item(diagnostic, location(session, report, diagnostic)));
                    String status = report.success() ? "Build succeeded" : "Build failed (" + report.phase() + ")";
                    if (!output.stderr().isBlank()) status += "\nCompiler stderr: " + output.stderr();
                    publish(session, new Outcome(session.id, session.unit.id(), status, report.success(), List.copyOf(items)));
                } catch (Exception exception) {
                    mergeDependencies(session, report, false);
                    failed(session, exception);
                }
            }
            @Override public void onCancel() { invalidate(session, "Build cancelled"); }
        });
    }

    private void observe(Session session, VasProjectProtocol.Report report) {
        var observed = report.lastObserved();
        if (observed != null) {
            final Path path;
            try { path = VasProjectInputs.resolveObservedPath(report.cwd(), observed); }
            catch (IOException exception) { throw new IllegalStateException(exception.getMessage(), exception); }
            session.observed.add(path);
            if (!session.inputs.containsKey(path)) {
                try {
                    capture(session, path);
                    // A section changed after launch cannot be proven to match native loaded bytes.
                    if (Files.exists(path) && Files.getLastModifiedTime(path).toMillis() >= session.startedMillis)
                        throw new IOException("Dependency changed during build: " + path);
                } catch (IOException exception) { throw new IllegalStateException(exception.getMessage(), exception); }
            }
            if (report.lastObservationLoaded() && session.inputs.get(path).digest() == null)
                throw new IllegalStateException("Loaded VAS source is no longer a regular file: " + path);
        }
    }

    private VasProjectInputs.Snapshot capture(Session session, Path path) throws IOException {
        if (session.inputs.size() >= 4096) throw new IOException("VAS input observation exceeds the 4096-file client limit.");
        var snapshot = VasProjectInputs.capture(path);
        if (session.inputBytes + snapshot.byteLength() > 64L * 1024 * 1024)
            throw new IOException("VAS input snapshots exceed the 64 MiB client limit.");
        session.inputBytes += snapshot.byteLength();
        session.inputs.put(path, snapshot);
        return snapshot;
    }

    private void mergeDependencies(Session session, VasProjectProtocol.Report report, boolean complete) {
        if (session.unit == null) return;
        String key = key(session.manifest.toString(), session.unit.id());
        dependencies.compute(key, (ignored, previous) -> {
            Set<Path> paths = new HashSet<>(complete && active(session) ? Set.of() : previous == null ? Set.of() : previous);
            paths.add(session.manifest);
            paths.add(Path.of(session.unit.config()));
            paths.add(Path.of(session.unit.entry()));
            paths.addAll(session.observed);
            for (Path path : List.copyOf(paths)) try { paths.add(path.toRealPath()); } catch (IOException ignoredException) { }
            return Set.copyOf(paths);
        });
    }

    private Location location(Session session, VasProjectProtocol.Report report, VasProjectProtocol.Diagnostic diagnostic) {
        if (!diagnostic.bindable()) return null;
        Path path = resolve(report.cwd(), diagnostic.section());
        if (path == null || !session.inputs.containsKey(path)) return null;
        var snapshot = session.inputs.get(path);
        if (snapshot.digest() == null) return null;
        int offset = report.invalidUtf8Sources().contains(diagnostic.section()) ? -1
            : VasProjectInputs.offset(snapshot.text(), diagnostic.row(), diagnostic.column());
        return new Location(path, offset, snapshot);
    }

    /** Cheap state-only check, safe in UI callbacks. No filesystem or dirty-alias reads. */
    private void guardState(Session session) {
        ProgressIndicator indicator = session.indicator;
        if (!active(session) || (indicator != null && indicator.isCanceled())) throw new IllegalStateException("VAS project build cancelled.");
        if (!TrustedProjects.isProjectTrusted(project)) throw new IllegalStateException("Trust this project before Build Project.");
        if (!session.compilerSetting.equals(VasToolchainSettings.getInstance().compilerPath)) throw new IllegalStateException("VAS compiler setting changed. Build again.");
    }

    /** All disk checks run off EDT, and outside the short document read action. */
    private void guard(Session session, boolean content) {
        filesystemWork();
        guardState(session);
        List<Path> dirty = ReadAction.computeBlocking(() -> {
            List<Path> files = new ArrayList<>();
            FileDocumentManager manager = FileDocumentManager.getInstance();
            for (Document document : manager.getUnsavedDocuments()) {
                VirtualFile file = manager.getFile(document);
                if (file != null) files.add(Path.of(file.getPath()));
            }
            return files;
        });
        for (Path path : dirty) {
            if (path.toString().toLowerCase(Locale.ROOT).endsWith(".vas") || inputAlias(session, path))
                throw new IllegalStateException("Save the VAS project input before building: " + path);
        }
        try {
            if (session.compiler != null && (session.compilerStamp == null
                || !session.compiler.equals(Path.of(session.compilerSetting).toRealPath())
                || !session.compilerStamp.equals(VasProjectInputs.stamp(session.compiler))))
                throw new IOException("VAS compiler executable changed. Build again.");
            for (var snapshot : session.inputs.values()) if (!snapshot.unchanged(content))
                throw new IOException("VAS input changed. Build again: " + snapshot.path());
        } catch (IOException exception) { throw new IllegalStateException(exception.getMessage(), exception); }
    }

    private boolean inputAlias(Session session, Path path) {
        filesystemWork();
        if (same(path, session.manifest)) return true;
        Set<Path> known = new HashSet<>(session.inputs.keySet());
        if (session.unit != null) known.addAll(dependencies.getOrDefault(key(session.manifest.toString(), session.unit.id()), Set.of()));
        for (Path input : known) {
            if (same(path, input)) return true;
            try { if (Files.isSameFile(path, input)) return true; } catch (IOException ignored) { }
        }
        return false;
    }

    private void vfsChanged(VFileEvent event) {
        if (event instanceof VFilePropertyChangeEvent property && !property.isRename()
            && !VirtualFile.PROP_SYMLINK_TARGET.equals(property.getPropertyName())
            && !VirtualFile.PROP_CHILDREN_CASE_SENSITIVITY.equals(property.getPropertyName())) return;
        if (event instanceof VFileCreateEvent && event.isFromRefresh()) {
            Session session = current;
            if (session == null || !active(session)) return;
            Path path = Path.of(event.getPath()).toAbsolutePath().normalize();
            if (!knownPath(session, path, true)) return;
            session.inputEvents.incrementAndGet();
            // Every discovery validates the same saved snapshot. Coalesce a whole
            // refresh burst under one key instead of hashing every input per file.
            queueChange(session, session.manifest, true);
        } else changed(Path.of(event.getPath()), true);
    }

    private void queueChange(Session session, Path path, boolean discovery) {
        // A mutation wins over discovery when both events coalesce for one path.
        session.changeKinds.merge(path, discovery, (previous, next) -> previous && next);
        session.aliasChecks.submit(path);
    }

    /** Listener work is purely lexical. Physical aliases are coalesced on a worker. */
    private void changed(Path path, boolean includeAncestors) {
        Session session = current;
        if (session == null || !active(session)) return;
        Path changed = path.toAbsolutePath().normalize();
        session.inputEvents.incrementAndGet();
        boolean known = knownPath(session, changed, includeAncestors);
        // A bytecode destination can itself end .vas; only real inputs invalidate it.
        boolean output = session.unit != null && same(changed, Path.of(session.unit.output()));
        if (known || !output && changed.toString().toLowerCase(Locale.ROOT).endsWith(".vas")) {
            invalidate(session, "Inputs changed; build again");
        } else {
            queueChange(session, changed, false);
        }
    }
    private boolean knownPath(Session session, Path path, boolean includeAncestors) {
        if (same(path, session.manifest) || session.inputs.containsKey(path)
            || dependencies.values().stream().anyMatch(paths -> paths.contains(path))) return true;
        return includeAncestors && (session.manifest.startsWith(path)
            || session.inputs.keySet().stream().anyMatch(input -> input.startsWith(path))
            || dependencies.values().stream().anyMatch(paths -> paths.stream().anyMatch(input -> input.startsWith(path))));
    }
    private static boolean same(Path first, Path second) { return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize()); }
    private static Path resolve(String cwd, String path) {
        try { Path value = Path.of(path); return value.isAbsolute() ? value : Path.of(cwd).resolve(value).normalize(); }
        catch (RuntimeException exception) { return null; }
    }
    private boolean active(Session session) { return !disposed && !project.isDisposed() && !session.cancelled && current == session; }
    public void cancel() { invalidate("Build cancelled"); }
    public void compilerChanged() { invalidate("VAS compiler setting changed. Build again."); }
    private void invalidate(String reason) { invalidate(current, reason); }
    private void invalidate(Session session, String reason) {
        if (session == null) return;
        Outcome result;
        synchronized (stateLock) {
            if (current != session || session.cancelled) return;
            session.cancelled = true;
            session.indicator = null;
            result = new Outcome(session.id, session.unit == null ? "" : session.unit.id(), reason, false, List.of());
            latest = result;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!disposed && !project.isDisposed() && current == session && latest == result) ui().show(result);
        });
    }
    private void failed(Session session, Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        publish(session, new Outcome(session.id, session.unit == null ? "" : session.unit.id(), message, false, List.of()));
    }
    private void publishProgress(Session session, Outcome result) {
        // Empty, non-success progress is safe to display immediately, even while a
        // filesystem is slow. Never leave the previous build's success visible.
        long revision = session.publications.incrementAndGet();
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!active(session) || session.publications.get() != revision) return;
            try { guardState(session); }
            catch (RuntimeException exception) { invalidate(session, exception.getMessage()); return; }
            synchronized (stateLock) {
                if (!active(session) || session.publications.get() != revision) return;
                latest = result;
            }
            ui().show(result);
        });
    }

    private void publish(Session session, Outcome result) {
        validatePublication(session, result, session.publications.incrementAndGet());
    }

    /** Validate off-thread, then use an in-memory event/revision barrier on EDT. */
    private void validatePublication(Session session, Outcome result, long revision) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (!active(session) || session.publications.get() != revision) return;
            long events = session.inputEvents.get();
            try { guard(session, false); }
            catch (RuntimeException exception) { invalidate(session, exception.getMessage()); return; }
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!active(session) || session.publications.get() != revision) return;
                try { guardState(session); }
                catch (RuntimeException exception) { invalidate(session, exception.getMessage()); return; }
                if (session.inputEvents.get() != events) {
                    // An edit or VFS event arrived after validation. Revalidate on a
                    // worker rather than doing alias/stat/hash work on the UI thread.
                    validatePublication(session, result, revision);
                    return;
                }
                synchronized (stateLock) {
                    if (!active(session) || session.publications.get() != revision) return;
                    latest = result;
                }
                ui().show(result);
            });
        });
    }
    private Interaction ui() { return interaction != null ? interaction : VasProjectBuildView.interaction(project); }
    @Override public void dispose() { disposed = true; if (current != null) current.cancelled = true; }

    private static final class Session {
        final long id;
        final Path manifest;
        final String compilerSetting;
        final Map<Path, VasProjectInputs.Snapshot> inputs = new ConcurrentHashMap<>();
        final Set<Path> observed = ConcurrentHashMap.newKeySet();
        final Map<Path, Boolean> changeKinds = new ConcurrentHashMap<>();
        final AtomicLong inputEvents = new AtomicLong();
        final AtomicLong publications = new AtomicLong();
        VasProjectChangeQueue aliasChecks;
        volatile boolean cancelled;
        volatile ProgressIndicator indicator;
        volatile Path compiler;
        VasProjectProtocol.Descriptor descriptor;
        volatile VasProjectProtocol.Unit unit;
        long startedMillis;
        long inputBytes;
        String compilerStamp;
        Session(long id, Path manifest, String compilerSetting) { this.id = id; this.manifest = manifest; this.compilerSetting = compilerSetting == null ? "" : compilerSetting; }
    }
}
