package com.verseangelscript.rider.lang;

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.tree.IElementType;
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

        PsiElement navigationElement = elementAtOffset(sourceElement, offset);

        Set<PsiElement> targets = new LinkedHashSet<>();
        for (PsiReference reference : navigationElement.getReferences()) {
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
        IElementType elementType = navigationElement.getNode().getElementType();
        if (targets.isEmpty() && elementType == VasTypes.IDENTIFIER) {
            targets.addAll(VasSymbolResolver.findDeclarations(navigationElement));
        }
        if (targets.isEmpty()) {
            targets.addAll(VasSymbolResolver.findImplementations(navigationElement));
        }
        return targets.isEmpty() ? null : targets.toArray(PsiElement.EMPTY_ARRAY);
    }

    private static @NotNull PsiElement elementAtOffset(
        @NotNull PsiElement sourceElement,
        int offset
    ) {
        if (sourceElement.getContainingFile() == null) {
            return sourceElement;
        }
        PsiElement atOffset = sourceElement.getContainingFile().findElementAt(offset);
        if (atOffset == null && offset > 0) {
            atOffset = sourceElement.getContainingFile().findElementAt(offset - 1);
        }
        return atOffset == null ? sourceElement : atOffset;
    }
}
