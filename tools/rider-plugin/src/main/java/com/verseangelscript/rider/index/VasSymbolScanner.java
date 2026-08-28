package com.verseangelscript.rider.index;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.verseangelscript.rider.lang.VasLexer;
import com.verseangelscript.rider.lang.VasTypes;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class VasSymbolScanner {
    private static final Set<String> BUILTIN_TYPES = Set.of(
        "auto", "bool", "double", "float", "int", "int8", "int16", "int32", "int64",
        "string", "uint", "uint8", "uint16", "uint32", "uint64", "void"
    );
    private static final Set<String> TYPE_DECLARATIONS = Set.of(
        "class", "interface", "enum", "namespace", "typedef"
    );
    private static final Set<String> NON_DECLARATION_CALL_PREFIXES = Set.of(
        "if", "for", "foreach", "while", "switch", "return", "case", "delete", "cast"
    );

    private VasSymbolScanner() {
    }

    public static @NotNull List<VasSymbol> scan(@NotNull CharSequence source) {
        List<Token> tokens = tokenize(source);
        List<VasSymbol> symbols = new ArrayList<>();
        Set<Integer> declarationOffsets = new HashSet<>();
        int braceDepth = 0;

        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.type() == VasTypes.RBRACE) {
                braceDepth = Math.max(0, braceDepth - 1);
            }

            if (token.type() == VasTypes.KEYWORD && TYPE_DECLARATIONS.contains(token.text())) {
                int nameIndex = nextIdentifier(tokens, index + 1);
                if (nameIndex >= 0) {
                    Token name = tokens.get(nameIndex);
                    VasSymbolKind kind = switch (token.text()) {
                        case "class" -> VasSymbolKind.CLASS;
                        case "interface" -> VasSymbolKind.INTERFACE;
                        case "enum" -> VasSymbolKind.ENUM;
                        case "namespace" -> VasSymbolKind.NAMESPACE;
                        default -> VasSymbolKind.TYPE_ALIAS;
                    };
                    add(symbols, declarationOffsets, name, kind, braceDepth);
                }
            } else if (token.type() == VasTypes.IDENTIFIER) {
                Token next = tokenAt(tokens, index + 1);
                if (next != null && next.type() == VasTypes.LPAREN
                    && looksLikeFunctionDeclaration(tokens, index)) {
                    add(symbols, declarationOffsets, token, VasSymbolKind.FUNCTION, braceDepth);
                } else if (looksLikeVariableDeclaration(tokens, index)) {
                    add(symbols, declarationOffsets, token, VasSymbolKind.VARIABLE, braceDepth);
                }
            }

            if (token.type() == VasTypes.LBRACE) {
                braceDepth++;
            }
        }
        return List.copyOf(symbols);
    }

    private static boolean looksLikeFunctionDeclaration(List<Token> tokens, int nameIndex) {
        Token previous = tokenAt(tokens, nameIndex - 1);
        if (previous == null || NON_DECLARATION_CALL_PREFIXES.contains(previous.text())) {
            return false;
        }
        if (previous.type() == VasTypes.OPERATOR
            && (previous.text().contains(".") || previous.text().contains("="))) {
            return false;
        }

        int closeParen = matchingRightParen(tokens, nameIndex + 1);
        if (closeParen < 0) {
            return false;
        }
        int tailIndex = closeParen + 1;
        while (tailIndex < tokens.size()) {
            String text = tokens.get(tailIndex).text();
            if (!Set.of("const", "property", "override", "final").contains(text)) {
                break;
            }
            tailIndex++;
        }
        Token tail = tokenAt(tokens, tailIndex);
        if (tail == null || !(tail.type() == VasTypes.LBRACE || ";".equals(tail.text()))) {
            return false;
        }

        return previous.type() == VasTypes.IDENTIFIER
            || previous.type() == VasTypes.KEYWORD
            || (previous.type() == VasTypes.OPERATOR
                && (previous.text().contains("@") || previous.text().contains("&")));
    }

    private static boolean looksLikeVariableDeclaration(List<Token> tokens, int nameIndex) {
        Token previous = tokenAt(tokens, nameIndex - 1);
        Token next = tokenAt(tokens, nameIndex + 1);
        if (previous == null || next == null || next.type() == VasTypes.LPAREN) {
            return false;
        }
        if (!("=".equals(next.text()) || ";".equals(next.text()) || ",".equals(next.text())
            || next.type() == VasTypes.RBRACKET || next.type() == VasTypes.RPAREN
            || next.type() == VasTypes.OPERATOR)) {
            return false;
        }

        if (previous.type() == VasTypes.KEYWORD) {
            return BUILTIN_TYPES.contains(previous.text());
        }
        if (previous.type() == VasTypes.IDENTIFIER) {
            return true;
        }
        if (previous.type() == VasTypes.OPERATOR
            && (previous.text().contains("@") || previous.text().contains("&"))) {
            Token type = tokenAt(tokens, nameIndex - 2);
            return type != null
                && (type.type() == VasTypes.IDENTIFIER
                    || (type.type() == VasTypes.KEYWORD && BUILTIN_TYPES.contains(type.text())));
        }
        return false;
    }

    private static int matchingRightParen(List<Token> tokens, int leftParenIndex) {
        int depth = 0;
        for (int index = leftParenIndex; index < tokens.size(); index++) {
            IElementType type = tokens.get(index).type();
            if (type == VasTypes.LPAREN) {
                depth++;
            } else if (type == VasTypes.RPAREN && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static int nextIdentifier(List<Token> tokens, int startIndex) {
        for (int index = startIndex; index < tokens.size(); index++) {
            IElementType type = tokens.get(index).type();
            if (type == VasTypes.IDENTIFIER) {
                return index;
            }
            if (type == VasTypes.LBRACE || ";".equals(tokens.get(index).text())) {
                return -1;
            }
        }
        return -1;
    }

    private static void add(
        List<VasSymbol> symbols,
        Set<Integer> offsets,
        Token token,
        VasSymbolKind kind,
        int braceDepth
    ) {
        if (offsets.add(token.start())) {
            symbols.add(new VasSymbol(token.text(), kind, token.start(), braceDepth));
        }
    }

    private static Token tokenAt(List<Token> tokens, int index) {
        return index >= 0 && index < tokens.size() ? tokens.get(index) : null;
    }

    private static List<Token> tokenize(CharSequence source) {
        VasLexer lexer = new VasLexer();
        lexer.start(source);
        List<Token> tokens = new ArrayList<>();
        while (lexer.getTokenType() != null) {
            IElementType type = lexer.getTokenType();
            if (type != TokenType.WHITE_SPACE && type != VasTypes.COMMENT
                && type != VasTypes.PREPROCESSOR) {
                tokens.add(new Token(
                    type,
                    source.subSequence(lexer.getTokenStart(), lexer.getTokenEnd()).toString(),
                    lexer.getTokenStart()
                ));
            }
            lexer.advance();
        }
        return tokens;
    }

    private record Token(IElementType type, String text, int start) {
    }
}
