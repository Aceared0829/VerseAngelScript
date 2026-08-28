package com.verseangelscript.rider.lang;

import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.verseangelscript.rider.index.VasSymbolKind;
import com.verseangelscript.rider.index.VasSymbolResolver;
import org.jetbrains.annotations.NotNull;

import java.util.List;

final class VasCallNavigation {
    private VasCallNavigation() {
    }

    static void show(@NotNull AnActionEvent event, boolean callers) {
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (editor == null || file == null) {
            return;
        }
        PsiElement source = file.findElementAt(editor.getCaretModel().getOffset());
        PsiElement callable = resolveCallable(source);
        if (callable == null) {
            HintManager.getInstance().showInformationHint(editor, "Place the caret on a VAS function");
            return;
        }

        List<PsiElement> targets = callers
            ? VasSymbolResolver.findCallers(callable)
            : VasSymbolResolver.findCallees(callable);
        if (targets.isEmpty()) {
            HintManager.getInstance().showInformationHint(
                editor,
                callers ? "No VAS callers found" : "No VAS callees found"
            );
            return;
        }
        NavigationUtil.getPsiElementPopup(
            targets.toArray(PsiElement.EMPTY_ARRAY),
            callers ? "VAS Callers" : "VAS Callees"
        ).showInBestPositionFor(editor);
    }

    static boolean isAvailable(@NotNull AnActionEvent event) {
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        return file != null && file.getLanguage().isKindOf(
            com.verseangelscript.rider.VasLanguage.INSTANCE
        );
    }

    private static PsiElement resolveCallable(PsiElement source) {
        if (source == null) {
            return null;
        }
        if (VasSymbolResolver.findSymbol(source)
            .map(symbol -> symbol.kind() == VasSymbolKind.FUNCTION)
            .orElse(false)) {
            return source;
        }
        return VasSymbolResolver.findDeclarations(source).stream()
            .filter(candidate -> VasSymbolResolver.findSymbol(candidate)
                .map(symbol -> symbol.kind() == VasSymbolKind.FUNCTION)
                .orElse(false))
            .findFirst()
            .orElse(null);
    }
}
