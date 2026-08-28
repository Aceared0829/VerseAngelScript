package com.verseangelscript.rider.lang;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

public final class VasSyntaxHighlighter extends SyntaxHighlighterBase {
    public static final TextAttributesKey KEYWORD = TextAttributesKey.createTextAttributesKey(
        "VAS_KEYWORD",
        DefaultLanguageHighlighterColors.KEYWORD
    );
    public static final TextAttributesKey IDENTIFIER = TextAttributesKey.createTextAttributesKey(
        "VAS_IDENTIFIER",
        DefaultLanguageHighlighterColors.IDENTIFIER
    );
    public static final TextAttributesKey NUMBER = TextAttributesKey.createTextAttributesKey(
        "VAS_NUMBER",
        DefaultLanguageHighlighterColors.NUMBER
    );
    public static final TextAttributesKey STRING = TextAttributesKey.createTextAttributesKey(
        "VAS_STRING",
        DefaultLanguageHighlighterColors.STRING
    );
    public static final TextAttributesKey COMMENT = TextAttributesKey.createTextAttributesKey(
        "VAS_COMMENT",
        DefaultLanguageHighlighterColors.LINE_COMMENT
    );
    public static final TextAttributesKey PREPROCESSOR = TextAttributesKey.createTextAttributesKey(
        "VAS_PREPROCESSOR",
        DefaultLanguageHighlighterColors.METADATA
    );
    public static final TextAttributesKey OPERATOR = TextAttributesKey.createTextAttributesKey(
        "VAS_OPERATOR",
        DefaultLanguageHighlighterColors.OPERATION_SIGN
    );
    public static final TextAttributesKey BRACES = TextAttributesKey.createTextAttributesKey(
        "VAS_BRACES",
        DefaultLanguageHighlighterColors.BRACES
    );
    public static final TextAttributesKey PARENTHESES = TextAttributesKey.createTextAttributesKey(
        "VAS_PARENTHESES",
        DefaultLanguageHighlighterColors.PARENTHESES
    );
    public static final TextAttributesKey BRACKETS = TextAttributesKey.createTextAttributesKey(
        "VAS_BRACKETS",
        DefaultLanguageHighlighterColors.BRACKETS
    );
    public static final TextAttributesKey BAD_CHARACTER = TextAttributesKey.createTextAttributesKey(
        "VAS_BAD_CHARACTER",
        HighlighterColors.BAD_CHARACTER
    );

    private static final TextAttributesKey[] EMPTY = TextAttributesKey.EMPTY_ARRAY;

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new VasLexer();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        if (tokenType == VasTypes.KEYWORD) {
            return pack(KEYWORD);
        }
        if (tokenType == VasTypes.IDENTIFIER) {
            return pack(IDENTIFIER);
        }
        if (tokenType == VasTypes.NUMBER) {
            return pack(NUMBER);
        }
        if (tokenType == VasTypes.STRING) {
            return pack(STRING);
        }
        if (tokenType == VasTypes.COMMENT) {
            return pack(COMMENT);
        }
        if (tokenType == VasTypes.PREPROCESSOR) {
            return pack(PREPROCESSOR);
        }
        if (tokenType == VasTypes.OPERATOR) {
            return pack(OPERATOR);
        }
        if (tokenType == VasTypes.LBRACE || tokenType == VasTypes.RBRACE) {
            return pack(BRACES);
        }
        if (tokenType == VasTypes.LPAREN || tokenType == VasTypes.RPAREN) {
            return pack(PARENTHESES);
        }
        if (tokenType == VasTypes.LBRACKET || tokenType == VasTypes.RBRACKET) {
            return pack(BRACKETS);
        }
        if (tokenType == TokenType.BAD_CHARACTER) {
            return pack(BAD_CHARACTER);
        }
        return EMPTY;
    }
}
