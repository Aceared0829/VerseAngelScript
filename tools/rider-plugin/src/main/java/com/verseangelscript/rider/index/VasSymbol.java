package com.verseangelscript.rider.index;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public record VasSymbol(
    @NotNull String name,
    @NotNull VasSymbolKind kind,
    int offset,
    int braceDepth,
    int scopeStart,
    int scopeEnd,
    boolean projectVisible,
    boolean definition,
    @NotNull String container,
    @NotNull String declaredType,
    int parameterCount,
    int requiredParameterCount,
    @NotNull List<String> baseTypes
) {
    public boolean isProjectVisible() {
        return projectVisible;
    }

    public boolean isVisibleAt(int usageOffset) {
        return projectVisible || usageOffset >= Math.max(offset, scopeStart) && usageOffset <= scopeEnd;
    }

    public @NotNull String qualifiedName() {
        return container.isEmpty() ? name : container + "::" + name;
    }

    public @NotNull String signature() {
        return kind == VasSymbolKind.FUNCTION
            ? qualifiedName() + "/" + parameterCount
            : qualifiedName();
    }
}
