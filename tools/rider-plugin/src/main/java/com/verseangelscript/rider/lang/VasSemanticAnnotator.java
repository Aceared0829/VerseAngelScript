package com.verseangelscript.rider.lang;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.verseangelscript.rider.index.*;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/** One pass per file, rather than rebuilding the dependency closure for every identifier. */
public final class VasSemanticAnnotator implements Annotator {
    public static final TextAttributesKey TYPE = key("VAS_TYPE", DefaultLanguageHighlighterColors.CLASS_NAME);
    public static final TextAttributesKey FUNCTION = key("VAS_FUNCTION", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);
    public static final TextAttributesKey NAMESPACE = key("VAS_NAMESPACE", DefaultLanguageHighlighterColors.METADATA);
    public static final TextAttributesKey VARIABLE = key("VAS_VARIABLE", DefaultLanguageHighlighterColors.LOCAL_VARIABLE);
    public static final TextAttributesKey MACRO = key("VAS_MACRO", DefaultLanguageHighlighterColors.CONSTANT);

    private static TextAttributesKey key(String name, TextAttributesKey fallback) {
        return TextAttributesKey.createTextAttributesKey(name, fallback);
    }

    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof PsiFile file)) return;
        String text = file.getText();
        Map<Integer, TextAttributesKey> declarations = new HashMap<>();
        Map<String, TextAttributesKey> names = new HashMap<>();
        for (PsiFile dependency : VasSymbolResolver.navigationFiles(file)) {
            for (VasSymbol symbol : VasSymbolScanner.scan(dependency.getText())) {
                TextAttributesKey attributes = switch (symbol.kind()) {
                    case CLASS, INTERFACE, ENUM, TYPE_ALIAS -> TYPE;
                    case FUNCTION -> FUNCTION;
                    case NAMESPACE -> NAMESPACE;
                    case VARIABLE -> VARIABLE;
                    case ENUM_MEMBER -> MACRO;
                };
                if (dependency.equals(file)) declarations.put(symbol.offset(), attributes);
                if (symbol.isProjectVisible() && symbol.kind() != VasSymbolKind.VARIABLE) names.put(symbol.name(), attributes);
            }
            for (var macro : VasMacroScanner.scan(dependency.getText())) {
                names.put(macro.name(), MACRO);
                if (dependency.equals(file)) shade(holder, macro.offset(), macro.offset() + macro.name().length(), MACRO);
            }
        }
        VasRuntimeSymbols.ALL.keySet().forEach(name -> names.putIfAbsent(name, FUNCTION));
        VasLexer lexer = new VasLexer();
        lexer.start(text);
        while (lexer.getTokenType() != null) {
            if (lexer.getTokenType() == VasTypes.IDENTIFIER) {
                int start = lexer.getTokenStart(), end = lexer.getTokenEnd();
                TextAttributesKey attributes = declarations.get(start);
                if (attributes == null) {
                    attributes = names.get(text.substring(start, end));
                    // Function names in source declarations are certain; calls need a following '('.
                    if (attributes == FUNCTION) {
                        int next = end;
                        while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
                        if (next == text.length() || text.charAt(next) != '(') attributes = null;
                    }
                }
                if (attributes != null) shade(holder, start, end, attributes);
            } else if (lexer.getTokenType() == VasTypes.PREPROCESSOR && text.startsWith("import", lexer.getTokenStart())) {
                String directive = text.substring(lexer.getTokenStart(), lexer.getTokenEnd());
                var imports = VasIncludeScanner.scan(directive).includes();
                if (imports.size() == 1 && imports.getFirst().kind() == VasIncludeScanner.Kind.MODULE) {
                    shade(holder, lexer.getTokenStart(), lexer.getTokenStart() + 6, VasSyntaxHighlighter.KEYWORD);
                    shade(holder, lexer.getTokenStart() + imports.getFirst().pathStart(), lexer.getTokenStart() + imports.getFirst().pathEnd(), NAMESPACE);
                }
            } else if (lexer.getTokenType() == VasTypes.PREPROCESSOR && text.charAt(lexer.getTokenStart()) == '#') {
                int base = lexer.getTokenStart() + 1;
                String directive = text.substring(base, lexer.getTokenEnd());
                VasLexer words = new VasLexer();
                words.start(directive);
                while (words.getTokenType() != null) {
                    if (words.getTokenType() == VasTypes.IDENTIFIER && names.get(directive.substring(words.getTokenStart(), words.getTokenEnd())) == MACRO)
                        shade(holder, base + words.getTokenStart(), base + words.getTokenEnd(), MACRO);
                    words.advance();
                }
            }
            lexer.advance();
        }
    }

    private static void shade(AnnotationHolder holder, int start, int end, TextAttributesKey key) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(new TextRange(start, end)).textAttributes(key).create();
    }
}
