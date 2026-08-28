package com.verseangelscript.rider.lang;

import com.intellij.codeInsight.hints.codeVision.ReferencesCodeVisionProvider;
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.verseangelscript.rider.VasLanguage;
import com.verseangelscript.rider.index.VasSymbolResolver;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class VasReferencesCodeVisionProvider extends ReferencesCodeVisionProvider {
    @Override
    public boolean acceptsFile(@NotNull PsiFile file) {
        return file.getLanguage().isKindOf(VasLanguage.INSTANCE);
    }

    @Override
    public boolean acceptsElement(@NotNull PsiElement element) {
        return VasSymbolResolver.findSymbol(element).isPresent();
    }

    @Override
    public String getHint(@NotNull PsiElement element, @NotNull PsiFile file) {
        int count = ReferencesSearch.search(element).findAll().size();
        return count + (count == 1 ? " usage" : " usages");
    }

    @Override
    public @NotNull String getId() {
        return "vas.references";
    }

    @Override
    public @NotNull List<CodeVisionRelativeOrdering> getRelativeOrderings() {
        return List.of();
    }
}
