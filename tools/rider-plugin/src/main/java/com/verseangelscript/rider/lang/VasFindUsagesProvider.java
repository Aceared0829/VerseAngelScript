package com.verseangelscript.rider.lang;

import com.intellij.lang.cacheBuilder.DefaultWordsScanner;
import com.intellij.lang.cacheBuilder.WordsScanner;
import com.intellij.lang.findUsages.FindUsagesProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.TokenSet;
import com.verseangelscript.rider.VasLanguage;
import com.verseangelscript.rider.index.VasSymbolResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class VasFindUsagesProvider implements FindUsagesProvider {
    @Override
    public @Nullable WordsScanner getWordsScanner() {
        return new DefaultWordsScanner(
            new VasLexer(),
            TokenSet.create(VasTypes.IDENTIFIER),
            TokenSet.create(VasTypes.COMMENT),
            TokenSet.create(VasTypes.STRING)
        );
    }

    @Override
    public boolean canFindUsagesFor(@NotNull PsiElement element) {
        return element.getLanguage().isKindOf(VasLanguage.INSTANCE)
            && VasSymbolResolver.findSymbol(element).isPresent();
    }

    @Override
    public @Nullable String getHelpId(@NotNull PsiElement element) {
        return null;
    }

    @Override
    public @NotNull String getType(@NotNull PsiElement element) {
        return VasSymbolResolver.findSymbol(element)
            .map(symbol -> symbol.kind().displayName())
            .orElse("symbol");
    }

    @Override
    public @NotNull String getDescriptiveName(@NotNull PsiElement element) {
        return element.getText();
    }

    @Override
    public @NotNull String getNodeText(@NotNull PsiElement element, boolean useFullName) {
        return element.getText();
    }
}
