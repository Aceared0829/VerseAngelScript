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
