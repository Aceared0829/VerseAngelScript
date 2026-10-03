package com.verseangelscript.rider.projectbuild;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * End-to-end protocol conformance against the real native compiler. Ordinary IDE
 * test runs may omit VAS_TEST_COMPILER; CI must supply the freshly built binary.
 * No installed shell, fake compiler, or embedded runtime is used as a substitute.
 */
public final class VasProjectProtocolCompilerTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private Path compiler;
    private Path root;
    private Path manifest;
    private Path unrelatedCwd;

    @Before public void prepare() throws Exception {
        String configured = System.getenv("VAS_TEST_COMPILER");
        String ci = System.getenv("CI");
        boolean inCi = ci != null && !ci.isBlank() && !ci.equalsIgnoreCase("false") && !ci.equals("0");
        if (inCi) assertTrue("CI must set VAS_TEST_COMPILER to the real native build", configured != null && !configured.isBlank());
        Assume.assumeTrue("Set VAS_TEST_COMPILER for real native compiler protocol tests", configured != null && !configured.isBlank());
        compiler = VasProjectProcess.validateNativeCompiler(configured);
        root = temporary.newFolder("native 文😀 space $value ; & (group) `tick`").toPath().toAbsolutePath();
        unrelatedCwd = temporary.newFolder("unrelated working directory").toPath().toAbsolutePath();
        manifest = root.resolve("vas-project.json");
        write("host/default.txt", "func \"void hostCall()\"\n");
        write("host/alternate.txt", "func \"void anotherHostCall()\"\n");
        write("src/main.vas", "void main() { hostCall(); }\n");
        Files.writeString(manifest, versionedManifest().toString(), StandardCharsets.UTF_8);
    }

    @Test public void descriptorIsReadOnlyAndUnitsSelectDifferentHostConfigAndOutput() throws Exception {
        byte[] originalManifest = Files.readAllBytes(manifest);
        VasProjectProtocol.Descriptor descriptor = describe();
        assertArrayEquals(originalManifest, Files.readAllBytes(manifest));
        assertFalse("Description must not create output parents", Files.exists(root.resolve("out")));
        assertEquals(identity(manifest), descriptor.project());
        assertEquals(identity(root), descriptor.root());
        assertEquals(2, descriptor.units().size());
        var game = descriptor.units().get(0);
        var editor = descriptor.units().get(1);
        assertEquals(game.entry(), editor.entry());
        assertNotEquals(game.config(), editor.config());
        assertNotEquals(game.output(), editor.output());

        VasProjectProtocol.Report successful = build(descriptor, game);
        assertTrue(successful.success());
        assertTrue(successful.dependenciesComplete());
        assertEquals(identity(unrelatedCwd), successful.cwd().replace('\\', '/'));
        assertTrue(Files.size(Path.of(game.output())) > 0);
        byte[] preserved = "preserve failed unit bytecode".getBytes(StandardCharsets.UTF_8);
        Files.write(Path.of(editor.output()), preserved);
        VasProjectProtocol.Report failed = build(descriptor, editor);
        assertFalse(failed.success());
        assertTrue(failed.complete());
        assertTrue(failed.dependenciesComplete());
        assertEquals("compile", failed.phase());
        assertTrue(failed.diagnostics().stream().anyMatch(d -> d.severity().equals("error") && d.message().contains("hostCall")));
        assertArrayEquals(preserved, Files.readAllBytes(Path.of(editor.output())));
        assertArrayEquals(originalManifest, Files.readAllBytes(manifest));
        assertFalse("Literal filename metacharacters must never run commands", Files.exists(root.resolve("must-not-run")));
    }

    @Test public void nestedUnicodeIncludesKeepNativeIdentityAndUtf8ByteDiagnosticPosition() throws Exception {
        write("src/main.vas", "// #include \"commented.vas\"\n#if NEVER_DEFINED\n#include \"inactive.vas\"\n#endif\n#include 'nested/first.vas'\nvoid main() { first(); }\n");
        write("src/nested/first.vas", "#include \"文😀.vas\"\nvoid first() {}\n");
        String prefix = "\t/* 🦋é */ ";
        write("src/nested/文😀.vas", "void broken() {\n" + prefix + "missingSymbol();\n}\n");
        VasProjectProtocol.Descriptor descriptor = describe();
        VasProjectProtocol.Report report = build(descriptor, descriptor.units().getFirst());
        assertFalse(report.success()); assertTrue(report.dependenciesComplete()); assertEquals("compile", report.phase());
        assertEquals(Set.of(identity(root.resolve("src/main.vas")), identity(root.resolve("src/nested/first.vas")),
            identity(root.resolve("src/nested/文😀.vas"))), report.observedPaths());
        var diagnostic = report.diagnostics().stream().filter(d -> d.severity().equals("error") && d.message().contains("missingSymbol"))
            .findFirst().orElseThrow(() -> new AssertionError("Native nested include error missing: " + report.diagnostics()));
        assertTrue(diagnostic.bindable());
        assertEquals(identity(root.resolve("src/nested/文😀.vas")), diagnostic.section());
        assertEquals(2, diagnostic.row());
        assertEquals(prefix.getBytes(StandardCharsets.UTF_8).length + 1, diagnostic.column());
        assertTrue(report.invalidUtf8Sources().isEmpty());
        assertFalse(Files.exists(root.resolve("out")));
    }

    @Test public void legacyDescriptorAndBuildWarnWithoutRewritingOrExecutingMetadata() throws Exception {
        JsonObject legacy = new JsonObject(); legacy.addProperty("name", "Legacy 文😀");
        legacy.addProperty("entry", "src/main.vas"); legacy.addProperty("builderConfig", "host/default.txt");
        legacy.addProperty("bytecodeOutput", "out/legacy.vasbc");
        legacy.addProperty("builder", "touch must-not-run ; & $(must-not-run)");
        legacy.addProperty("runner", "ignored legacy runner ; & $(must-not-run)");
        Files.writeString(manifest, legacy.toString(), StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(manifest);
        VasProjectProtocol.Descriptor descriptor = describe();
        assertTrue(descriptor.legacy()); assertNull(descriptor.schemaVersion());
        assertEquals(1, descriptor.warnings().size());
        assertEquals("main", descriptor.units().getFirst().id());
        VasProjectProtocol.Report report = build(descriptor, descriptor.units().getFirst());
        assertTrue(report.success());
        assertEquals(1, report.diagnostics().stream().filter(d -> d.severity().equals("warning")).count());
        assertArrayEquals(before, Files.readAllBytes(manifest));
        assertFalse(Files.exists(root.resolve("must-not-run")));
    }

    @Test public void malformedManifestProducesReadableFailedDescriptorAndNoOutput() throws Exception {
        Files.writeString(manifest, "{\"schemaVersion\":1,\"compilationUnits\":[", StandardCharsets.UTF_8);
        byte[] original = Files.readAllBytes(manifest);
        VasProjectProcess.Result response = describeResponse();
        assertNotEquals(0, response.exitCode());
        var failure = assertThrows(VasProjectProtocol.ProtocolException.class,
            () -> VasProjectProtocol.describe(response.stdout(), response.exitCode()));
        assertTrue(failure.getMessage(), failure.getMessage().contains("project:"));
        assertTrue(failure.getMessage(), failure.getMessage().contains("parse"));
        assertArrayEquals(original, Files.readAllBytes(manifest));
        assertFalse(Files.exists(root.resolve("out")));
    }

    @Test public void includeReadFailureRetainsCandidateObservationAndPreviousOutput() throws Exception {
        VasProjectProtocol.Descriptor descriptor = describe();
        var unit = descriptor.units().getFirst();
        assertTrue(build(descriptor, unit).success());
        byte[] originalOutput = Files.readAllBytes(Path.of(unit.output()));
        write("src/main.vas", "#include \"nested/missing.vas\"\nvoid main() {}\n");
        VasProjectProtocol.Report report = build(descriptor, unit);
        assertFalse(report.success()); assertFalse(report.dependenciesComplete()); assertEquals("load", report.phase());
        assertEquals(Set.of(identity(root.resolve("src/main.vas")), identity(root.resolve("src/nested/missing.vas"))), report.observedPaths());
        assertArrayEquals(originalOutput, Files.readAllBytes(Path.of(unit.output())));
    }

    @Test public void invalidUtf8SourceIsObservedWithoutPretendingItHasUnicodePositions() throws Exception {
        byte[] prefix = "void main() {}\n// invalid comment byte: ".getBytes(StandardCharsets.UTF_8);
        byte[] source = java.util.Arrays.copyOf(prefix, prefix.length + 2);
        source[prefix.length] = (byte) 0xff; source[prefix.length + 1] = '\n';
        Files.write(root.resolve("src/main.vas"), source);
        VasProjectProtocol.Descriptor descriptor = describe();
        VasProjectProtocol.Report report = build(descriptor, descriptor.units().getFirst());
        assertTrue(report.success());
        assertEquals(Set.of(identity(root.resolve("src/main.vas"))), report.invalidUtf8Sources());
        assertEquals(report.invalidUtf8Sources(), report.observedPaths());
    }

    private VasProjectProtocol.Descriptor describe() throws Exception {
        VasProjectProcess.Result response = describeResponse();
        return VasProjectProtocol.describe(response.stdout(), response.exitCode());
    }

    private VasProjectProcess.Result describeResponse() throws Exception {
        VasProjectProcess.Result response = VasProjectProcess.run(compiler, List.of("--describe-project=json", manifest.toString()),
            unrelatedCwd, 30_000, VasProjectProtocol.MAX_DESCRIPTOR_BYTES, () -> { }, null);
        assertEquals("Descriptor stderr must not become a second protocol channel", "", response.stderr());
        return response;
    }

    private VasProjectProtocol.Report build(VasProjectProtocol.Descriptor descriptor, VasProjectProtocol.Unit unit) throws Exception {
        var report = new VasProjectProtocol.Report(descriptor, unit);
        var response = VasProjectProcess.run(compiler,
            List.of("--report=jsonl", "--project", manifest.toString(), "--unit", unit.id()), unrelatedCwd,
            30_000, VasProjectProtocol.MAX_REPORT_BYTES, () -> { }, report::acceptLine);
        assertEquals("", response.stderr());
        assertEquals("Streaming report must not retain all stdout", 0, response.stdout().length);
        report.finish(response.exitCode());
        return report;
    }

    private void write(String relative, String text) throws IOException {
        Path file = root.resolve(relative); Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String identity(Path path) { return path.toAbsolutePath().normalize().toString().replace('\\', '/'); }
    private static JsonObject versionedManifest() {
        JsonObject manifest = new JsonObject(); manifest.addProperty("schemaVersion", 1); manifest.addProperty("name", "Native Rider protocol contract");
        JsonArray units = new JsonArray(); units.add(unit("game", "host/default.txt", "out/game.vasbc"));
        units.add(unit("editor", "host/alternate.txt", "out/editor.vasbc")); manifest.add("compilationUnits", units); return manifest;
    }
    private static JsonObject unit(String id, String config, String output) {
        JsonObject unit = new JsonObject(); unit.addProperty("id", id); unit.addProperty("entry", "src/main.vas");
        JsonObject host = new JsonObject(); host.addProperty("config", config); unit.add("hostApi", host); unit.addProperty("output", output); return unit;
    }
}
