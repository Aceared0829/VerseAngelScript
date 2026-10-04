package com.verseangelscript.rider.diagnostics;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.TestDaemonCodeAnalyzerImpl;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.ide.impl.TrustedPaths;
import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.lang.ExternalAnnotatorsFilter;
import com.intellij.lang.ExternalLanguageAnnotators;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.testFramework.ServiceContainerUtil;
import com.jetbrains.rider.test.OpenSolutionParams;
import com.jetbrains.rider.test.annotations.Solution;
import com.jetbrains.rider.test.annotations.TestSettings;
import com.jetbrains.rider.test.enums.BuildTool;
import com.jetbrains.rider.test.enums.Mono;
import com.jetbrains.rider.test.enums.sdk.SdkVersion;
import com.jetbrains.rider.test.junit5.base.PerTestSolutionTestBase;
import com.verseangelscript.rider.VasLanguage;
import com.verseangelscript.rider.build.VasSettingsState;
import com.verseangelscript.rider.build.VasToolchainSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real Rider solution/editor passes with no project-supplied process execution. */
@Solution(name = "vas-navigation", slnName = "VasNavigation.sln")
@TestSettings(buildTool = BuildTool.AUTODETECT, mono = Mono.NONE, sdkVersion = SdkVersion.NONE)
public final class VasRiderTrustIntegrationTest extends PerTestSolutionTestBase {
    private static final String SOURCE = "void main() {\n\t/*漢字😀*/\tmissing();\n}";
    private static final String MESSAGE = "VAS trust fixture diagnostic";
    private static final String AUDIT_PREFIX = "VAS_RIDER_TRUST_AUDIT_V1 ";
    private static final String FILTER_CLASS = "(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+[A-Za-z_$][A-Za-z0-9_$]*";

    @Override
    public void modifyOpenSolutionParams(OpenSolutionParams parameters) {
        super.modifyOpenSolutionParams(parameters);
        parameters.setWaitForCaches(true);
        parameters.setWaitForSolutionBuilder(true);
        parameters.setRestoreNuGetPackages(false);
    }

    @Test
    @Tag("season/vas")
    void usesApplicationCompilerWithUnsavedModuleAndIncludeSnapshots() throws Exception {
        String compiler = System.getenv("VAS_TEST_COMPILER"), config = System.getenv("VAS_TEST_CONFIG");
        org.junit.jupiter.api.Assumptions.assumeTrue(compiler != null && config != null, "Set native compiler/config to verify real background compilation");
        Project project = getSolutionApiFacade().getProject();
        Path root = getSolutionApiFacade().getActiveSolutionDirectory();
        VirtualFile main = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src/arena/main.vas"));
        VirtualFile demo = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src/arena/Arena/Demo.vas"));
        assertNotNull(main); assertNotNull(demo);
        PsiFile mainPsi = ReadAction.computeBlocking(() -> PsiManager.getInstance(project).findFile(main));
        PsiFile demoPsi = ReadAction.computeBlocking(() -> PsiManager.getInstance(project).findFile(demo));
        var documents = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance();
        Document mainDocument = ReadAction.computeBlocking(() -> documents.getDocument(main));
        Document demoDocument = ReadAction.computeBlocking(() -> documents.getDocument(demo));
        String beforeMain = mainDocument.getText(), beforeDemo = demoDocument.getText();
        byte[] beforeMainBytes = Files.readAllBytes(Path.of(main.getPath()));
        var settings = VasSettingsState.getInstance(project); var app = VasToolchainSettings.getInstance();
        String previousBuilder = settings.builderPath, previousConfig = settings.configPath, previousCompiler = app.compilerPath;
        Disposable lifetime = Disposer.newDisposable("Native VAS editing snapshots");
        try {
            EdtTestUtil.runInEdtAndWait(() -> {
                TrustedPaths isolatedTrust = new TrustedPaths(); isolatedTrust.loadState(TrustedPaths.getInstance().getState());
                ServiceContainerUtil.replaceService(ApplicationManager.getApplication(), TrustedPaths.class, isolatedTrust, lifetime);
                setTrust(project, true); settings.builderPath = ""; settings.configPath = config; app.compilerPath = compiler;
                replaceText(project, mainDocument, beforeMain.replace("return 2;", "return MissingEditorName;"));
            });
            var annotator = registeredAnnotator();
            var request = ReadAction.computeBlocking(() -> annotator.collectInformation(mainPsi));
            var result = annotator.doAnnotate(request);
            assertNotNull(result);
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.message().contains("MissingEditorName")), result.diagnostics().toString());
            EdtTestUtil.runInEdtAndWait(() -> {
                replaceText(project, mainDocument, beforeMain);
                replaceText(project, demoDocument, beforeDemo + "\nvoid BrokenForEditor() { UnknownEditorCall(); }\n");
            });
            var moduleRequest = ReadAction.computeBlocking(() -> annotator.collectInformation(demoPsi));
            var moduleResult = annotator.doAnnotate(moduleRequest);
            assertNotNull(moduleResult);
            assertTrue(moduleResult.diagnostics().stream().anyMatch(d -> d.message().contains("UnknownEditorCall")), moduleResult.diagnostics().toString());
            EdtTestUtil.runInEdtAndWait(() -> replaceText(project, demoDocument, beforeDemo));
            var fixed = annotator.doAnnotate(ReadAction.computeBlocking(() -> annotator.collectInformation(mainPsi)));
            assertNotNull(fixed); assertTrue(fixed.diagnostics().isEmpty(), fixed.diagnostics().toString());
            assertArrayEquals(beforeMainBytes, Files.readAllBytes(Path.of(main.getPath())), "native snapshot must not save the editor document");
        } finally {
            EdtTestUtil.runInEdtAndWait(() -> {
                replaceText(project, mainDocument, beforeMain); replaceText(project, demoDocument, beforeDemo);
                settings.builderPath = previousBuilder; settings.configPath = previousConfig; app.compilerPath = previousCompiler;
                Disposer.dispose(lifetime);
            });
        }
    }

    @Test
    @Tag("season/vas")
    void guardsRegisteredBackgroundDiagnosticsAcrossTrustChanges() throws Exception {
        Project project = getSolutionApiFacade().getProject();
        assertFalse(project.isDefault());
        Path root = getSolutionApiFacade().getActiveSolutionDirectory();
        Path builder = Files.createTempFile(root, "recording-builder-", ".txt");
        Path config = Files.createTempFile(root, "recording-config-", ".txt");
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src/main.vas"));
        assertNotNull(file);
        PsiFile psi = ReadAction.computeBlocking(() -> PsiManager.getInstance(project).findFile(file));
        assertNotNull(psi);
        VasExternalAnnotator annotator = registeredAnnotator();
        Disposable lifetime = Disposer.newDisposable("VAS diagnostic trust test");
        AtomicInteger launches = new AtomicInteger();
        VasSettingsState settings = VasSettingsState.getInstance(project);
        String oldBuilder = settings.builderPath;
        String oldConfig = settings.configPath;
        DaemonCodeAnalyzerImpl daemon = (DaemonCodeAnalyzerImpl) DaemonCodeAnalyzerImpl.getInstance(project);
        boolean oldTimer = daemon.isUpdateByTimerEnabled();
        TestDaemonCodeAnalyzerImpl passes = new TestDaemonCodeAnalyzerImpl(project);
        try {
            EdtTestUtil.runInEdtAndWait(() -> {
                passes.prepareForTest();
                // Replace only the test application's storage. Never write real trust
                // settings or disable trust globally. Disposal restores the old service.
                TrustedPaths isolatedTrust = new TrustedPaths();
                isolatedTrust.loadState(TrustedPaths.getInstance().getState());
                ServiceContainerUtil.replaceService(ApplicationManager.getApplication(),
                    TrustedPaths.class, isolatedTrust, lifetime);
                VasExternalAnnotator.installTestLauncher(project, (command, timeout) -> {
                    launches.incrementAndGet();
                    assertEquals(builder.toString(), command.getExePath());
                    assertEquals(20_000, timeout);
                    List<String> arguments = command.getParametersList().getList();
                    assertEquals(config.toString(), arguments.get(0));
                    ProcessOutput output = new ProcessOutput();
                    output.appendStdout(arguments.get(1) + " (2, 17) : ERR : " + MESSAGE + "\n");
                    output.appendStderr(builder + " (1, 1) : WARN : another file\n");
                    output.setExitCode(1);
                    return output;
                }, lifetime);
                // Relative paths can come from the project's vas.xml itself.
                Path projectRoot = Path.of(project.getBasePath());
                settings.builderPath = projectRoot.relativize(builder).toString();
                settings.configPath = projectRoot.relativize(config).toString();
                setTrust(project, false);
                reportFilters(annotator, psi, false);
            });

            assertNull(ReadAction.computeBlocking(() -> annotator.collectInformation(psi)));
            // Even a caller that bypasses collection cannot execute a queued request.
            assertNull(annotator.doAnnotate(new VasExternalAnnotator.Request(project, file, SOURCE)));
            assertEquals(0, launches.get());

            TextEditor editor = EdtTestUtil.runInEdtAndGet(() -> {
                var opened = FileEditorManager.getInstance(project)
                    .openTextEditor(new OpenFileDescriptor(project, file), true);
                assertNotNull(opened);
                return TextEditorProvider.getInstance().getTextEditor(opened);
            });
            Document document = editor.getEditor().getDocument();
            EdtTestUtil.runInEdtAndWait(() -> {
                replaceText(project, document, SOURCE);
                runPasses(project, psi, editor, passes);
                assertEquals(0, launches.get(), "opening an untrusted VAS file must not launch a compiler");
                replaceText(project, document, SOURCE + "\n// edited while untrusted\n");
                runPasses(project, psi, editor, passes);
                assertEquals(0, launches.get(), "editing an untrusted VAS file must not launch a compiler");
                assertFalse(TrustedProjects.isProjectTrusted(project));
                setTrust(project, true);
            });

            VasExternalAnnotator.Request queued = ReadAction.computeBlocking(() -> annotator.collectInformation(psi));
            assertNotNull(queued);
            EdtTestUtil.runInEdtAndWait(() -> setTrust(project, false));
            assertNull(annotator.doAnnotate(queued), "a previously collected request must recheck trust");
            assertEquals(0, launches.get());

            EdtTestUtil.runInEdtAndWait(() -> setTrust(project, true));
            VirtualFile losesTrustDuringPreparation = new LightVirtualFile("snapshot.vas") {
                @Override
                public String getPath() {
                    EdtTestUtil.runInEdtAndWait(() -> setTrust(project, false));
                    return file.getPath();
                }
            };
            assertNull(annotator.doAnnotate(new VasExternalAnnotator.Request(
                project, losesTrustDuringPreparation, SOURCE)), "trust must be checked again immediately before launch");
            assertFalse(TrustedProjects.isProjectTrusted(project));
            assertEquals(0, launches.get());

            EdtTestUtil.runInEdtAndWait(() -> setTrust(project, true));
            VasExternalAnnotator.Result result = annotator.doAnnotate(queued);
            assertNotNull(result);
            assertEquals(1, launches.get());
            assertEquals(1, result.diagnostics().size(), "only the submitted file's diagnostic is retained");
            assertEquals(MESSAGE, result.diagnostics().getFirst().message());
            assertEquals(2, result.diagnostics().getFirst().line());
            assertEquals(17, result.diagnostics().getFirst().column());

            EdtTestUtil.runInEdtAndWait(() -> {
                reportFilters(annotator, psi, true);
                runPasses(project, psi, editor, passes);
                assertTrue(launches.get() > 1, "the registered ordinary highlighting pass must reach the recording launcher");
                HighlightInfo diagnostic = DaemonCodeAnalyzerImpl.getHighlights(document, HighlightSeverity.WARNING, project)
                    .stream().filter(info -> MESSAGE.equals(info.getDescription())).findFirst().orElseThrow();
                int start = document.getText().indexOf("missing");
                assertEquals(start, diagnostic.getStartOffset());
                assertEquals(start + "missing".length(), diagnostic.getEndOffset());

                settings.builderPath = " ";
                int beforeBlank = launches.get();
                runPasses(project, psi, editor, passes);
                assertEquals(beforeBlank, launches.get(), "trusted projects still require a configured builder");
                assertTrue(TrustedProjects.isProjectTrusted(project));
            });
            assertNull(annotator.doAnnotate(queued), "blank builder also disables a direct queued request");
        } finally {
            EdtTestUtil.runInEdtAndWait(() -> {
                settings.builderPath = oldBuilder;
                settings.configPath = oldConfig;
                try {
                    FileEditorManager.getInstance(project).closeFile(file);
                    passes.cleanupAfterTest();
                } finally {
                    Disposer.dispose(lifetime);
                    daemon.setUpdateByTimerEnabled(oldTimer);
                }
            });
            Files.deleteIfExists(builder);
            Files.deleteIfExists(config);
        }
    }

    private static VasExternalAnnotator registeredAnnotator() {
        return ExternalLanguageAnnotators.INSTANCE.allForLanguage(VasLanguage.INSTANCE).stream()
            .filter(VasExternalAnnotator.class::isInstance).map(VasExternalAnnotator.class::cast)
            .findFirst().orElseThrow(() -> new AssertionError("VAS external annotator must be registered"));
    }

    private static void setTrust(Project project, boolean trusted) {
        TrustedProjects.setProjectTrusted(project, trusted);
        // Unknown projects are implicitly trusted in headless tests. Explicit false
        // must take precedence; a null collect result alone would not prove this.
        boolean actual = TrustedProjects.isProjectTrusted(project);
        assertEquals(trusted, actual);
        System.out.println(AUDIT_PREFIX + "TRUST trusted=" + actual);
    }

    private static void reportFilters(VasExternalAnnotator annotator, PsiFile psi, boolean trusted) {
        assertEquals(trusted, TrustedProjects.isProjectTrusted(psi.getProject()));
        List<FilterDecision> filters = ReadAction.computeBlocking(() -> ExternalAnnotatorsFilter.EXTENSION_POINT_NAME.getExtensionList()
            .stream().map(filter -> new FilterDecision(filter.getClass().getName(), filter.isProhibited(annotator, psi))).toList());
        System.out.println(AUDIT_PREFIX + "FILTERS trusted=" + trusted + " count=" + filters.size());
        for (FilterDecision filter : filters) {
            // Audit output is deliberately limited to class names and booleans;
            // never include toString(), exceptions, paths, or process output.
            assertTrue(filter.className().matches(FILTER_CLASS), "Unexpected filter class name format");
            System.out.println(AUDIT_PREFIX + "FILTER trusted=" + trusted
                + " class=" + filter.className() + " prohibited=" + filter.prohibited());
        }
    }

    private record FilterDecision(String className, boolean prohibited) {
    }

    private static void replaceText(Project project, Document document, String text) {
        WriteCommandAction.runWriteCommandAction(project, () -> document.setText(text));
        PsiDocumentManager.getInstance(project).commitAllDocuments();
    }

    private static void runPasses(Project project, PsiFile psi, TextEditor editor,
                                  TestDaemonCodeAnalyzerImpl passes) throws Exception {
        ((DaemonCodeAnalyzerImpl) DaemonCodeAnalyzerImpl.getInstance(project)).restart(psi, "VAS trust regression");
        passes.runPasses(psi, editor.getEditor().getDocument(), editor, new int[0], false, true, null);
    }
}
