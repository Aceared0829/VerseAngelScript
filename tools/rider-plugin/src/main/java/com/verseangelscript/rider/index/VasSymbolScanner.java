package com.verseangelscript.rider.index;

import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.verseangelscript.rider.lang.VasLexer;
import com.verseangelscript.rider.lang.VasTypes;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        Map<Integer, Integer> matchingBraces = matchingBraces(tokens);
        Set<Integer> functionBodies = functionBodies(tokens);
        Map<Integer, String> containerScopes = containerScopes(tokens);
        Deque<Integer> braceStack = new ArrayDeque<>();

        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.type() == VasTypes.RBRACE) {
                if (!braceStack.isEmpty()) {
                    braceStack.removeLast();
                }
            }

            int braceDepth = braceStack.size();

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
                    add(
                        symbols,
                        declarationOffsets,
                        name,
                        kind,
                        braceDepth,
                        -1,
                        -1,
                        true,
                        true,
                        containerName(braceStack, containerScopes),
                        token.text(),
                        -1,
                        baseTypes(tokens, nameIndex)
                    );
                }
            } else if (token.type() == VasTypes.IDENTIFIER) {
                Token next = tokenAt(tokens, index + 1);
                if (next != null && next.type() == VasTypes.LPAREN
                    && looksLikeFunctionDeclaration(tokens, index)) {
                    int bodyIndex = functionBodyIndex(tokens, index);
                    add(
                        symbols,
                        declarationOffsets,
                        token,
                        VasSymbolKind.FUNCTION,
                        braceDepth,
                        -1,
                        -1,
                        true,
                        bodyIndex >= 0,
                        functionContainer(tokens, index, braceStack, containerScopes),
                        declaredType(tokens, index),
                        parameterCount(tokens, index + 1),
                        List.of()
                    );
                } else if (looksLikeVariableDeclaration(tokens, index)) {
                    Scope parameterScope = parameterScope(tokens, index, matchingBraces);
                    Scope enclosingScope = parameterScope != null
                        ? parameterScope
                        : enclosingScope(tokens, braceStack, matchingBraces);
                    boolean insideFunction = braceStack.stream().anyMatch(functionBodies::contains);
                    boolean projectVisible = parameterScope == null && !insideFunction;
                    add(
                        symbols,
                        declarationOffsets,
                        token,
                        VasSymbolKind.VARIABLE,
                        braceDepth,
                        enclosingScope == null ? -1 : enclosingScope.start(),
                        enclosingScope == null ? -1 : enclosingScope.end(),
                        projectVisible,
                        true,
                        containerName(braceStack, containerScopes),
                        declaredType(tokens, index),
                        -1,
                        List.of()
                    );
                }
            }

            if (token.type() == VasTypes.LBRACE) {
                braceStack.addLast(index);
            }
        }
        return List.copyOf(symbols);
    }

    public static @NotNull VasUsageContext usageContext(
        @NotNull CharSequence source,
        int identifierOffset
    ) {
        List<Token> tokens = tokenize(source);
        int identifierIndex = -1;
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).start() == identifierOffset) {
                identifierIndex = index;
                break;
            }
        }
        if (identifierIndex < 0) {
            return VasUsageContext.PLAIN;
        }

        int arguments = -1;
        Token next = tokenAt(tokens, identifierIndex + 1);
        if (next != null && next.type() == VasTypes.LPAREN) {
            arguments = parameterCount(tokens, identifierIndex + 1);
        }

        String qualifier = "";
        Token separator = tokenAt(tokens, identifierIndex - 1);
        Token owner = tokenAt(tokens, identifierIndex - 2);
        if (separator != null && owner != null
            && (".".equals(separator.text()) || "::".equals(separator.text()))) {
            qualifier = owner.text();
        }
        return new VasUsageContext(arguments, qualifier);
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
                && (previous.text().contains("@") || previous.text().contains("&")
                    || "::".equals(previous.text())));
    }

    private static int functionBodyIndex(List<Token> tokens, int nameIndex) {
        int closeParen = matchingRightParen(tokens, nameIndex + 1);
        if (closeParen < 0) {
            return -1;
        }
        int tailIndex = closeParen + 1;
        while (tailIndex < tokens.size()
            && Set.of("const", "property", "override", "final").contains(tokens.get(tailIndex).text())) {
            tailIndex++;
        }
        Token tail = tokenAt(tokens, tailIndex);
        return tail != null && tail.type() == VasTypes.LBRACE ? tailIndex : -1;
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

    private static Map<Integer, Integer> matchingBraces(List<Token> tokens) {
        Map<Integer, Integer> pairs = new HashMap<>();
        Deque<Integer> stack = new ArrayDeque<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).type() == VasTypes.LBRACE) {
                stack.addLast(index);
            } else if (tokens.get(index).type() == VasTypes.RBRACE && !stack.isEmpty()) {
                int left = stack.removeLast();
                pairs.put(left, index);
            }
        }
        return pairs;
    }

    private static Set<Integer> functionBodies(List<Token> tokens) {
        Set<Integer> bodies = new HashSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).type() == VasTypes.IDENTIFIER
                && tokenAt(tokens, index + 1) != null
                && tokenAt(tokens, index + 1).type() == VasTypes.LPAREN
                && looksLikeFunctionDeclaration(tokens, index)) {
                int bodyIndex = functionBodyIndex(tokens, index);
                if (bodyIndex >= 0) {
                    bodies.add(bodyIndex);
                }
            }
        }
        return bodies;
    }

    private static Map<Integer, String> containerScopes(List<Token> tokens) {
        Map<Integer, String> scopes = new HashMap<>();
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.type() != VasTypes.KEYWORD || !TYPE_DECLARATIONS.contains(token.text())) {
                continue;
            }
            int nameIndex = nextIdentifier(tokens, index + 1);
            if (nameIndex < 0) {
                continue;
            }
            for (int cursor = nameIndex + 1; cursor < tokens.size(); cursor++) {
                Token candidate = tokens.get(cursor);
                if (candidate.type() == VasTypes.LBRACE) {
                    scopes.put(cursor, tokens.get(nameIndex).text());
                    break;
                }
                if (";".equals(candidate.text())) {
                    break;
                }
            }
        }
        return scopes;
    }

    private static String containerName(
        Deque<Integer> braceStack,
        Map<Integer, String> containerScopes
    ) {
        return braceStack.stream()
            .map(containerScopes::get)
            .filter(name -> name != null && !name.isEmpty())
            .reduce((left, right) -> left + "::" + right)
            .orElse("");
    }

    private static String functionContainer(
        List<Token> tokens,
        int nameIndex,
        Deque<Integer> braceStack,
        Map<Integer, String> containerScopes
    ) {
        Token qualifier = tokenAt(tokens, nameIndex - 1);
        Token owner = tokenAt(tokens, nameIndex - 2);
        if (qualifier != null && owner != null && "::".equals(qualifier.text())
            && owner.type() == VasTypes.IDENTIFIER) {
            return owner.text();
        }
        return containerName(braceStack, containerScopes);
    }

    private static String declaredType(List<Token> tokens, int nameIndex) {
        int typeIndex = nameIndex - 1;
        Token previous = tokenAt(tokens, typeIndex);
        if (previous != null && previous.type() == VasTypes.OPERATOR) {
            if ("::".equals(previous.text())) {
                typeIndex -= 2;
            } else if (previous.text().contains("@") || previous.text().contains("&")) {
                typeIndex--;
            }
        }
        Token type = tokenAt(tokens, typeIndex);
        return type == null ? "" : type.text();
    }

    private static int parameterCount(List<Token> tokens, int leftParenIndex) {
        int rightParen = matchingRightParen(tokens, leftParenIndex);
        if (rightParen < 0 || rightParen == leftParenIndex + 1) {
            return 0;
        }
        int count = 1;
        int nestedParens = 0;
        int nestedBrackets = 0;
        for (int index = leftParenIndex + 1; index < rightParen; index++) {
            IElementType type = tokens.get(index).type();
            if (type == VasTypes.LPAREN) {
                nestedParens++;
            } else if (type == VasTypes.RPAREN) {
                nestedParens--;
            } else if (type == VasTypes.LBRACKET) {
                nestedBrackets++;
            } else if (type == VasTypes.RBRACKET) {
                nestedBrackets--;
            } else if (nestedParens == 0 && nestedBrackets == 0
                && ",".equals(tokens.get(index).text())) {
                count++;
            }
        }
        return count;
    }

    private static List<String> baseTypes(List<Token> tokens, int nameIndex) {
        List<String> bases = new ArrayList<>();
        boolean afterColon = false;
        for (int index = nameIndex + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.type() == VasTypes.LBRACE || ";".equals(token.text())) {
                break;
            }
            if (token.text().contains(":")) {
                afterColon = true;
            } else if (afterColon && token.type() == VasTypes.IDENTIFIER) {
                bases.add(token.text());
            }
        }
        return List.copyOf(bases);
    }

    private static Scope enclosingScope(
        List<Token> tokens,
        Deque<Integer> braceStack,
        Map<Integer, Integer> matchingBraces
    ) {
        if (braceStack.isEmpty()) {
            return null;
        }
        int leftIndex = braceStack.getLast();
        Integer rightIndex = matchingBraces.get(leftIndex);
        if (rightIndex == null) {
            return new Scope(tokens.get(leftIndex).start(), Integer.MAX_VALUE);
        }
        return new Scope(tokens.get(leftIndex).start(), tokens.get(rightIndex).end());
    }

    private static Scope parameterScope(
        List<Token> tokens,
        int variableIndex,
        Map<Integer, Integer> matchingBraces
    ) {
        int parenthesisDepth = 0;
        int leftParen = -1;
        for (int index = variableIndex - 1; index >= 0; index--) {
            IElementType type = tokens.get(index).type();
            if (type == VasTypes.RPAREN) {
                parenthesisDepth++;
            } else if (type == VasTypes.LPAREN) {
                if (parenthesisDepth == 0) {
                    leftParen = index;
                    break;
                }
                parenthesisDepth--;
            } else if (parenthesisDepth == 0
                && (type == VasTypes.LBRACE || type == VasTypes.RBRACE || ";".equals(tokens.get(index).text()))) {
                break;
            }
        }
        if (leftParen <= 0 || tokens.get(leftParen - 1).type() != VasTypes.IDENTIFIER
            || !looksLikeFunctionDeclaration(tokens, leftParen - 1)) {
            return null;
        }
        int bodyIndex = functionBodyIndex(tokens, leftParen - 1);
        if (bodyIndex < 0) {
            return new Scope(0, -1);
        }
        Integer rightIndex = matchingBraces.get(bodyIndex);
        return new Scope(
            tokens.get(bodyIndex).start(),
            rightIndex == null ? Integer.MAX_VALUE : tokens.get(rightIndex).end()
        );
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
        int braceDepth,
        int scopeStart,
        int scopeEnd,
        boolean projectVisible,
        boolean definition,
        String container,
        String declaredType,
        int parameterCount,
        List<String> baseTypes
    ) {
        if (offsets.add(token.start())) {
            symbols.add(new VasSymbol(
                token.text(),
                kind,
                token.start(),
                braceDepth,
                scopeStart,
                scopeEnd,
                projectVisible,
                definition,
                container,
                declaredType,
                parameterCount,
                baseTypes
            ));
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
                    lexer.getTokenStart(),
                    lexer.getTokenEnd()
                ));
            }
            lexer.advance();
        }
        return tokens;
    }

    private record Token(IElementType type, String text, int start, int end) {
    }

    private record Scope(int start, int end) {
    }
}
