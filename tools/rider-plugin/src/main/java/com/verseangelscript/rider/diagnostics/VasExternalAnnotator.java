package com.verseangelscript.rider.diagnostics;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.verseangelscript.rider.build.VasSettingsState;
import com.verseangelscript.rider.build.VasToolchainSettings;
import com.verseangelscript.rider.index.VasIncludeScanner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

public final class VasExternalAnnotator extends ExternalAnnotator<
    VasExternalAnnotator.Request,
    VasExternalAnnotator.Result
> {
    private static final int TIMEOUT_MS = 20_000;
    private static final Key<ProcessLauncher> TEST_LAUNCHER = Key.create("vas.diagnostics.testLauncher");

    @Override
    public @Nullable Request collectInformation(@NotNull PsiFile file) {
        Project project = file.getProject();
        if (!canRunDiagnostics(project)) {
            return null;
        }
        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null || project.getBasePath() == null) {
            return null;
        }
        Map<String, String> unsaved = new HashMap<>();
        var documents = FileDocumentManager.getInstance();
        long bytes = 0;
        for (Document document : documents.getUnsavedDocuments()) {
            VirtualFile open = documents.getFile(document);
            if (open != null && open.getExtension() != null && open.getExtension().equalsIgnoreCase("vas")
                && open.getPath().startsWith(project.getBasePath() + "/") && document.getTextLength() <= 4 * 1024 * 1024) {
                bytes += document.getTextLength();
                if (unsaved.size() >= 256 || bytes > 16 * 1024 * 1024) break;
                unsaved.put(open.getPath(), document.getText());
            }
        }
        unsaved.put(virtualFile.getPath(), file.getText());
        return new Request(project, virtualFile, file.getText(), Map.copyOf(unsaved));
    }

    @Override
    public @Nullable Result doAnnotate(Request request) {
        ProgressManager.checkCanceled();
        // Requests may have been queued while the project was still trusted/open.
        if (!canRunDiagnostics(request.project()) || request.project().getBasePath() == null) {
            return null;
        }
        Toolchain toolchain = resolveToolchain(request.project());
        if (toolchain == null) {
            return null;
        }

        Path temporaryRoot = null;
        try {
            temporaryRoot = Files.createTempDirectory("vas-rider-diagnostics-");
            Path source = Path.of(request.file().getPath()).toAbsolutePath().normalize();
            Path relativeSource = source.startsWith(toolchain.root())
                ? toolchain.root().relativize(source)
                : Path.of(source.getFileName().toString());
            Path temporarySource = temporaryRoot.resolve(relativeSource).normalize();
            if (!temporarySource.startsWith(temporaryRoot)) {
                return null;
            }

            Files.createDirectories(temporarySource.getParent());
            Files.writeString(temporarySource, request.sourceText(), StandardCharsets.UTF_8);
            copyIncludes(
                source,
                temporarySource,
                request.sourceText(),
                toolchain.root(),
                temporaryRoot,
                new HashSet<>(), request.openSources(), new long[] {0}
            );

            Path output = temporaryRoot.resolve("out/diagnostics.vasbc");
            Files.createDirectories(output.getParent());
            GeneralCommandLine commandLine = new GeneralCommandLine(toolchain.builder().toString())
                .withParameters(
                    toolchain.config().toString(),
                    temporarySource.toString(),
                    output.toString()
                )
                .withWorkDirectory(temporaryRoot.toFile())
                .withCharset(StandardCharsets.UTF_8);
            List<String> includeRoots = new ArrayList<>();
            for (Path parent = temporarySource.getParent(); parent != null && parent.startsWith(temporaryRoot); parent = parent.getParent())
                includeRoots.add(parent.toString());
            commandLine.withEnvironment("VAS_INCLUDE_PATH", String.join(java.io.File.pathSeparator, includeRoots));
            ProgressManager.checkCanceled();
            // Recheck after preparing the snapshot, immediately before starting a process.
            if (!canRunDiagnostics(request.project())) {
                return null;
            }
            ProcessLauncher launcher = request.project().getUserData(TEST_LAUNCHER);
            ProcessOutput process = launcher == null
                ? new CapturingProcessHandler(commandLine).runProcess(TIMEOUT_MS)
                : launcher.run(commandLine, TIMEOUT_MS);
            if (process.isTimeout()) {
                return null;
            }

            String compilerOutput = process.getStdout() + process.getStderr();
            List<VasCompilerDiagnostic> currentFileDiagnostics = new ArrayList<>();
            String expectedPath = temporarySource.toAbsolutePath().normalize().toString();
            for (VasCompilerDiagnostic diagnostic : VasDiagnosticParser.parse(compilerOutput)) {
                if (diagnostic.line() <= 0 || diagnostic.column() <= 0) {
                    continue;
                }
                Path reported;
                try {
                    reported = Path.of(diagnostic.filePath()).toAbsolutePath().normalize();
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (reported.toString().equalsIgnoreCase(expectedPath)) {
                    currentFileDiagnostics.add(diagnostic);
                }
            }
            return new Result(List.copyOf(currentFileDiagnostics), request.sourceText(), request.openSources());
        } catch (ExecutionException | IOException ignored) {
            return null;
        } finally {
            deleteTemporaryTree(temporaryRoot);
        }
    }

    @Override
    public void apply(
        @NotNull PsiFile file,
        Result result,
        @NotNull AnnotationHolder holder
    ) {
        if (!canRunDiagnostics(file.getProject())) {
            return;
        }
        Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null) {
            return;
        }
        if (result.sourceText() != null && !document.getText().equals(result.sourceText())) return;
        for (var snapshot : result.openSources().entrySet()) {
            VirtualFile open = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(snapshot.getKey());
            Document cached = open == null ? null : FileDocumentManager.getInstance().getCachedDocument(open);
            if (cached != null && !cached.getText().equals(snapshot.getValue())) return;
        }

        for (VasCompilerDiagnostic diagnostic : result.diagnostics()) {
            TextRange range = diagnosticRange(document, diagnostic.line(), diagnostic.column());
            HighlightSeverity severity = diagnostic.severity()
                == VasCompilerDiagnostic.Severity.ERROR
                ? HighlightSeverity.ERROR
                : HighlightSeverity.WARNING;
            holder.newAnnotation(severity, diagnostic.message())
                .range(range)
                .create();
        }
    }

    static @NotNull TextRange diagnosticRange(
        @NotNull Document document,
        int oneBasedLine,
        int oneBasedColumn
    ) {
        if (document.getTextLength() == 0) {
            return TextRange.EMPTY_RANGE;
        }
        int line = oneBasedLine <= 1 ? 0 : Math.min(document.getLineCount() - 1, oneBasedLine - 1);
        VasDiagnosticRange.Range range = VasDiagnosticRange.forLine(
            document.getCharsSequence(),
            document.getLineStartOffset(line),
            document.getLineEndOffset(line),
            oneBasedColumn
        );
        return new TextRange(range.startOffset(), range.endOffset());
    }

    private static boolean canRunDiagnostics(Project project) {
        return !project.isDisposed() && TrustedProjects.isProjectTrusted(project);
    }

    // Project-scoped so the actual registered annotator can be exercised without
    // ever running a project-supplied binary. This is not a user-facing setting.
    @TestOnly
    static void installTestLauncher(Project project, ProcessLauncher launcher, Disposable lifetime) {
        if (!ApplicationManager.getApplication().isUnitTestMode()) {
            throw new IllegalStateException("Diagnostic launcher replacement is only available in tests");
        }
        ProcessLauncher previous = project.getUserData(TEST_LAUNCHER);
        project.putUserData(TEST_LAUNCHER, launcher);
        Disposer.register(lifetime, () -> project.putUserData(TEST_LAUNCHER, previous));
    }

    @FunctionalInterface
    interface ProcessLauncher {
        ProcessOutput run(GeneralCommandLine commandLine, int timeoutMs) throws ExecutionException;
    }

    private static @Nullable Toolchain resolveToolchain(Project project) {
        Path projectRoot = Path.of(project.getBasePath()).toAbsolutePath().normalize();
        VasSettingsState settings = VasSettingsState.getInstance(project);

        // An external annotator is started merely by opening or editing a file. Never
        // auto-discover and execute .vas/bin/vasbuild.exe from an arbitrary project.
        // A configured path is necessary but not proof of consent: vas.xml may come
        // from the project itself. The caller must also check actual project trust.
        // The explicit Build action is a separate, user-initiated operation.
        String configuredBuilder = settings.builderPath;
        if (configuredBuilder == null || configuredBuilder.isBlank()) configuredBuilder = VasToolchainSettings.getInstance().compilerPath;
        if (configuredBuilder == null || configuredBuilder.isBlank()) {
            return null;
        }

        Path root = projectRoot;
        Path builder = resolveConfiguredPath(root, configuredBuilder, "");
        Path config = resolveConfiguredPath(root, settings.configPath, ".vas/vasbuild.config.txt");
        if (settings.configPath == null || settings.configPath.isBlank()) {
            if (!Files.isRegularFile(config)) {
                config = projectRoot.resolve("tests/vasbuild/fixtures/minimal-config.txt");
            }
        }
        return Files.isRegularFile(builder) && Files.isRegularFile(config)
            ? new Toolchain(root, builder.normalize(), config.normalize())
            : null;
    }

    private static Path resolveConfiguredPath(Path root, String configured, String fallback) {
        String value = configured == null ? "" : configured.trim();
        Path path = value.isEmpty() ? Path.of(fallback) : Path.of(value);
        return path.isAbsolute() ? path.normalize() : root.resolve(path).normalize();
    }

    private static void copyIncludes(
        Path actualSource,
        Path temporarySource,
        String sourceText,
        Path actualRoot,
        Path temporaryRoot,
        Set<Path> visited, Map<String, String> openSources, long[] bytes
    ) throws IOException {
        Path normalizedSource = actualSource.toAbsolutePath().normalize();
        if (!visited.add(normalizedSource)) {
            return;
        }
        bytes[0] += sourceText.length();
        if (visited.size() > 256 || bytes[0] > 16 * 1024 * 1024) throw new IOException("Diagnostic snapshot limit exceeded");
        for (var dependency : VasIncludeScanner.scan(sourceText).includes()) {
            Path included = normalizedSource.getParent().resolve(dependency.path()).normalize();
            if (!Files.isRegularFile(included) || dependency.kind() == VasIncludeScanner.Kind.SYSTEM) {
                Set<Path> matches = new java.util.LinkedHashSet<>();
                for (Path parent = normalizedSource.getParent(); parent != null && parent.startsWith(actualRoot); parent = parent.getParent()) {
                    Path candidate = parent.resolve(dependency.path()).normalize();
                    if (candidate.startsWith(actualRoot) && Files.isRegularFile(candidate)) matches.add(candidate);
                }
                if (matches.size() != 1) continue;
                included = matches.iterator().next();
            }
            if (!included.startsWith(actualRoot) || !Files.isRegularFile(included)) {
                continue;
            }
            Path target = temporaryRoot.resolve(actualRoot.relativize(included)).normalize();
            if (!target.startsWith(temporaryRoot)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            String text = openSources.get(included.toString().replace('\\', '/'));
            if (text == null) text = openSources.get(included.toString());
            if (text == null) for (var snapshot : openSources.entrySet()) {
                if (snapshot.getKey().replace('\\', '/').equalsIgnoreCase(included.toString().replace('\\', '/'))) { text = snapshot.getValue(); break; }
            }
            if (text == null) {
                if (Files.size(included) > 4 * 1024 * 1024) throw new IOException("Diagnostic source limit exceeded");
                text = Files.readString(included, StandardCharsets.UTF_8);
            }
            Files.writeString(target, text, StandardCharsets.UTF_8);
            copyIncludes(
                included,
                target,
                text,
                actualRoot,
                temporaryRoot,
                visited, openSources, bytes
            );
        }
    }

    private static void deleteTemporaryTree(@Nullable Path root) {
        if (root == null || !root.getFileName().toString().startsWith("vas-rider-diagnostics-")) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The operating system will eventually clean abandoned temp files.
                }
            });
        } catch (IOException ignored) {
            // The operating system will eventually clean abandoned temp files.
        }
    }

    public record Request(
        @NotNull Project project,
        @NotNull VirtualFile file,
        @NotNull String sourceText,
        @NotNull Map<String, String> openSources
    ) {
        public Request(Project project, VirtualFile file, String sourceText) { this(project, file, sourceText, Map.of()); }
    }

    public record Result(@NotNull List<VasCompilerDiagnostic> diagnostics, @Nullable String sourceText, @NotNull Map<String, String> openSources) {
        public Result(List<VasCompilerDiagnostic> diagnostics) { this(diagnostics, null, Map.of()); }
    }

    private record Toolchain(@NotNull Path root, @NotNull Path builder, @NotNull Path config) {
    }
}
