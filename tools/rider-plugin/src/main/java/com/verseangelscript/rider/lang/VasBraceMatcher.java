package com.verseangelscript.rider.lang;

import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class VasBraceMatcher implements PairedBraceMatcher {
    private static final BracePair[] PAIRS = {
        new BracePair(VasTypes.LBRACE, VasTypes.RBRACE, true),
        new BracePair(VasTypes.LPAREN, VasTypes.RPAREN, false),
        new BracePair(VasTypes.LBRACKET, VasTypes.RBRACKET, false)
    };

    @Override
    public BracePair @NotNull [] getPairs() {
        return PAIRS;
    }

    @Override
    public boolean isPairedBracesAllowedBeforeType(
        @NotNull IElementType leftBraceType,
        @Nullable IElementType contextType
    ) {
        return true;
    }

    @Override
    public int getCodeConstructStart(
        PsiFile file,
        int openingBraceOffset
    ) {
        return openingBraceOffset;
    }
}
