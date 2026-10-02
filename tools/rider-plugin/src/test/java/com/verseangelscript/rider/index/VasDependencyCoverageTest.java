package com.verseangelscript.rider.index;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VasDependencyCoverageTest {
    @Test
    public void siblingSectionsShareTheirKnownCompilationRoot() {
        assertTrue(VasDependencyCoverage.haveCommonRoot("api", "consumer",
            Map.of("root", List.of("api", "consumer"), "api", List.of(), "consumer", List.of())));
    }

    @Test
    public void nestedAndCyclicClosuresKeepCommonMembership() {
        assertTrue(VasDependencyCoverage.haveCommonRoot("api", "consumer",
            Map.of("root", List.of("bridge", "api", "consumer"), "bridge", List.of("root", "api", "consumer"))));
    }

    @Test
    public void theRootItselfIsASectionOfItsCompilationUnit() {
        assertTrue(VasDependencyCoverage.haveCommonRoot("root", "consumer", Map.of("root", List.of("consumer"))));
        assertTrue(VasDependencyCoverage.haveCommonRoot("api", "root", Map.of("root", List.of("api"))));
    }

    @Test
    public void sharedDependenciesDoNotMakeSeparateConsumersSiblings() {
        assertFalse(VasDependencyCoverage.haveCommonRoot("one", "two",
            Map.of("one", List.of("utility"), "two", List.of("utility"))));
        assertFalse(VasDependencyCoverage.haveCommonRoot("api", "unrelated",
            Map.of("root", List.of("api"), "otherRoot", List.of("unrelated"))));
    }
}
