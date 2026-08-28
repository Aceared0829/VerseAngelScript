package com.verseangelscript.rider.lang;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import com.verseangelscript.rider.VasFileType;
import com.verseangelscript.rider.VasLanguage;
import org.jetbrains.annotations.NotNull;

public final class VasFile extends PsiFileBase {
    public VasFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, VasLanguage.INSTANCE);
    }

    @Override
    public @NotNull FileType getFileType() {
        return VasFileType.INSTANCE;
    }

    @Override
    public String toString() {
        return "VAS File";
    }
}
