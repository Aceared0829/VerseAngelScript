package com.verseangelscript.rider.lang;

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiFile;
import com.verseangelscript.rider.VasFileType;

public final class VasEnterHandler implements EnterHandlerDelegate {
    @Override public Result preprocessEnter(PsiFile file, Editor editor, Ref<Integer> caretOffset,
        Ref<Integer> caretAdvance, DataContext data, EditorActionHandler original) {
        if (file.getFileType() != VasFileType.INSTANCE || editor.getSelectionModel().hasSelection()) return Result.Continue;
        var document = editor.getDocument(); var text = document.getCharsSequence();
        int offset = editor.getCaretModel().getOffset();
        if (!VasTypedHandler.inCode(text, offset)) return Result.Continue;
        int start = document.getLineStartOffset(document.getLineNumber(offset)), end = start;
        while (end < offset && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) end++;
        String indent = text.subSequence(start, end).toString();
        int previous = offset - 1;
        while (previous >= start && Character.isWhitespace(text.charAt(previous))) previous--;
        boolean block = previous >= start && text.charAt(previous) == '{';
        String inner = indent + (block ? "\t" : "");
        String insert = "\n" + inner;
        if (block && offset < text.length() && text.charAt(offset) == '}') insert += "\n" + indent;
        document.insertString(offset, insert);
        editor.getCaretModel().moveToOffset(offset + 1 + inner.length());
        return Result.Stop;
    }
}
