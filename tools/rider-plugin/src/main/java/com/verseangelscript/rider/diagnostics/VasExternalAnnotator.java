package com.verseangelscript.rider.diagnostics;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.verseangelscript.rider.build.VasSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VasExternalAnnotator extends ExternalAnnotator<
    VasExternalAnnotator.Request,
    VasExternalAnnotator.Result
> {
    private static final int TIMEOUT_MS = 20_000;
    private static final Pattern INCLUDE = Pattern.compile(
        "(?m)^\\s*#include\\s+\"([^\"]+)\""
    );

    @Override
    public @Nullable Request collectInformation(@NotNull PsiFile file) {
        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null || file.getProject().getBasePath() == null) {
            return null;
        }
        return new Request(file.getProject(), virtualFile, file.getText());
    }

    @Override
    public @Nullable Result doAnnotate(Request request) {
        ProgressManager.checkCanceled();
        Toolchain toolchain = resolveToolchain(request.project(), request.file());
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
                new HashSet<>()
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
            ProcessOutput process = new CapturingProcessHandler(commandLine).runProcess(TIMEOUT_MS);
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
            return new Result(List.copyOf(currentFileDiagnostics));
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
        Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
        if (document == null) {
            return;
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
        int line = Math.max(0, Math.min(document.getLineCount() - 1, oneBasedLine - 1));
        int lineStart = document.getLineStartOffset(line);
        int lineEnd = document.getLineEndOffset(line);
        int offset = Math.max(lineStart, Math.min(lineEnd, lineStart + oneBasedColumn - 1));
        CharSequence chars = document.getCharsSequence();

        while (offset < lineEnd && Character.isWhitespace(chars.charAt(offset))) {
            offset++;
        }
        if (offset >= lineEnd) {
            return TextRange.from(Math.max(lineStart, lineEnd - 1), 1);
        }

        int start = offset;
        int end = offset;
        if (isIdentifierCharacter(chars.charAt(offset))) {
            while (start > lineStart && isIdentifierCharacter(chars.charAt(start - 1))) {
                start--;
            }
            while (end < lineEnd && isIdentifierCharacter(chars.charAt(end))) {
                end++;
            }
        } else {
            end++;
        }
        return new TextRange(start, Math.max(start + 1, end));
    }

    private static boolean isIdentifierCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private static @Nullable Toolchain resolveToolchain(Project project, VirtualFile file) {
        Path projectRoot = Path.of(project.getBasePath()).toAbsolutePath().normalize();
        Path source = Path.of(file.getPath()).toAbsolutePath().normalize();
        VasSettingsState settings = VasSettingsState.getInstance(project);

        // An external annotator is started merely by opening or editing a file. Never
        // auto-discover and execute .vas/bin/vasbuild.exe from an arbitrary project.
        // The user must opt in by configuring a builder path, or use the explicit Build
        // action, which is a user-initiated operation.
        if (settings.builderPath == null || settings.builderPath.isBlank()) {
            return null;
        }

        Path root = projectRoot;
        Path builder = resolveConfiguredPath(root, settings.builderPath, "");
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
        Set<Path> visited
    ) throws IOException {
        Path normalizedSource = actualSource.toAbsolutePath().normalize();
        if (!visited.add(normalizedSource)) {
            return;
        }

        Matcher matcher = INCLUDE.matcher(sourceText);
        while (matcher.find()) {
            Path included = normalizedSource.getParent().resolve(matcher.group(1)).normalize();
            if (!included.startsWith(actualRoot) || !Files.isRegularFile(included)) {
                continue;
            }
            Path target = temporarySource.getParent().resolve(matcher.group(1)).normalize();
            if (!target.startsWith(temporaryRoot)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.copy(included, target, StandardCopyOption.REPLACE_EXISTING);
            copyIncludes(
                included,
                target,
                Files.readString(included, StandardCharsets.UTF_8),
                actualRoot,
                temporaryRoot,
                visited
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
        @NotNull String sourceText
    ) {
    }

    public record Result(@NotNull List<VasCompilerDiagnostic> diagnostics) {
    }

    private record Toolchain(@NotNull Path root, @NotNull Path builder, @NotNull Path config) {
    }
}
