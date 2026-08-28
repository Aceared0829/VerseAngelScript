package com.verseangelscript.rider.index;

import org.jetbrains.annotations.NotNull;

public record VasSymbol(
    @NotNull String name,
    @NotNull VasSymbolKind kind,
    int offset,
    int braceDepth
) {
    public boolean isProjectVisible() {
        return kind != VasSymbolKind.VARIABLE || braceDepth == 0;
    }
}
