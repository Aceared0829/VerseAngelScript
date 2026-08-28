package com.verseangelscript.rider.lang;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.impl.source.tree.LeafElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

public final class VasIdentifierPsiElement extends LeafPsiElement
    implements PsiNameIdentifierOwner {
    public VasIdentifierPsiElement(@NotNull IElementType type, @NotNull CharSequence text) {
        super(type, text);
    }

    @Override
    public @NotNull PsiElement getNameIdentifier() {
        return this;
    }

    @Override
    public @NotNull PsiElement setName(@NotNull String name) {
        LeafElement replacement = replaceWithText(name);
        return replacement.getPsi();
    }
}
