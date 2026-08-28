package com.verseangelscript.rider.index;

import org.jetbrains.annotations.NotNull;

public record VasSymbol(
    @NotNull String name,
    @NotNull VasSymbolKind kind,
    int offset,
    int braceDepth,
    int scopeStart,
    int scopeEnd,
    boolean projectVisible,
    boolean definition
) {
    public boolean isProjectVisible() {
        return projectVisible;
    }

    public boolean isVisibleAt(int usageOffset) {
        return projectVisible || usageOffset >= scopeStart && usageOffset <= scopeEnd;
    }
}
