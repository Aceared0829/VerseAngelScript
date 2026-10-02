package com.verseangelscript.rider.lang;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.usageView.UsageInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Verifies the actual mutation set, including explicitly narrowed rename scopes. */
public final class VasRenamePsiElementProcessor extends RenamePsiElementProcessor {
    @Override
    public boolean canProcessElement(@NotNull PsiElement element) {
        return element instanceof VasIdentifierPsiElement;
    }

    @Override
    public void renameElement(
        @NotNull PsiElement element,
        @NotNull String newName,
        UsageInfo @NotNull [] usages,
        @Nullable RefactoringElementListener listener
    ) {
        List<PsiReference> references = Arrays.stream(usages)
            .map(UsageInfo::getReference).filter(Objects::nonNull).toList();
        // This check precedes the generic processor's first reference/declaration
        // update, so a missing usage never leaves a partially renamed symbol.
        VasRenameSafety.checkRename(element, newName, occurrence -> references.stream().anyMatch(reference ->
            reference.getElement().isEquivalentTo(occurrence) && reference.isReferenceTo(element)
                && reference.getRangeInElement().getStartOffset() == 0
                && reference.getRangeInElement().getEndOffset() == occurrence.getTextLength()));
        super.renameElement(element, newName, usages, listener);
    }
}
