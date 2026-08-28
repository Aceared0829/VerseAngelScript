package com.verseangelscript.rider.lang;

import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceContributor;
import com.intellij.psi.PsiReferenceProvider;
import com.intellij.psi.PsiReferenceRegistrar;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

public final class VasReferenceContributor extends PsiReferenceContributor {
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(VasTypes.IDENTIFIER),
            new PsiReferenceProvider() {
                @Override
                public PsiReference @NotNull [] getReferencesByElement(
                    @NotNull PsiElement element,
                    @NotNull ProcessingContext context
                ) {
                    return new PsiReference[] {new VasReference(element)};
                }
            }
        );
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(VasTypes.PREPROCESSOR),
            new PsiReferenceProvider() {
                @Override
                public PsiReference @NotNull [] getReferencesByElement(
                    @NotNull PsiElement element,
                    @NotNull ProcessingContext context
                ) {
                    VasIncludeReference reference = VasIncludeReference.create(element);
                    return reference == null
                        ? PsiReference.EMPTY_ARRAY
                        : new PsiReference[] {reference};
                }
            }
        );
    }
}
