package com.verseangelscript.rider.lang;

import com.intellij.lexer.LexerBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.verseangelscript.rider.index.VasIncludeScanner;

import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class VasLexer extends LexerBase {
    private CharSequence buffer = "";
    private int endOffset;
    private int tokenStart;
    private int tokenEnd;
    private IElementType tokenType;
    private final Map<Integer, Integer> includeEnds = new HashMap<>();

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
        includeEnds.clear();
        for (VasIncludeScanner.Include include : VasIncludeScanner.scan(buffer.subSequence(0, endOffset)).includes()) {
            includeEnds.put(include.offset(), include.end());
        }
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

        if (Character.isWhitespace(current) || current == '\uFEFF') {
            tokenEnd = tokenStart + 1;
            while (tokenEnd < endOffset && (Character.isWhitespace(buffer.charAt(tokenEnd)) || buffer.charAt(tokenEnd) == '\uFEFF')) {
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
            Integer includeEnd = includeEnds.get(tokenStart);
            if (includeEnd != null) {
                // A legal include may span lines; code after its quote is still
                // code, even when it occurs on the same physical line.
                tokenEnd = includeEnd;
            } else {
                tokenEnd = tokenStart + 1;
                while (tokenEnd < endOffset && !isLineBreak(buffer.charAt(tokenEnd))) {
                    tokenEnd++;
                }
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

        if (isDigitInRadix(current, 10) || current == '.' && tokenStart + 1 < endOffset
            && isDigitInRadix(buffer.charAt(tokenStart + 1), 10)) {
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
        if (quote == '"' && isHeredocDelimiter(tokenStart)) {
            // Native VAS heredocs have no escape processing. Their whole body,
            // including newlines and apparent code, remains one opaque token.
            tokenEnd = tokenStart + 3;
            while (tokenEnd < endOffset && !isHeredocDelimiter(tokenEnd)) {
                tokenEnd++;
            }
            if (tokenEnd < endOffset) {
                tokenEnd += 3;
            }
            tokenType = VasTypes.STRING;
            return;
        }
        tokenEnd = tokenStart + 1;
        boolean escaped = false;
        while (tokenEnd < endOffset) {
            char value = buffer.charAt(tokenEnd++);
            if (escaped) {
                escaped = false;
            } else if (value == '\\') {
                escaped = true;
            } else if (value == quote) {
                break;
            }
        }
        tokenType = VasTypes.STRING;
    }

    private boolean isHeredocDelimiter(int offset) {
        return offset + 2 < endOffset && buffer.charAt(offset) == '"'
            && buffer.charAt(offset + 1) == '"' && buffer.charAt(offset + 2) == '"';
    }

    private void locateNumber() {
        tokenEnd = tokenStart;
        int radix = 0;
        if (buffer.charAt(tokenStart) == '0' && tokenStart + 1 < endOffset) {
            radix = switch (buffer.charAt(tokenStart + 1)) {
                case 'b', 'B' -> 2;
                case 'o', 'O' -> 8;
                case 'd', 'D' -> 10;
                case 'x', 'X' -> 16;
                default -> 0;
            };
        }
        if (radix != 0) {
            tokenEnd += 2;
            consumeDigits(radix);
        } else {
            consumeDigits(10);
            boolean floatingPoint = false;
            if (tokenEnd < endOffset && buffer.charAt(tokenEnd) == '.') {
                floatingPoint = true;
                tokenEnd++;
                consumeDigits(10);
            }
            if (tokenEnd < endOffset && (buffer.charAt(tokenEnd) == 'e' || buffer.charAt(tokenEnd) == 'E')) {
                floatingPoint = true;
                tokenEnd++;
                if (tokenEnd < endOffset && (buffer.charAt(tokenEnd) == '+' || buffer.charAt(tokenEnd) == '-')) {
                    tokenEnd++;
                }
                consumeDigits(10);
            }
            if (floatingPoint && tokenEnd < endOffset
                && (buffer.charAt(tokenEnd) == 'f' || buffer.charAt(tokenEnd) == 'F')) {
                tokenEnd++;
            }
        }
        tokenType = VasTypes.NUMBER;
    }

    private void consumeDigits(int radix) {
        // Match the compiler's based/decimal literal grammar. An apostrophe is a
        // separator only between two digits in the same radix, never a string start.
        while (tokenEnd < endOffset) {
            char value = buffer.charAt(tokenEnd);
            if (!isDigitInRadix(value, radix) && !(value == '\'' && tokenEnd > tokenStart
                && tokenEnd + 1 < endOffset && isDigitInRadix(buffer.charAt(tokenEnd - 1), radix)
                && isDigitInRadix(buffer.charAt(tokenEnd + 1), radix))) {
                break;
            }
            tokenEnd++;
        }
    }

    private static boolean isDigitInRadix(char value, int radix) {
        int digit = value >= '0' && value <= '9' ? value - '0'
            : value >= 'a' && value <= 'f' ? value - 'a' + 10
            : value >= 'A' && value <= 'F' ? value - 'A' + 10 : -1;
        return digit >= 0 && digit < radix;
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
