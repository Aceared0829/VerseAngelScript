package com.verseangelscript.rider.lang;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class VasLexer extends LexerBase {
    private CharSequence buffer = "";
    private int endOffset;
    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;

    @Override
    public void start(
        @NotNull CharSequence buffer,
        int startOffset,
        int endOffset,
        int initialState
    ) {
        this.buffer = buffer;
        this.endOffset = endOffset;
        this.tokenStart = startOffset;
        locateToken();
    }

    @Override
    public int getState() {
        return 0;
    }

    @Override
    public @Nullable IElementType getTokenType() {
        return tokenType;
    }

    @Override
    public int getTokenStart() {
        return tokenStart;
    }

    @Override
    public int getTokenEnd() {
        return tokenEnd;
    }

    @Override
    public void advance() {
        tokenStart = tokenEnd;
        locateToken();
    }

    @Override
    public @NotNull CharSequence getBufferSequence() {
        return buffer;
    }

    @Override
    public int getBufferEnd() {
        return endOffset;
    }

    private void locateToken() {
        if (tokenStart >= endOffset) {
            tokenEnd = tokenStart;
            tokenType = null;
            return;
        }

        char current = buffer.charAt(tokenStart);

        if (Character.isWhitespace(current)) {
            tokenEnd = tokenStart + 1;
            while (tokenEnd < endOffset && Character.isWhitespace(buffer.charAt(tokenEnd))) {
                tokenEnd++;
            }
            tokenType = TokenType.WHITE_SPACE;
            return;
        }

        if (current == '/' && tokenStart + 1 < endOffset) {
            char next = buffer.charAt(tokenStart + 1);
            if (next == '/') {
                tokenEnd = tokenStart + 2;
                while (tokenEnd < endOffset && !isLineBreak(buffer.charAt(tokenEnd))) {
                    tokenEnd++;
                }
                tokenType = VasTypes.COMMENT;
                return;
            }
            if (next == '*') {
                tokenEnd = tokenStart + 2;
                while (tokenEnd + 1 < endOffset
                    && !(buffer.charAt(tokenEnd) == '*' && buffer.charAt(tokenEnd + 1) == '/')) {
                    tokenEnd++;
                }
                tokenEnd = Math.min(endOffset, tokenEnd + 2);
                tokenType = VasTypes.COMMENT;
                return;
            }
        }

        if (current == '"' || current == '\'') {
            locateString(current);
            return;
        }

        if (current == '#') {
            tokenEnd = tokenStart + 1;
            while (tokenEnd < endOffset && !isLineBreak(buffer.charAt(tokenEnd))) {
                tokenEnd++;
            }
            tokenType = VasTypes.PREPROCESSOR;
            return;
        }

        if (isIdentifierStart(current)) {
            tokenEnd = tokenStart + 1;
            while (tokenEnd < endOffset) {
                char value = buffer.charAt(tokenEnd);
                if (!isIdentifierPart(value)) {
                    break;
                }
                tokenEnd++;
            }
            String identifier = buffer.subSequence(tokenStart, tokenEnd).toString();
            tokenType = VasKeywords.SET.contains(identifier) ? VasTypes.KEYWORD : VasTypes.IDENTIFIER;
            return;
        }

        if (Character.isDigit(current)) {
            locateNumber();
            return;
        }

        tokenEnd = tokenStart + 1;
        tokenType = switch (current) {
            case '{' -> VasTypes.LBRACE;
            case '}' -> VasTypes.RBRACE;
            case '(' -> VasTypes.LPAREN;
            case ')' -> VasTypes.RPAREN;
            case '[' -> VasTypes.LBRACKET;
            case ']' -> VasTypes.RBRACKET;
            default -> isOperator(current) ? VasTypes.OPERATOR : TokenType.BAD_CHARACTER;
        };

        if (tokenType == VasTypes.OPERATOR) {
            while (tokenEnd < endOffset && isOperator(buffer.charAt(tokenEnd))) {
                if (buffer.charAt(tokenEnd) == '/' && tokenEnd + 1 < endOffset
                    && (buffer.charAt(tokenEnd + 1) == '/' || buffer.charAt(tokenEnd + 1) == '*')) {
                    break;
                }
                tokenEnd++;
            }
        }
    }

    private void locateString(char quote) {
        tokenEnd = tokenStart + 1;
        boolean escaped = false;
        while (tokenEnd < endOffset) {
            char value = buffer.charAt(tokenEnd++);
            if (escaped) {
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == quote || isLineBreak(value)) {
                break;
            }
        }
        tokenType = VasTypes.STRING;
    }

    private void locateNumber() {
        tokenEnd = tokenStart;
        if (buffer.charAt(tokenStart) == '0' && tokenStart + 1 < endOffset
            && (buffer.charAt(tokenStart + 1) == 'x' || buffer.charAt(tokenStart + 1) == 'X')) {
            tokenEnd += 2;
            while (tokenEnd < endOffset && (Character.digit(buffer.charAt(tokenEnd), 16) >= 0
                || buffer.charAt(tokenEnd) == '_')) {
                tokenEnd++;
            }
        } else {
            consumeDecimalDigits();
            if (tokenEnd < endOffset && buffer.charAt(tokenEnd) == '.') {
                tokenEnd++;
                consumeDecimalDigits();
            }
            if (tokenEnd < endOffset && (buffer.charAt(tokenEnd) == 'e' || buffer.charAt(tokenEnd) == 'E')) {
                int exponent = tokenEnd + 1;
                if (exponent < endOffset && (buffer.charAt(exponent) == '+' || buffer.charAt(exponent) == '-')) {
                    exponent++;
                }
                if (exponent < endOffset && Character.isDigit(buffer.charAt(exponent))) {
                    tokenEnd = exponent;
                    consumeDecimalDigits();
                }
            }
            if (tokenEnd < endOffset && (buffer.charAt(tokenEnd) == 'f' || buffer.charAt(tokenEnd) == 'F')
                && (tokenEnd + 1 == endOffset || !Character.isJavaIdentifierPart(buffer.charAt(tokenEnd + 1)))) {
                tokenEnd++;
            }
        }
        tokenType = VasTypes.NUMBER;
    }

    private void consumeDecimalDigits() {
        while (tokenEnd < endOffset && (Character.isDigit(buffer.charAt(tokenEnd)) || buffer.charAt(tokenEnd) == '_')) {
            tokenEnd++;
        }
    }

    private static boolean isIdentifierStart(char value) {
        return value == '_' || value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
            || value >= 128 && Character.isJavaIdentifierStart(value);
    }

    private static boolean isIdentifierPart(char value) {
        return isIdentifierStart(value) || value >= '0' && value <= '9'
            || value >= 128 && Character.isJavaIdentifierPart(value);
    }

    private static boolean isLineBreak(char value) {
        return value == '\n' || value == '\r';
    }

    private static boolean isOperator(char value) {
        return "+-*/%=!<>&|^~?:;,.@".indexOf(value) >= 0;
    }
}
