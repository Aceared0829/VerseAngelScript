package com.verseangelscript.rider.diagnostics;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VasDiagnosticRangeTest {
    @Test
    public void preservesWholeIdentifierHighlighting() {
        assertRange("void main() { missing_name2(); }", 19, 14, 27);
        assertRange("left+right", 5, 4, 5);
    }

    @Test
    public void convertsColumnsAfterCjkAndEmoji() {
        // Byte columns verified with vasbuild's No matching symbol diagnostics.
        assertRange("void main() { /*漢字*/ missing(); }", 26, 21, 28);
        assertRange("void main() { /*😀*/ missing(); }", 24, 21, 28);
        assertRange("\t/*漢字😀*/\tmissing();", 17, 10, 17);
    }

    @Test
    public void countsTabsAsSingleBytes() {
        assertRange("\t\tmissing", 3, 2, 9);
        assertRange("\t\tmissing", 1, 2, 9);
    }

    @Test
    public void countsTwoByteCharactersAndCombiningMarks() {
        String text = "/*é e\u0301*/ missing";
        assertToken(text, "missing");
    }

    @Test
    public void keepsBmpAndSupplementaryLettersInIdentifierRanges() {
        assertRange("ab漢字_2", 4, 0, 6);
        // U+10400 DESERET CAPITAL LETTER LONG I is a supplementary letter.
        assertRange("x𐐀_2", 3, 0, 5);
        assertRange("x𐐀_2", 6, 0, 5);
    }

    @Test
    public void floorsColumnsInsideUtf8SequencesToCodePointBoundaries() {
        for (int column = 2; column <= 5; column++) {
            assertRange("(😀)", column, 1, 3);
        }
        for (int column = 2; column <= 4; column++) {
            assertRange("(漢)", column, 1, 2);
        }
        assertRange("(😀)", 6, 3, 4);
    }

    @Test
    public void highlightsWholeSupplementaryPunctuation() {
        assertRange("😀!", 1, 0, 2);
    }

    @Test
    public void countsBomOnlyWhenItIsInSubmittedText() {
        String withBom = "\uFEFFvoid main() { /*漢字😀*/ missing(); }";
        assertRange(withBom, 33, 24, 31);
        assertToken(withBom.substring(1), "missing");
        assertRange("\uFEFFmissing", 1, 0, 1);
        assertRange("\uFEFFmissing", 4, 1, 8);
    }

    @Test
    public void usesTheProvidedLineBoundsWithoutIncludingCrLf() {
        String text = "ignored\r\n\t/*漢字😀*/\tmissing();\r\nlast";
        int start = text.indexOf('\t');
        int end = text.indexOf('\r', start);
        assertEquals(new VasDiagnosticRange.Range(start + 10, start + 17),
            VasDiagnosticRange.forLine(text, start, end, 17));
        assertEquals(new VasDiagnosticRange.Range(end - 1, end),
            VasDiagnosticRange.forLine(text, start, end, Integer.MAX_VALUE));

        String normalized = text.replace("\r\n", "\n");
        int normalizedStart = normalized.indexOf('\t');
        assertEquals(new VasDiagnosticRange.Range(normalizedStart + 10, normalizedStart + 17),
            VasDiagnosticRange.forLine(normalized, normalizedStart, normalized.indexOf('\n', normalizedStart), 17));
    }

    @Test
    public void skipsWhitespaceToNextToken() {
        assertRange(" \t  missing();", 2, 4, 11);
        assertRange("\u2003missing", 2, 1, 8);
    }

    @Test
    public void fallsBackToLastCodePointAtEndOfNonemptyLine() {
        assertRange("name", 5, 3, 4);
        assertRange("😀", 5, 0, 2);
        assertRange("漢", 100, 0, 1);
        assertRange("name \t", 5, 5, 6);
        assertRange(" \t ", 1, 2, 3);
    }

    @Test
    public void returnsEmptyRangeForEmptyDocumentOrLine() {
        assertRange("", 1, 0, 0);
        assertRange("", Integer.MAX_VALUE, 0, 0);
        assertEquals(new VasDiagnosticRange.Range(2, 2),
            VasDiagnosticRange.forLine("x\n", 2, 2, 1));
        assertEquals(new VasDiagnosticRange.Range(2, 2),
            VasDiagnosticRange.forLine("x\n\ny", 2, 2, 500));
    }

    @Test
    public void clampsInvalidAndOversizedColumnsWithoutOverflow() {
        for (int column : new int[] {Integer.MIN_VALUE, -1, 0, 1}) {
            assertRange("😀!", column, 0, 2);
        }
        assertRange("a😀", Integer.MAX_VALUE, 1, 3);
    }

    @Test
    public void neverSplitsSurrogatePairsOrEscapesLineBounds() {
        for (String line : new String[] {"", "abc", "漢字", "😀", "𐐀_x 😀 \t", " \t", "\uFEFFé\u0301😀"}) {
            String text = "prior\n" + line + "\nnext";
            int lineStart = 6;
            int lineEnd = lineStart + line.length();
            int byteLength = line.getBytes(StandardCharsets.UTF_8).length;
            for (int column = -2; column <= byteLength + 3; column++) {
                VasDiagnosticRange.Range range = VasDiagnosticRange.forLine(text, lineStart, lineEnd, column);
                assertTrue(range.startOffset() >= lineStart);
                assertTrue(range.endOffset() <= lineEnd);
                assertTrue(range.startOffset() <= range.endOffset());
                assertFalse(splitsSurrogatePair(text, range.startOffset()));
                assertFalse(splitsSurrogatePair(text, range.endOffset()));
            }
        }
    }

    private static void assertToken(String text, String token) {
        int start = text.indexOf(token);
        int column = text.substring(0, start).getBytes(StandardCharsets.UTF_8).length + 1;
        assertRange(text, column, start, start + token.length());
    }

    private static void assertRange(String text, int column, int start, int end) {
        assertEquals(new VasDiagnosticRange.Range(start, end),
            VasDiagnosticRange.forLine(text, 0, text.length(), column));
    }

    private static boolean splitsSurrogatePair(String text, int offset) {
        return offset > 0 && offset < text.length()
            && Character.isHighSurrogate(text.charAt(offset - 1))
            && Character.isLowSurrogate(text.charAt(offset));
    }
}
