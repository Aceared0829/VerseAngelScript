package com.verseangelscript.rider.index;

import org.jetbrains.annotations.NotNull;

/** Syntax known at a use site; UNKNOWN arity/qualifiers must never become plain lookup. */
public record VasUsageContext(
    int argumentCount,
    @NotNull String qualifier,
    @NotNull Access access,
    @NotNull String container
) {
    public enum Access { UNQUALIFIED, MEMBER, QUALIFIED, UNSUPPORTED }
    public static final int NOT_A_CALL = -1;
    public static final int UNKNOWN_ARGUMENTS = -2;
    public static final VasUsageContext PLAIN = new VasUsageContext(
        NOT_A_CALL, "", Access.UNQUALIFIED, ""
    );
}
