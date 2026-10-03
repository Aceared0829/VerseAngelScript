package com.verseangelscript.rider.projectbuild;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

public final class VasProjectProtocolTest {
    private static final String ROOT = "/work/space & café";
    private static final String PROJECT = ROOT + "/vas-project.json";
    private static final String ENTRY = ROOT + "/src/main.vas";
    private static final String CONFIG = ROOT + "/host/api.txt";
    private static final String OUTPUT = ROOT + "/out/main.vasbc";

    @Test public void describesImmutableCompilerOwnedIdentitiesAndAdditiveFields() {
        JsonObject value = descriptor();
        value.add("future", JsonParser.parseString("{\"nested\":[true,null,\"🦋\"]}"));
        VasProjectProtocol.Descriptor result = describe(value);
        assertEquals(PROJECT, result.project());
        assertEquals(ROOT, result.root());
        assertEquals(Integer.valueOf(1), result.schemaVersion());
        assertFalse(result.legacy());
        assertEquals(new VasProjectProtocol.Unit("game", ENTRY, CONFIG, OUTPUT), result.units().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> result.units().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.warnings().add("mutable"));
    }

    @Test public void acceptsLegacyMainAndSurfacesCompilerWarning() {
        JsonObject value = descriptor();
        value.addProperty("legacyProject", true);
        value.add("projectSchemaVersion", com.google.gson.JsonNull.INSTANCE);
        unit(value).addProperty("id", "main");
        JsonObject warning = new JsonObject();
        warning.addProperty("code", "legacy_project");
        warning.addProperty("message", "Legacy builder and runner are ignored.");
        value.getAsJsonArray("warnings").add(warning);
        VasProjectProtocol.Descriptor result = describe(value);
        assertTrue(result.legacy());
        assertNull(result.schemaVersion());
        assertEquals(List.of("Legacy builder and runner are ignored."), result.warnings());
    }

    @Test public void surfacesNativeFailedDescriptorExplanation() {
        JsonObject value = failedDescriptor();
        VasProjectProtocol.ProtocolException error = assertThrows(VasProjectProtocol.ProtocolException.class,
            () -> VasProjectProtocol.describe(terminated(value), 255));
        assertEquals("/compilationUnits/0/entry: Expected a portable path", error.getMessage());
    }

    @Test public void rejectsMalformedFailedDescriptorAndExitDisagreement() {
        JsonObject failed = failedDescriptor();
        failed.getAsJsonArray("errors").get(0).getAsJsonObject().addProperty("row", "1");
        rejectsDescriptor(failed, 1);
        rejectsDescriptor(failedDescriptor(), 0);
        rejectsDescriptor(descriptor(), 1);
        JsonObject successfulWithError = descriptor();
        successfulWithError.add("errors", failedDescriptor().get("errors"));
        rejectsDescriptor(successfulWithError, 0);
    }

    @Test public void rejectsDescriptorMissingLfMultipleLinesCrLfAndBom() {
        String wire = descriptor().toString();
        for (String bad : List.of(wire, wire + "\n\n", wire + "\n{}\n", wire + "\r\n", "\ufeff" + wire + "\n")) {
            assertThrows(bad, VasProjectProtocol.ProtocolException.class, () -> VasProjectProtocol.describe(bytes(bad), 0));
        }
    }

    @Test public void rejectsDescriptorResourceLimitAndInvalidWireUtf8() {
        byte[] oversized = new byte[VasProjectProtocol.MAX_DESCRIPTOR_BYTES + 1];
        oversized[oversized.length - 1] = '\n';
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> VasProjectProtocol.describe(oversized, 0));
        assertThrows(VasProjectProtocol.ProtocolException.class,
            () -> VasProjectProtocol.describe(new byte[] {'{', (byte) 0xff, '}', '\n'}, 0));
    }

    @Test public void rejectsDuplicateJsonKeysIncludingEscapedEquivalentAndUnknownObjects() {
        String wire = descriptor().toString();
        for (String addition : List.of("\"version\":1,", "\"\\u0076ersion\":1,", "\"extra\":{\"a\":1,\"\\u0061\":2},")) {
            String bad = "{" + addition + wire.substring(1) + "\n";
            assertThrows(VasProjectProtocol.ProtocolException.class, () -> VasProjectProtocol.describe(bytes(bad), 0));
        }
    }

    @Test public void rejectsUnpairedUnicodeEscapesAndDeepAdditiveValues() {
        String wire = descriptor().toString();
        for (String badValue : List.of("\"\\ud800\"", "\"\\udc00\"", "\"\\ud800x\"", "[".repeat(70) + "0" + "]".repeat(70))) {
            String bad = "{\"extra\":" + badValue + "," + wire.substring(1) + "\n";
            assertThrows(VasProjectProtocol.ProtocolException.class, () -> VasProjectProtocol.describe(bytes(bad), 0));
        }
    }

    @Test public void rejectsNonJsonLeniency() {
        for (String bad : List.of("{'protocol':'vas-project'}\n", "{unquoted:1}\n", "/*comment*/{}\n", "{\"x\":NaN}\n", "{} trailing\n")) {
            assertThrows(VasProjectProtocol.ProtocolException.class, () -> VasProjectProtocol.describe(bytes(bad), 0));
        }
    }

    @Test public void rejectsUnsupportedProtocolsAndWrongScalarTypes() {
        JsonObject value = descriptor();
        value.addProperty("version", 2);
        assertTrue(rejectsDescriptor(value, 0).getMessage().contains("supporting native project protocol v1"));
        value = descriptor(); value.addProperty("success", "true"); rejectsDescriptor(value, 0);
        value = descriptor(); value.addProperty("version", "1"); rejectsDescriptor(value, 0);
        value = descriptor(); value.addProperty("version", 1.0); rejectsDescriptor(value, 0);
        value = descriptor(); value.addProperty("legacyProject", 0); rejectsDescriptor(value, 0);
        value = descriptor(); value.addProperty("projectSchemaVersion", 2); rejectsDescriptor(value, 0);
    }

    @Test public void rejectsMissingEmptyDuplicateAndInvalidUnitIds() {
        JsonObject value = descriptor(); value.getAsJsonArray("compilationUnits").remove(0); rejectsDescriptor(value, 0);
        value = descriptor(); value.getAsJsonArray("compilationUnits").add(unit(value).deepCopy()); rejectsDescriptor(value, 0);
        for (String id : List.of("", ".game", "a/b", "two units", "x".repeat(65))) {
            value = descriptor(); unit(value).addProperty("id", id); rejectsDescriptor(value, 0);
        }
        value = descriptor(); unit(value).remove("hostApi"); rejectsDescriptor(value, 0);
        value = descriptor();
        for (int i = 1; i <= 256; i++) {
            JsonObject another = unit(value).deepCopy();
            another.addProperty("id", "u" + i); another.addProperty("output", ROOT + "/out/" + i);
            value.getAsJsonArray("compilationUnits").add(another);
        }
        rejectsDescriptor(value, 0);
    }

    @Test public void validatesAbsoluteNormalizedDescriptorPathsAndRootRelationship() {
        for (String path : List.of("relative.vas", ROOT + "/../main.vas", ROOT + "//main.vas", ROOT + "/./main.vas",
            ROOT + "/main.vas/", ROOT + "/main\\x.vas", ROOT + "/bad\u0000.vas", "/other/main.vas", ROOT + "/main.as")) {
            JsonObject value = descriptor(); unit(value).addProperty("entry", path); rejectsDescriptor(value, 0);
        }
        JsonObject value = descriptor(); value.addProperty("projectRoot", "/other"); rejectsDescriptor(value, 0);
    }

    @Test public void acceptsWindowsAndUncAndFilesystemRootIdentitiesOnAnyTestHost() {
        for (String root : List.of("C:/work", "C:/", "//server/share/work", "/")) {
            JsonObject value = descriptor();
            String prefix = root.endsWith("/") ? root : root + "/";
            value.addProperty("project", prefix + "vas-project.json"); value.addProperty("projectRoot", root);
            unit(value).addProperty("entry", prefix + "main.vas");
            unit(value).getAsJsonObject("hostApi").addProperty("config", prefix + "api.txt");
            unit(value).addProperty("output", prefix + "main.vasbc");
            assertEquals(root, describe(value).root());
        }
    }

    @Test public void rejectsDuplicateOutputWithWindowsCaseComparison() {
        JsonObject value = descriptor();
        String wire = value.toString().replace(ROOT, "C:/work");
        value = JsonParser.parseString(wire).getAsJsonObject();
        JsonObject second = unit(value).deepCopy();
        second.addProperty("id", "editor"); second.addProperty("output", "C:/work/out/MAIN.VASBC");
        value.getAsJsonArray("compilationUnits").add(second);
        rejectsDescriptor(value, 0);
    }

    @Test public void reportsSuccessOnlyAfterMatchingTerminalAndExit() {
        VasProjectProtocol.Report report = report();
        report.acceptLine(line(start()));
        report.acceptLine(line(loaded(2, ENTRY, true)));
        report.acceptLine(line(result(3, true, "output", true)));
        assertFalse(report.complete()); assertFalse(report.success()); assertFalse(report.dependenciesComplete());
        report.finish(0);
        assertTrue(report.complete()); assertTrue(report.success()); assertTrue(report.dependenciesComplete());
        assertEquals("output", report.phase()); assertEquals(ROOT, report.cwd());
        assertEquals(Set.of(ENTRY), report.observedPaths());
        assertThrows(UnsupportedOperationException.class, () -> report.observedPaths().clear());
    }

    @Test public void acceptsFailedBuildWithCompleteDiscoveryAndNonzeroExit() {
        VasProjectProtocol.Report report = report(); report.acceptLine(line(start()));
        report.acceptLine(line(result(2, false, "compile", true))); report.finish(1);
        assertTrue(report.complete()); assertFalse(report.success()); assertTrue(report.dependenciesComplete());
    }

    @Test public void treatsSignedWindowsFailureExitAsAnOrdinaryNonzeroExit() {
        VasProjectProtocol.Report report = started();
        report.acceptLine(line(result(2, false, "compile", true))); report.finish(-1);
        assertTrue(report.complete()); assertFalse(report.success()); assertTrue(report.dependenciesComplete());
        var failure = rejectsDescriptor(failedDescriptor(), -1);
        assertEquals("/compilationUnits/0/entry: Expected a portable path", failure.getMessage());
        VasProjectProtocol.Report disagreement = started();
        disagreement.acceptLine(line(result(2, true, "output", true)));
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> disagreement.finish(-1));
        rejectsDescriptor(descriptor(), -1);
    }

    @Test public void exposesOnlyCurrentObservationAndWhetherNativeBytesWereLoaded() {
        VasProjectProtocol.Report report = started();
        assertNull(report.lastObserved()); assertFalse(report.lastObservationLoaded());
        report.acceptLine(line(attempt(2, ENTRY, "other.vas", ROOT + "/other.vas")));
        assertEquals(ROOT + "/other.vas", report.lastObserved().display()); assertFalse(report.lastObservationLoaded());
        report.acceptLine(line(loaded(3, ROOT + "/other.vas", true)));
        assertEquals(ROOT + "/other.vas", report.lastObserved().display()); assertTrue(report.lastObservationLoaded());
        report.acceptLine(line(includeResult(4, 2, "loaded")));
        assertNull(report.lastObserved()); assertFalse(report.lastObservationLoaded());
        report.acceptLine(line(diagnostic(5, ENTRY, 1, 1)));
        assertNull(report.lastObserved()); assertFalse(report.lastObservationLoaded());
    }

    @Test public void acceptsPartialConfigFailureWithoutAuthoritativeDependencies() {
        VasProjectProtocol.Report report = report(); report.acceptLine(line(start()));
        report.acceptLine(line(result(2, false, "config", false))); report.finish(255);
        assertTrue(report.complete()); assertFalse(report.success()); assertFalse(report.dependenciesComplete());
    }

    @Test public void keepsExactDiagnosticBytePositionsAndUnknownOrSignedPositions() {
        VasProjectProtocol.Report report = report(); report.acceptLine(line(start()));
        JsonObject diagnostic = diagnostic(2, ENTRY, 3, 9); diagnostic.addProperty("message", "tab\t emoji 🦋\nNUL \u0000");
        report.acceptLine(line(diagnostic));
        report.acceptLine(line(diagnostic(3, CONFIG, 4, 0)));
        report.acceptLine(line(diagnostic(4, "", -1, 0)));
        List<VasProjectProtocol.Diagnostic> values = report.diagnostics();
        assertEquals(3, values.get(0).row()); assertEquals(9, values.get(0).column()); assertEquals(ENTRY, values.get(0).section());
        assertTrue(values.get(0).bindable()); assertEquals(diagnostic.get("message").getAsString(), values.get(0).message());
        assertEquals(0, values.get(1).column()); assertEquals(-1, values.get(2).row()); assertFalse(values.get(2).bindable());
        assertThrows(UnsupportedOperationException.class, () -> values.clear());
    }

    @Test public void keepsInvalidSourceEncodingSeparateFromPathEncoding() {
        VasProjectProtocol.Report report = report(); report.acceptLine(line(start()));
        report.acceptLine(line(loaded(2, ENTRY, false)));
        report.acceptLine(line(diagnostic(3, ENTRY, 3, 9)));
        assertEquals(Set.of(ENTRY), report.invalidUtf8Sources());
        assertEquals(Set.of(ENTRY), report.observedPaths());
        assertTrue(report.diagnostics().getFirst().bindable());
        assertEquals(9, report.diagnostics().getFirst().column());
    }

    @Test public void neverBindsReplacementFilenameAndRetainsDistinctRawIdentities() {
        VasProjectProtocol.Report report = report(); report.acceptLine(line(start()));
        String display = "/work/\ufffd.vas";
        JsonObject first = loaded(2, display, false); invalid(first, "section", "2f776f726b2fff2e766173");
        JsonObject second = loaded(3, display, true); invalid(second, "section", "2f776f726b2ffe2e766173");
        JsonObject diagnostic = diagnostic(4, display, 1, 1); invalid(diagnostic, "section", "2f776f726b2fff2e766173");
        report.acceptLine(line(first)); report.acceptLine(line(second)); report.acceptLine(line(diagnostic));
        assertEquals(2, report.observed().size()); assertTrue(report.observedPaths().isEmpty());
        assertTrue(report.invalidUtf8Sources().isEmpty()); assertEquals(1, report.invalidUtf8SourceIdentities().size());
        assertFalse(report.diagnostics().getFirst().bindable());
    }

    @Test public void literalReplacementCharacterIsValidAndBindable() {
        VasProjectProtocol.Report report = report(); report.acceptLine(line(start()));
        report.acceptLine(line(loaded(2, "/work/\ufffd.vas", true)));
        report.acceptLine(line(diagnostic(3, "/work/\ufffd.vas", 1, 1)));
        assertEquals(Set.of("/work/\ufffd.vas"), report.observedPaths()); assertTrue(report.diagnostics().getFirst().bindable());
    }

    @Test public void metadataMatchesNativeOneReplacementPerInvalidByte() {
        VasProjectProtocol.Report report = report(); report.acceptLine(line(start()));
        JsonObject diagnostic = diagnostic(2, "", 0, 0);
        diagnostic.addProperty("message", "🦋\ufffd\ufffd\ufffd\ufffd\ufffd");
        invalid(diagnostic, "message", "f09fa68be282eda080");
        report.acceptLine(line(diagnostic));
        assertEquals("🦋\ufffd\ufffd\ufffd\ufffd\ufffd", report.diagnostics().getFirst().message());
    }

    @Test public void rejectsMissingDuplicateMismatchedAndValidRawByteMetadata() {
        for (int mode = 0; mode < 7; mode++) {
            VasProjectProtocol.Report report = started();
            JsonObject value = loaded(2, "\ufffd", true);
            invalid(value, "section", "ff");
            switch (mode) {
                case 0 -> value.remove("invalidUtf8Fields");
                case 1 -> value.getAsJsonArray("invalidUtf8Fields").add("section");
                case 2 -> value.getAsJsonObject("rawBytes").addProperty("section", "FF");
                case 3 -> value.getAsJsonObject("rawBytes").addProperty("section", "f");
                case 4 -> value.getAsJsonObject("rawBytes").addProperty("extra", "ff");
                case 5 -> value.getAsJsonObject("rawBytes").addProperty("section", "efbfbd");
                case 6 -> value.addProperty("section", "different");
                default -> fail();
            }
            assertThrows("metadata variant " + mode, VasProjectProtocol.ProtocolException.class, () -> report.acceptLine(line(value)));
        }
    }

    @Test public void observesActualIncludeCandidatesAndPairsNestedResults() {
        VasProjectProtocol.Report report = started();
        report.acceptLine(line(attempt(2, ENTRY, "../shared.vas", ROOT + "/shared.vas")));
        report.acceptLine(line(loaded(3, ROOT + "/shared.vas", true)));
        report.acceptLine(line(attempt(4, ROOT + "/shared.vas", "missing.vas", ROOT + "/missing.vas")));
        report.acceptLine(line(includeResult(5, 4, "failed")));
        report.acceptLine(line(includeResult(6, 2, "failed")));
        report.acceptLine(line(result(7, false, "load", false))); report.finish(1);
        assertEquals(Set.of(ROOT + "/shared.vas", ROOT + "/missing.vas"), report.observedPaths());
        assertFalse(report.dependenciesComplete());
    }

    @Test public void allowsNonStackIncludePairingAndNullCandidates() {
        VasProjectProtocol.Report report = started();
        report.acceptLine(line(attempt(2, ENTRY, "a", null)));
        report.acceptLine(line(attempt(3, ENTRY, "b", ROOT + "/b.vas")));
        report.acceptLine(line(includeResult(4, 2, "rejected")));
        report.acceptLine(line(includeResult(5, 3, "skipped")));
        report.acceptLine(line(result(6, false, "load", false))); report.finish(1);
        assertEquals(Set.of(ROOT + "/b.vas"), report.observedPaths());
    }

    @Test public void rejectsOrphanDuplicateAndUnfinishedIncludeResults() {
        VasProjectProtocol.Report orphan = started();
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> orphan.acceptLine(line(includeResult(2, 99, "loaded"))));
        VasProjectProtocol.Report duplicate = started(); duplicate.acceptLine(line(attempt(2, ENTRY, "a", ENTRY)));
        duplicate.acceptLine(line(includeResult(3, 2, "skipped")));
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> duplicate.acceptLine(line(includeResult(4, 2, "skipped"))));
        VasProjectProtocol.Report missing = started(); missing.acceptLine(line(attempt(2, ENTRY, "a", ENTRY)));
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> missing.acceptLine(line(result(3, false, "load", false))));
    }

    @Test public void rejectsEveryChangedStartIdentityAndWrongPositionContract() {
        for (String field : List.of("project", "unit", "entry", "config", "output", "compiler", "positionEncoding")) {
            VasProjectProtocol.Report report = report(); JsonObject value = start(); value.addProperty(field, "changed");
            assertThrows(field, VasProjectProtocol.ProtocolException.class, () -> report.acceptLine(line(value)));
        }
        for (String field : List.of("projectSchemaVersion", "positionBase")) {
            VasProjectProtocol.Report report = report(); JsonObject value = start(); value.addProperty(field, 2);
            assertThrows(field, VasProjectProtocol.ProtocolException.class, () -> report.acceptLine(line(value)));
        }
        VasProjectProtocol.Report report = report(); JsonObject value = start(); value.addProperty("legacyProject", true);
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> report.acceptLine(line(value)));
    }

    @Test public void rejectsUnusableCwdAndPreservesNativeWindowsCwd() {
        for (String cwd : Arrays.asList(null, "relative", "bad\0cwd")) {
            VasProjectProtocol.Report report = report(); JsonObject value = start(); value.addProperty("cwd", cwd);
            assertThrows(VasProjectProtocol.ProtocolException.class, () -> report.acceptLine(line(value)));
        }
        VasProjectProtocol.Report report = report(); JsonObject value = start(); value.addProperty("cwd", "C:\\work\\native");
        report.acceptLine(line(value)); assertEquals("C:\\work\\native", report.cwd());
    }

    @Test public void rejectsSequenceGapDuplicateStartUnknownRecordAndWrongProtocol() {
        VasProjectProtocol.Report missingStart = report();
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> missingStart.acceptLine(line(result(1, false, "arguments", false))));
        VasProjectProtocol.Report gap = started();
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> gap.acceptLine(line(loaded(3, ENTRY, true))));
        VasProjectProtocol.Report twice = started(); JsonObject duplicate = start(); duplicate.addProperty("seq", 2);
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> twice.acceptLine(line(duplicate)));
        VasProjectProtocol.Report unknown = started();
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> unknown.acceptLine(line(record("future_type", 2))));
        VasProjectProtocol.Report protocol = report(); JsonObject start = start(); start.addProperty("version", 2);
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> protocol.acceptLine(line(start)));
    }

    @Test public void rejectsMissingTerminalExitDisagreementAndExtraTerminalData() {
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> report().finish(0));
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> started().finish(0));
        VasProjectProtocol.Report exit = started(); exit.acceptLine(line(result(2, true, "output", true)));
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> exit.finish(1)); assertFalse(exit.success());
        VasProjectProtocol.Report exitZero = started(); exitZero.acceptLine(line(result(2, false, "compile", true)));
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> exitZero.finish(0));
        VasProjectProtocol.Report trailing = started(); trailing.acceptLine(line(result(2, true, "output", true)));
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> trailing.acceptLine(line(result(3, true, "output", true))));
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> trailing.finish(0)); assertFalse(trailing.complete());
    }

    @Test public void rejectsImpossibleTerminalCombinations() {
        for (JsonObject value : List.of(result(2, true, "compile", true), result(2, true, "output", false),
            result(2, false, "arguments", true), result(2, false, "unknown", false))) {
            VasProjectProtocol.Report report = started();
            assertThrows(VasProjectProtocol.ProtocolException.class, () -> report.acceptLine(line(value)));
        }
    }

    @Test public void rejectsReportFramingDuplicateKeysWrongIntegersAndInvalidWireUtf8() {
        for (byte[] raw : List.of(bytes(""), bytes("\ufeff" + start()), bytes(start() + "\r"), bytes(start() + "\n"),
            bytes(start().toString().replace("\"seq\":1", "\"seq\":1,\"\\u0073eq\":1")),
            bytes(start().toString().replace("\"seq\":1", "\"seq\":1.0")), new byte[] {(byte) 0xff})) {
            VasProjectProtocol.Report report = report();
            assertThrows(VasProjectProtocol.ProtocolException.class, () -> report.acceptLine(raw));
        }
        VasProjectProtocol.Report position = started(); JsonObject value = diagnostic(2, ENTRY, 1, 1); value.addProperty("row", 2147483648L);
        assertThrows(VasProjectProtocol.ProtocolException.class, () -> position.acceptLine(line(value)));
    }

    @Test public void resourceFailureIsStickyAndKeepsPartialDependencies() {
        VasProjectProtocol.Report report = started(); report.acceptLine(line(loaded(2, ENTRY, true)));
        byte[] oversized = new byte[VasProjectProtocol.MAX_RECORD_BYTES + 1];
        VasProjectProtocol.ProtocolException first = assertThrows(VasProjectProtocol.ProtocolException.class, () -> report.acceptLine(oversized));
        assertSame(first, assertThrows(VasProjectProtocol.ProtocolException.class, () -> report.finish(0)));
        assertSame(first, assertThrows(VasProjectProtocol.ProtocolException.class, () -> report.acceptLine(line(result(3, true, "output", true)))));
        assertEquals(Set.of(ENTRY), report.observedPaths()); assertFalse(report.complete()); assertNotNull(report.failure());
    }

    @Test public void enforcesTotalReportByteLimitInUtf8Bytes() {
        VasProjectProtocol.Report report = started();
        String padding = "é".repeat(490_000);
        boolean rejected = false;
        for (int sequence = 2; sequence < 80; sequence++) {
            JsonObject value = loaded(sequence, ENTRY, true); value.addProperty("padding", padding);
            try { report.acceptLine(line(value)); }
            catch (VasProjectProtocol.ProtocolException expected) {
                assertTrue(expected.getMessage().contains("total byte limit")); rejected = true; break;
            }
        }
        assertTrue("The 64 MiB report cap must count bytes, not characters", rejected);
        assertFalse(report.complete());
    }

    @Test public void enforcesEventLimitEvenWithSmallRecords() {
        VasProjectProtocol.Report report = started();
        for (int sequence = 2; sequence <= VasProjectProtocol.MAX_EVENTS; sequence++) report.acceptLine(line(loaded(sequence, ENTRY, true)));
        VasProjectProtocol.ProtocolException error = assertThrows(VasProjectProtocol.ProtocolException.class,
            () -> report.acceptLine(line(result(VasProjectProtocol.MAX_EVENTS + 1, true, "output", true))));
        assertTrue(error.getMessage().contains("event limit"));
    }

    private static VasProjectProtocol.Descriptor describe(JsonObject value) { return VasProjectProtocol.describe(terminated(value), 0); }
    private static VasProjectProtocol.ProtocolException rejectsDescriptor(JsonObject value, int exit) {
        return assertThrows(VasProjectProtocol.ProtocolException.class, () -> VasProjectProtocol.describe(terminated(value), exit));
    }
    private static VasProjectProtocol.Report report() {
        VasProjectProtocol.Descriptor descriptor = describe(descriptor());
        return new VasProjectProtocol.Report(descriptor, descriptor.units().getFirst());
    }
    private static VasProjectProtocol.Report started() { VasProjectProtocol.Report report = report(); report.acceptLine(line(start())); return report; }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] line(JsonObject value) { return bytes(value.toString()); }
    private static byte[] terminated(JsonObject value) { return bytes(value + "\n"); }
    private static JsonObject unit(JsonObject value) { return value.getAsJsonArray("compilationUnits").get(0).getAsJsonObject(); }
    private static JsonObject descriptor() {
        JsonObject value = new JsonObject(); value.addProperty("protocol", "vas-project"); value.addProperty("version", 1);
        value.addProperty("success", true); value.addProperty("project", PROJECT); value.addProperty("projectRoot", ROOT);
        value.addProperty("projectSchemaVersion", 1); value.addProperty("legacyProject", false); value.add("name", com.google.gson.JsonNull.INSTANCE);
        JsonObject unit = new JsonObject(); unit.addProperty("id", "game"); unit.addProperty("entry", ENTRY); unit.addProperty("output", OUTPUT);
        JsonObject api = new JsonObject(); api.addProperty("config", CONFIG); unit.add("hostApi", api);
        JsonArray units = new JsonArray(); units.add(unit); value.add("compilationUnits", units);
        value.add("warnings", new JsonArray()); value.add("errors", new JsonArray()); return value;
    }
    private static JsonObject failedDescriptor() {
        JsonObject value = descriptor(); value.addProperty("success", false); value.add("projectSchemaVersion", com.google.gson.JsonNull.INSTANCE);
        value.add("compilationUnits", new JsonArray()); JsonObject error = new JsonObject(); error.addProperty("code", "project_path");
        error.addProperty("field", "/compilationUnits/0/entry"); error.addProperty("message", "Expected a portable path");
        error.addProperty("section", PROJECT); error.addProperty("row", 0); error.addProperty("column", 0);
        error.add("byteOffset", com.google.gson.JsonNull.INSTANCE); value.getAsJsonArray("errors").add(error); return value;
    }
    private static JsonObject record(String type, int seq) {
        JsonObject value = new JsonObject(); value.addProperty("protocol", "vasbuild"); value.addProperty("version", 1);
        value.addProperty("type", type); value.addProperty("seq", seq); value.add("invalidUtf8Fields", new JsonArray());
        value.add("rawBytes", new JsonObject()); return value;
    }
    private static JsonObject start() {
        JsonObject value = record("start", 1); value.addProperty("compiler", "vasbuild"); value.addProperty("compilerVersion", "2.39.0 WIP");
        value.addProperty("positionEncoding", "utf-8-bytes"); value.addProperty("positionBase", 1); value.addProperty("cwd", ROOT);
        value.addProperty("project", PROJECT); value.addProperty("unit", "game"); value.addProperty("entry", ENTRY);
        value.addProperty("config", CONFIG); value.addProperty("output", OUTPUT); value.addProperty("projectSchemaVersion", 1);
        value.addProperty("legacyProject", false); return value;
    }
    private static JsonObject result(int seq, boolean success, String phase, boolean complete) {
        JsonObject value = record("result", seq); value.addProperty("success", success); value.addProperty("phase", phase);
        value.addProperty("dependenciesComplete", complete); return value;
    }
    private static JsonObject loaded(int seq, String section, boolean utf8Valid) {
        JsonObject value = record("section_loaded", seq); value.addProperty("section", section); value.addProperty("utf8Valid", utf8Valid); return value;
    }
    private static JsonObject diagnostic(int seq, String section, int row, int column) {
        JsonObject value = record("diagnostic", seq); value.addProperty("severity", "error"); value.addProperty("message", "Unknown symbol");
        value.addProperty("section", section); value.addProperty("row", row); value.addProperty("column", column); return value;
    }
    private static JsonObject attempt(int seq, String from, String requested, String resolved) {
        JsonObject value = record("include_attempt", seq); value.addProperty("from", from); value.addProperty("requested", requested);
        value.addProperty("resolved", resolved); return value;
    }
    private static JsonObject includeResult(int seq, int attemptSeq, String status) {
        JsonObject value = record("include_result", seq); value.addProperty("attemptSeq", attemptSeq); value.addProperty("status", status); return value;
    }
    private static void invalid(JsonObject value, String field, String hex) {
        value.getAsJsonArray("invalidUtf8Fields").add(field); value.getAsJsonObject("rawBytes").addProperty(field, hex);
    }
}
