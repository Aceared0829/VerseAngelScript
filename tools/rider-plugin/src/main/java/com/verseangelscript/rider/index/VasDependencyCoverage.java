package com.verseangelscript.rider.index;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/** Known file membership only; this does not infer a compilation root or bind sibling symbols. */
public final class VasDependencyCoverage {
    private VasDependencyCoverage() { }

    public static <F> boolean haveCommonRoot(
        F declaration, F consumer, Map<F, ? extends Collection<F>> transitiveIncludes
    ) {
        for (Map.Entry<F, ? extends Collection<F>> root : transitiveIncludes.entrySet()) {
            boolean hasDeclaration = Objects.equals(root.getKey(), declaration) || root.getValue().contains(declaration);
            boolean hasConsumer = Objects.equals(root.getKey(), consumer) || root.getValue().contains(consumer);
            if (hasDeclaration && hasConsumer) {
                return true;
            }
        }
        return false;
    }
}
