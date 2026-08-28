package com.verseangelscript.rider.lang;

import com.intellij.psi.PsiElement;
import com.intellij.psi.search.searches.DefinitionsScopedSearch;
import com.intellij.util.Processor;
import com.intellij.util.QueryExecutor;
import com.verseangelscript.rider.VasLanguage;
import com.verseangelscript.rider.index.VasSymbolResolver;
import org.jetbrains.annotations.NotNull;

public final class VasDefinitionsSearch
    implements QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> {
    @Override
    public boolean execute(
        @NotNull DefinitionsScopedSearch.SearchParameters parameters,
        @NotNull Processor<? super PsiElement> consumer
    ) {
        PsiElement element = parameters.getElement();
        if (!element.getLanguage().isKindOf(VasLanguage.INSTANCE)) {
            return true;
        }
        for (PsiElement implementation : VasSymbolResolver.findImplementations(element)) {
            if (!consumer.process(implementation)) {
                return false;
            }
        }
        return true;
    }
}
