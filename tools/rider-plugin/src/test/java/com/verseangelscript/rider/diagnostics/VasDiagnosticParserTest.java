package com.verseangelscript.rider.diagnostics;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public final class VasDiagnosticParserTest {
    @Test
    public void parsesErrorsAndWarningsAndIgnoresInfoMessages() {
        String output = """
            D:/Project/src/main.vas (3, 1) : INFO : Compiling void main()
            D:/Project/src/main.vas (10, 25) : ERR  : No matching symbol 'result3'
            D:/Project/src/main.vas (12, 9) : WARN : Variable is never used
            D:/Project/src/main.vas (0, 0) : ERR  : Script failed to build
            """;

        List<VasCompilerDiagnostic> diagnostics = VasDiagnosticParser.parse(output);
        assertEquals(3, diagnostics.size());
        assertEquals(10, diagnostics.get(0).line());
        assertEquals(25, diagnostics.get(0).column());
        assertEquals(VasCompilerDiagnostic.Severity.ERROR, diagnostics.get(0).severity());
        assertEquals("No matching symbol 'result3'", diagnostics.get(0).message());
        assertEquals(VasCompilerDiagnostic.Severity.WARNING, diagnostics.get(1).severity());
        assertEquals(0, diagnostics.get(2).line());
    }

    @Test
    public void acceptsWindowsPathsContainingDriveColons() {
        List<VasCompilerDiagnostic> diagnostics = VasDiagnosticParser.parse(
            "C:\\Work\\src\\main.vas (4, 7) : ERR : Expected ';'"
        );

        assertEquals(1, diagnostics.size());
        assertEquals("C:\\Work\\src\\main.vas", diagnostics.get(0).filePath());
    }
}
