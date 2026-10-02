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
}
