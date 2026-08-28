package com.verseangelscript.rider.diagnostics;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VasDiagnosticParser {
    private static final Pattern MESSAGE = Pattern.compile(
        "^(.+?)\\s*\\((\\d+),\\s*(\\d+)\\)\\s*:\\s*(ERR|WARN)\\s*:\\s*(.+)$"
    );

    private VasDiagnosticParser() {
    }

    public static @NotNull List<VasCompilerDiagnostic> parse(@NotNull String output) {
        List<VasCompilerDiagnostic> diagnostics = new ArrayList<>();
        for (String line : output.split("\\R")) {
            Matcher matcher = MESSAGE.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }

            VasCompilerDiagnostic.Severity severity = "ERR".equals(matcher.group(4))
                ? VasCompilerDiagnostic.Severity.ERROR
                : VasCompilerDiagnostic.Severity.WARNING;
            diagnostics.add(new VasCompilerDiagnostic(
                matcher.group(1).trim(),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                severity,
                matcher.group(5).trim()
            ));
        }
        return List.copyOf(diagnostics);
    }
}
