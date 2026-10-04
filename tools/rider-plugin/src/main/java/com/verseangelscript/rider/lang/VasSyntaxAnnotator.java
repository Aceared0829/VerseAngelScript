package com.verseangelscript.rider.lang;

import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Immediate structural errors do not require a compiler process or project trust. */
public final class VasSyntaxAnnotator implements Annotator {
    public record Problem(int start, int end, String message) { }
    public static List<Problem> inspect(CharSequence text) {
        var problems = new ArrayList<Problem>(); var stack = new ArrayDeque<Problem>();
        VasLexer lexer = new VasLexer(); lexer.start(text);
        while (lexer.getTokenType() != null) {
            var type = lexer.getTokenType(); int start = lexer.getTokenStart(), end = lexer.getTokenEnd();
            String value = text.subSequence(start, end).toString();
            if (type == VasTypes.COMMENT && value.startsWith("/*") && !value.endsWith("*/"))
                problems.add(new Problem(start, Math.min(end, start + 2), "Unterminated block comment"));
            else if (type == VasTypes.STRING) {
                int delimiter = value.startsWith("\"\"\"") ? 3 : 1;
                String opening = value.substring(0, delimiter);
                if (value.length() < 2 * delimiter || !value.endsWith(opening) || delimiter == 1 && escapedEnding(value))
                    problems.add(new Problem(start, Math.min(end, start + delimiter), "Unterminated string literal"));
            } else if (type == TokenType.BAD_CHARACTER) problems.add(new Problem(start, end, "Invalid character"));
            else if (type == VasTypes.LPAREN || type == VasTypes.LBRACKET || type == VasTypes.LBRACE)
                stack.push(new Problem(start, end, value));
            else if (type == VasTypes.RPAREN || type == VasTypes.RBRACKET || type == VasTypes.RBRACE) {
                char expected = value.charAt(0);
                if (stack.isEmpty() || VasTypedHandler.closing(stack.peek().message().charAt(0)) != expected)
                    problems.add(new Problem(start, end, "Unexpected '" + expected + "'"));
                else stack.pop();
            }
            lexer.advance();
        }
        // Inactive macro branches may deliberately contain unmatched code. Their
        // actual structural diagnostics belong to the preprocessed compiler input.
        boolean conditional = java.util.regex.Pattern.compile("(?m)^\\s*#(?:if|ifdef|ifndef)\\b").matcher(text).find();
        if (conditional) problems.removeIf(p -> p.message().startsWith("Unexpected"));
        else for (Problem open : stack) problems.add(new Problem(open.start(), open.end(), "Expected '" + VasTypedHandler.closing(open.message().charAt(0)) + "'"));
        return List.copyOf(problems);
    }
    private static boolean escapedEnding(String value) {
        int slashes = 0;
        for (int i = value.length() - 2; i >= 0 && value.charAt(i) == '\\'; i--) slashes++;
        return slashes % 2 != 0;
    }
    @Override public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof PsiFile file)) return;
        for (Problem problem : inspect(file.getText())) holder.newAnnotation(HighlightSeverity.ERROR, problem.message())
            .range(new TextRange(problem.start(), problem.end())).create();
    }
}
