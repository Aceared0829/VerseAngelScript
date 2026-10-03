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
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.progress.impl.CoreProgressManager;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.PlatformTestUtil;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Rider Solution Host + registered action + actual native compiler.
 * Rider invokes each test on EDT; the platform-backed wrapper pumps EDT events
 * while the blocking case body and its cleanup execute on a pooled thread.
 * Unit selection, presentation, and launch/filesystem observers are hooked.
 * One filesystem observer uses a bounded barrier to verify EDT responsiveness;
 * actual filesystem validation and native processes are never replaced.
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
        runCaseOffEdt(this::buildsExplicitUnitsWithDistinctHostConfigurationsOffEdt);
    }

    private void buildsExplicitUnitsWithDistinctHostConfigurationsOffEdt() throws Exception {
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
        runCaseOffEdt(this::navigatesNestedUtf8DiagnosticsIndependentlyOfActiveEditorOffEdt);
    }

    private void navigatesNestedUtf8DiagnosticsIndependentlyOfActiveEditorOffEdt() throws Exception {
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
    void navigatesExternalIncludeOnFirstVfsDiscovery() throws Exception {
        runCaseOffEdt(this::navigatesExternalIncludeOnFirstVfsDiscoveryOffEdt);
    }

    private void navigatesExternalIncludeOnFirstVfsDiscoveryOffEdt() throws Exception {
        try (Fixture fixture = fixture()) {
            Path externalDirectory = Files.createTempDirectory("vas-native-external 漢😀 ").toAbsolutePath().normalize();
            Path externalLeaf = externalDirectory.resolve("external-leaf 漢😀.vas");
            try {
                assertFalse(externalDirectory.startsWith(fixture.root), "this case must exercise a native include outside the Rider project");
                // Use NIO only: the compiler, not VFS or PSI, first discovers and
                // reads this source. The first navigation must establish VFS identity.
                Files.writeString(externalLeaf, UTF8_SOURCE, StandardCharsets.UTF_8);
                Files.setLastModifiedTime(externalLeaf, FileTime.fromMillis(System.currentTimeMillis() - 2_000));
                String includePath = externalLeaf.toString().replace('\\', '/');
                fixture.writePath(fixture.entry, "#include \"" + includePath + "\"\nvoid main() { included(); }\n");
                fixture.open(fixture.decoy);
                EdtTestUtil.runInEdtAndWait(() -> {
                    assertNull(LocalFileSystem.getInstance().findFileByNioFile(externalDirectory));
                    assertNull(LocalFileSystem.getInstance().findFileByNioFile(externalLeaf));
                });
                var completed = fixture.build();
                assertFalse(completed.success());
                var item = completed.items().stream()
                    .filter(value -> value.diagnostic().message().contains("No matching symbol 'missing'"))
                    .findFirst().orElseThrow();
                assertEquals(2, item.diagnostic().row());
                assertEquals(17, item.diagnostic().column());
                assertNotNull(item.location());
                assertEquals(externalLeaf, item.location().file().toAbsolutePath().normalize());
                assertEquals(UTF8_SOURCE.indexOf("missing"), item.location().offset());
                assertEquals(UTF8_SOURCE, item.location().snapshot().text());
                assertTrue(item.location().snapshot().unchanged(true));
                assertTrue(fixture.service.dependencies(fixture.manifest.toString(), "good").contains(externalLeaf));
                EdtTestUtil.runInEdtAndWait(() -> {
                    assertNull(LocalFileSystem.getInstance().findFileByNioFile(externalDirectory),
                        "native compilation must not prime VFS discovery for this test");
                    assertNull(LocalFileSystem.getInstance().findFileByNioFile(externalLeaf));
                });
                int launchesAfterBuild = fixture.launches.size();
                int publicationsAfterBuild = fixture.outcomes.size();
                long revisionBeforeNavigation = fixture.service.inputRevision(completed);
                assertTrue(revisionBeforeNavigation >= 0);
                EdtTestUtil.runInEdtAndWait(() -> VasProjectBuildView.navigate(fixture.project, completed, item));
                await(() -> {
                    SelectedPoint selected = selectedPoint(fixture.project);
                    // Build 262 LocalFileSystemBase.normalize expands Windows 8.3
                    // names. The native temp path may retain RUNNER~1 while VFS
                    // returns the long spelling: require the same physical file,
                    // not the same lexical spelling. Keep that filesystem call off EDT.
                    return selected.path() != null && selected.caret() == UTF8_SOURCE.indexOf("missing")
                        && samePhysicalFile(selected.path(), externalLeaf);
                }, () -> "first production navigation must discover the external include and open its precise diagnostic location; "
                    + coldNavigationState(fixture, completed, item, externalLeaf, revisionBeforeNavigation));
                quietEdt();
                assertSame(completed, fixture.service.latest(), "discovering unchanged external bytes must not invalidate completed diagnostics");
                assertEquals(publicationsAfterBuild, fixture.outcomes.size());
                assertEquals(launchesAfterBuild, fixture.launches.size(), "VFS discovery must never trigger a new compilation");
                assertTrue(fixture.service.isCurrent(completed));
                EdtTestUtil.runInEdtAndWait(() -> {
                    assertNotNull(LocalFileSystem.getInstance().findFileByNioFile(externalDirectory));
                    assertNotNull(LocalFileSystem.getInstance().findFileByNioFile(externalLeaf));
                });
            } finally {
                EdtTestUtil.runInEdtAndWait(() -> {
                    fixture.service.cancel();
                    // Resolve only cached VFS identity; this also handles an
                    // expanded Windows temp path without introducing a refresh.
                    VirtualFile externalFile = LocalFileSystem.getInstance().findFileByNioFile(externalLeaf);
                    if (externalFile != null) FileEditorManager.getInstance(fixture.project).closeFile(externalFile);
                });
                if (Files.exists(externalDirectory)) {
                    try (var paths = Files.walk(externalDirectory)) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                    }
                }
                fixture.refresh(externalLeaf);
                fixture.refresh(externalDirectory);
                assertFalse(Files.exists(externalDirectory));
            }
        }
        passed("navigatesExternalIncludeOnFirstVfsDiscovery");
    }

    @Test
    void preservesLegacyWarningsAndLiteralUnicodeMetacharacterPaths() throws Exception {
        runCaseOffEdt(this::preservesLegacyWarningsAndLiteralUnicodeMetacharacterPathsOffEdt);
    }

    private void preservesLegacyWarningsAndLiteralUnicodeMetacharacterPathsOffEdt() throws Exception {
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
        runCaseOffEdt(this::rejectsDirtyEntryManifestHostAndKnownIncludeOffEdt);
    }

    private void rejectsDirtyEntryManifestHostAndKnownIncludeOffEdt() throws Exception {
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
    void rejectsDirtyPhysicalAliasOnFirstIncludeTraversal() throws Exception {
        runCaseOffEdt(this::rejectsDirtyPhysicalAliasOnFirstIncludeTraversalOffEdt);
    }

    private void rejectsDirtyPhysicalAliasOnFirstIncludeTraversalOffEdt() throws Exception {
        try (Fixture fixture = fixture()) {
            String savedShared = "void included() {}\n";
            Path sharedText = fixture.write("src/shared.txt", savedShared);
            Path sharedVas = sharedText.resolveSibling("shared.vas");
            // A hardlink is supported by the native Windows/NTFS CI filesystem
            // without the elevated privileges sometimes required for symlinks.
            // Failure to create it fails this mandatory case; there is no skip.
            Files.createLink(sharedVas, sharedText);
            fixture.refresh(sharedVas);
            assertTrue(Files.isSameFile(sharedText, sharedVas));
            Path main = fixture.write("src/main.vas", "#include \"shared.vas\"\nvoid main() { included(); hostCall(); }\n");
            JsonObject definition = new JsonObject();
            definition.addProperty("schemaVersion", 1);
            JsonArray units = new JsonArray();
            JsonObject unit = fixture.unit("good", fixture.goodConfig, fixture.goodOutput);
            unit.addProperty("entry", fixture.relative(main));
            units.add(unit);
            definition.add("compilationUnits", units);
            fixture.writePath(fixture.manifest, definition.toString());
            String savedUnrelated = "Unrelated saved text\n";
            Path unrelatedText = fixture.write("notes/unrelated.txt", savedUnrelated);
            fixture.open(sharedText);
            Document aliasDocument = fixture.document(sharedText);
            Document unrelatedDocument = fixture.document(unrelatedText);
            try {
                fixture.replace(aliasDocument, savedShared + "void unsavedOnly() { missingDirtyBufferSymbol(); }\n");
                fixture.replace(unrelatedDocument, savedUnrelated + "Unrelated unsaved edit\n");
                assertTrue(EdtTestUtil.runInEdtAndGet(() -> FileDocumentManager.getInstance().isDocumentUnsaved(aliasDocument)));
                assertTrue(EdtTestUtil.runInEdtAndGet(() -> FileDocumentManager.getInstance().isDocumentUnsaved(unrelatedDocument)));
                assertEquals(savedShared, Files.readString(sharedVas, StandardCharsets.UTF_8),
                    "native compilation would otherwise succeed against the saved hardlink bytes");
                assertTrue(Files.isSameFile(sharedText, sharedVas));
                assertNull(fixture.service.latest(), "the physical alias must already be dirty before the first action");
                assertTrue(fixture.launches.isEmpty());
                assertTrue(fixture.service.dependencies(fixture.manifest.toString(), "good").isEmpty(),
                    "this include must be unknown in the prior dependency graph");

                var rejected = fixture.build();
                assertFalse(rejected.success());
                assertTrue(rejected.status().toLowerCase(java.util.Locale.ROOT).contains("save"), rejected.status());
                assertTrue(rejected.status().contains("shared.txt"), "the rejection must identify the dirty physical alias");
                assertTrue(rejected.items().isEmpty());
                assertEquals(1, fixture.buildArguments().size(), "the real compiler must discover the previously unknown include");
                assertTrue(fixture.service.dependencies(fixture.manifest.toString(), "good").contains(sharedVas),
                    "first-traversal observations must survive the dirty-alias rejection");
                quietEdt();
                assertSame(rejected, fixture.service.latest());
                assertTrue(fixture.outcomes.stream().allMatch(outcome -> !outcome.success() && outcome.items().isEmpty()),
                    "dirty aliases must suppress every success and diagnostic publication, including late queued outcomes");
                // The compiler may already have written bytecode before the final
                // saved-input guard rejects publication. Do not require its absence.
                assertTrue(EdtTestUtil.runInEdtAndGet(() -> FileDocumentManager.getInstance().isDocumentUnsaved(aliasDocument)),
                    "the action must not save the dirty alias implicitly");
                assertEquals(savedShared, Files.readString(sharedText, StandardCharsets.UTF_8));

                // Reload only the alias without saving/replacing its inode. The
                // unrelated .txt document deliberately remains dirty for the control.
                EdtTestUtil.runInEdtAndWait(() -> FileDocumentManager.getInstance().reloadFromDisk(aliasDocument));
                assertFalse(EdtTestUtil.runInEdtAndGet(() -> FileDocumentManager.getInstance().isDocumentUnsaved(aliasDocument)));
                assertTrue(Files.isSameFile(sharedText, sharedVas));
                assertTrue(EdtTestUtil.runInEdtAndGet(() -> FileDocumentManager.getInstance().isDocumentUnsaved(unrelatedDocument)));
                var control = fixture.build();
                assertTrue(control.success(), control.status());
                assertEquals(rejected.generation() + 1, control.generation());
                assertEquals(2, fixture.buildArguments().size());
                assertTrue(Files.size(fixture.goodOutput) > 0);
                assertTrue(EdtTestUtil.runInEdtAndGet(() -> FileDocumentManager.getInstance().isDocumentUnsaved(unrelatedDocument)),
                    "unrelated dirty .txt documents neither block compilation nor get saved implicitly");
                assertEquals(savedUnrelated, Files.readString(unrelatedText, StandardCharsets.UTF_8));
            } finally {
                EdtTestUtil.runInEdtAndWait(() -> {
                    FileDocumentManager.getInstance().reloadFromDisk(aliasDocument);
                    FileDocumentManager.getInstance().reloadFromDisk(unrelatedDocument);
                });
            }
        }
        passed("rejectsDirtyPhysicalAliasOnFirstIncludeTraversal");
    }

    @Test
    void retainsDependenciesAfterPartialTraversal() throws Exception {
        runCaseOffEdt(this::retainsDependenciesAfterPartialTraversalOffEdt);
    }

    private void retainsDependenciesAfterPartialTraversalOffEdt() throws Exception {
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
        runCaseOffEdt(this::blocksUntrustedAndPassiveProjectExecutionOffEdt);
    }

    private void blocksUntrustedAndPassiveProjectExecutionOffEdt() throws Exception {
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
        runCaseOffEdt(this::cancelsSelectionAndSuppressesSupersededResultsOffEdt);
    }

    private void cancelsSelectionAndSuppressesSupersededResultsOffEdt() throws Exception {
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
    void preservesNewBuildWhenSupersededSelectionIsCancelled() throws Exception {
        runCaseOffEdt(this::preservesNewBuildWhenSupersededSelectionIsCancelledOffEdt);
    }

    private void preservesNewBuildWhenSupersededSelectionIsCancelledOffEdt() throws Exception {
        try (Fixture fixture = fixture()) {
            assertNull(fixture.service.latest());
            AtomicInteger selectionOrder = new AtomicInteger();
            AtomicInteger publicationsBeforeNewRequest = new AtomicInteger(-1);
            fixture.selection = descriptor -> {
                if (selectionOrder.getAndIncrement() == 0) {
                    publicationsBeforeNewRequest.set(fixture.outcomes.size());
                    // Model a modal selection dialog being superseded by a new
                    // registered action before the obsolete dialog returns cancel.
                    fixture.invokeAction();
                    return null;
                }
                return "good";
            };
            var completed = fixture.build();
            assertTrue(completed.success(), completed.status());
            assertEquals(2, completed.generation(), "the second explicit invocation must own the completed result");
            assertEquals("good", completed.unit());
            assertEquals(2, selectionOrder.get());
            assertEquals(2, fixture.selections.get());
            assertEquals(2, fixture.launches.stream().filter(args -> args.getFirst().equals("--describe-project=json")).count());
            assertEquals(1, fixture.buildArguments().size(), "only the new selection may launch the native unit build");
            assertEquals(List.of("--report=jsonl", "--project", fixture.manifest.toString(), "--unit", "good"),
                fixture.buildArguments().getFirst());
            assertTrue(Files.size(fixture.goodOutput) > 0);
            quietEdt();
            assertSame(completed, fixture.service.latest());
            assertTrue(publicationsBeforeNewRequest.get() >= 0);
            assertTrue(fixture.outcomes.stream().skip(publicationsBeforeNewRequest.get())
                .allMatch(outcome -> outcome.generation() == completed.generation()),
                "the cancelled obsolete selection must not publish late outcomes or cancel the newer session");
            assertTrue(fixture.outcomes.stream().skip(publicationsBeforeNewRequest.get())
                .noneMatch(outcome -> outcome.status().toLowerCase(java.util.Locale.ROOT).contains("cancel")));
        }
        passed("preservesNewBuildWhenSupersededSelectionIsCancelled");
    }

    @Test
    void suppressesResultsAfterInputsSettingsOrTrustChange() throws Exception {
        runCaseOffEdt(this::suppressesResultsAfterInputsSettingsOrTrustChangeOffEdt);
    }

    private void suppressesResultsAfterInputsSettingsOrTrustChangeOffEdt() throws Exception {
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
    void invalidatesCompletedResultsAfterTrustOrCompilerChange() throws Exception {
        runCaseOffEdt(this::invalidatesCompletedResultsAfterTrustOrCompilerChangeOffEdt);
    }

    private void invalidatesCompletedResultsAfterTrustOrCompilerChangeOffEdt() throws Exception {
        try (Fixture fixture = fixture()) {
            var first = fixture.build();
            assertTrue(first.success(), first.status());
            assertFalse(first.items().isEmpty(), "the real compiler must publish diagnostics before they can be invalidated");
            int launchesAfterFirst = fixture.launches.size();
            int selectionsAfterFirst = fixture.selections.get();
            fixture.trust(false);
            await(() -> {
                var latest = fixture.service.latest();
                return latest != null && latest.generation() == first.generation() && !latest.success()
                    && latest.items().isEmpty() && latest.status().toLowerCase(java.util.Locale.ROOT).contains("trust")
                    && fixture.outcomes.stream().anyMatch(shown -> shown == latest);
            }, "revoking trust must clear an already-completed result without another action or navigation click");
            var trustInvalidated = fixture.service.latest();
            assertNotSame(first, trustInvalidated);
            quietEdt();
            assertSame(trustInvalidated, fixture.service.latest());
            assertEquals(launchesAfterFirst, fixture.launches.size());
            assertEquals(selectionsAfterFirst, fixture.selections.get());
            fixture.trust(true);
            quietEdt();
            assertSame(trustInvalidated, fixture.service.latest(), "restoring trust must not restore stale success or initiate a build");
            assertEquals(launchesAfterFirst, fixture.launches.size());
            assertEquals(selectionsAfterFirst, fixture.selections.get());

            var second = fixture.build();
            assertTrue(second.success(), second.status());
            assertEquals(first.generation() + 1, second.generation());
            assertFalse(second.items().isEmpty());
            int launchesAfterSecond = fixture.launches.size();
            int selectionsAfterSecond = fixture.selections.get();
            fixture.applyCompilerSetting("");
            await(() -> {
                var latest = fixture.service.latest();
                return latest != null && latest.generation() == second.generation() && !latest.success()
                    && latest.items().isEmpty() && latest.status().toLowerCase(java.util.Locale.ROOT).contains("compiler")
                    && fixture.outcomes.stream().anyMatch(shown -> shown == latest);
            }, "applying a changed compiler setting must immediately invalidate completed results");
            var compilerInvalidated = fixture.service.latest();
            assertNotSame(second, compilerInvalidated);
            quietEdt();
            assertSame(compilerInvalidated, fixture.service.latest());
            assertEquals(launchesAfterSecond, fixture.launches.size(), "settings changes must never launch project tools");
            assertEquals(selectionsAfterSecond, fixture.selections.get());
            fixture.applyCompilerSetting(fixture.compiler.toString());
            quietEdt();
            assertEquals(second.generation(), fixture.service.latest().generation());
            assertFalse(fixture.service.latest().success());
            assertTrue(fixture.service.latest().items().isEmpty());
            assertEquals(launchesAfterSecond, fixture.launches.size(), "restoring compiler settings still requires an explicit action");
            assertEquals(selectionsAfterSecond, fixture.selections.get());
        }
        passed("invalidatesCompletedResultsAfterTrustOrCompilerChange");
    }

    @Test
    void invalidatesCompletedResultsAfterDependencyDirectoryChanges() throws Exception {
        runCaseOffEdt(this::invalidatesCompletedResultsAfterDependencyDirectoryChangesOffEdt);
    }

    private void invalidatesCompletedResultsAfterDependencyDirectoryChangesOffEdt() throws Exception {
        try (Fixture fixture = fixture()) {
            Path dependency = fixture.write("src/nested/dependency.vas", "void included() {}\n");
            fixture.writePath(fixture.entry, "#include \"nested/dependency.vas\"\nvoid main() { included(); }\n");
            var first = fixture.build();
            assertTrue(first.success(), first.status());
            assertFalse(first.items().isEmpty());
            assertTrue(fixture.service.dependencies(fixture.manifest.toString(), "good").contains(dependency));
            VirtualFile parent = EdtTestUtil.runInEdtAndGet(() ->
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dependency.getParent()));
            assertNotNull(parent);
            int launchesAfterFirst = fixture.launches.size();
            int selectionsAfterFirst = fixture.selections.get();
            int publicationsAfterFirst = fixture.outcomes.size();
            EdtTestUtil.runInEdtAndWait(() -> WriteAction.run(() -> parent.rename(fixture, "renamed-nested")));
            assertFalse(Files.exists(dependency));
            assertTrue(Files.exists(dependency.getParent().resolveSibling("renamed-nested").resolve(dependency.getFileName())));
            await(() -> {
                var latest = fixture.service.latest();
                return latest != null && latest.generation() == first.generation() && !latest.success()
                    && latest.items().isEmpty() && fixture.outcomes.stream().anyMatch(shown -> shown == latest);
            }, "renaming a dependency's containing directory must clear completed diagnostics without another action");
            quietEdt();
            assertEquals(first.generation(), fixture.service.latest().generation());
            assertFalse(fixture.service.latest().success());
            assertTrue(fixture.service.latest().items().isEmpty());
            assertEquals(launchesAfterFirst, fixture.launches.size());
            assertEquals(selectionsAfterFirst, fixture.selections.get());
            assertEquals(publicationsAfterFirst + 1, fixture.outcomes.size(),
                "VFS before/after notifications must publish exactly one invalidation for a completed generation");
            var onlyInvalidation = fixture.outcomes.getLast();
            assertEquals(first.generation(), onlyInvalidation.generation());
            assertFalse(onlyInvalidation.success());
            assertTrue(onlyInvalidation.items().isEmpty());

            Document entryDocument = fixture.document(fixture.entry);
            String savedEntry = EdtTestUtil.runInEdtAndGet(entryDocument::getText);
            try {
                fixture.replace(entryDocument, savedEntry + "// edit after the session was invalidated\n");
                VirtualFile child = EdtTestUtil.runInEdtAndGet(() -> parent.findChild("dependency.vas"));
                assertNotNull(child);
                EdtTestUtil.runInEdtAndWait(() -> WriteAction.run(() -> child.delete(fixture)));
                assertFalse(child.isValid());
                quietEdt();
                assertSame(onlyInvalidation, fixture.service.latest());
                assertEquals(publicationsAfterFirst + 1, fixture.outcomes.size(),
                    "editing and deleting inputs after invalidation must not reopen or republish the results UI");
                assertEquals(launchesAfterFirst, fixture.launches.size());
            } finally {
                fixture.restore(entryDocument, savedEntry);
            }
            EdtTestUtil.runInEdtAndWait(() -> WriteAction.run(() -> parent.rename(fixture, "nested")));
            fixture.writePath(dependency, "void included() {}\n");
            assertTrue(Files.exists(dependency));
            quietEdt();
            assertEquals(first.generation(), fixture.service.latest().generation());
            assertFalse(fixture.service.latest().success());
            assertEquals(launchesAfterFirst, fixture.launches.size(), "restoring the directory must not automatically rebuild");
            assertEquals(selectionsAfterFirst, fixture.selections.get());
            assertEquals(publicationsAfterFirst + 1, fixture.outcomes.size());
            var second = fixture.build();
            assertTrue(second.success(), second.status());
            assertEquals(first.generation() + 1, second.generation());
            assertFalse(second.items().isEmpty());
            int launchesAfterSecond = fixture.launches.size();
            int selectionsAfterSecond = fixture.selections.get();
            int publicationsAfterSecond = fixture.outcomes.size();
            EdtTestUtil.runInEdtAndWait(() -> WriteAction.run(() -> parent.delete(fixture)));
            assertFalse(parent.isValid());
            assertFalse(Files.exists(dependency.getParent()));
            await(() -> {
                var latest = fixture.service.latest();
                return latest != null && latest.generation() == second.generation() && !latest.success()
                    && latest.items().isEmpty() && fixture.outcomes.stream().anyMatch(shown -> shown == latest);
            }, "deleting a dependency's containing directory must invalidate a completed result without navigation");
            quietEdt();
            assertEquals(second.generation(), fixture.service.latest().generation());
            assertFalse(fixture.service.latest().success());
            assertTrue(fixture.service.latest().items().isEmpty());
            assertEquals(launchesAfterSecond, fixture.launches.size(), "directory deletion must never launch project tools");
            assertEquals(selectionsAfterSecond, fixture.selections.get());
            assertEquals(publicationsAfterSecond + 1, fixture.outcomes.size(),
                "deleting a directory must also coalesce its before/after and descendant notifications");
        }
        passed("invalidatesCompletedResultsAfterDependencyDirectoryChanges");
    }

    @Test
    void ignoresBytecodeOutputChangesWithVasSuffix() throws Exception {
        runCaseOffEdt(this::ignoresBytecodeOutputChangesWithVasSuffixOffEdt);
    }

    private void ignoresBytecodeOutputChangesWithVasSuffixOffEdt() throws Exception {
        try (Fixture fixture = fixture()) {
            Path output = fixture.entry.getParent().resolve("generated.vas");
            JsonObject definition = new JsonObject();
            definition.addProperty("schemaVersion", 1);
            JsonArray units = new JsonArray();
            units.add(fixture.unit("good", fixture.goodConfig, output));
            definition.add("compilationUnits", units);
            fixture.writePath(fixture.manifest, definition.toString());
            assertFalse(Files.exists(output));
            var completed = fixture.build();
            assertTrue(completed.success(), completed.status());
            assertTrue(Files.size(output) > 0);
            assertFalse(fixture.service.dependencies(fixture.manifest.toString(), "good").contains(output),
                "a generated bytecode path is not a source dependency merely because its suffix is .vas");
            int launchesAfterBuild = fixture.launches.size();
            int publicationsAfterBuild = fixture.outcomes.size();
            fixture.refresh(output);
            quietEdt();
            assertSame(completed, fixture.service.latest(), "observing the compiler's generated .vas output must not invalidate its own build");
            assertEquals(publicationsAfterBuild, fixture.outcomes.size());
            assertEquals(launchesAfterBuild, fixture.launches.size());
            Files.writeString(output, "// modified generated artifact\n", StandardCharsets.UTF_8);
            fixture.refresh(output);
            quietEdt();
            assertSame(completed, fixture.service.latest(), "changes to the selected bytecode output must not masquerade as input edits");
            assertEquals(publicationsAfterBuild, fixture.outcomes.size());
            assertEquals(launchesAfterBuild, fixture.launches.size());

            VirtualFile sourceDirectory = EdtTestUtil.runInEdtAndGet(() ->
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(fixture.entry.getParent()));
            assertNotNull(sourceDirectory);
            EdtTestUtil.runInEdtAndWait(() -> WriteAction.run(() -> sourceDirectory.rename(fixture, "renamed-src")));
            assertFalse(Files.exists(fixture.entry));
            await(() -> {
                var latest = fixture.service.latest();
                return latest != null && latest.generation() == completed.generation() && !latest.success()
                    && latest.items().isEmpty() && fixture.outcomes.stream().anyMatch(shown -> shown == latest);
            }, "excluding an output file must not exclude its ancestor directory when that directory also contains a real entry input");
            quietEdt();
            assertEquals(completed.generation(), fixture.service.latest().generation());
            assertFalse(fixture.service.latest().success());
            assertTrue(fixture.service.latest().items().isEmpty());
            assertEquals(publicationsAfterBuild + 1, fixture.outcomes.size());
            assertEquals(launchesAfterBuild, fixture.launches.size(), "artifact and directory changes must never automatically build");
        }
        passed("ignoresBytecodeOutputChangesWithVasSuffix");
    }

    @Test
    void keepsFilesystemValidationOffEdt() throws Exception {
        runCaseOffEdt(this::keepsFilesystemValidationOffEdtCase);
    }

    private void keepsFilesystemValidationOffEdtCase() throws Exception {
        try (Fixture fixture = fixture()) {
            Path unrelated = fixture.write("notes/unrelated.txt", "Saved unrelated note\n");
            Document note = fixture.document(unrelated);
            String savedNote = EdtTestUtil.runInEdtAndGet(note::getText);
            AtomicInteger filesystemCalls = new AtomicInteger();
            AtomicInteger filesystemCallsOnEdt = new AtomicInteger();
            AtomicBoolean holdBackgroundCheck = new AtomicBoolean();
            AtomicBoolean observerTimedOut = new AtomicBoolean();
            CountDownLatch backgroundEntered = new CountDownLatch(1);
            CountDownLatch releaseBackground = new CountDownLatch(1);
            CountDownLatch backgroundReturned = new CountDownLatch(1);
            EdtTestUtil.runInEdtAndWait(() -> fixture.service.installFilesystemObserver(() -> {
                filesystemCalls.incrementAndGet();
                if (ApplicationManager.getApplication().isDispatchThread()) {
                    filesystemCallsOnEdt.incrementAndGet();
                    return; // Never manufacture an EDT deadlock if the regression occurs.
                }
                if (holdBackgroundCheck.get()) {
                    backgroundEntered.countDown();
                    try {
                        if (!releaseBackground.await(15, TimeUnit.SECONDS)) {
                            observerTimedOut.set(true);
                            throw new IllegalStateException("Timed out releasing the filesystem observation barrier");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while observing filesystem validation", interrupted);
                    } finally {
                        backgroundReturned.countDown();
                    }
                }
            }, fixture.lifetime));
            try {
                var completed = fixture.build();
                assertTrue(completed.success(), completed.status());
                assertTrue(filesystemCalls.get() > 0, "the real build must exercise filesystem validation");
                assertEquals(0, filesystemCallsOnEdt.get());
                int callsAfterBuild = filesystemCalls.get();
                int launchesAfterBuild = fixture.launches.size();
                int publicationsAfterBuild = fixture.outcomes.size();
                holdBackgroundCheck.set(true);
                fixture.replace(note, savedNote + "First unrelated edit\n");
                assertTrue(backgroundEntered.await(10, TimeUnit.SECONDS),
                    "an unrelated dirty document must receive physical-alias validation on a worker");
                assertTrue(filesystemCalls.get() > callsAfterBuild);
                assertSame(completed, fixture.service.latest());

                CountDownLatch edtReturned = new CountDownLatch(1);
                AtomicReference<Throwable> edtFailure = new AtomicReference<>();
                ApplicationManager.getApplication().invokeLater(() -> {
                    try {
                        // A real write/commit, not just repainting, must complete
                        // while the physical-alias worker is explicitly held.
                        fixture.replace(note, savedNote + "Second unrelated edit while the worker waits\n");
                    } catch (Throwable failure) {
                        edtFailure.set(failure);
                    } finally {
                        edtReturned.countDown();
                    }
                });
                assertTrue(edtReturned.await(5, TimeUnit.SECONDS),
                    "the EDT must remain able to edit documents while filesystem validation is blocked on a worker");
                assertNull(edtFailure.get(), "the EDT edit must finish without an exception");
                assertSame(completed, fixture.service.latest());
                assertEquals(publicationsAfterBuild, fixture.outcomes.size());
                assertEquals(launchesAfterBuild, fixture.launches.size());
                holdBackgroundCheck.set(false);
                releaseBackground.countDown();
                assertTrue(backgroundReturned.await(10, TimeUnit.SECONDS));
                assertTrue(fixture.service.isCurrent(completed), "unrelated dirty text must not invalidate the saved compilation inputs");
                quietEdt();
                assertSame(completed, fixture.service.latest());
                assertEquals(publicationsAfterBuild, fixture.outcomes.size());
                assertEquals(launchesAfterBuild, fixture.launches.size());
                fixture.restore(note, savedNote);

                Document entry = fixture.document(fixture.entry);
                String savedEntry = EdtTestUtil.runInEdtAndGet(entry::getText);
                try {
                    fixture.replace(entry, savedEntry + "// a real input still invalidates immediately\n");
                    await(() -> {
                        var latest = fixture.service.latest();
                        return latest != null && latest.generation() == completed.generation() && !latest.success()
                            && latest.items().isEmpty() && fixture.outcomes.stream().anyMatch(shown -> shown == latest);
                    }, "a real input edit must still invalidate completed results after alias checks move off the EDT");
                    quietEdt();
                    assertEquals(launchesAfterBuild, fixture.launches.size());
                    assertEquals(publicationsAfterBuild + 1, fixture.outcomes.size());
                    assertEquals(0, filesystemCallsOnEdt.get(), "no observed filesystem validation may execute on the EDT");
                    assertFalse(observerTimedOut.get());
                } finally {
                    fixture.restore(entry, savedEntry);
                }
            } finally {
                // Always unblock workers before fixture/project disposal, even
                // if an assertion or the deliberately bounded UI check fails.
                holdBackgroundCheck.set(false);
                releaseBackground.countDown();
                fixture.restore(note, savedNote);
            }
        }
        passed("keepsFilesystemValidationOffEdt");
    }

    @Test
    void rejectsMissingManifestWithoutActiveFileFallback() throws Exception {
        runCaseOffEdt(this::rejectsMissingManifestWithoutActiveFileFallbackOffEdt);
    }

    private void rejectsMissingManifestWithoutActiveFileFallbackOffEdt() throws Exception {
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

    private record SelectedPoint(Path path, int caret) {}

    private static SelectedPoint selectedPoint(Project project) {
        return EdtTestUtil.runInEdtAndGet(() -> {
            var editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor == null) return new SelectedPoint(null, -1);
            VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
            return new SelectedPoint(file == null ? null : Path.of(file.getPath()), editor.getCaretModel().getOffset());
        });
    }

    private static boolean samePhysicalFile(Path first, Path second) {
        assertFalse(ApplicationManager.getApplication().isDispatchThread(), "physical file identity checks must remain off EDT");
        if (first == null || second == null) return false;
        try { return Files.isSameFile(first, second); }
        catch (java.io.IOException ignored) { return false; }
    }

    private record ColdVfsState(Path path, boolean valid, boolean documentCached,
                                boolean documentUnsaved, boolean documentMatches,
                                boolean selectedVfsIdentity, boolean selectedDocumentIdentity) {}

    /** Failure-only, fixed fields: never emit paths, source text, or raw exception/status messages. */
    private static String coldNavigationState(Fixture fixture, VasProjectBuildService.Outcome completed,
                                             VasProjectBuildService.Item item, Path expected, long initialRevision) {
        assertFalse(ApplicationManager.getApplication().isDispatchThread());
        var latest = fixture.service.latest();
        SelectedPoint selected = selectedPoint(fixture.project);
        ColdVfsState cached = EdtTestUtil.runInEdtAndGet(() -> {
            VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(expected);
            if (file == null) return new ColdVfsState(null, false, false, false, false, false, false);
            var manager = FileDocumentManager.getInstance();
            Document document = manager.getCachedDocument(file);
            var editor = FileEditorManager.getInstance(fixture.project).getSelectedTextEditor();
            return new ColdVfsState(Path.of(file.getPath()), file.isValid(), document != null,
                document != null && manager.isDocumentUnsaved(document),
                document != null && document.getText().equals(item.location().snapshot().editorText()),
                editor != null && manager.getFile(editor.getDocument()) == file,
                editor != null && document != null && editor.getDocument() == document);
        });
        String snapshot;
        try { snapshot = item.location().snapshot().unchanged(true) ? "unchanged" : "changed"; }
        catch (java.io.IOException | RuntimeException ignored) { snapshot = "check-error"; }
        String selectedIdentity = selected.path() == null ? "none"
            : samePhysicalFile(selected.path(), expected) ? "external"
            : samePhysicalFile(selected.path(), fixture.decoy) ? "decoy" : "other";
        return "cold-navigation{status=" + outcomeCategory(latest)
            + ",expectedGeneration=" + completed.generation()
            + ",latestGeneration=" + (latest == null ? -1 : latest.generation())
            + ",sameOutcome=" + (latest == completed)
            + ",initialRevision=" + initialRevision + ",currentRevision=" + fixture.service.inputRevision(completed)
            + ",snapshot=" + snapshot + ",vfsCached=" + (cached.path() != null) + ",vfsValid=" + cached.valid()
            + ",lexicalIdentity=" + expected.equals(cached.path()) + ",physicalIdentity=" + samePhysicalFile(cached.path(), expected)
            + ",documentCached=" + cached.documentCached() + ",documentUnsaved=" + cached.documentUnsaved()
            + ",documentMatches=" + cached.documentMatches() + ",selected=" + selectedIdentity
            + ",selectedVfsIdentity=" + cached.selectedVfsIdentity()
            + ",selectedDocumentIdentity=" + cached.selectedDocumentIdentity()
            + ",caret=" + selected.caret() + ",expectedCaret=" + item.location().offset()
            + ",publications=" + fixture.outcomes.size() + ",launches=" + fixture.launches.size() + "}";
    }

    private static String outcomeCategory(VasProjectBuildService.Outcome outcome) {
        if (outcome == null) return "missing";
        if (outcome.success()) return "success";
        String status = outcome.status().toLowerCase(java.util.Locale.ROOT);
        if (status.startsWith("build failed")) return "build-failed";
        if (status.contains("trust")) return "trust";
        if (status.contains("save")) return "save-required";
        if (status.contains("input") || status.contains("files changed")) return "input-changed";
        if (status.contains("compiler")) return "compiler";
        if (status.contains("cancel")) return "cancelled";
        if (status.equals("reading project…") || status.equals("building…")) return "progress";
        return "other-failure";
    }

    @FunctionalInterface
    private interface NativeCase {
        void run() throws Exception;
    }

    /**
     * Build 262's Rider EdtInvocationInterceptor runs @Test methods on EDT.
     * PlatformTestUtil.waitForFuture pumps the IDE invocation queue and releases
     * its write-intent lock while dispatching; a plain Future.get/join here would
     * deadlock the worker's existing EdtTestUtil calls and production callbacks.
     */
    private static void runCaseOffEdt(NativeCase testCase) throws Exception {
        var application = ApplicationManager.getApplication();
        assertTrue(application.isDispatchThread(), "the Rider test entry point must retain the host's EDT lifecycle");
        assertFalse(application.isWriteAccessAllowed(), "do not pump the case under a write action");
        CompletableFuture<Throwable> completion = new CompletableFuture<>();
        Future<?> worker = application.executeOnPooledThread(() -> {
            Throwable failure = null;
            try {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Native Rider case was cancelled before orchestration started");
                }
                // CoreProgressManager otherwise runs Backgroundable tasks in the
                // caller's thread in unit/headless mode. This supported platform
                // switch exercises the real pooled run + EDT callback path.
                // Restore inside the worker's finally, after case/fixture cleanup:
                // an outer timeout must not restore it while that work still runs.
                PlatformTestUtil.withSystemProperty("intellij.progress.task.ignoreHeadless", "true", () -> {
                    assertTrue(CoreProgressManager.shouldKeepTasksAsynchronous());
                    assertFalse(application.isDispatchThread(), "blocking test orchestration must run off EDT");
                    assertFalse(application.isReadAccessAllowed(), "the worker must not inherit an EDT read action");
                    testCase.run();
                });
            } catch (Throwable thrown) {
                failure = thrown;
            } finally {
                // Complete only after the test body, its cleanup, and property
                // restoration. Future cancellation alone is not completion.
                completion.complete(failure);
            }
        });
        final Throwable failure;
        try {
            failure = PlatformTestUtil.waitForFuture(completion, TimeUnit.MINUTES.toMillis(3));
        } catch (RuntimeException | Error waitingFailure) {
            // Interrupted latches release their test barriers in finally. Keep
            // dispatching EDT cleanup after interrupting the worker; never join
            // it from EDT or mistake worker.cancel(true) for finished cleanup.
            boolean interrupted = Thread.interrupted()
                || waitingFailure.getCause() instanceof InterruptedException;
            worker.cancel(true);
            try {
                PlatformTestUtil.waitForFuture(completion, TimeUnit.SECONDS.toMillis(10));
            } catch (RuntimeException | Error cleanupFailure) {
                waitingFailure.addSuppressed(cleanupFailure);
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
            throw waitingFailure;
        }
        // The completion carries the original assertion/exception so JUnit sees
        // worker failures directly, rather than a successful pooled submission.
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new AssertionError(failure);
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
        await(condition, () -> message);
    }

    private static void await(BooleanSupplier condition, Supplier<String> message) throws InterruptedException {
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
                    service.installLaunchObserver(args -> {
                        assertFalse(ApplicationManager.getApplication().isDispatchThread(),
                            "the actual native compiler launch must run in the background");
                        launches.add(List.copyOf(args));
                    }, lifetime);
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
