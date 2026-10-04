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
        Set<Integer> lifecycleNames = lifecycleNames(tokens);
        Set<Integer> functionBodies = functionBodies(tokens, lifecycleNames);
        Map<Integer, String> containerScopes = containerScopes(tokens);
        Deque<Integer> braceStack = new ArrayDeque<>();
        Set<Integer> enumBodies = new HashSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (!"enum".equals(tokens.get(index).text())) continue;
            int cursor = index + 1;
            while (cursor < tokens.size() && tokens.get(cursor).type() != VasTypes.LBRACE && !";".equals(tokens.get(cursor).text())) cursor++;
            if (cursor < tokens.size() && tokens.get(cursor).type() == VasTypes.LBRACE) enumBodies.add(cursor);
        }
        int parenthesisDepth = 0, bracketDepth = 0;

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
                if (!braceStack.isEmpty() && enumBodies.contains(braceStack.getLast()) && parenthesisDepth == 0 && bracketDepth == 0
                    && (index == braceStack.getLast() + 1 || ",".equals(tokens.get(index - 1).text()))
                    && next != null && (next.type() == VasTypes.RBRACE || Set.of(",", "=").contains(next.text()))) {
                    String enumType = containerName(braceStack, containerScopes);
                    int separator = enumType.lastIndexOf("::");
                    String owner = separator < 0 ? "" : enumType.substring(0, separator);
                    add(symbols, declarationOffsets, token, VasSymbolKind.ENUM_MEMBER, braceDepth, -1, -1,
                        true, true, owner, enumType, -1, -1, List.of());
                } else if (next != null && next.type() == VasTypes.LPAREN
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
                    Scope parameterScope = parameterScope(tokens, index, matchingBraces, lifecycleNames);
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

            if (token.type() == VasTypes.LBRACE) braceStack.addLast(index);
            if (token.type() == VasTypes.LPAREN) parenthesisDepth++;
            if (token.type() == VasTypes.RPAREN) parenthesisDepth = Math.max(0, parenthesisDepth - 1);
            if (token.type() == VasTypes.LBRACKET) bracketDepth++;
            if (token.type() == VasTypes.RBRACKET) bracketDepth = Math.max(0, bracketDepth - 1);
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
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).start() == classSymbol.offset()) {
                return !lifecycleNames(tokens, index).isEmpty();
            }
        }
        return false;
    }

    private static Set<Integer> lifecycleNames(List<Token> tokens) {
        Set<Integer> names = new HashSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            if ("class".equals(tokens.get(index).text())) {
                int nameIndex = nextIdentifier(tokens, index + 1);
                if (nameIndex >= 0) {
                    names.addAll(lifecycleNames(tokens, nameIndex));
                }
            }
        }
        return names;
    }

    private static Set<Integer> lifecycleNames(List<Token> tokens, int classNameIndex) {
        Set<Integer> names = new HashSet<>();
        int body = classNameIndex + 1;
        while (body < tokens.size() && tokens.get(body).type() != VasTypes.LBRACE) {
            if (";".equals(tokens.get(body).text())) {
                return names;
            }
            body++;
        }
        String className = tokens.get(classNameIndex).text();
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
                && token.text().equals(className) && tokenAt(tokens, index + 1) != null
                && tokens.get(index + 1).type() == VasTypes.LPAREN) {
                int nameStart = "~".equals(tokens.get(index - 1).text()) ? index - 1 : index;
                if (isStatementDeclarationHead(tokens, nameStart)) {
                    names.add(index);
                }
            }
        }
        return names;
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
        if (identifierIndex < 0 || tokens.get(identifierIndex).type() != VasTypes.IDENTIFIER) {
            return new VasUsageContext(VasUsageContext.NOT_A_CALL, "", VasUsageContext.Access.UNSUPPORTED, "");
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
        if (tokens instanceof TokenList analyzed) {
            return analyzed.functionDeclarations.computeIfAbsent(nameIndex, index -> computeFunctionDeclaration(tokens, index));
        }
        return computeFunctionDeclaration(tokens, nameIndex);
    }

    private static boolean computeFunctionDeclaration(List<Token> tokens, int nameIndex) {
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
        boolean functionImport = tail != null && "from".equals(tail.text())
            && tokenAt(tokens, tailIndex + 1) != null && tokens.get(tailIndex + 1).type() == VasTypes.STRING
            && tokenAt(tokens, tailIndex + 2) != null && ";".equals(tokens.get(tailIndex + 2).text());
        if (tail == null || !(tail.type() == VasTypes.LBRACE || ";".equals(tail.text()) || functionImport)) {
            return false;
        }

        int typeEnd = qualifiedNameStart(tokens, nameIndex) - 1;
        if (typeEnd >= 0 && "&".equals(tokens.get(typeEnd).text())) {
            typeEnd--;
        }
        int typeStart = declarationTypeStart(tokens, typeEnd);
        if (typeStart < 0 || !isStatementDeclarationHead(tokens, typeStart)) {
            return false;
        }
        if (tokens instanceof TokenList analyzed) {
            int brace = analyzed.enclosingBrace[typeStart];
            return brace < 0 || containerScopes(tokens).containsKey(brace);
        }
        Deque<Integer> braces = new ArrayDeque<>();
        for (int index = 0; index < typeStart; index++) {
            if (tokens.get(index).type() == VasTypes.LBRACE) {
                braces.addLast(index);
            } else if (tokens.get(index).type() == VasTypes.RBRACE && !braces.isEmpty()) {
                braces.removeLast();
            }
        }
        // Functions live at module/namespace/type scope. A typed-looking
        // expression inside another body is not a nested function declaration.
        return braces.isEmpty() || containerScopes(tokens).containsKey(braces.getLast());
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
        if (previous == null || next == null) {
            return false;
        }
        if (next.type() == VasTypes.LPAREN) {
            int close = matchingRightParen(tokens, nameIndex + 1);
            next = tokenAt(tokens, close + 1);
            if (close < 0 || next == null || !(";".equals(next.text()) || ",".equals(next.text()))) {
                return false;
            }
        }
        if (!("=".equals(next.text()) || ";".equals(next.text()) || ",".equals(next.text())
            || next.type() == VasTypes.RBRACKET || next.type() == VasTypes.RPAREN
            || ":".equals(next.text()))) {
            return false;
        }

        if (",".equals(previous.text())) {
            return previousDeclarator(tokens, nameIndex) >= 0;
        }
        int typeEnd = nameIndex - 1;
        if (Set.of("in", "out", "inout").contains(tokens.get(typeEnd).text())) {
            typeEnd--;
            if (typeEnd < 0 || !"&".equals(tokens.get(typeEnd).text())) {
                return false;
            }
        }
        boolean reference = typeEnd >= 0 && "&".equals(tokens.get(typeEnd).text());
        if (reference) {
            typeEnd--;
        }
        int typeStart = declarationTypeStart(tokens, typeEnd);
        if (typeStart < 0) {
            return false;
        }
        boolean parameter = isParameterDeclarationHead(tokens, typeStart);
        // '&' is a TYPEMOD for parameters, not part of a local/global variable
        // TYPE. In expressions, flags & mask must remain a use of mask.
        return parameter || !reference && isStatementDeclarationHead(tokens, typeStart);
    }

    private static int declarationTypeStart(List<Token> tokens, int typeEnd) {
        int index = typeEnd;
        // TYPE suffixes are (@ const?) and []. Keep unfamiliar array/generic
        // types as scoped declarations, without claiming their member owner.
        while (index >= 0) {
            if ("const".equals(tokens.get(index).text())) {
                if (index == 0 || !"@".equals(tokens.get(index - 1).text())) {
                    return -1;
                }
                index -= 2;
            } else if ("@".equals(tokens.get(index).text())) {
                index--;
            } else if (tokens.get(index).type() == VasTypes.RBRACKET) {
                if (index == 0 || tokens.get(index - 1).type() != VasTypes.LBRACKET) {
                    return -1;
                }
                index -= 2;
            } else {
                break;
            }
        }
        if (index >= 0 && ">".equals(tokens.get(index).text())) {
            int depth = 1;
            while (--index >= 0 && depth > 0) {
                String text = tokens.get(index).text();
                if (">".equals(text)) {
                    depth++;
                } else if ("<".equals(text)) {
                    depth--;
                } else if (";".equals(text) || tokens.get(index).type() == VasTypes.LBRACE
                    || tokens.get(index).type() == VasTypes.RBRACE) {
                    return -1;
                }
            }
            if (depth != 0) {
                return -1;
            }
        }
        Token type = tokenAt(tokens, index);
        if (type == null || !(type.type() == VasTypes.IDENTIFIER
            || type.type() == VasTypes.KEYWORD && BUILTIN_TYPES.contains(type.text()))) {
            return -1;
        }
        index = qualifiedNameStart(tokens, index);
        if (index > 0 && "::".equals(tokens.get(index - 1).text())) {
            index--;
        }
        if (index > 0 && "const".equals(tokens.get(index - 1).text())) {
            index--;
        }
        return index;
    }

    private static boolean isParameterDeclarationHead(List<Token> tokens, int typeStart) {
        Token before = tokenAt(tokens, typeStart - 1);
        if (before == null || !(before.type() == VasTypes.LPAREN || ",".equals(before.text()))) {
            return false;
        }
        int leftParen = enclosingLeftParen(tokens, typeStart);
        if (leftParen <= 0 || tokens.get(leftParen - 1).type() != VasTypes.IDENTIFIER) {
            return false;
        }
        int name = leftParen - 1;
        return looksLikeFunctionDeclaration(tokens, name) || lifecycleNames(tokens).contains(name);
    }

    private static boolean isStatementDeclarationHead(List<Token> tokens, int typeStart) {
        int head = typeStart - 1;
        while (head >= 0) {
            if (Set.of("private", "protected", "public", "shared", "external", "explicit", "import")
                .contains(tokens.get(head).text())) {
                head--;
            } else if (tokens.get(head).type() == VasTypes.RBRACKET) {
                // scriptbuilder removes metadata blocks before compiling a
                // declaration; they are not an expression preceding its type.
                int depth = 1;
                while (--head >= 0 && depth > 0) {
                    if (tokens.get(head).type() == VasTypes.RBRACKET) {
                        depth++;
                    } else if (tokens.get(head).type() == VasTypes.LBRACKET) {
                        depth--;
                    }
                }
                if (depth != 0) {
                    return false;
                }
            } else {
                break;
            }
        }
        Token before = tokenAt(tokens, head);
        if (before == null || before.type() == VasTypes.LBRACE || before.type() == VasTypes.RBRACE) {
            return true;
        }
        if (";".equals(before.text())) {
            return enclosingLeftParen(tokens, head) < 0;
        }
        return before.type() == VasTypes.LPAREN && head > 0
            && Set.of("for", "foreach").contains(tokens.get(head - 1).text());
    }

    private static int enclosingLeftParen(List<Token> tokens, int beforeIndex) {
        int depth = 0;
        for (int index = beforeIndex - 1; index >= 0; index--) {
            IElementType type = tokens.get(index).type();
            if (type == VasTypes.RPAREN) {
                depth++;
            } else if (type == VasTypes.LPAREN) {
                if (depth == 0) {
                    return index;
                }
                depth--;
            } else if (depth == 0 && (type == VasTypes.LBRACE || type == VasTypes.RBRACE)) {
                break;
            }
        }
        return -1;
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
        if (tokens instanceof TokenList analyzed) return analyzed.parenthesisPairs.getOrDefault(leftParenIndex, -1);
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
        if (tokens instanceof TokenList analyzed) return analyzed.bracePairs;
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

    private static Set<Integer> functionBodies(List<Token> tokens, Set<Integer> lifecycleNames) {
        Set<Integer> bodies = new HashSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (tokens.get(index).type() == VasTypes.IDENTIFIER
                && tokenAt(tokens, index + 1) != null
                && tokenAt(tokens, index + 1).type() == VasTypes.LPAREN
                && (looksLikeFunctionDeclaration(tokens, index) || lifecycleNames.contains(index))) {
                int bodyIndex = functionBodyIndex(tokens, index);
                if (bodyIndex >= 0) {
                    bodies.add(bodyIndex);
                }
            }
        }
        return bodies;
    }

    private static Map<Integer, String> containerScopes(List<Token> tokens) {
        if (tokens instanceof TokenList analyzed) {
            if (analyzed.containers == null) analyzed.containers = computeContainerScopes(tokens);
            return analyzed.containers;
        }
        return computeContainerScopes(tokens);
    }

    private static Map<Integer, String> computeContainerScopes(List<Token> tokens) {
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
        if (type == null || !(type.type() == VasTypes.IDENTIFIER || type.type() == VasTypes.KEYWORD)) {
            return "";
        }
        int start = qualifiedNameStart(tokens, typeIndex);
        // Keep the declaration as a lexical blocker until global-qualified type
        // identity is supported, rather than treating ::T as a relative T.
        return start > 0 && "::".equals(tokens.get(start - 1).text()) ? "" : qualifiedName(tokens, typeIndex);
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
        Map<Integer, Integer> matchingBraces,
        Set<Integer> lifecycleNames
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
            || !(looksLikeFunctionDeclaration(tokens, leftParen - 1) || lifecycleNames.contains(leftParen - 1))) {
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
        TokenList tokens = new TokenList();
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
                            || first == ':' && text.charAt(index) == ':'
                            || (first == '&' || first == '|') && text.charAt(index) == first)) {
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
        tokens.finish();
        return tokens;
    }

    /** Per-scan immutable topology; no global state or cross-document lifetime. */
    private static final class TokenList extends ArrayList<Token> {
        private final Map<Integer, Integer> bracePairs = new HashMap<>();
        private final Map<Integer, Integer> parenthesisPairs = new HashMap<>();
        private final Map<Integer, Boolean> functionDeclarations = new HashMap<>();
        private Map<Integer, String> containers;
        private int[] enclosingBrace;

        private void finish() {
            enclosingBrace = new int[size()];
            Deque<Integer> braces = new ArrayDeque<>(), parentheses = new ArrayDeque<>();
            for (int index = 0; index < size(); index++) {
                enclosingBrace[index] = braces.isEmpty() ? -1 : braces.getLast();
                IElementType type = get(index).type();
                if (type == VasTypes.LBRACE) braces.addLast(index);
                else if (type == VasTypes.RBRACE && !braces.isEmpty()) bracePairs.put(braces.removeLast(), index);
                if (type == VasTypes.LPAREN) parentheses.addLast(index);
                else if (type == VasTypes.RPAREN && !parentheses.isEmpty()) parenthesisPairs.put(parentheses.removeLast(), index);
            }
        }
    }

    private record Token(IElementType type, String text, int start, int end) {
    }

    private record Scope(int start, int end) {
    }
}
