package com.verseangelscript.rider.lang;

import org.junit.Test;
import static org.junit.Assert.*;

public final class VasEditingSupportTest {
    @Test public void reportsStructuralErrorsButProtectsStringsCommentsAndHeredocs() {
        assertTrue(VasSyntaxAnnotator.inspect("void Main() { string Text = \"} )\"; /* ] */ }").isEmpty());
        assertTrue(VasSyntaxAnnotator.inspect("string Text = \"\"\"} ] (\n\"\"\";").isEmpty());
        assertTrue(VasSyntaxAnnotator.inspect("void Main(] {}").stream().anyMatch(p -> p.message().contains("Unexpected")));
        assertTrue(VasSyntaxAnnotator.inspect("void Main() {").stream().anyMatch(p -> p.message().equals("Expected '}'")));
        assertTrue(VasSyntaxAnnotator.inspect("string Text = \"oops").stream().anyMatch(p -> p.message().contains("string")));
        assertTrue(VasSyntaxAnnotator.inspect("/* oops").stream().anyMatch(p -> p.message().contains("comment")));
        assertTrue(VasSyntaxAnnotator.inspect("string Text = \"escaped\\\"").stream().anyMatch(p -> p.message().contains("string")));
    }
    @Test public void avoidsPairingInsideProtectedTokensAndDigitSeparators() {
        assertFalse(VasTypedHandler.inCode("// typing {", 10));
        assertFalse(VasTypedHandler.inCode("\"typing {\"", 8));
        assertFalse(VasTypedHandler.inCode("/* typing { */", 10));
        assertTrue(VasTypedHandler.inCode("/* done */", 10));
        assertTrue(VasTypedHandler.inCode("\"done\"", 6));
        assertFalse(VasTypedHandler.isQuoteEnd("\"escaped\\\"", 9, '"'));
        assertTrue(VasTypedHandler.isQuoteEnd("\"done\"", 5, '"'));
    }
    @Test public void selectsInnermostCallAndCountsOnlyItsArguments() {
        String text = "Outer(Inner(1, 2), {3, 4}, \"comma,\", ";
        var call = VasCallSupport.callAt(text, text.length());
        assertEquals("Outer", call.name()); assertEquals(3, call.parameter());
        var nested = VasCallSupport.callAt("Outer(Inner(1, ", 15);
        assertEquals("Inner", nested.name()); assertEquals(1, nested.parameter());
        assertNull(VasCallSupport.callAt("if (true)", 9));
    }
    @Test public void signatureRangesProtectGenericAndDefaultArgumentCommas() {
        var signature = VasCallSupport.signature("Add(array<int>@ Values, int Count = Other(1, 2), string Text = \"a,b\")");
        assertEquals(3, signature.parameters().size());
        int[] last = signature.parameters().get(2);
        assertEquals("string Text = \"a,b\"", signature.label().substring(last[0], last[1]));
    }
}
