package com.verseangelscript.rider.lang;

import com.intellij.lang.ASTFactory;
import com.intellij.psi.impl.source.tree.LeafElement;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class VasAstFactory extends ASTFactory {
    @Override
    public @Nullable LeafElement createLeaf(
        @NotNull IElementType type,
        @NotNull CharSequence text
    ) {
        return type == VasTypes.IDENTIFIER
            ? new VasIdentifierPsiElement(type, text)
            // Returning null is the ASTFactory extension protocol: IntelliJ will
            // create the platform-default leaf for token types we do not own.
            : null;
    }
}
