package com.verseangelscript.rider.projectbuild;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.ide.impl.TrustedPaths;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import com.jetbrains.rider.test.OpenSolutionParams;
import com.jetbrains.rider.test.annotations.Solution;
import com.jetbrains.rider.test.annotations.TestSettings;
import com.jetbrains.rider.test.enums.BuildTool;
import com.jetbrains.rider.test.enums.Mono;
import com.jetbrains.rider.test.enums.sdk.SdkVersion;
import com.jetbrains.rider.test.junit5.base.PerTestSolutionTestBase;
import com.verseangelscript.rider.build.VasBuildAction;
import com.verseangelscript.rider.build.VasBuildProjectAction;
import com.verseangelscript.rider.build.VasRunAction;
import com.verseangelscript.rider.build.VasSettingsConfigurable;
import com.verseangelscript.rider.build.VasSettingsState;
import com.verseangelscript.rider.build.VasToolchainSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Rider Solution Host + registered action + actual native compiler.
 * Only unit selection, presentation, and a read-only launch observer are hooked.
 * The compiler environment is mandatory: a skipped/absent case is not evidence.
 * Cancellation here covers selection/queued work; native process-tree cancellation
 * is exercised independently by VasProjectProcessTest, not claimed by these cases.
 */
@Solution(name = "vas-navigation", slnName = "VasNavigation.sln")
@TestSettings(buildTool = BuildTool.AUTODETECT, mono = Mono.NONE, sdkVersion = SdkVersion.NONE)
@Tag("season/vas")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
public final class VasRiderProjectBuildIntegrationTest extends PerTestSolutionTestBase {
    private static final String AUDIT = "VAS_RIDER_PROJECT_BUILD_AUDIT_V1 PASS case=";
    private static final String UTF8_SOURCE = "void included() {\n\t/*漢字😀*/\tmissing();\n}\n";
    private static final Duration WAIT = Duration.ofSeconds(40);

    @Override
    public void modifyOpenSolutionParams(OpenSolutionParams parameters) {
        super.modifyOpenSolutionParams(parameters);
        parameters.setWaitForCaches(true);
        parameters.setWaitForSolutionBuilder(true);
        parameters.setRestoreNuGetPackages(false);
    }

    @Test
    void buildsExplicitUnitsWithDistinctHostConfigurations() throws Exception {
        try (Fixture fixture = fixture()) {
            EdtTestUtil.runInEdtAndWait(() -> {
                assertInstanceOf(VasBuildProjectAction.class, ActionManager.getInstance().getAction("VAS.BuildProject"));
                assertInstanceOf(VasBuildAction.class, ActionManager.getInstance().getAction("VAS.BuildCurrentFile"));
                assertInstanceOf(VasRunAction.class, ActionManager.getInstance().getAction("VAS.RunCurrentFile"));
            });
            fixture.open(fixture.decoy);
            fixture.selection = descriptor -> {
                assertFalse(descriptor.legacy());
                assertEquals(List.of("good", "other-host"), descriptor.units().stream().map(VasProjectProtocol.Unit::id).toList());
                assertEquals(descriptor.units().get(0).entry(), descriptor.units().get(1).entry());
                assertNotEquals(descriptor.units().get(0).config(), descriptor.units().get(1).config());
                return "good";
            };
            var good = fixture.build();
            assertTrue(good.success(), good.status());
            assertEquals("good", good.unit());
            assertTrue(Files.size(fixture.goodOutput) > 0);
            assertFalse(Files.exists(fixture.badOutput));
            fixture.selection = descriptor -> "other-host";
            var other = fixture.build();
            assertFalse(other.success());
            assertEquals("other-host", other.unit());
            assertTrue(other.items().stream().anyMatch(item -> item.diagnostic().message().contains("hostCall")));
            assertFalse(Files.exists(fixture.badOutput));
            assertEquals(List.of("good", "other-host"), fixture.buildArguments().stream().map(args -> args.get(4)).toList());
            assertTrue(fixture.launches.stream().allMatch(args -> args.contains(fixture.manifest.toString())));
            assertTrue(fixture.launches.stream().noneMatch(args -> args.contains(fixture.decoy.toString())),
                "the active broken editor must never replace the selected unit entry");
        }
        passed("buildsExplicitUnitsWithDistinctHostConfigurations");
    }

    @Test
    void navigatesNestedUtf8DiagnosticsIndependentlyOfActiveEditor() throws Exception {
        try (Fixture fixture = fixture()) {
            Path middle = fixture.write("src/middle.vas", "#include \"nested/leaf.vas\"\n");
            Path leaf = fixture.write("src/nested/leaf.vas", UTF8_SOURCE);
            fixture.writePath(fixture.entry, "#include \"middle.vas\"\nvoid main() { included(); }\n");
            fixture.open(fixture.decoy);
            var outcome = fixture.build();
            assertFalse(outcome.success());
            var item = outcome.items().stream()
                .filter(value -> value.diagnostic().message().contains("No matching symbol 'missing'"))
                .findFirst().orElseThrow();
            assertEquals(2, item.diagnostic().row());
            assertEquals(17, item.diagnostic().column(), "native positions are UTF-8 bytes, including literal tabs");
            assertNotNull(item.location());
            assertEquals(leaf.toAbsolutePath().normalize(), item.location().file().toAbsolutePath().normalize());
            assertEquals(UTF8_SOURCE.indexOf("missing"), item.location().offset());
            assertEquals(UTF8_SOURCE, item.location().snapshot().text());
            assertTrue(item.location().snapshot().unchanged(true));
            var dependencies = fixture.service.dependencies(fixture.manifest.toString(), "good");
            assertTrue(dependencies.containsAll(List.of(fixture.manifest, fixture.entry, fixture.goodConfig, middle, leaf)));
            assertTrue(fixture.service.dependencies(fixture.manifest.toString(), "other-host").isEmpty(),
                "observed dependencies belong to the selected manifest/unit");
            EdtTestUtil.runInEdtAndWait(() -> VasProjectBuildView.navigate(fixture.project, outcome, item));
            await(() -> EdtTestUtil.runInEdtAndGet(() -> {
                var editor = FileEditorManager.getInstance(fixture.project).getSelectedTextEditor();
                if (editor == null) return false;
                var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
                return file != null && Path.of(file.getPath()).equals(leaf)
                    && editor.getCaretModel().getOffset() == UTF8_SOURCE.indexOf("missing");
            }), "production diagnostic navigation must open the nested source at its verified UTF-16 offset");
            fixture.open(fixture.decoy);
            Document changed = fixture.document(leaf);
            try {
                fixture.replace(changed, UTF8_SOURCE + "// newer unsaved snapshot\n");
                EdtTestUtil.runInEdtAndWait(() -> VasProjectBuildView.navigate(fixture.project, outcome, item));
                quietEdt();
                assertEquals(fixture.decoy, EdtTestUtil.runInEdtAndGet(() -> {
                    var editor = FileEditorManager.getInstance(fixture.project).getSelectedTextEditor();
                    assertNotNull(editor);
                    var file = FileDocumentManager.getInstance().getFile(editor.getDocument());
                    assertNotNull(file);
                    return Path.of(file.getPath());
                }), "a completed result cannot navigate after its source snapshot becomes stale");
            } finally {
                fixture.restore(changed, UTF8_SOURCE);
            }
        }
        passed("navigatesNestedUtf8DiagnosticsIndependentlyOfActiveEditor");
    }

    @Test
    void preservesLegacyWarningsAndLiteralUnicodeMetacharacterPaths() throws Exception {
        try (Fixture fixture = fixture()) {
            byte[] originalCompiler = Files.readAllBytes(fixture.compiler);
            Path literalCompiler = fixture.directory.resolve("compiler 漢😀 & $; ' ()" +
                (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""));
            Files.copy(fixture.compiler, literalCompiler, StandardCopyOption.COPY_ATTRIBUTES);
            assertArrayEquals(originalCompiler, Files.readAllBytes(literalCompiler));
            assertTrue(literalCompiler.toFile().setExecutable(true) || Files.isExecutable(literalCompiler));
            EdtTestUtil.runInEdtAndWait(() -> VasToolchainSettings.getInstance().compilerPath = literalCompiler.toString());
            JsonObject legacy = new JsonObject();
            legacy.addProperty("name", "Legacy 漢😀 & $; ' ()");
            legacy.addProperty("entry", fixture.relative(fixture.entry));
            legacy.addProperty("builderConfig", fixture.relative(fixture.goodConfig));
            legacy.addProperty("bytecodeOutput", fixture.relative(fixture.goodOutput));
            legacy.addProperty("builder", "ignored-project-command & must-never-run");
            legacy.addProperty("runner", "ignored-project-runner");
            fixture.writePath(fixture.manifest, legacy.toString());
            byte[] manifestBefore = Files.readAllBytes(fixture.manifest);
            fixture.selection = descriptor -> {
                assertTrue(descriptor.legacy());
                assertNull(descriptor.schemaVersion());
                assertEquals(1, descriptor.warnings().size());
                assertEquals(List.of("main"), descriptor.units().stream().map(VasProjectProtocol.Unit::id).toList());
                return "main";
            };
            var outcome = fixture.build();
            assertTrue(outcome.success(), outcome.status());
            assertEquals("main", outcome.unit());
            assertEquals(1, outcome.items().stream().filter(item -> item.diagnostic().severity().equals("warning")
                && item.diagnostic().message().toLowerCase(java.util.Locale.ROOT).contains("legacy")).count());
            assertArrayEquals(manifestBefore, Files.readAllBytes(fixture.manifest), "legacy adaptation must not rewrite the manifest");
            assertTrue(Files.size(fixture.goodOutput) > 0);
            assertEquals(List.of("--report=jsonl", "--project", fixture.manifest.toString(), "--unit", "main"),
                fixture.buildArguments().getFirst(), "metacharacters remain one literal argument; no shell is involved");
        }
        passed("preservesLegacyWarningsAndLiteralUnicodeMetacharacterPaths");
    }

    @Test
    void rejectsDirtyEntryManifestHostAndKnownInclude() throws Exception {
        try (Fixture fixture = fixture()) {
            Path include = fixture.write("src/nested/known.vas", "void included() {}\n");
            fixture.writePath(fixture.entry, "#include \"nested/known.vas\"\nvoid main() { included(); hostCall(); }\n");
            assertTrue(fixture.build().success());
            assertTrue(fixture.service.dependencies(fixture.manifest.toString(), "good").contains(include));
            for (Path input : List.of(fixture.entry, fixture.manifest, fixture.goodConfig, include)) {
                Document document = fixture.document(input);
                String saved = EdtTestUtil.runInEdtAndGet(document::getText);
                byte[] outputBefore = Files.readAllBytes(fixture.goodOutput);
                int buildsBefore = fixture.buildArguments().size();
                try {
                    fixture.replace(document, saved + "\n ");
                    assertTrue(EdtTestUtil.runInEdtAndGet(() -> FileDocumentManager.getInstance().isDocumentUnsaved(document)));
                    var rejected = fixture.build();
                    assertFalse(rejected.success());
                    assertTrue(rejected.status().toLowerCase(java.util.Locale.ROOT).contains("save"), rejected.status());
                    assertEquals(buildsBefore, fixture.buildArguments().size(), "dirty saved inputs must block native compilation");
                    assertArrayEquals(outputBefore, Files.readAllBytes(fixture.goodOutput));
                    assertTrue(EdtTestUtil.runInEdtAndGet(() -> FileDocumentManager.getInstance().isDocumentUnsaved(document)),
                        "Build Project must not implicitly save dirty documents");
                } finally {
                    fixture.restore(document, saved);
                }
            }
        }
        passed("rejectsDirtyEntryManifestHostAndKnownInclude");
    }

    @Test
    void retainsDependenciesAfterPartialTraversal() throws Exception {
        try (Fixture fixture = fixture()) {
            Path first = fixture.write("src/known-first.vas", "#include \"nested/known-second.vas\"\n");
            Path second = fixture.write("src/nested/known-second.vas", "void included() {}\n");
            fixture.writePath(fixture.entry, "#include \"known-first.vas\"\nvoid main() { included(); }\n");
            assertTrue(fixture.build().success());
            var complete = fixture.service.dependencies(fixture.manifest.toString(), "good");
            assertTrue(complete.containsAll(List.of(first, second)));
            byte[] successfulOutput = Files.readAllBytes(fixture.goodOutput);
            Path missing = fixture.entry.getParent().resolve("now-missing.vas");
            assertFalse(Files.exists(missing));
            fixture.writePath(fixture.entry, "#include \"now-missing.vas\"\n#include \"known-first.vas\"\nvoid main() { included(); }\n");
            var failed = fixture.build();
            assertFalse(failed.success());
            assertTrue(failed.status().contains("load"), failed.status());
            var incomplete = fixture.service.dependencies(fixture.manifest.toString(), "good");
            assertTrue(incomplete.containsAll(complete), "partial traversal cannot erase prior dependency observations");
            assertTrue(incomplete.contains(missing), "missing include candidates must remain watched for later creation");
            assertTrue(fixture.service.dependencies(fixture.manifest.toString(), "other-host").isEmpty());
            assertArrayEquals(successfulOutput, Files.readAllBytes(fixture.goodOutput));
        }
        passed("retainsDependenciesAfterPartialTraversal");
    }

    @Test
    void blocksUntrustedAndPassiveProjectExecution() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.trust(false);
            fixture.open(fixture.decoy);
            Document document = fixture.document(fixture.decoy);
            String saved = EdtTestUtil.runInEdtAndGet(document::getText);
            fixture.replace(document, saved + "\n// edit while untrusted\n");
            fixture.applyCompilerSetting("");
            fixture.applyCompilerSetting(fixture.compiler.toString());
            quietEdt();
            assertTrue(fixture.launches.isEmpty(), "opening, editing, and applying settings must not invoke project tools");
            assertTrue(fixture.outcomes.isEmpty(), "passive editor/settings events must not initiate a build session");
            fixture.restore(document, saved);
            var blocked = fixture.build();
            assertFalse(blocked.success());
            assertTrue(blocked.status().contains("Trust"), blocked.status());
            assertTrue(fixture.launches.isEmpty(), "untrusted explicit action must not even describe a manifest");
            assertEquals(0, fixture.selections.get());
            assertFalse(Files.exists(fixture.goodOutput));
            fixture.trust(true);
            assertTrue(fixture.build().success(), "the same registered action must work after explicit trust");
            assertEquals(1, fixture.buildArguments().size());
        }
        passed("blocksUntrustedAndPassiveProjectExecution");
    }

    @Test
    void cancelsSelectionAndSuppressesSupersededResults() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.selection = descriptor -> {
                fixture.service.cancel();
                return "good";
            };
            var cancelled = fixture.build();
            assertFalse(cancelled.success());
            assertTrue(cancelled.status().toLowerCase(java.util.Locale.ROOT).contains("cancel"));
            quietEdt();
            assertEquals(1, fixture.launches.size(), "the real descriptor ran before selection was cancelled");
            assertTrue(fixture.buildArguments().isEmpty(), "cancelling inside selection must not schedule a native build");
            assertFalse(Files.exists(fixture.goodOutput));

            fixture.selection = descriptor -> "other-host";
            long previous = cancelled.generation();
            fixture.outcomes.clear();
            EdtTestUtil.runInEdtAndWait(() -> {
                fixture.invokeAction();
                fixture.invokeAction();
            });
            var latest = fixture.awaitOutcome(previous + 1);
            assertEquals(previous + 2, latest.generation());
            assertEquals("other-host", latest.unit());
            assertFalse(latest.success());
            quietEdt();
            assertTrue(fixture.outcomes.stream().allMatch(outcome -> outcome.generation() == latest.generation()),
                "queued results from the superseded invocation must never reach presentation");
            assertSame(latest, fixture.service.latest());
            assertEquals(1, fixture.buildArguments().size());
        }
        passed("cancelsSelectionAndSuppressesSupersededResults");
    }

    @Test
    void suppressesResultsAfterInputsSettingsOrTrustChange() throws Exception {
        try (Fixture fixture = fixture()) {
            for (String change : List.of("input", "compiler", "trust")) {
                Document document = fixture.document(fixture.entry);
                String saved = EdtTestUtil.runInEdtAndGet(document::getText);
                int buildsBefore = fixture.buildArguments().size();
                fixture.selection = descriptor -> {
                    switch (change) {
                        case "input" -> fixture.replace(document, saved + "\n// changed during selection\n");
                        case "compiler" -> VasToolchainSettings.getInstance().compilerPath = "";
                        case "trust" -> fixture.trust(false);
                        default -> throw new AssertionError(change);
                    }
                    return "good";
                };
                try {
                    var rejected = fixture.build();
                    assertFalse(rejected.success(), change);
                    quietEdt();
                    assertEquals(buildsBefore, fixture.buildArguments().size(), "selection-to-launch guard must recheck " + change);
                    assertFalse(Files.exists(fixture.goodOutput));
                    assertTrue(rejected.items().isEmpty(), "stale diagnostic locations must not be published");
                    assertSame(rejected, fixture.service.latest());
                } finally {
                    fixture.restore(document, saved);
                    EdtTestUtil.runInEdtAndWait(() -> VasToolchainSettings.getInstance().compilerPath = fixture.compiler.toString());
                    fixture.trust(true);
                }
            }
        }
        passed("suppressesResultsAfterInputsSettingsOrTrustChange");
    }

    @Test
    void rejectsMissingManifestWithoutActiveFileFallback() throws Exception {
        try (Fixture fixture = fixture()) {
            fixture.open(fixture.entry);
            Files.delete(fixture.manifest);
            fixture.refresh(fixture.manifest);
            var rejected = fixture.build();
            assertFalse(rejected.success());
            assertTrue(rejected.status().contains("vas-project.json"), rejected.status());
            assertTrue(fixture.launches.isEmpty());
            assertEquals(0, fixture.selections.get());
            assertFalse(Files.exists(fixture.goodOutput), "a saved, compilable active entry is not a project manifest");
        }
        passed("rejectsMissingManifestWithoutActiveFileFallback");
    }

    private Fixture fixture() throws Exception {
        assertFalse(ApplicationManager.getApplication().isDispatchThread(), "waits must never block the Rider EDT");
        String configured = System.getenv("VAS_TEST_COMPILER");
        assertNotNull(configured, "VAS_TEST_COMPILER is mandatory for native Build Project acceptance");
        assertFalse(configured.isBlank(), "VAS_TEST_COMPILER must name the actual native compiler");
        Path compiler = VasProjectProcess.validateNativeCompiler(configured);
        Project project = getSolutionApiFacade().getProject();
        assertFalse(project.isDefault());
        Path root = getSolutionApiFacade().getActiveSolutionDirectory().toAbsolutePath().normalize();
        assertNotNull(project.getBasePath());
        assertEquals(root, Path.of(project.getBasePath()).toAbsolutePath().normalize(),
            "Build Project must use the active Rider solution root");
        return new Fixture(project, root, compiler);
    }

    private static void passed(String name) {
        // Printed only after all assertions AND fixture cleanup have succeeded.
        System.out.println(AUDIT + name);
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        assertFalse(ApplicationManager.getApplication().isDispatchThread());
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, message);
            EdtTestUtil.runInEdtAndWait(() -> { });
            Thread.sleep(20);
        }
    }

    private static void quietEdt() throws InterruptedException {
        assertFalse(ApplicationManager.getApplication().isDispatchThread());
        // A bounded negative-observation window plus a final EDT barrier. No
        // native compiler is delayed or replaced to manufacture race evidence.
        Thread.sleep(200);
        EdtTestUtil.runInEdtAndWait(() -> { });
    }

    private static final class Fixture implements AutoCloseable {
        final Project project;
        final Path root, compiler, directory, manifest, entry, goodConfig, badConfig, goodOutput, badOutput, decoy;
        final Disposable lifetime = Disposer.newDisposable("VAS native project build fixture");
        final List<List<String>> launches = new CopyOnWriteArrayList<>();
        final List<VasProjectBuildService.Outcome> outcomes = new CopyOnWriteArrayList<>();
        final List<VirtualFile> opened = new ArrayList<>();
        final AtomicInteger selections = new AtomicInteger();
        final String previousCompiler, previousBuilder;
        final VasProjectBuildService service;
        volatile Function<VasProjectProtocol.Descriptor, String> selection = descriptor -> "good";
        volatile VirtualFile activeFile;

        Fixture(Project project, Path root, Path compiler) throws Exception {
            this.project = project;
            this.root = root;
            this.compiler = compiler;
            manifest = root.resolve("vas-project.json");
            assertFalse(Files.exists(manifest), "the temporary host fixture must not overwrite an existing manifest");
            directory = Files.createTempDirectory(root, "project-build 漢😀 & $; ' () ");
            entry = directory.resolve("src/entry 漢😀 & $;.vas");
            goodConfig = directory.resolve("host/good 漢😀 & $;.txt");
            badConfig = directory.resolve("host/other 漢😀 & $;.txt");
            decoy = directory.resolve("src/active-decoy.vas");
            goodOutput = directory.resolve("out/good 漢😀 & $;.vasbc");
            badOutput = directory.resolve("out/other 漢😀 & $;.vasbc");
            previousCompiler = VasToolchainSettings.getInstance().compilerPath;
            previousBuilder = VasSettingsState.getInstance(project).builderPath;
            service = VasProjectBuildService.getInstance(project);
            try {
                writePath(entry, "void main() { hostCall(); }\n");
                writePath(goodConfig, "func \"void hostCall()\"\n");
                writePath(badConfig, "func \"void anotherHostCall()\"\n");
                writePath(decoy, "void activeEditorIsNeverTheEntry() { missingActiveEditorSymbol(); }\n");
                Files.createDirectories(directory.resolve("out"));
                JsonObject definition = new JsonObject();
                definition.addProperty("schemaVersion", 1);
                JsonArray units = new JsonArray();
                units.add(unit("good", goodConfig, goodOutput));
                units.add(unit("other-host", badConfig, badOutput));
                definition.add("compilationUnits", units);
                writePath(manifest, definition.toString());
                EdtTestUtil.runInEdtAndWait(() -> {
                    TrustedPaths isolated = new TrustedPaths();
                    isolated.loadState(TrustedPaths.getInstance().getState());
                    ServiceContainerUtil.replaceService(ApplicationManager.getApplication(), TrustedPaths.class, isolated, lifetime);
                    // Disable the separate legacy annotator; these tests exercise only
                    // explicitly invoked project builds with the real native compiler.
                    VasSettingsState.getInstance(project).builderPath = "";
                    VasToolchainSettings.getInstance().compilerPath = compiler.toString();
                    trust(true);
                    service.installLaunchObserver(args -> launches.add(List.copyOf(args)), lifetime);
                    service.setInteraction(new VasProjectBuildService.Interaction() {
                        @Override public String select(VasProjectProtocol.Descriptor descriptor) {
                            assertTrue(ApplicationManager.getApplication().isDispatchThread());
                            selections.incrementAndGet();
                            return selection.apply(descriptor);
                        }
                        @Override public void show(VasProjectBuildService.Outcome outcome) {
                            assertTrue(ApplicationManager.getApplication().isDispatchThread());
                            outcomes.add(outcome);
                        }
                    });
                });
                assertTrue(launches.isEmpty());
            } catch (Exception | Error failure) {
                try { close(); } catch (Exception | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                throw failure;
            }
        }

        JsonObject unit(String id, Path config, Path output) {
            JsonObject unit = new JsonObject();
            unit.addProperty("id", id);
            unit.addProperty("entry", relative(entry));
            JsonObject host = new JsonObject();
            host.addProperty("config", relative(config));
            unit.add("hostApi", host);
            unit.addProperty("output", relative(output));
            return unit;
        }

        String relative(Path path) { return root.relativize(path).toString().replace('\\', '/'); }

        Path write(String relative, String text) throws Exception {
            Path path = directory.resolve(relative);
            writePath(path, text);
            return path;
        }

        void writePath(Path path, String text) throws Exception {
            Files.createDirectories(path.getParent());
            Files.writeString(path, text, StandardCharsets.UTF_8);
            // Avoid ambiguous same-millisecond newly-created dependency times in
            // the saved-snapshot guard. Content and paths remain real disk inputs.
            Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() - 2_000));
            refresh(path);
        }

        void refresh(Path path) {
            EdtTestUtil.runInEdtAndWait(() -> {
                VirtualFile existing = LocalFileSystem.getInstance().findFileByNioFile(path);
                if (existing != null) existing.refresh(false, false);
                else LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
            });
        }

        Document document(Path path) {
            return EdtTestUtil.runInEdtAndGet(() -> {
                VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
                assertNotNull(file);
                Document document = FileDocumentManager.getInstance().getDocument(file);
                assertNotNull(document);
                if (!opened.contains(file)) opened.add(file);
                return document;
            });
        }

        void open(Path path) {
            EdtTestUtil.runInEdtAndWait(() -> {
                activeFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
                assertNotNull(activeFile);
                opened.add(activeFile);
                assertNotNull(FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, activeFile), true));
            });
        }

        void replace(Document document, String text) {
            EdtTestUtil.runInEdtAndWait(() -> {
                WriteCommandAction.runWriteCommandAction(project, () -> document.setText(text));
                PsiDocumentManager.getInstance(project).commitAllDocuments();
            });
        }

        void restore(Document document, String text) {
            replace(document, text);
            EdtTestUtil.runInEdtAndWait(() -> FileDocumentManager.getInstance().saveDocument(document));
        }

        void trust(boolean trusted) {
            EdtTestUtil.runInEdtAndWait(() -> {
                TrustedProjects.setProjectTrusted(project, trusted);
                assertEquals(trusted, TrustedProjects.isProjectTrusted(project), "test trust must override implicit headless trust");
            });
        }

        void applyCompilerSetting(String value) {
            EdtTestUtil.runInEdtAndWait(() -> {
                VasSettingsConfigurable configurable = new VasSettingsConfigurable(project);
                try {
                    var panel = configurable.createComponent();
                    assertNotNull(panel);
                    var fields = Arrays.stream(panel.getComponents()).filter(TextFieldWithBrowseButton.class::isInstance)
                        .map(TextFieldWithBrowseButton.class::cast).toList();
                    assertEquals(5, fields.size());
                    fields.getLast().setText(value);
                    configurable.apply();
                    assertEquals(value, VasToolchainSettings.getInstance().compilerPath);
                } finally {
                    configurable.disposeUIResources();
                }
            });
        }

        void invokeAction() {
            assertTrue(ApplicationManager.getApplication().isDispatchThread());
            AnAction action = ActionManager.getInstance().getAction("VAS.BuildProject");
            assertNotNull(action, "the tested action must be registered in the loaded plugin");
            DataContext context = dataId -> {
                if (CommonDataKeys.PROJECT.is(dataId)) return project;
                if (CommonDataKeys.VIRTUAL_FILE.is(dataId)) return activeFile;
                return null;
            };
            action.actionPerformed(AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, context));
        }

        VasProjectBuildService.Outcome build() throws InterruptedException {
            var before = service.latest();
            long generation = before == null ? 0 : before.generation();
            EdtTestUtil.runInEdtAndWait(this::invokeAction);
            return awaitOutcome(generation);
        }

        VasProjectBuildService.Outcome awaitOutcome(long afterGeneration) throws InterruptedException {
            await(() -> outcomes.stream().anyMatch(outcome -> outcome.generation() > afterGeneration && terminal(outcome)),
                "registered Build Project action did not publish a terminal outcome within " + WAIT.toSeconds() + " seconds");
            return outcomes.stream().filter(outcome -> outcome.generation() > afterGeneration && terminal(outcome))
                .max(Comparator.comparingLong(VasProjectBuildService.Outcome::generation)).orElseThrow();
        }

        private static boolean terminal(VasProjectBuildService.Outcome outcome) {
            return !outcome.status().equals("Reading project…") && !outcome.status().equals("Building…");
        }

        List<List<String>> buildArguments() {
            return launches.stream().filter(args -> args.getFirst().equals("--report=jsonl")).toList();
        }

        @Override public void close() throws Exception {
            try {
                EdtTestUtil.runInEdtAndWait(service::cancel);
                quietEdt();
                EdtTestUtil.runInEdtAndWait(() -> {
                    for (VirtualFile file : FileEditorManager.getInstance(project).getOpenFiles()) {
                        if (Path.of(file.getPath()).startsWith(directory) || Path.of(file.getPath()).equals(manifest)) {
                            FileEditorManager.getInstance(project).closeFile(file);
                        }
                    }
                    for (VirtualFile file : opened) {
                        Document document = FileDocumentManager.getInstance().getCachedDocument(file);
                        if (document != null && FileDocumentManager.getInstance().isDocumentUnsaved(document)) {
                            FileDocumentManager.getInstance().reloadFromDisk(document);
                        }
                    }
                });
                Files.deleteIfExists(manifest);
                if (Files.exists(directory)) {
                    try (var paths = Files.walk(directory)) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                    }
                }
                refresh(manifest);
                refresh(directory);
                // Drain cleanup invalidations while the capture interaction is
                // still installed, so cleanup cannot open a real tool window.
                EdtTestUtil.runInEdtAndWait(() -> { });
                assertFalse(Files.exists(manifest));
                assertFalse(Files.exists(directory));
            } finally {
                EdtTestUtil.runInEdtAndWait(() -> {
                    service.setInteraction(null);
                    VasToolchainSettings.getInstance().compilerPath = previousCompiler;
                    VasSettingsState.getInstance(project).builderPath = previousBuilder;
                    Disposer.dispose(lifetime);
                });
            }
        }
    }
}
