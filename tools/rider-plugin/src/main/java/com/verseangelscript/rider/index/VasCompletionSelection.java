package com.verseangelscript.rider.index;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Reuses navigation's scope/member rules without selecting a call overload. */
public final class VasCompletionSelection {
    private VasCompletionSelection() { }

    public static List<VasSymbol> select(List<VasSymbol> local, List<VasSymbol> dependencies,
                                        int offset, VasUsageContext usage) {
        var source = local.stream().map(symbol -> new VasSymbolSelection.Candidate<>(symbol, symbol)).toList();
        var included = dependencies.stream().map(symbol -> new VasSymbolSelection.Candidate<>(symbol, symbol)).toList();
        var context = new VasUsageContext(VasUsageContext.NOT_A_CALL, usage.qualifier(), usage.access(), usage.container());
        LinkedHashSet<String> names = new LinkedHashSet<>();
        local.forEach(symbol -> names.add(symbol.name()));
        dependencies.forEach(symbol -> names.add(symbol.name()));
        List<VasSymbol> result = new ArrayList<>();
        for (String name : names) result.addAll(VasSymbolSelection.select(source, included, name, offset, context));
        return result;
    }
}
