package com.verseangelscript.rider.index;

import org.jetbrains.annotations.NotNull;

public record VasUsageContext(int argumentCount, @NotNull String qualifier) {
    public static final VasUsageContext PLAIN = new VasUsageContext(-1, "");
}
