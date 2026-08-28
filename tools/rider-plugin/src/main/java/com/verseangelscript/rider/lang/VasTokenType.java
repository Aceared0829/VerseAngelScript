package com.verseangelscript.rider.lang;

import com.intellij.psi.tree.IElementType;
import com.verseangelscript.rider.VasLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public final class VasTokenType extends IElementType {
    public VasTokenType(@NotNull @NonNls String debugName) {
        super(debugName, VasLanguage.INSTANCE);
    }

    @Override
    public String toString() {
        return "VAS_" + super.toString();
    }
}
