package com.verseangelscript.rider.lang;

import com.intellij.lang.ASTFactory;
import com.intellij.psi.impl.source.tree.LeafElement;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

public final class VasAstFactory extends ASTFactory {
    @Override
    public @NotNull LeafElement createLeaf(
        @NotNull IElementType type,
        @NotNull CharSequence text
    ) {
        return type == VasTypes.IDENTIFIER
            ? new VasIdentifierPsiElement(type, text)
            : super.createLeaf(type, text);
    }
}
