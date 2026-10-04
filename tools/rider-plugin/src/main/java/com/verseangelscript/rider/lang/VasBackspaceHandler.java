package com.verseangelscript.rider.lang;

import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiFile;
import com.verseangelscript.rider.VasFileType;

public final class VasBackspaceHandler extends BackspaceHandlerDelegate {
    private boolean paired;
    @Override public void beforeCharDeleted(char c, PsiFile file, Editor editor) {
        int offset = editor.getCaretModel().getOffset(); var text = editor.getDocument().getCharsSequence();
        paired = file.getFileType() == VasFileType.INSTANCE && offset > 0 && offset < text.length()
            && VasTypedHandler.closing(c) != 0 && text.charAt(offset) == VasTypedHandler.closing(c)
            && VasTypedHandler.inCode(text, offset - 1);
    }
    @Override public boolean charDeleted(char c, PsiFile file, Editor editor) {
        if (!paired) return false;
        paired = false;
        int offset = editor.getCaretModel().getOffset(); var document = editor.getDocument();
        if (offset < document.getTextLength() && document.getCharsSequence().charAt(offset) == VasTypedHandler.closing(c)) {
            document.deleteString(offset, offset + 1); return true;
        }
        return false;
    }
}
