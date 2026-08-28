package com.verseangelscript.rider.lang;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.verseangelscript.rider.VasLanguage;
import com.verseangelscript.rider.index.VasSymbolResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

public final class VasGotoDeclarationHandler implements GotoDeclarationHandler {
    @Override
    public PsiElement @Nullable [] getGotoDeclarationTargets(
        @Nullable PsiElement sourceElement,
        int offset,
        @NotNull Editor editor
    ) {
        if (sourceElement == null || !sourceElement.getLanguage().isKindOf(VasLanguage.INSTANCE)) {
            return null;
        }

        Set<PsiElement> targets = new LinkedHashSet<>();
        for (PsiReference reference : sourceElement.getReferences()) {
            if (reference instanceof PsiPolyVariantReference polyReference) {
                for (ResolveResult result : polyReference.multiResolve(false)) {
                    if (result.getElement() != null) {
                        targets.add(result.getElement());
                    }
                }
            } else {
                PsiElement resolved = reference.resolve();
                if (resolved != null) {
                    targets.add(resolved);
                }
            }
        }
        if (targets.isEmpty()) {
            targets.addAll(VasSymbolResolver.findImplementations(sourceElement));
        }
        return targets.isEmpty() ? null : targets.toArray(PsiElement.EMPTY_ARRAY);
    }
}
