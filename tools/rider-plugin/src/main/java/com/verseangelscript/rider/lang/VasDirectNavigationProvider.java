package com.verseangelscript.rider.lang;

import com.intellij.navigation.DirectNavigationProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.verseangelscript.rider.VasLanguage;
import com.verseangelscript.rider.index.VasSymbolResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class VasDirectNavigationProvider implements DirectNavigationProvider {
    @Override
    public @Nullable PsiElement getNavigationElement(@NotNull PsiElement element) {
        if (!element.getLanguage().isKindOf(VasLanguage.INSTANCE)) {
            return null;
        }

        for (PsiReference reference : element.getReferences()) {
            PsiElement resolved = reference.resolve();
            if (resolved != null) {
                return resolved;
            }
        }

        if (element.getNode().getElementType() == VasTypes.PREPROCESSOR) {
            return VasIncludeReference.resolveTarget(element);
        }
        if (element.getNode().getElementType() != VasTypes.IDENTIFIER) {
            return null;
        }
        List<PsiElement> declarations = VasSymbolResolver.findDeclarations(element);
        return declarations.isEmpty() ? null : declarations.get(0);
    }
}
