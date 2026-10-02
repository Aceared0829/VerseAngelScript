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
                    if (kind == VasSymbolKind.NAMESPACE) {
                        String container = containerName(braceStack, containerScopes);
                        int component = nameIndex;
                        while (true) {
                            Token part = tokens.get(component);
                            add(symbols, declarationOffsets, part, kind, braceDepth, -1, -1,
                                true, true, container, "namespace", -1, -1, List.of());
                            container = container.isEmpty() ? part.text() : container + "::" + part.text();
                            if (tokenAt(tokens, component + 2) == null
                                || !"::".equals(tokens.get(component + 1).text())
                                || tokens.get(component + 2).type() != VasTypes.IDENTIFIER) {
                                break;
                            }
                            component += 2;
                        }
                    } else {
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
                            -1,
                            baseTypes(tokens, nameIndex)
                        );
                    }
                }
            } else if (token.type() == VasTypes.IDENTIFIER) {
                Token next = tokenAt(tokens, index + 1);
                if (next != null && next.type() == VasTypes.LPAREN
                    && looksLikeFunctionDeclaration(tokens, index)) {
                    int bodyIndex = functionBodyIndex(tokens, index);
                    Scope bodyScope = scopeForBrace(tokens, bodyIndex, matchingBraces);
                    add(
                        symbols,
                        declarationOffsets,
                        token,
                        VasSymbolKind.FUNCTION,
                        braceDepth,
                        bodyScope == null ? -1 : bodyScope.start(),
                        bodyScope == null ? -1 : bodyScope.end(),
                        true,
                        bodyIndex >= 0,
                        functionContainer(tokens, index, braceStack, containerScopes),
                        declaredType(tokens, index),
                        parameterRange(tokens, index + 1).maximum(),
                        parameterRange(tokens, index + 1).minimum(),
                        List.of()
                    );
                } else if (looksLikeVariableDeclaration(tokens, index)) {
                    Scope parameterScope = parameterScope(tokens, index, matchingBraces);
                    Scope controlScope = controlVariableScope(tokens, index, matchingBraces);
                    Scope enclosingScope = parameterScope != null ? parameterScope
                        : controlScope != null ? controlScope : enclosingScope(tokens, braceStack, matchingBraces);
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

    /** Explicit lifecycle names form a rename family that the reference scanner cannot rewrite safely. */
    public static boolean hasExplicitLifecycleDeclaration(
        @NotNull CharSequence source, @NotNull VasSymbol classSymbol
    ) {
        if (classSymbol.kind() != VasSymbolKind.CLASS) {
            return false;
        }
        List<Token> tokens = tokenize(source);
        int body = -1;
        boolean afterName = false;
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.start() == classSymbol.offset()) {
                afterName = true;
            } else if (afterName && ";".equals(token.text())) {
                return false;
            } else if (afterName && token.type() == VasTypes.LBRACE) {
                body = index;
                break;
            }
        }
        if (body < 0) {
            return false;
        }
        int depth = 0;
        for (int index = body + 1; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.type() == VasTypes.RBRACE) {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (token.type() == VasTypes.LBRACE) {
                depth++;
            } else if (depth == 0 && token.type() == VasTypes.IDENTIFIER
                && token.text().equals(classSymbol.name()) && tokenAt(tokens, index + 1) != null
                && tokens.get(index + 1).type() == VasTypes.LPAREN) {
                int prefix = index - 1;
                if ("~".equals(tokens.get(prefix).text())) {
                    prefix--;
                }
                while (prefix > body && Set.of("private", "protected", "public", "explicit")
                    .contains(tokens.get(prefix).text())) {
                    prefix--;
                }
                Token previous = tokens.get(prefix);
                if (previous.type() == VasTypes.LBRACE || previous.type() == VasTypes.RBRACE
                    || ";".equals(previous.text())) {
                    return true;
                }
            }
        }
        return false;
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

        int arguments = VasUsageContext.NOT_A_CALL;
        Token next = tokenAt(tokens, identifierIndex + 1);
        if (next != null && next.type() == VasTypes.LPAREN) {
            arguments = argumentCount(tokens, identifierIndex + 1);
        }

        String qualifier = "";
        VasUsageContext.Access access = VasUsageContext.Access.UNQUALIFIED;
        Token separator = tokenAt(tokens, identifierIndex - 1);
        if (separator != null && (".".equals(separator.text()) || "::".equals(separator.text()))) {
            int ownerIndex = identifierIndex - 2;
            Token owner = tokenAt(tokens, ownerIndex);
            access = ".".equals(separator.text())
                ? VasUsageContext.Access.MEMBER : VasUsageContext.Access.QUALIFIED;
            if (owner == null || !(owner.type() == VasTypes.IDENTIFIER || "this".equals(owner.text()))) {
                access = VasUsageContext.Access.UNSUPPORTED;
            } else {
                qualifier = owner.text();
                if (access == VasUsageContext.Access.QUALIFIED) {
                    while (ownerIndex >= 2 && "::".equals(tokens.get(ownerIndex - 1).text())
                        && tokens.get(ownerIndex - 2).type() == VasTypes.IDENTIFIER) {
                        ownerIndex -= 2;
                        qualifier = tokens.get(ownerIndex).text() + "::" + qualifier;
                    }
                }
                Token preceding = tokenAt(tokens, ownerIndex - 1);
                // Do not reinterpret a.b.f(), factory().f(), or indexed receivers as b.f().
                if (preceding != null && (".".equals(preceding.text()) || "::".equals(preceding.text()))) {
                    access = VasUsageContext.Access.UNSUPPORTED;
                }
            }
        }
        return new VasUsageContext(arguments, qualifier, access, containerAt(tokens, identifierIndex));
    }

    private static String containerAt(List<Token> tokens, int identifierIndex) {
        Map<Integer, String> containers = containerScopes(tokens);
        Deque<Integer> braces = new ArrayDeque<>();
        for (int index = 0; index < identifierIndex; index++) {
            if (tokens.get(index).type() == VasTypes.LBRACE) {
                braces.addLast(index);
            } else if (tokens.get(index).type() == VasTypes.RBRACE && !braces.isEmpty()) {
                braces.removeLast();
            }
        }
        // Out-of-line method bodies carry the explicitly written owner too.
        for (int index = identifierIndex - 1; index >= 0; index--) {
            if (tokens.get(index).type() == VasTypes.IDENTIFIER
                && tokenAt(tokens, index + 1) != null
                && tokens.get(index + 1).type() == VasTypes.LPAREN
                && looksLikeFunctionDeclaration(tokens, index)
                && braces.contains(functionBodyIndex(tokens, index))) {
                return functionContainer(tokens, index, braces, containers);
            }
        }
        return containerName(braces, containers);
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

        if ("::".equals(previous.text())) {
            int start = qualifiedNameStart(tokens, nameIndex);
            Token returnType = tokenAt(tokens, start - 1);
            return returnType != null && (returnType.type() == VasTypes.IDENTIFIER
                || (returnType.type() == VasTypes.KEYWORD && BUILTIN_TYPES.contains(returnType.text()))
                || "@".equals(returnType.text()) || "&".equals(returnType.text())
                || hasUnsupportedTypeSuffix(tokens, start));
        }
        return previous.type() == VasTypes.IDENTIFIER
            || hasUnsupportedTypeSuffix(tokens, nameIndex)
            || previous.type() == VasTypes.KEYWORD && BUILTIN_TYPES.contains(previous.text())
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
            || ":".equals(next.text()))) {
            return false;
        }

        if (",".equals(previous.text())) {
            return previousDeclarator(tokens, nameIndex) >= 0;
        }
        if (">".equals(previous.text()) || previous.type() == VasTypes.RBRACKET) {
            return hasUnsupportedTypeSuffix(tokens, nameIndex);
        }
        if (Set.of("in", "out", "inout").contains(previous.text())) {
            Token reference = tokenAt(tokens, nameIndex - 2);
            if (reference == null || !"&".equals(reference.text())) {
                return false;
            }
            int typeIndex = nameIndex - 3;
            while (typeIndex >= 0 && Set.of("@", "const").contains(tokens.get(typeIndex).text())) {
                typeIndex--;
            }
            Token type = tokenAt(tokens, typeIndex);
            return type != null && (type.type() == VasTypes.IDENTIFIER
                || type.type() == VasTypes.KEYWORD && BUILTIN_TYPES.contains(type.text())
                || hasUnsupportedTypeSuffix(tokens, typeIndex + 1));
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

    private static boolean hasUnsupportedTypeSuffix(List<Token> tokens, int nameIndex) {
        Token previous = tokenAt(tokens, nameIndex - 1);
        if (previous == null) {
            return false;
        }
        if (">".equals(previous.text()) || previous.type() == VasTypes.RBRACKET) {
            // Preserve shadowing even when the declaration's generic/array type is
            // unsupported for member lookup. Its declaredType will remain unknown.
            IElementType close = previous.type();
            int depth = 0;
            for (int index = nameIndex - 1; index >= 1; index--) {
                Token token = tokens.get(index);
                boolean right = close == VasTypes.RBRACKET ? token.type() == VasTypes.RBRACKET : ">".equals(token.text());
                boolean left = close == VasTypes.RBRACKET ? token.type() == VasTypes.LBRACKET : "<".equals(token.text());
                if (right) {
                    depth++;
                } else if (left && --depth == 0) {
                    Token type = tokens.get(index - 1);
                    return type.type() == VasTypes.IDENTIFIER
                        || type.type() == VasTypes.KEYWORD && BUILTIN_TYPES.contains(type.text());
                } else if (token.type() == VasTypes.LBRACE || ";".equals(token.text())) {
                    break;
                }
            }
            return false;
        }
        return false;
    }

    private static int previousDeclarator(List<Token> tokens, int nameIndex) {
        int nested = 0;
        for (int index = nameIndex - 2; index >= 0; index--) {
            Token token = tokens.get(index);
            if (token.type() == VasTypes.RPAREN || token.type() == VasTypes.RBRACKET || token.type() == VasTypes.RBRACE) {
                nested++;
            } else if (token.type() == VasTypes.LPAREN || token.type() == VasTypes.LBRACKET || token.type() == VasTypes.LBRACE) {
                if (nested == 0) {
                    break;
                }
                nested--;
            } else if (nested == 0 && ";".equals(token.text())) {
                break;
            } else if (nested == 0 && token.type() == VasTypes.IDENTIFIER
                && looksLikeVariableDeclaration(tokens, index)) {
                return index;
            }
        }
        return -1;
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
                    String scope = tokens.get(nameIndex).text();
                    if ("namespace".equals(token.text())) {
                        for (int component = nameIndex + 1; component + 1 < cursor
                            && "::".equals(tokens.get(component).text())
                            && tokens.get(component + 1).type() == VasTypes.IDENTIFIER; component += 2) {
                            scope += "::" + tokens.get(component + 1).text();
                        }
                    }
                    scopes.put(cursor, scope);
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

    private static int qualifiedNameStart(List<Token> tokens, int nameIndex) {
        int start = nameIndex;
        while (start >= 2 && "::".equals(tokens.get(start - 1).text())
            && tokens.get(start - 2).type() == VasTypes.IDENTIFIER) {
            start -= 2;
        }
        return start;
    }

    private static String qualifiedName(List<Token> tokens, int endIndex) {
        int start = qualifiedNameStart(tokens, endIndex);
        StringBuilder name = new StringBuilder();
        for (int index = start; index <= endIndex; index++) {
            name.append(tokens.get(index).text());
        }
        return name.toString();
    }

    private static String functionContainer(
        List<Token> tokens,
        int nameIndex,
        Deque<Integer> braceStack,
        Map<Integer, String> containerScopes
    ) {
        int start = qualifiedNameStart(tokens, nameIndex);
        if (start != nameIndex) {
            String lexical = containerName(braceStack, containerScopes);
            String owner = qualifiedName(tokens, nameIndex - 2);
            return lexical.isEmpty() ? owner : lexical + "::" + owner;
        }
        return containerName(braceStack, containerScopes);
    }

    private static String declaredType(List<Token> tokens, int nameIndex) {
        if (nameIndex > 0 && ",".equals(tokens.get(nameIndex - 1).text())) {
            int previous = previousDeclarator(tokens, nameIndex);
            return previous < 0 ? "" : declaredType(tokens, previous);
        }
        int typeIndex = qualifiedNameStart(tokens, nameIndex) - 1;
        while (typeIndex >= 0 && Set.of("@", "&", "in", "out", "inout", "const")
            .contains(tokens.get(typeIndex).text())) {
            typeIndex--;
        }
        Token type = tokenAt(tokens, typeIndex);
        return type == null || !(type.type() == VasTypes.IDENTIFIER || type.type() == VasTypes.KEYWORD)
            ? "" : qualifiedName(tokens, typeIndex);
    }

    private static int argumentCount(List<Token> tokens, int leftParenIndex) {
        List<List<Token>> parameters = parameters(tokens, leftParenIndex);
        // Without a type parser, a comma inside generic syntax cannot be distinguished
        // reliably from an argument separator. Decline angle-bracket expressions.
        return parameters == null || parameters.stream().flatMap(List::stream)
            .anyMatch(token -> "<".equals(token.text()) || ">".equals(token.text()))
            ? VasUsageContext.UNKNOWN_ARGUMENTS : parameters.size();
    }

    private static ParameterRange parameterRange(List<Token> tokens, int leftParenIndex) {
        List<List<Token>> parameters = parameters(tokens, leftParenIndex);
        if (parameters == null) {
            return new ParameterRange(-1, -1);
        }
        int required = 0;
        boolean sawDefault = false;
        for (List<Token> parameter : parameters) {
            // Generic parameter syntax needs a type parser; do not invent an arity.
            boolean hasDefault = parameter.stream().anyMatch(token -> "=".equals(token.text()));
            if (hasDefault && "=".equals(parameter.getLast().text())) {
                return new ParameterRange(-1, -1);
            }
            if (parameter.stream().takeWhile(token -> !"=".equals(token.text()))
                .anyMatch(token -> token.text().contains("<") || token.text().contains(">"))) {
                return new ParameterRange(-1, -1);
            }
            if (hasDefault) {
                sawDefault = true;
            } else if (sawDefault) {
                return new ParameterRange(-1, -1);
            } else {
                required++;
            }
        }
        return new ParameterRange(required, parameters.size());
    }

    /** Split only complete lists, respecting calls, indexing and initializer lists. */
    private static List<List<Token>> parameters(List<Token> tokens, int leftParenIndex) {
        int rightParen = matchingRightParen(tokens, leftParenIndex);
        if (rightParen < 0) {
            return null;
        }
        if (rightParen == leftParenIndex + 1) {
            return List.of();
        }
        List<List<Token>> parameters = new ArrayList<>();
        Deque<IElementType> nested = new ArrayDeque<>();
        int start = leftParenIndex + 1;
        for (int index = start; index < rightParen; index++) {
            Token token = tokens.get(index);
            IElementType type = token.type();
            if (type == VasTypes.LPAREN || type == VasTypes.LBRACKET || type == VasTypes.LBRACE) {
                nested.addLast(type);
            } else if (type == VasTypes.RPAREN || type == VasTypes.RBRACKET || type == VasTypes.RBRACE) {
                IElementType expected = type == VasTypes.RPAREN ? VasTypes.LPAREN
                    : type == VasTypes.RBRACKET ? VasTypes.LBRACKET : VasTypes.LBRACE;
                if (nested.isEmpty() || nested.removeLast() != expected) {
                    return null;
                }
            } else if (nested.isEmpty() && ",".equals(token.text())) {
                if (start == index) {
                    return null;
                }
                parameters.add(tokens.subList(start, index));
                start = index + 1;
            }
        }
        if (!nested.isEmpty() || start == rightParen) {
            return null;
        }
        parameters.add(tokens.subList(start, rightParen));
        return parameters;
    }

    private record ParameterRange(int minimum, int maximum) {
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

    private static Scope scopeForBrace(
        List<Token> tokens,
        int leftIndex,
        Map<Integer, Integer> matchingBraces
    ) {
        if (leftIndex < 0) {
            return null;
        }
        Integer rightIndex = matchingBraces.get(leftIndex);
        return new Scope(
            tokens.get(leftIndex).start(),
            rightIndex == null ? Integer.MAX_VALUE : tokens.get(rightIndex).end()
        );
    }

    private static Scope controlVariableScope(
        List<Token> tokens, int variableIndex, Map<Integer, Integer> matchingBraces
    ) {
        for (int index = variableIndex - 1; index >= 1; index--) {
            Token token = tokens.get(index);
            if (token.type() == VasTypes.LBRACE || token.type() == VasTypes.RBRACE || ";".equals(token.text())) {
                return null;
            }
            if (token.type() == VasTypes.LPAREN
                && Set.of("for", "foreach", "if", "while", "switch").contains(tokens.get(index - 1).text())) {
                int rightParen = matchingRightParen(tokens, index);
                if (rightParen < 0) {
                    return new Scope(tokens.get(variableIndex).start(), Integer.MAX_VALUE);
                }
                int end = statementEnd(tokens, rightParen + 1, matchingBraces);
                return new Scope(tokens.get(variableIndex).start(),
                    end < 0 ? Integer.MAX_VALUE : tokens.get(end).end());
            }
        }
        return null;
    }

    private static int statementEnd(List<Token> tokens, int start, Map<Integer, Integer> matchingBraces) {
        Token first = tokenAt(tokens, start);
        if (first == null) {
            return -1;
        }
        if (first.type() == VasTypes.LBRACE) {
            return matchingBraces.getOrDefault(start, -1);
        }
        if (Set.of("for", "foreach", "if", "while", "switch").contains(first.text())
            && tokenAt(tokens, start + 1) != null && tokens.get(start + 1).type() == VasTypes.LPAREN) {
            int rightParen = matchingRightParen(tokens, start + 1);
            int end = rightParen < 0 ? -1 : statementEnd(tokens, rightParen + 1, matchingBraces);
            if (end >= 0 && "if".equals(first.text()) && tokenAt(tokens, end + 1) != null
                && "else".equals(tokens.get(end + 1).text())) {
                end = statementEnd(tokens, end + 2, matchingBraces);
            }
            return end;
        }
        for (int index = start; index < tokens.size(); index++) {
            if (";".equals(tokens.get(index).text())) {
                return index;
            }
            if (tokens.get(index).type() == VasTypes.RBRACE) {
                return index;
            }
        }
        return -1;
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
        int requiredParameterCount,
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
                requiredParameterCount,
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
                String text = source.subSequence(lexer.getTokenStart(), lexer.getTokenEnd()).toString();
                if (type == VasTypes.OPERATOR) {
                    // The highlighter lexer groups adjacent operators (e.g. ",-" or "=-").
                    // For syntax, separators and assignment must be individual tokens.
                    for (int index = 0; index < text.length();) {
                        int start = index++;
                        char first = text.charAt(start);
                        if (index < text.length() && (text.charAt(index) == '=' && "=!<>+-*/%&|^".indexOf(first) >= 0
                            || first == ':' && text.charAt(index) == ':')) {
                            index++;
                        }
                        tokens.add(new Token(type, text.substring(start, index),
                            lexer.getTokenStart() + start, lexer.getTokenStart() + index));
                    }
                } else {
                    tokens.add(new Token(type, text, lexer.getTokenStart(), lexer.getTokenEnd()));
                }
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
