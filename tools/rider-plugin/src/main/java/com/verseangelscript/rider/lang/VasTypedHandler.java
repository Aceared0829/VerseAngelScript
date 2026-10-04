package com.verseangelscript.rider.lang;

import com.intellij.codeInsight.AutoPopupController;
import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.verseangelscript.rider.VasFileType;
import org.jetbrains.annotations.NotNull;

/** Native typed-action hook: pairing participates in the editor's typing/undo command. */
public final class VasTypedHandler extends TypedHandlerDelegate {
    static char closing(char c) {
        return switch (c) { case '{' -> '}'; case '(' -> ')'; case '[' -> ']'; case '"', '\'' -> c; default -> 0; };
    }

    static boolean inCode(CharSequence text, int offset) {
        VasLexer lexer = new VasLexer();
        lexer.start(text);
        while (lexer.getTokenType() != null) {
            if (lexer.getTokenStart() <= offset && offset < lexer.getTokenEnd()
                || offset == text.length() && lexer.getTokenEnd() == offset) {
                if (lexer.getTokenType() == VasTypes.STRING) {
                    int start = lexer.getTokenStart(), end = lexer.getTokenEnd();
                    return offset == start || offset == end && end > start + 1
                        && text.charAt(end - 1) == text.charAt(start);
                }
                if (lexer.getTokenType() == VasTypes.COMMENT && offset == lexer.getTokenEnd()
                    && offset >= 2 && text.charAt(offset - 2) == '*' && text.charAt(offset - 1) == '/') return true;
                return lexer.getTokenType() != VasTypes.COMMENT && lexer.getTokenType() != VasTypes.PREPROCESSOR;
            }
            lexer.advance();
        }
        return true;
    }

    static boolean isQuoteEnd(CharSequence text, int offset, char quote) {
        if (offset >= text.length() || text.charAt(offset) != quote) return false;
        int slashes = 0;
        for (int i = offset - 1; i >= 0 && text.charAt(i) == '\\'; i--) slashes++;
        if (slashes % 2 != 0) return false;
        VasLexer lexer = new VasLexer(); lexer.start(text);
        while (lexer.getTokenType() != null) {
            if (lexer.getTokenType() == VasTypes.STRING && lexer.getTokenStart() < offset
                && lexer.getTokenEnd() == offset + 1 && text.charAt(lexer.getTokenStart()) == quote) return true;
            lexer.advance();
        }
        return false;
    }
    private static boolean afterNumber(CharSequence text, int offset) {
        VasLexer lexer = new VasLexer(); lexer.start(text);
        while (lexer.getTokenType() != null) {
            if (lexer.getTokenEnd() == offset) return lexer.getTokenType() == VasTypes.NUMBER;
            if (lexer.getTokenStart() >= offset) break;
            lexer.advance();
        }
        return false;
    }
    @Override public @NotNull Result beforeSelectionRemoved(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        var selection = editor.getSelectionModel(); char close = closing(c);
        if (file.getFileType() != VasFileType.INSTANCE || close == 0 || !selection.hasSelection()
            || !inCode(editor.getDocument().getCharsSequence(), selection.getSelectionStart())) return Result.CONTINUE;
        var settings = CodeInsightSettings.getInstance();
        if (c == '"' || c == '\'' ? !settings.AUTOINSERT_PAIR_QUOTE : !settings.AUTOINSERT_PAIR_BRACKET) return Result.CONTINUE;
        int start = selection.getSelectionStart(), end = selection.getSelectionEnd(); String selected = selection.getSelectedText();
        editor.getDocument().replaceString(start, end, "" + c + selected + close);
        selection.setSelection(start + 1, end + 1); editor.getCaretModel().moveToOffset(end + 1);
        return Result.STOP;
    }

    @Override
    public @NotNull Result beforeCharTyped(char c, @NotNull Project project, @NotNull Editor editor,
                                           @NotNull PsiFile file, @NotNull FileType fileType) {
        if (fileType != VasFileType.INSTANCE || editor.getSelectionModel().hasSelection()) return Result.CONTINUE;
        var document = editor.getDocument(); var text = document.getCharsSequence();
        int offset = editor.getCaretModel().getOffset();
        boolean quote = c == '"' || c == '\'';
        var settings = CodeInsightSettings.getInstance();
        if (quote ? !settings.AUTOINSERT_PAIR_QUOTE : !settings.AUTOINSERT_PAIR_BRACKET) return Result.CONTINUE;
        if (offset < text.length() && text.charAt(offset) == c
            && (quote ? isQuoteEnd(text, offset, c) : ")]}".indexOf(c) >= 0 && inCode(text, offset))) {
            editor.getCaretModel().moveToOffset(offset + 1);
            return Result.STOP;
        }
        char close = closing(c);
        if (close == 0 || !inCode(text, offset) || c == '\'' && afterNumber(text, offset)) return Result.CONTINUE;
        if (offset < text.length() && !Character.isWhitespace(text.charAt(offset)) && ")]};,:".indexOf(text.charAt(offset)) < 0) return Result.CONTINUE;
        document.insertString(offset, "" + c + close);
        editor.getCaretModel().moveToOffset(offset + 1);
        if (c == '(') AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null);
        return Result.STOP;
    }

    @Override public @NotNull Result charTyped(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (file.getFileType() == VasFileType.INSTANCE && (c == '(' || c == ',')
            && inCode(editor.getDocument().getCharsSequence(), Math.max(0, editor.getCaretModel().getOffset() - 1)))
            AutoPopupController.getInstance(project).autoPopupParameterInfo(editor, null);
        return Result.CONTINUE;
    }

    @Override
    public @NotNull Result checkAutoPopup(char c, @NotNull Project project, @NotNull Editor editor, @NotNull PsiFile file) {
        if (file.getFileType() != VasFileType.INSTANCE) return Result.CONTINUE;
        int offset = editor.getCaretModel().getOffset();
        if (inCode(editor.getDocument().getCharsSequence(), Math.max(0, offset - 1))
            && (Character.isJavaIdentifierPart(c) || c == '.' || c == ':' && offset >= 2
            && editor.getDocument().getCharsSequence().charAt(offset - 2) == ':')) {
            AutoPopupController.getInstance(project).scheduleAutoPopup(editor);
            return Result.STOP;
        }
        return Result.CONTINUE;
    }
}
