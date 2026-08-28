package com.verseangelscript.rider.lang;

import java.util.Map;

public final class VasRuntimeSymbols {
    public static final Map<String, String> ALL = Map.ofEntries(
        Map.entry("print", "void print(const string &in)"),
        Map.entry("getInput", "string getInput()"),
        Map.entry("getCommandLineArgs", "array<string>@ getCommandLineArgs()"),
        Map.entry("exec", "int exec(const string &in)"),
        Map.entry("array", "VAS standard type"),
        Map.entry("dictionary", "VAS standard type"),
        Map.entry("datetime", "VAS standard type")
    );

    private VasRuntimeSymbols() {
    }
}
