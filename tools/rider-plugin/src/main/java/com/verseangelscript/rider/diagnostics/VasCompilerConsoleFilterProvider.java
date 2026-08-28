package com.verseangelscript.rider.diagnostics;

import com.intellij.execution.filters.ConsoleFilterProvider;
import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Font;
import java.nio.file.Path;
import java.util.List;

public final class VasCompilerConsoleFilterProvider implements ConsoleFilterProvider {
    @Override
    public Filter @NotNull [] getDefaultFilters(@NotNull Project project) {
        return new Filter[] {new VasCompilerConsoleFilter(project)};
    }

    private static final class VasCompilerConsoleFilter implements Filter {
        private final Project project;

        private VasCompilerConsoleFilter(Project project) {
            this.project = project;
        }

        @Override
        public @Nullable Result applyFilter(@NotNull String line, int entireLength) {
            List<VasCompilerDiagnostic> diagnostics = VasDiagnosticParser.parse(line);
            if (diagnostics.isEmpty()) {
                return null;
            }

            VasCompilerDiagnostic diagnostic = diagnostics.get(0);
            int lineBreakLength = line.endsWith("\r\n") ? 2 : line.endsWith("\n") ? 1 : 0;
            int start = Math.max(0, entireLength - line.length());
            int end = Math.max(start, entireLength - lineBreakLength);
            TextAttributes attributes = new TextAttributes(
                diagnostic.severity() == VasCompilerDiagnostic.Severity.ERROR
                    ? JBColor.RED
                    : JBColor.ORANGE,
                null,
                null,
                null,
                Font.PLAIN
            );

            VirtualFile file = findFile(diagnostic.filePath());
            OpenFileHyperlinkInfo hyperlink = file == null || diagnostic.line() <= 0
                ? null
                : new OpenFileHyperlinkInfo(
                    project,
                    file,
                    diagnostic.line() - 1,
                    Math.max(0, diagnostic.column() - 1)
                );
            return new Result(start, end, hyperlink, attributes);
        }

        private static @Nullable VirtualFile findFile(@NotNull String filePath) {
            try {
                String normalized = Path.of(filePath).toAbsolutePath().normalize()
                    .toString().replace('\\', '/');
                return LocalFileSystem.getInstance().findFileByPath(normalized);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}
