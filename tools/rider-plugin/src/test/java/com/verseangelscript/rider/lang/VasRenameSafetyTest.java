package com.verseangelscript.rider.lang;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VasRenameSafetyTest {
    @Test
    public void acceptsCompilerValidAsciiIdentifierSubset() {
        for (String name : List.of("value", "Value2", "_value", "VALUE_3")) {
            assertTrue(name, VasRenameSafety.isValidNewName(name));
        }
    }

    @Test
    public void rejectsInvalidAndUnverifiedCompilerNames() {
        for (String name : List.of("$renamed", "x$y", "2value", "", "a b", "if", "class", "café", "a\u0000b")) {
            assertFalse(name, VasRenameSafety.isValidNewName(name));
        }
    }
    @Test
    public void dependencyPreflightOnlyConsidersRelevantIdentifierSpellings() {
        assertTrue(VasRenameSafety.containsRelevantIdentifier("bool renamed; if (left && value) {}", "value", "renamed"));
        assertTrue(VasRenameSafety.containsRelevantIdentifier("int renamed;", "value", "renamed"));
        assertTrue(VasRenameSafety.containsRelevantIdentifier("#if FEATURE value;", "value", "renamed"));
        assertFalse(VasRenameSafety.containsRelevantIdentifier(
            "#include \"missing.vas\"\n// value renamed\nstring text = \"value renamed\"; int valueElse;", "value", "renamed"));
        assertFalse(VasRenameSafety.containsRelevantIdentifier("int number = 0b101;", "b101", "renamed"));
    }

}
