package com.verseangelscript.rider.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementResolveResult;
import com.intellij.psi.PsiPolyVariantReferenceBase;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.PsiNamedElement;
import com.verseangelscript.rider.index.VasSymbolResolver;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class VasReference extends PsiPolyVariantReferenceBase<PsiElement> {
    public VasReference(@NotNull PsiElement element) {
        super(element, TextRange.from(0, element.getTextLength()), true);
    }

    @Override
    public ResolveResult @NotNull [] multiResolve(boolean incompleteCode) {
        List<ResolveResult> results = new ArrayList<>();
        for (PsiElement declaration : VasSymbolResolver.findDeclarations(myElement)) {
            if (!declaration.isEquivalentTo(myElement)) {
                results.add(new PsiElementResolveResult(declaration));
            }
        }
        return results.toArray(ResolveResult.EMPTY_ARRAY);
    }

    @Override
    public @NotNull PsiElement handleElementRename(@NotNull String newElementName) {
        if (myElement instanceof PsiNamedElement namedElement) {
            return namedElement.setName(newElementName);
        }
        return super.handleElementRename(newElementName);
    }
}
