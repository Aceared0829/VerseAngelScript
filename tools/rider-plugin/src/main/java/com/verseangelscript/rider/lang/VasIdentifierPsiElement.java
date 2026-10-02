package com.verseangelscript.rider.lang;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiCheckedRenameElement;
import com.intellij.psi.ContributedReferenceHost;
import com.intellij.psi.PsiNameIdentifierOwner;
import com.intellij.psi.PsiReference;
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry;
import com.intellij.psi.impl.source.tree.LeafElement;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class VasIdentifierPsiElement extends LeafPsiElement
    implements PsiNameIdentifierOwner, PsiCheckedRenameElement, ContributedReferenceHost {
    public VasIdentifierPsiElement(@NotNull IElementType type, @NotNull CharSequence text) {
        super(type, text);
    }

    @Override
    public @NotNull PsiElement getNameIdentifier() {
        return this;
    }

    @Override
    public @NotNull String getName() {
        // ReferencesSearch uses the declaration name to schedule its word search.
        return getText();
    }

    @Override
    public PsiReference @NotNull [] getReferences() {
        return ReferenceProvidersRegistry.getReferencesFromProviders(this);
    }

    @Override
    public @Nullable PsiReference getReference() {
        PsiReference[] references = getReferences();
        return references.length == 1 ? references[0] : null;
    }

    @Override
    public void checkSetName(@NotNull String name) {
        // RenameProcessor preflights PsiCheckedRenameElement before changing any text.
        // Do not repeat the check from setName while a multi-file rename is in progress.
        VasRenameSafety.checkRename(this, name);
    }

    @Override
    public @NotNull PsiElement setName(@NotNull String name) {
        LeafElement replacement = replaceWithText(name);
        return replacement.getPsi();
    }
}
