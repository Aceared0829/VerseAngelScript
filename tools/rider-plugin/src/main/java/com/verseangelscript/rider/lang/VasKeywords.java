package com.verseangelscript.rider.lang;

import java.util.List;
import java.util.Set;

public final class VasKeywords {
    public static final List<String> ALL = List.of(
        "abstract", "and", "auto", "bool", "break", "case", "cast", "catch",
        "class", "const", "continue", "default", "delete", "do", "double",
        "else", "enum", "explicit", "external", "false", "final", "float",
        "for", "foreach", "from", "funcdef", "function", "get", "if", "import",
        "in", "inout", "int", "int8", "int16", "int32", "int64", "interface",
        "is", "mixin", "namespace", "not", "null", "or", "out", "override",
        "private", "property", "protected", "return", "set", "shared", "super",
        "switch", "this", "true", "try", "typedef", "uint", "uint8", "uint16",
        "uint32", "uint64", "using", "void", "while", "xor"
    );

    // Native as_tokendef.h reserved words, excluding contextual editor keywords.
    public static final Set<String> RESERVED = Set.of(
        "and", "auto", "bool", "break", "case", "cast", "catch", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "false",
        "float", "for", "foreach", "funcdef", "if", "import", "in", "inout",
        "int", "int8", "int16", "int32", "int64", "interface", "is", "mixin",
        "namespace", "not", "null", "or", "out", "private", "protected", "return",
        "switch", "true", "try", "typedef", "uint", "uint8", "uint16", "uint32",
        "uint64", "using", "void", "while", "xor"
    );

    public static final Set<String> SET = Set.copyOf(ALL);

    private VasKeywords() {
    }
}
