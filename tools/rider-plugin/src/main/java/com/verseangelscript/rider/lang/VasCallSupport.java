package com.verseangelscript.rider.lang;

import com.intellij.psi.PsiFile;
import com.verseangelscript.rider.index.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class VasCallSupport {
    public record Call(String name, int nameOffset, int open, int parameter) { }
    public record Signature(String label, List<int[]> parameters) { }
    private record Word(String text, int start) { }
    private VasCallSupport() { }

    public static Call callAt(CharSequence text, int offset) {
        VasLexer lexer = new VasLexer(); lexer.start(text);
        var stack = new ArrayDeque<Call>(); String previous = ""; int previousOffset = 0;
        while (lexer.getTokenType() != null && lexer.getTokenStart() < offset) {
            var type = lexer.getTokenType();
            if (type == VasTypes.STRING || type == VasTypes.COMMENT || type == VasTypes.PREPROCESSOR
                || type == com.intellij.psi.TokenType.WHITE_SPACE) { lexer.advance(); continue; }
            String word = text.subSequence(lexer.getTokenStart(), lexer.getTokenEnd()).toString();
            if (word.equals("(") || word.equals("[") || word.equals("{"))
                stack.push(new Call(word.equals("(") ? previous : "", previousOffset, lexer.getTokenStart(), 0));
            else if (word.equals(")") || word.equals("]") || word.equals("}")) { if (!stack.isEmpty()) stack.pop(); }
            else if (word.equals(",") && !stack.isEmpty()) {
                Call call = stack.pop(); stack.push(new Call(call.name(), call.nameOffset(), call.open(), call.parameter() + 1));
            }
            previous = word; previousOffset = lexer.getTokenStart(); lexer.advance();
        }
        for (Call call : stack) if (!call.name().isEmpty() && Character.isJavaIdentifierStart(call.name().charAt(0))
            && !List.of("if", "while", "for", "switch", "catch").contains(call.name())) return call;
        return null;
    }

    public static Signature signature(String text, VasSymbol symbol) {
        int open = text.indexOf('(', symbol.offset());
        if (open < 0) return new Signature(symbol.name() + "()", List.of());
        String suffix = text.substring(open, Math.min(text.length(), open + 8192));
        VasLexer lexer = new VasLexer(); lexer.start(suffix);
        int depth = 0, end = open;
        while (lexer.getTokenType() != null) {
            if (lexer.getTokenType() == VasTypes.LPAREN) depth++;
            if (lexer.getTokenType() == VasTypes.RPAREN && --depth == 0) { end = open + lexer.getTokenEnd(); break; }
            lexer.advance();
        }
        if (end <= open) return new Signature(symbol.name() + "(...)", List.of());
        String label = symbol.qualifiedName() + text.substring(open, end).replaceAll("\\s+", " ");
        return signature(label);
    }

    public static Signature signature(String label) {
        List<int[]> ranges = new ArrayList<>(); int open = label.indexOf('('), begin = open + 1, nesting = 0;
        if (open < 0) return new Signature(label, ranges);
        boolean quoted = false; char quote = 0;
        for (int i = begin; i < label.length(); i++) {
            char c = label.charAt(i);
            if (quoted) { if (c == '\\') i++; else if (c == quote) quoted = false; continue; }
            if (c == '"' || c == '\'') { quoted = true; quote = c; continue; }
            if (c == '(' || c == '[' || c == '<' || c == '{') nesting++;
            else if (c == ')' && nesting == 0 || c == ',' && nesting == 0) {
                int start = begin, end = i;
                while (start < end && Character.isWhitespace(label.charAt(start))) start++;
                while (end > start && Character.isWhitespace(label.charAt(end - 1))) end--;
                if (end > start) ranges.add(new int[] {start, end});
                begin = i + 1; if (c == ')') break;
            } else if (c == ')' || c == ']' || c == '>' || c == '}') nesting--;
        }
        return new Signature(label, List.copyOf(ranges));
    }

    public static List<Signature> signatures(PsiFile file, Call call) {
        List<Signature> result = new ArrayList<>();
        if (call == null) return result;
        var local = VasSymbolScanner.scan(file.getText()); var others = new ArrayList<VasSymbol>();
        var sourceTexts = new java.util.HashMap<VasSymbol, String>();
        for (var symbol : local) sourceTexts.put(symbol, file.getText());
        var files = VasSymbolResolver.navigationFiles(file);
        for (PsiFile dependency : files) if (!dependency.equals(file)) {
            String text = dependency.getText(); var symbols = VasSymbolScanner.scan(text); others.addAll(symbols);
            for (var symbol : symbols) sourceTexts.putIfAbsent(symbol, text);
        }
        var usage = VasSymbolScanner.usageContext(file.getText(), call.nameOffset());
        for (VasSymbol symbol : VasCompletionSelection.select(local, others, call.nameOffset(), usage)) {
            if (symbol.kind() != VasSymbolKind.FUNCTION || !symbol.name().equals(call.name())) continue;
            Signature signature = signature(sourceTexts.get(symbol), symbol);
            if (result.stream().noneMatch(s -> s.label().equals(signature.label()))) result.add(signature);
        }
        if (result.isEmpty() && usage.access() == VasUsageContext.Access.UNQUALIFIED) {
            String runtime = VasRuntimeSymbols.ALL.get(call.name());
            if (runtime != null && runtime.contains("(")) result.add(signature(runtime));
        }
        return result;
    }
}
