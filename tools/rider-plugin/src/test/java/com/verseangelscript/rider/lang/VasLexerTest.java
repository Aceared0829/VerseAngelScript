package com.verseangelscript.rider.lang;

import com.intellij.psi.tree.IElementType;
import com.verseangelscript.rider.index.VasIncludeScanner;
import com.verseangelscript.rider.index.VasSymbolScanner;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class VasLexerTest {
    @Test
    public void recognizesCoreVasTokens() {
        String source = "#include \"shared.vas\"\nclass Player { int health = 100; } // ready";
        VasLexer lexer = new VasLexer();
        lexer.start(source);

        List<Token> tokens = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            tokens.add(new Token(
                lexer.getTokenType(),
                source.substring(lexer.getTokenStart(), lexer.getTokenEnd())
            ));
            lexer.advance();
        }

        assertToken(tokens, VasTypes.PREPROCESSOR, "#include \"shared.vas\"");
        assertToken(tokens, VasTypes.KEYWORD, "class");
        assertToken(tokens, VasTypes.IDENTIFIER, "Player");
        assertToken(tokens, VasTypes.NUMBER, "100");
        assertToken(tokens, VasTypes.COMMENT, "// ready");
    }

    @Test
    public void moduleImportsAreOpaqueButNativeFunctionImportsRemainTokens() {
        String source = "import pkg.tool;\n#include <api.vas>\nimport int host(int) from \"other\";\nint value = 42;";
        VasLexer lexer = new VasLexer();
        lexer.start(source);
        List<Token> tokens = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            tokens.add(new Token(lexer.getTokenType(), source.substring(lexer.getTokenStart(), lexer.getTokenEnd())));
            lexer.advance();
        }
        assertToken(tokens, VasTypes.PREPROCESSOR, "import pkg.tool;");
        assertToken(tokens, VasTypes.PREPROCESSOR, "#include <api.vas>");
        assertToken(tokens, VasTypes.KEYWORD, "import");
        assertToken(tokens, VasTypes.IDENTIFIER, "host");
        assertToken(tokens, VasTypes.IDENTIFIER, "value");
    }

    @Test
    public void keepsEscapedQuotesInsideStrings() {
        String source = "string value = \"VAS \\\"script\\\"\";";
        VasLexer lexer = new VasLexer();
        lexer.start(source);

        while (lexer.getTokenType() != VasTypes.STRING) {
            lexer.advance();
        }

        assertEquals("\"VAS \\\"script\\\"\"", source.substring(lexer.getTokenStart(), lexer.getTokenEnd()));
    }

    @Test
    public void heredocsKeepMultilineContentOpaqueAndResumeAfterTheirDelimiter() {
        String literal = "\"\"\"\nvalue\nint forged;\n#include 'hidden.vas'\n// text\n' \" \\\n\"\"\"";
        VasLexer lexer = new VasLexer();
        lexer.start(literal + ";value");
        assertSame(VasTypes.STRING, lexer.getTokenType());
        assertEquals(literal.length(), lexer.getTokenEnd());
        lexer.advance();
        assertSame(VasTypes.OPERATOR, lexer.getTokenType());
        lexer.advance();
        assertSame(VasTypes.IDENTIFIER, lexer.getTokenType());
        assertEquals(literal.length() + 1, lexer.getTokenStart());
    }

    @Test
    public void heredocTerminatorsAreNotEscapedAndEmptyHeredocsAreWhole() {
        for (String literal : List.of("\"\"\"\"\"\"", "\"\"\"text\\\"\"\"")) {
            VasLexer lexer = new VasLexer();
            lexer.start(literal + ";value");
            assertSame(literal, VasTypes.STRING, lexer.getTokenType());
            assertEquals(literal, literal.length(), lexer.getTokenEnd());
            lexer.advance();
            lexer.advance();
            assertSame(literal, VasTypes.IDENTIFIER, lexer.getTokenType());
        }
    }

    @Test
    public void incompleteHeredocsRemainOpaqueThroughEndOfInput() {
        for (String literal : List.of("\"\"\"", "\"\"\"\nvalue", "\"\"\"\nvalue\n\"", "\"\"\"\nvalue\n\"\"")) {
            VasLexer lexer = new VasLexer();
            lexer.start(literal);
            assertSame(literal, VasTypes.STRING, lexer.getTokenType());
            assertEquals(literal, literal.length(), lexer.getTokenEnd());
            lexer.advance();
            assertNull(literal, lexer.getTokenType());
        }
    }

    @Test
    public void ordinaryMultilineStringsRetainEscapesAndIncompleteContent() {
        for (String literal : List.of("\"first\nvalue\\\"still quoted\nlast\"", "'first\r\nvalue\\'last'",
            "\"first\nvalue", "'first\r\nvalue")) {
            VasLexer lexer = new VasLexer();
            lexer.start(literal);
            assertSame(literal, VasTypes.STRING, lexer.getTokenType());
            assertEquals(literal, literal.length(), lexer.getTokenEnd());
            lexer.advance();
            assertNull(literal, lexer.getTokenType());
        }
    }

    @Test
    public void stringContentsCannotBecomeSymbolsIncludesOrRenameCandidates() {
        for (String literal : List.of("\"\"\"\nvalue\nint forged;\n#include 'hidden.vas'\n\"\"\"",
            "\"\"\"\nvalue\nint forged;\n#include 'hidden.vas'",
            "\"first\nvalue\nint forged;\n#include 'hidden.vas'\"")) {
            String source = "string text = " + literal + ";";
            assertEquals(literal, List.of("text"),
                VasSymbolScanner.scan(source).stream().map(symbol -> symbol.name()).toList());
            assertTrue(literal, VasIncludeScanner.scan(source).includes().isEmpty());
            assertFalse(literal, VasRenameSafety.containsRelevantIdentifier(source, "value", "renamed"));
        }
    }

    @Test
    public void commentsAfterOperatorsRemainComments() {
        VasLexer lexer = new VasLexer();
        lexer.start("object./*note*/field");
        lexer.advance();
        assertSame(VasTypes.OPERATOR, lexer.getTokenType());
        lexer.advance();
        assertSame(VasTypes.COMMENT, lexer.getTokenType());
        lexer.advance();
        assertSame(VasTypes.IDENTIFIER, lexer.getTokenType());
    }

    @Test
    public void arithmeticAfterNumbersDoesNotHideIdentifierUsages() {
        for (String source : List.of("1+value", "1-value", "1e-3+value", "2E+4-value", "0xFF+value", "1.5f+value")) {
            VasLexer lexer = new VasLexer();
            lexer.start(source);
            List<Token> tokens = new ArrayList<>();
            while (lexer.getTokenType() != null) {
                tokens.add(new Token(lexer.getTokenType(), source.substring(lexer.getTokenStart(), lexer.getTokenEnd())));
                lexer.advance();
            }
            assertEquals(source, 3, tokens.size());
            assertToken(tokens, VasTypes.IDENTIFIER, "value");
            assertSame(VasTypes.NUMBER, tokens.get(0).type());
            assertSame(VasTypes.OPERATOR, tokens.get(1).type());
        }
    }

    @Test
    public void nativeBasedLiteralsAndDigitSeparatorsStayWhole() {
        for (String literal : List.of("0b101", "0B1'01", "0o123", "0O1'23", "0d123", "0D1'23",
            "0xA'FF", "0XfF", "1'000", "1'000.2'5", "1.2e-1'0", ".5", ".5F")) {
            String source = literal + "+value";
            VasLexer lexer = new VasLexer();
            lexer.start(source);
            assertSame(source, VasTypes.NUMBER, lexer.getTokenType());
            assertEquals(source, literal.length(), lexer.getTokenEnd());
            lexer.advance();
            assertSame(source, VasTypes.OPERATOR, lexer.getTokenType());
            assertEquals(source, literal.length() + 1, lexer.getTokenEnd());
            lexer.advance();
            assertSame(source, VasTypes.IDENTIFIER, lexer.getTokenType());
            assertEquals(source, "value", source.substring(lexer.getTokenStart(), lexer.getTokenEnd()));
        }
    }

    @Test
    public void radixSeparatorsRequireDigitsOnBothSides() {
        for (String source : List.of("1'text'", "0b1'2'", "0o7'8'", "0xF'G'")) {
            VasLexer lexer = new VasLexer();
            lexer.start(source);
            assertSame(source, VasTypes.NUMBER, lexer.getTokenType());
            assertEquals(source, source.indexOf('\''), lexer.getTokenEnd());
            lexer.advance();
            assertSame(source, VasTypes.STRING, lexer.getTokenType());
        }
    }

    @Test
    public void includesSpanLegalWhitespaceWithoutHidingFollowingCode() {
        for (String directive : List.of("#include 'api.vas'", "#include\"api.vas\"", "#include\n\"api.vas\"")) {
            String source = directive + " int value;";
            VasLexer lexer = new VasLexer();
            lexer.start(source);
            assertSame(directive, VasTypes.PREPROCESSOR, lexer.getTokenType());
            assertEquals(directive, directive.length(), lexer.getTokenEnd());
            lexer.advance();
            lexer.advance();
            assertSame(directive, VasTypes.KEYWORD, lexer.getTokenType());
            assertEquals(directive, "int", source.substring(lexer.getTokenStart(), lexer.getTokenEnd()));
        }
    }

    @Test
    public void dollarIsNotAnIdentifierButUnicodeSourceRemainsReadable() {
        VasLexer lexer = new VasLexer();
        lexer.start("$renamed");
        assertSame(com.intellij.psi.TokenType.BAD_CHARACTER, lexer.getTokenType());
        lexer.start("café");
        assertSame(VasTypes.IDENTIFIER, lexer.getTokenType());
        assertEquals(4, lexer.getTokenEnd());
    }

    @Test
    public void nonAsciiNativeIdentifiersNeverExposeTheirAsciiSuffix() {
        for (String name : List.of("😀value", "€value", "\u0301value", "\u2003value", "\u00A0value", "value😀tail", "value\uFEFFtail", "get😀value", "string😀value")) {
            VasLexer lexer = new VasLexer();
            lexer.start(name + ";");
            assertSame(name, VasTypes.IDENTIFIER, lexer.getTokenType());
            assertEquals(name, name.length(), lexer.getTokenEnd());
            lexer.advance();
            assertSame(name, VasTypes.OPERATOR, lexer.getTokenType());
            assertFalse(name, VasRenameSafety.containsRelevantIdentifier(name + ";", "value", "renamed"));
        }
    }

    @Test
    public void onlyNativeWhitespaceSeparatesIdentifiers() {
        VasLexer lexer = new VasLexer();
        lexer.start(" \t\r\n\uFEFFvalue");
        assertSame(com.intellij.psi.TokenType.WHITE_SPACE, lexer.getTokenType());
        assertEquals(4, lexer.getTokenEnd());
        lexer.advance();
        assertSame(com.intellij.psi.TokenType.WHITE_SPACE, lexer.getTokenType());
        assertEquals(5, lexer.getTokenEnd());
        lexer.advance();
        assertSame(VasTypes.IDENTIFIER, lexer.getTokenType());
        assertEquals(10, lexer.getTokenEnd());
        for (String source : List.of("\fvalue", "\u000Bvalue")) {
            lexer.start(source);
            assertSame(com.intellij.psi.TokenType.BAD_CHARACTER, lexer.getTokenType());
        }
    }

    @Test
    public void nativeReservedWordsSplitBeforeNonAsciiIdentifierBytes() {
        for (String suffix : List.of("😀value", "\u2003value", "\uFEFFvalue")) {
            VasLexer lexer = new VasLexer();
            lexer.start("int" + suffix);
            assertSame(VasTypes.KEYWORD, lexer.getTokenType());
            assertEquals(3, lexer.getTokenEnd());
            lexer.advance();
            if (suffix.charAt(0) == '\uFEFF') {
                assertSame(com.intellij.psi.TokenType.WHITE_SPACE, lexer.getTokenType());
                lexer.advance();
            }
            assertSame(VasTypes.IDENTIFIER, lexer.getTokenType());
            assertEquals(3 + suffix.length(), lexer.getTokenEnd());
        }
    }

    private static void assertToken(List<Token> tokens, IElementType expectedType, String expectedText) {
        Token token = tokens.stream()
            .filter(candidate -> candidate.text().equals(expectedText))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing token: " + expectedText));
        assertSame(expectedType, token.type());
    }

    private record Token(IElementType type, String text) {
    }
}
