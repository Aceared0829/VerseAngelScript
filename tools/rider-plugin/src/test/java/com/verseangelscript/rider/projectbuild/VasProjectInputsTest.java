package com.verseangelscript.rider.projectbuild;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public final class VasProjectInputsTest {
    @Test public void mapsExactBytePointsWithoutInventingRanges() {
        String source = "void main() {\n\t/*漢字😀*/\tmissing();\n}";
        assertEquals(source.indexOf("missing"), VasProjectInputs.offset(source, 2, 17));
        assertEquals(-1, VasProjectInputs.offset(source, 0, 0));
        assertEquals(-1, VasProjectInputs.offset(source, 2, 0));
        assertEquals(-1, VasProjectInputs.offset(source, 2, 5));
        assertEquals(-1, VasProjectInputs.offset(source, 40, 1));
        assertEquals(-1, VasProjectInputs.offset(source, 2, 1000));
    }
    @Test public void accountsForDiskBomAndCrLfInEditorCoordinates() {
        String source = "\uFEFFvoid main() {\r\n\t/*漢字😀*/\tmissing();\r\n}";
        String editor = source.substring(1).replace("\r\n", "\n");
        assertEquals(editor.indexOf("missing"), VasProjectInputs.offset(source, 2, 17));
        assertEquals(0, VasProjectInputs.offset(source, 1, 4));
    }
    @Test public void invalidSourceBytesHaveNoUnicodeLocation() throws Exception {
        Path file = Files.createTempFile("vas-input-", ".vas");
        try {
            Files.write(file, new byte[]{(byte)0xff});
            var snapshot = VasProjectInputs.capture(file);
            assertNull(snapshot.text());
            assertTrue(snapshot.unchanged(true));
            Files.writeString(file, "replacement");
            assertFalse(snapshot.unchanged(true));
        } finally { Files.deleteIfExists(file); }
    }
    @Test public void capturesAbsentCandidatesWithoutPretendingTheyAreSources() throws Exception {
        Path directory = Files.createTempDirectory("vas-missing-");
        Path file = directory.resolve("candidate.vas");
        try {
            var snapshot = VasProjectInputs.capture(file);
            assertNull(snapshot.digest());
            assertTrue(snapshot.unchanged(true));
            Files.writeString(file, "void main() {}");
            assertFalse(snapshot.unchanged(true));
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
    }
}
