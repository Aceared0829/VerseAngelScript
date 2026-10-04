package com.verseangelscript.rider.lang;

import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesHandlerFactory;
import com.intellij.find.findUsages.FindUsagesOptions;
import com.intellij.psi.PsiElement;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.Processor;
import com.verseangelscript.rider.index.VasSymbolResolver;
import com.verseangelscript.rider.index.VasUsageSearch;
import org.jetbrains.annotations.NotNull;

public final class VasFindUsagesHandlerFactory extends FindUsagesHandlerFactory {
    static PsiElement target(PsiElement element) {
        if (!(element instanceof VasIdentifierPsiElement)) return null;
        if (VasSymbolResolver.findSymbol(element).isPresent()) return element;
        var candidates = VasSymbolResolver.findNavigationDeclarations(element);
        return candidates.size() == 1 && VasSymbolResolver.findSymbol(candidates.getFirst()).isPresent() ? candidates.getFirst() : null;
    }
    @Override public boolean canFindUsages(@NotNull PsiElement element) {
        return target(element) != null;
    }
    @Override public FindUsagesHandler createFindUsagesHandler(@NotNull PsiElement element, boolean forHighlightUsages) {
        PsiElement target = target(element);
        return target != null ? new Handler(target) : null;
    }
    static final class Handler extends FindUsagesHandler {
        Handler(PsiElement element) { super(element); }
        @Override public boolean processElementUsages(@NotNull PsiElement element,
            @NotNull Processor<? super UsageInfo> processor, @NotNull FindUsagesOptions options) {
            return VasUsageSearch.process(element, options.searchScope, usage -> processor.process(new UsageInfo(usage)));
        }
    }
}
