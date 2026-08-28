package com.verseangelscript.rider.diagnostics;

import org.jetbrains.annotations.NotNull;

public record VasCompilerDiagnostic(
    @NotNull String filePath,
    int line,
    int column,
    @NotNull Severity severity,
    @NotNull String message
) {
    public enum Severity {
        ERROR,
        WARNING
    }
}
