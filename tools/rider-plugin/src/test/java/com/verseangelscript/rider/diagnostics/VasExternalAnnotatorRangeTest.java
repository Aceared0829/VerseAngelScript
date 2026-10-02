package com.verseangelscript.rider.diagnostics;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.impl.DocumentImpl;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Exercises the adapter against real IntelliJ document line offsets. */
public final class VasExternalAnnotatorRangeTest {
    @Test
    public void mapsByteColumnsAgainstNormalizedDocumentText() {
        String diskText = "void main() {\r\n\t/*漢字😀*/\tmissing();\r\n}";
        String sourceText = StringUtil.convertLineSeparators(diskText);
        Document document = new DocumentImpl(sourceText);
        int start = sourceText.indexOf("missing");
        assertEquals(new TextRange(start, start + 7), VasExternalAnnotator.diagnosticRange(document, 2, 17));
    }

    @Test
    public void usesSubmittedBomWithoutAnExtraDiskAdjustment() {
        Document document = new DocumentImpl("\uFEFFvoid main() { /*漢字😀*/ missing(); }");
        assertEquals(new TextRange(24, 31), VasExternalAnnotator.diagnosticRange(document, 1, 33));
    }

    @Test
    public void handlesEmptyDocumentAndTrailingEmptyLine() {
        assertEquals(TextRange.EMPTY_RANGE,
            VasExternalAnnotator.diagnosticRange(new DocumentImpl(""), 1, 1));
        assertEquals(new TextRange(2, 2),
            VasExternalAnnotator.diagnosticRange(new DocumentImpl("x\n"), 2, 1));
        assertEquals(new TextRange(2, 2),
            VasExternalAnnotator.diagnosticRange(new DocumentImpl("x\n\ny"), 2, 100));
    }

    @Test
    public void clampsInvalidAndOversizedLinesAndColumns() {
        Document document = new DocumentImpl("😀\nlast\n");
        for (int line : new int[] {Integer.MIN_VALUE, -1, 0, 1}) {
            assertEquals(new TextRange(0, 2), VasExternalAnnotator.diagnosticRange(document, line, 0));
        }
        assertEquals(new TextRange(8, 8),
            VasExternalAnnotator.diagnosticRange(document, Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(new TextRange(6, 7),
            VasExternalAnnotator.diagnosticRange(document, 2, Integer.MAX_VALUE));
    }

    @Test
    public void keepsSupplementaryCharacterIntactAtEof() {
        assertEquals(new TextRange(0, 2),
            VasExternalAnnotator.diagnosticRange(new DocumentImpl("😀"), 1, 5));
    }
}
