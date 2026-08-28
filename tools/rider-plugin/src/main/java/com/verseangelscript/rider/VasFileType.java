package com.verseangelscript.rider;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

public final class VasFileType extends LanguageFileType {
    public static final VasFileType INSTANCE = new VasFileType();

    private VasFileType() {
        super(VasLanguage.INSTANCE);
    }

    @Override
    public @NotNull @NonNls String getName() {
        return "VAS";
    }

    @Override
    public @NotNull @Nls String getDescription() {
        return "Verse AngelScript source file";
    }

    @Override
    public @NotNull @NonNls String getDefaultExtension() {
        return "vas";
    }

    @Override
    public Icon getIcon() {
        return VasIcons.FILE;
    }
}
