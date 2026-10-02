package com.verseangelscript.rider.lang;

import com.intellij.psi.tree.IElementType;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

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
