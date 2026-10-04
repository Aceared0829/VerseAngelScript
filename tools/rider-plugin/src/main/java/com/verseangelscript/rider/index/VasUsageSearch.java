package com.verseangelscript.rider.index;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.search.UsageSearchContext;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Processor;
import com.verseangelscript.rider.lang.VasIdentifierPsiElement;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only source usages. Rename continues to use its complete-coverage references. */
public final class VasUsageSearch {
    private VasUsageSearch() { }

    public static boolean process(PsiElement target, SearchScope scope, Processor<? super PsiElement> processor) {
        if (!(target instanceof VasIdentifierPsiElement) || VasSymbolResolver.findSymbol(target).isEmpty()) return true;
        Set<PsiElement> seen = new HashSet<>();
        return PsiSearchHelper.getInstance(target.getProject()).processElementsWithWord((element, offset) -> {
            ProgressManager.checkCanceled();
            PsiElement leaf = element.getContainingFile().findElementAt(element.getTextOffset() + offset);
            VasIdentifierPsiElement usage = PsiTreeUtil.getParentOfType(leaf, VasIdentifierPsiElement.class, false);
            if (usage == null || !usage.getText().equals(target.getText()) || usage.isEquivalentTo(target)
                || VasSymbolResolver.findSymbol(usage).isPresent() || !seen.add(usage)) return true;
            List<PsiElement> candidates = VasSymbolResolver.findNavigationDeclarations(usage);
            // Retain lexical/receiver/arity filtering; never select an ambiguous same-name symbol.
            return candidates.size() != 1 || !target.getManager().areElementsEquivalent(candidates.getFirst(), target)
                || processor.process(usage);
        }, scope, target.getText(), UsageSearchContext.IN_CODE, true);
    }
}
