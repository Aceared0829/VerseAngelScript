package com.verseangelscript.rider;

import com.intellij.lang.Language;
import org.jetbrains.annotations.NotNull;

public final class VasLanguage extends Language {
    public static final VasLanguage INSTANCE = new VasLanguage();

    private VasLanguage() {
        super("VAS");
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Verse AngelScript";
    }
}
