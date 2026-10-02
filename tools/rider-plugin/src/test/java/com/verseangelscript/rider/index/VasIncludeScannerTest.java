package com.verseangelscript.rider.index;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VasIncludeScannerTest {
    @Test
    public void acceptsBothQuotesWithOptionalWhitespaceAcrossLines() {
        for (String directive : List.of("#include \"api.vas\"", "#include 'api.vas'",
            "#include\"api.vas\"", "#include'api.vas'", "#include\n\"api.vas\"",
            "#include \t\r\n 'api.vas'")) {
            VasIncludeScanner.Result result = VasIncludeScanner.scan(directive);
            assertTrue(directive, result.complete());
            assertEquals(List.of("api.vas"), paths(result));
            VasIncludeScanner.Include include = result.includes().getFirst();
            assertEquals("api.vas", directive.substring(include.pathStart(), include.pathEnd()));
            assertEquals(directive.length(), include.end());
        }
    }

    @Test
    public void retainsOffsetsAndAllDirectivesOnTheSameLine() {
        String source = "\uFEFF/* leading */ #include'a.vas' #include\"b.vas\"\nint value;";
        VasIncludeScanner.Result result = VasIncludeScanner.scan(source);
        assertTrue(result.complete());
        assertEquals(List.of("a.vas", "b.vas"), paths(result));
        assertEquals(source.indexOf('#'), result.includes().getFirst().offset());
    }

    @Test
    public void excludesCommentsAndBothOrdinaryStringForms() {
        String source = "// #include 'line.vas'\n/* #include\"block.vas\" */\n"
            + "string text = \"#include 'string.vas'\";\n"
            + "string other = '#include\"single.vas\"';\n#include 'real.vas'";
        VasIncludeScanner.Result result = VasIncludeScanner.scan(source);
        assertTrue(result.complete());
        assertEquals(List.of("real.vas"), paths(result));
    }

    @Test
    public void excludesEscapedQuotesAndMultilineHeredocs() {
        String source = "string escaped = \"\\\"#include 'escaped.vas'\";\n"
            + "string text = \"\"\"\n#include 'heredoc.vas'\n\"\"\";\n#include\"real.vas\"";
        VasIncludeScanner.Result result = VasIncludeScanner.scan(source);
        assertTrue(result.complete());
        assertEquals(List.of("real.vas"), paths(result));
    }

    @Test
    public void numericSeparatorsDoNotHideFollowingIncludes() {
        for (String value : List.of("1'000", "0b1'010", "0o7'123", "0d1'000", "0xF'F",
            "1.2'3e-4'5", ".1'2")) {
            VasIncludeScanner.Result result = VasIncludeScanner.scan("double value=" + value + ";\n#include 'api.vas'");
            assertTrue(value, result.complete());
            assertEquals(value, List.of("api.vas"), paths(result));
        }
    }

    @Test
    public void directivesMustHaveExactNamesAndQuotedNonemptyPaths() {
        for (String source : List.of("#include", "#include<api.vas>", "#include api.vas",
            "#include ''", "#include \"\"", "#include_more 'api.vas'", "# include 'api.vas'")) {
            VasIncludeScanner.Result result = VasIncludeScanner.scan(source);
            assertFalse(source, result.complete());
            assertTrue(source, result.includes().isEmpty());
        }
    }

    @Test
    public void commentsCannotReplaceWhitespaceInsideDirectives() {
        for (String source : List.of("#include/* comment */'api.vas'", "#include // comment\n'api.vas'",
            "#/* comment */include 'api.vas'")) {
            assertFalse(source, VasIncludeScanner.scan(source).complete());
            assertTrue(source, VasIncludeScanner.scan(source).includes().isEmpty());
        }
    }

    @Test
    public void conditionalAndUnknownPreprocessingMakesDependenciesIncomplete() {
        for (String directive : List.of("#if ENABLED", "#ifdef ENABLED", "#ifndef ENABLED", "#else",
            "#elif ENABLED", "#endif", "#define ENABLED", "#pragma custom")) {
            VasIncludeScanner.Result result = VasIncludeScanner.scan(directive + "\n#include 'api.vas'");
            assertFalse(directive, result.complete());
            assertEquals(List.of("api.vas"), paths(result));
        }
    }

    @Test
    public void malformedLiteralsAndPathsAreExplicitlyIncomplete() {
        for (String source : List.of("#include 'unfinished", "#include 'line\nbreak.vas'",
            "#include \"\"\"api.vas\"\"\"", "/* unfinished", "string value = 'unfinished")) {
            assertFalse(source, VasIncludeScanner.scan(source).complete());
        }
    }

    @Test
    public void stringsAndCommentsWithConditionalTextDoNotBlockCompleteSources() {
        assertTrue(VasIncludeScanner.scan("// #if FLAG\nstring value='#pragma custom'; /* #endif */").complete());
    }

    @Test
    public void shebangIsIgnoredAsInScriptbuilder() {
        VasIncludeScanner.Result result = VasIncludeScanner.scan("#!/usr/bin/env vas #include 'ignored.vas'\n#include 'api.vas'");
        assertTrue(result.complete());
        assertEquals(List.of("api.vas"), paths(result));
    }

    @Test
    public void preprocessingInsideMetadataOrExpressionsIsNotProven() {
        assertFalse(VasIncludeScanner.scan("[#include 'metadata.vas'] void run() {} ").complete());
        assertFalse(VasIncludeScanner.scan("void run(int x = #include 'expression.vas') {} ").complete());
    }

    @Test
    public void directivesInsideFunctionBodiesAndVariableInitializersAreIncomplete() {
        for (String source : List.of("void run() { #include 'api.vas' }",
            "void run() { if (true) { #include 'api.vas' } }",
            "int value = #include 'api.vas'")) {
            assertFalse(source, VasIncludeScanner.scan(source).complete());
        }
    }

    @Test
    public void directivesAtClassAndNamespaceBoundariesRemainSupported() {
        for (String source : List.of("namespace N { #include 'api.vas' }",
            "class C { #include 'api.vas' void run() {} #include 'after.vas' }",
            "shared class C { #include 'api.vas' }", "[metadata] void run() {} #include 'api.vas'")) {
            assertTrue(source, VasIncludeScanner.scan(source).complete());
        }
    }

    @Test
    public void nativeUnicodeIdentifierAndWhitespaceBoundariesStayExact() {
        assertTrue(VasIncludeScanner.scan("\uFEFF#include 'api.vas'").complete());
        for (String source : List.of("#include\u2003'api.vas'", "#include😀'api.vas'", "#include\uFEFF'api.vas'")) {
            assertFalse(source, VasIncludeScanner.scan(source).complete());
            assertTrue(source, VasIncludeScanner.scan(source).includes().isEmpty());
        }
        assertFalse(VasIncludeScanner.scan("#include \uFEFF'api.vas'").complete());
        assertTrue(VasIncludeScanner.scan("int 😀value;\n#include 'api.vas'").complete());
    }

    private static List<String> paths(VasIncludeScanner.Result result) {
        return result.includes().stream().map(VasIncludeScanner.Include::path).toList();
    }
}
