package com.verseangelscript.rider.diagnostics;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Optional real compiler checks; enable with VAS_TEST_COMPILER=/path/to/vasbuild. */
public final class VasDiagnosticCompilerTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void mapsRealCompilerColumnsFromUtf8DocumentSnapshots() throws Exception {
        for (Sample sample : List.of(
            new Sample("void main() { /*漢字*/ missing(); }", 1, 26),
            new Sample("void main() { /*😀*/ missing(); }", 1, 24),
            new Sample("void main() {\n\t/*漢字😀*/\tmissing();\n}", 2, 17),
            new Sample("void main() {\r\n\t/*漢字😀*/\tmissing();\r\n}", 2, 17),
            new Sample("\uFEFFvoid main() { /*漢字😀*/ missing(); }", 1, 33)
        )) {
            VasCompilerDiagnostic diagnostic = compile(sample.source()).stream()
                .filter(candidate -> candidate.message().contains("No matching symbol 'missing'"))
                .findFirst().orElseThrow();
            assertEquals(sample.line(), diagnostic.line());
            assertEquals(sample.column(), diagnostic.column());
            int start = sample.source().indexOf("missing");
            int lineStart = sample.source().lastIndexOf('\n', start) + 1;
            int lineEnd = sample.source().indexOf('\n', start);
            if (lineEnd < 0) {
                lineEnd = sample.source().length();
            } else if (sample.source().charAt(lineEnd - 1) == '\r') {
                lineEnd--;
            }
            assertEquals(new VasDiagnosticRange.Range(start, start + "missing".length()),
                VasDiagnosticRange.forLine(sample.source(), lineStart, lineEnd, diagnostic.column()));
        }
    }

    @Test
    public void keepsRealEofDiagnosticWithinTrailingEmptyLine() throws Exception {
        String source = "void main() {\n";
        VasCompilerDiagnostic diagnostic = compile(source).stream()
            .filter(candidate -> candidate.message().equals("Unexpected end of file"))
            .findFirst().orElseThrow();
        assertEquals(2, diagnostic.line());
        assertEquals(1, diagnostic.column());
        assertEquals(new VasDiagnosticRange.Range(source.length(), source.length()),
            VasDiagnosticRange.forLine(source, source.length(), source.length(), diagnostic.column()));
    }

    private List<VasCompilerDiagnostic> compile(String sourceText) throws Exception {
        String compiler = System.getenv("VAS_TEST_COMPILER");
        assumeTrue("Set VAS_TEST_COMPILER to run the real compiler checks", compiler != null && !compiler.isBlank());
        Path root = temporary.newFolder().toPath();
        Path source = root.resolve("source.vas");
        Path config = root.resolve("config.txt");
        // Match the annotator's document-snapshot write, with no implicit BOM.
        Files.writeString(source, sourceText, StandardCharsets.UTF_8);
        Files.writeString(config, "// Empty host configuration\n", StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(
            Path.of(compiler).toAbsolutePath().toString(),
            config.toString(), source.toString(), root.resolve("output.vasbc").toString()
        ).redirectErrorStream(true).start();
        try {
            assertTrue("vasbuild timed out", process.waitFor(20, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertNotEquals(output, 0, process.exitValue());
            return VasDiagnosticParser.parse(output);
        } finally {
            process.destroyForcibly();
        }
    }

    private record Sample(String source, int line, int column) {
    }
}
