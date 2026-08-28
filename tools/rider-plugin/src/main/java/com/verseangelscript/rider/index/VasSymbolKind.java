package com.verseangelscript.rider.index;

public enum VasSymbolKind {
    CLASS("class"),
    INTERFACE("interface"),
    ENUM("enum"),
    NAMESPACE("namespace"),
    TYPE_ALIAS("type alias"),
    FUNCTION("function"),
    VARIABLE("variable");

    private final String displayName;

    VasSymbolKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
