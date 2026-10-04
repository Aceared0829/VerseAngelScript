package com.verseangelscript.rider.lang;

import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.WriteIntentReadAction;
import com.intellij.openapi.command.UndoConfirmationPolicy;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiCheckedRenameElement;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiReferenceService;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.refactoring.rename.RenameProcessor;
import com.intellij.refactoring.rename.RenameUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.indexing.FileBasedIndex;
import com.jetbrains.rider.test.annotations.Solution;
import com.jetbrains.rider.test.OpenSolutionParams;
import com.jetbrains.rider.test.annotations.TestSettings;
import com.jetbrains.rider.test.enums.BuildTool;
import com.jetbrains.rider.test.enums.Mono;
import com.jetbrains.rider.test.enums.sdk.SdkVersion;
import com.jetbrains.rider.test.junit5.base.PerTestSolutionTestBase;
import com.verseangelscript.rider.VasFileType;
import com.verseangelscript.rider.index.VasSymbolResolver;
import com.verseangelscript.rider.index.VasSymbolIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs with Rider's frontend/backend test framework instead of the generic
 * IntelliJ light fixture. This ensures the plugin is exercised after a Rider
 * solution has been opened and its project services are available.
 */
@Solution(name = "vas-navigation", slnName = "VasNavigation.sln")
@TestSettings(buildTool = BuildTool.AUTODETECT, mono = Mono.NONE, sdkVersion = SdkVersion.NONE)
public final class VasRiderSolutionIntegrationTest extends PerTestSolutionTestBase {
    @Override
    public void modifyOpenSolutionParams(OpenSolutionParams params) {
        super.modifyOpenSolutionParams(params);
        // The NMake project must finish creating its real project/content model
        // before default-scope ReferencesSearch and rename preflight are exercised.
        params.setWaitForCaches(true);
        params.setWaitForSolutionBuilder(true);
        params.setRestoreNuGetPackages(false);
    }

    @Test
    @Tag("season/vas")
    void navigatesAndIndexesArenaModulesWithExportedMacros() {
        Project project = getSolutionApiFacade().getProject();
        Path sourceDirectory = getSolutionApiFacade().getActiveSolutionDirectory().resolve("src/arena");
        ApplicationManager.getApplication().runReadAction(() -> {
            PsiFile main = fixture(project, sourceDirectory, "main.vas");
            PsiFile demo = fixture(project, sourceDirectory, "Arena/Demo.vas");
            PsiFile config = fixture(project, sourceDirectory, "Arena/Config.vas");
            assertProjectIndexedFile(project, demo, "RunDemo");
            assertProjectIndexedFile(project, config, "VAS_ARENA_MAX_ROUNDS");
            var symbols = new VasGotoSymbolContributor();
            assertTrue(List.of(symbols.getNames(project, false)).contains("RunDemo"));
            assertTrue(symbols.getItemsByName("RunDemo", "RunDemo", project, false).length > 0);
            assertTrue(symbols.getItemsByName("VAS_ARENA_MAX_ROUNDS", "VAS_ARENA_MAX_ROUNDS", project, false).length > 0);
            assertEquals(8, VasSymbolResolver.inspectDependencyClosure(main).includedFiles().size());
            PsiElement imported = main.findElementAt(main.getText().indexOf("import Arena.Demo"));
            assertEquals(demo, new VasDirectNavigationProvider().getNavigationElement(imported));
            PsiElement runDemo = main.findElementAt(main.getText().indexOf("RunDemo("));
            PsiElement target = new VasDirectNavigationProvider().getNavigationElement(runDemo);
            assertNotNull(target, "macro/conditional directives must not erase known navigation candidates");
            assertEquals(demo, target.getContainingFile());
            assertEquals("RunDemo", target.getText());
            assertTrue(VasSymbolResolver.findDeclarations(runDemo).isEmpty(), "rename coverage remains conservative");
            PsiElement runBatch = demo.findElementAt(demo.getText().indexOf("RunBatch("));
            var manager = ((com.intellij.find.impl.FindManagerImpl)com.intellij.find.FindManager.getInstance(project)).getFindUsagesManager();
            var handler = manager.getFindUsagesHandler(runBatch, false);
            assertNotNull(handler, "native Find Usages must have a VAS handler");
            assertTrue(handler instanceof VasFindUsagesHandlerFactory.Handler, "the registered native handler must serve VAS");
            var options = handler.getFindUsagesOptions(); options.searchScope = GlobalSearchScope.projectScope(project);
            var usages = new java.util.ArrayList<com.intellij.usageView.UsageInfo>();
            assertTrue(handler.processElementUsages(runBatch, info -> { usages.add(info); return true; }, options));
            assertEquals(1, usages.size(), "RunBatch has one source usage even when imported modules contain macros");
            assertEquals(main, usages.getFirst().getFile());
            assertEquals(main.getText().indexOf("RunBatch("), usages.getFirst().getNavigationOffset());
            assertEquals("1 usage", new VasReferencesCodeVisionProvider().getHint(runBatch, demo));
            assertEquals(List.of("main"), VasSymbolResolver.findCallers(runBatch).stream().map(PsiElement::getText).toList());
            PsiElement call = main.findElementAt(main.getText().indexOf("RunBatch("));
            assertTrue(new VasFindUsagesProvider().canFindUsagesFor(call));
            var callHandler = manager.getFindUsagesHandler(call, false);
            assertNotNull(callHandler, "Find Usages must also work from a call, not only its declaration");
            assertEquals(runBatch, callHandler.getPsiElement());
            PsiFile combatant = fixture(project, sourceDirectory, "Arena/Combatant.vas");
            assertTrue(VasSymbolResolver.navigationFiles(combatant).stream().anyMatch(file -> file.equals(config)));
            assertTrue(VasSymbolResolver.navigationFiles(main).stream().anyMatch(file -> file.equals(combatant)));
            assertProjectIndexedFile(project, combatant, "FCombatant");
            assertProjectIndexedFile(project, combatant, "Warrior");
            var role = combatant.findElementAt(combatant.getText().indexOf("Warrior, 90"));
            var roleTarget = new VasDirectNavigationProvider().getNavigationElement(role);
            assertNotNull(roleTarget);
            assertEquals("Warrior", roleTarget.getText());
            assertTrue(roleTarget.getTextOffset() < role.getTextOffset());
        });
    }

    @Test
    @Tag("season/vas")
    void resolvesNestedIncludeAndDeclarationInOpenedRiderSolution() {
        Project project = getSolutionApiFacade().getProject();
        assertFalse(project.isDefault(), "the Rider solution must be opened before navigation is tested");

        Path solutionDirectory = getSolutionApiFacade().getActiveSolutionDirectory();
        VirtualFile mainFile = LocalFileSystem.getInstance()
            .refreshAndFindFileByNioFile(solutionDirectory.resolve("src/main.vas"));
        assertNotNull(mainFile);
        assertEquals(VasFileType.INSTANCE, mainFile.getFileType());

        PsiFile mainPsi = PsiManager.getInstance(project).findFile(mainFile);
        assertNotNull(mainPsi);

        PsiElement include = mainPsi.findElementAt(mainPsi.getText().indexOf("#include"));
        PsiElement includeTarget = new VasDirectNavigationProvider().getNavigationElement(include);
        assertNotNull(includeTarget);
        assertEquals("api.vas", includeTarget.getContainingFile().getName());

        int usageOffset = mainPsi.getText().lastIndexOf("add");
        PsiElement usage = mainPsi.findElementAt(usageOffset);
        PsiElement declaration = new VasDirectNavigationProvider().getNavigationElement(usage);
        assertNotNull(declaration);
        assertEquals("add", declaration.getText());
        assertEquals("math.vas", declaration.getContainingFile().getName());
    }

    @Test
    @Tag("season/vas")
    void keepsReferenceIdentitySafeAcrossScopesOverloadsAndIncludeCycles() {
        Project project = getSolutionApiFacade().getProject();
        assertFalse(project.isDefault(), "the safety cases require an opened Rider solution");
        Path sourceDirectory = getSolutionApiFacade().getActiveSolutionDirectory().resolve("src");
        PsiFile source = fixture(project, sourceDirectory, "safety.vas");
        PsiFile api = fixture(project, sourceDirectory, "safety-api.vas");
        PsiFile nested = fixture(project, sourceDirectory, "safety-nested.vas");
        PsiFile unrelated = fixture(project, sourceDirectory, "safety-unrelated.vas");
        PsiFile commented = fixture(project, sourceDirectory, "safety-commented.vas");

        assertResolvesTo(source, "member-call", api, "owned-declaration");
        assertResolvesTo(source, "safe-field-use", api, "safe-field-declaration");
        assertResolvesTo(source, "inner-receiver-call", api, "foreign-declaration");
        assertResolvesTo(source, "outer-receiver-call", api, "owned-declaration");
        assertUnresolved(source, "wrong-owner-call");
        assertUnresolved(source, "wrong-member-arity-call");
        assertUnresolved(source, "unknown-receiver-call");

        // A same-file declaration with the wrong arity must not hide an included match.
        assertResolvesTo(source, "included-overload-call", nested, "included-overload-declaration");
        assertUnresolved(source, "wrong-global-arity-call");
        assertAmbiguous(source, "same-arity-call", List.of(
            markedIdentifier(source, "same-arity-int-declaration"),
            markedIdentifier(nested, "same-arity-float-declaration")
        ));

        assertResolvesTo(source, "default-one-argument-call", api, "default-declaration");
        assertResolvesTo(source, "default-two-arguments-call", api, "default-declaration");
        assertUnresolved(source, "default-too-few-call");
        assertUnresolved(source, "default-too-many-call");
        assertAmbiguous(source, "default-overlap-call", List.of(
            markedIdentifier(source, "exact-overlap-declaration"),
            markedIdentifier(api, "default-overlap-declaration")
        ));

        assertResolvesTo(source, "before-local-use", source, "global-order-declaration");
        assertResolvesTo(source, "after-local-use", source, "local-order-declaration");
        assertResolvesTo(source, "nested-local-use", source, "nested-order-declaration");
        assertResolvesTo(source, "after-nested-use", source, "local-order-declaration");
        assertResolvesTo(source, "parameter-use", source, "parameter-declaration");

        // The root includes nested directly and through api; nested includes api again.
        assertResolvesTo(source, "cycle-call", nested, "cycle-declaration");
        assertNotNull(VasSymbolResolver.findSymbol(
            markedIdentifier(unrelated, "unrelated-declaration")).orElse(null));
        assertNotNull(VasSymbolResolver.findSymbol(
            markedIdentifier(commented, "commented-declaration")).orElse(null));
        assertUnresolved(source, "unrelated-call");
        assertUnresolved(source, "commented-include-call");

        for (String marker : List.of(
            "global-order-declaration", "local-order-declaration", "nested-order-declaration",
            "parameter-declaration", "same-arity-int-declaration", "exact-overlap-declaration"
        )) {
            PsiElement declaration = markedIdentifier(source, marker);
            assertTrue(VasSymbolResolver.findSymbol(declaration).isPresent(), marker);
            assertTrue(VasSymbolResolver.findDeclarations(declaration).isEmpty(), marker);
            assertEquals(0, new VasReference(declaration).multiResolve(false).length, marker);
        }
        assertFalse(new VasReference(markedIdentifier(source, "parameter-declaration"))
            .isReferenceTo(markedIdentifier(source, "global-order-declaration")),
            "a declaration must not be renamed as a reference to a same-name outer symbol");
        assertFalse(new VasReference(markedIdentifier(source, "member-call"))
            .isReferenceTo(markedIdentifier(source, "unrelated-local-declaration")),
            "member references must not rename a same-name local");

        String sourceBefore = source.getText();
        String apiBefore = api.getText();
        String nestedBefore = nested.getText();
        // RenameUtil.checkRename is the actual RenameProcessor preflight hook.
        // Rejection must happen before the declaration or any possible usage is changed.
        assertRenameAllowed(markedIdentifier(source, "local-order-declaration"), "localOrderRenamed");
        assertRenameAllowed(markedIdentifier(source, "parameter-declaration"), "parameterRenamed");
        assertRenameAllowed(markedIdentifier(source, "global-order-declaration"), "globalOrderRenamed");
        assertRenameAllowed(markedIdentifier(nested, "cycle-declaration"), "CycleLeafRenamed");
        assertRenameAllowed(markedIdentifier(api, "safe-field-declaration"), "SafeValueRenamed");
        PsiElement ownerType = api.findElementAt(api.getText().indexOf("SafetyOwner"));
        assertNotNull(ownerType);
        assertRenameAllowed(ownerType, "SafetyOwnerRenamed");
        assertRenameRejected(markedIdentifier(source, "same-arity-int-declaration"), "AmbiguousRenamed");
        assertRenameRejected(markedIdentifier(source, "exact-overlap-declaration"), "OverlapRenamed");
        assertRenameRejected(markedIdentifier(source, "unrelated-local-declaration"), "OwnedRenamed");
        assertRenameRejected(markedIdentifier(source, "same-arity-call"), "UnknownTargetRenamed");
        assertRenameRejected(markedIdentifier(source, "local-order-declaration"), "if");
        assertRenameRejected(markedIdentifier(source, "local-order-declaration"), "two names");
        assertRenameRejected(markedIdentifier(source, "local-order-declaration"), "$renamed");
        assertRenameRejected(markedIdentifier(source, "local-order-declaration"), "after");
        assertRenameRejected(markedIdentifier(source, "parameter-declaration"), "CycleLeaf");
        assertRenameRejected(markedIdentifier(source, "global-order-declaration"), "print");
        assertEquals(sourceBefore, source.getText(), "rename preflight must not mutate the source");
        assertEquals(apiBefore, api.getText(), "rename preflight must not mutate direct includes");
        assertEquals(nestedBefore, nested.getText(), "rename preflight must not mutate nested includes");
    }

    private static void assertRenameAllowed(PsiElement target, String newName) {
        assertTrue(target instanceof PsiCheckedRenameElement, "VAS declarations must expose the platform preflight hook");
        assertDoesNotThrow(() -> RenameUtil.checkRename(target, newName),
            "Expected safe rename of " + target.getContainingFile().getName() + ":" + target.getTextOffset()
                + " " + target.getText() + " -> " + newName);
    }

    @Test
    @Tag("season/vas")
    void registersReferencesAndPerformsAtomicRenameAndUndoInTheRiderHost() {
        Project project = getSolutionApiFacade().getProject();
        Path sourceDirectory = getSolutionApiFacade().getActiveSolutionDirectory().resolve("src");
        runOnEdtWithWriteIntent(() -> {
            assertTrue(ApplicationManager.getApplication().isUnitTestMode(),
                "rejected refactorings must use the platform's noninteractive test-mode error path");
            DumbService.getInstance(project).completeJustSubmittedTasks();
            PsiFile source = fixture(project, sourceDirectory, "rename-lifecycle.vas");
            PsiFile api = fixture(project, sourceDirectory, "rename-lifecycle-api.vas");
            assertProjectIndexedFile(project, source, "RenameLifecycle");
            assertProjectIndexedFile(project, api, "StableRename");
            PsiDocumentManager documents = PsiDocumentManager.getInstance(project);
            assertNotNull(documents.getDocument(source));
            assertNotNull(documents.getDocument(api));
            documents.commitAllDocuments();

            PsiElement target = markedIdentifier(api, "rename-function-declaration");
            assertEquals("StableRename", ((PsiNamedElement)target).getName());
            PsiElement firstUse = markedIdentifier(source, "rename-first-use");
            assertNotNull(firstUse.getReference());
            assertTrue(firstUse.getReference().isReferenceTo(target));
            assertTrue(PsiReferenceService.getService()
                .getReferences(firstUse, PsiReferenceService.Hints.NO_HINTS).stream()
                .anyMatch(reference -> reference.isReferenceTo(target)),
                "the platform service must discover contributed references without constructing VasReference directly");
            assertActualUsageOffsets(target, source, "rename-first-use", "rename-second-use");
            assertActualUsageOffsets(markedIdentifier(source, "rename-local-declaration"), source, "rename-local-use");
            assertActualUsageOffsets(markedIdentifier(api, "rename-parameter-declaration"), api, "rename-parameter-use");

            PsiElement ambiguous = markedIdentifier(api, "rename-ambiguous-declaration");
            assertTrue(ReferencesSearch.search(ambiguous).findAll().isEmpty(),
                "an ambiguous call must not be claimed as a usage of either overload");
            var ambiguousUsages = new java.util.ArrayList<PsiElement>();
            com.verseangelscript.rider.index.VasUsageSearch.process(ambiguous, GlobalSearchScope.projectScope(project),
                usage -> { ambiguousUsages.add(usage); return true; });
            assertTrue(ambiguousUsages.isEmpty(), "read-only usage lookup must also retain overload ambiguity");
            String sourceBefore = source.getText();
            String apiBefore = api.getText();
            RuntimeException rejection = assertThrows(RuntimeException.class,
                () -> renameProcessor(project, ambiguous, "AmbiguousRejected").run());
            assertTrue(rejection.getMessage().contains("Multiple declarations may identify"),
                "the actual refactoring must stop at the VAS ambiguity preflight: " + rejection);
            assertEquals(sourceBefore, source.getText(), "rejected rename must not change any use");
            assertEquals(apiBefore, api.getText(), "rejected rename must not change its declaration");

            RenameProcessor narrowed = new RenameProcessor(project, target, "ScopeRejected",
                new LocalSearchScope(api), false, false);
            narrowed.setPreviewUsages(false);
            RuntimeException scopeRejection = assertThrows(RuntimeException.class, narrowed::run);
            assertTrue(scopeRejection.getMessage().contains("does not cover every known usage"),
                "a narrowed mutation scope must be rejected before changing the declaration: " + scopeRejection);
            assertEquals(sourceBefore, source.getText(), "scope rejection must not change any use");
            assertEquals(apiBefore, api.getText(), "scope rejection must not change its declaration");

            // RenameProcessor creates its own command and write action. Starting it
            // inside an outer write action can deadlock its background usage search.
            renameProcessor(project, target, "StableRenamed").run();
            documents.commitAllDocuments();
            assertEquals(apiBefore.replace("/*rename-function-declaration*/StableRename",
                "/*rename-function-declaration*/StableRenamed"), api.getText());
            assertEquals(sourceBefore
                .replace("/*rename-first-use*/StableRename", "/*rename-first-use*/StableRenamed")
                .replace("/*rename-second-use*/StableRename", "/*rename-second-use*/StableRenamed"),
                source.getText(), "rename must preserve unrelated members, comments, strings and overloads");
            assertActualUsageOffsets(markedIdentifier(api, "rename-function-declaration"), source,
                "rename-first-use", "rename-second-use");

            UndoManager undo = UndoManager.getInstance(project);
            assertTrue(undo.isUndoAvailable(null), "the cross-file rename must create one undoable command");
            undo.undo(null);
            documents.commitAllDocuments();
            assertEquals(sourceBefore, source.getText(), "one undo must restore every changed usage");
            assertEquals(apiBefore, api.getText(), "one undo must restore the declaration");
            assertActualUsageOffsets(markedIdentifier(api, "rename-function-declaration"), source,
                "rename-first-use", "rename-second-use");
        });
    }

    @Test
    @Tag("season/vas")
    void nativeLiteralsAndLifecycleScopesCannotBecomeFalseRenameUsages() {
        Project project = getSolutionApiFacade().getProject();
        Path sourceDirectory = getSolutionApiFacade().getActiveSolutionDirectory().resolve("src");
        runOnEdtWithWriteIntent(() -> {
            DumbService.getInstance(project).completeJustSubmittedTasks();
            PsiFile source = fixture(project, sourceDirectory, "safety-lexical.vas");
            assertProjectIndexedFile(project, source, "NativeNumbers");
            PsiDocumentManager documents = PsiDocumentManager.getInstance(project);
            assertNotNull(documents.getDocument(source));
            documents.commitAllDocuments();

            for (String literal : List.of("0b101", "0o123", "0d123", "1'000", "1.2e-1'0")) {
                PsiElement number = source.findElementAt(source.getText().indexOf(literal) + 1);
                assertNotNull(number, literal);
                assertEquals(VasTypes.NUMBER, number.getNode().getElementType(), literal);
                assertTrue(PsiReferenceService.getService()
                    .getReferences(number, PsiReferenceService.Hints.NO_HINTS).isEmpty(), literal);
                assertNull(new VasDirectNavigationProvider().getNavigationElement(number), literal);
            }
            PsiElement heredoc = source.findElementAt(source.getText().indexOf("\nheredocValue\n") + 1);
            assertNotNull(heredoc);
            assertEquals(VasTypes.STRING, heredoc.getNode().getElementType());
            assertTrue(PsiReferenceService.getService().getReferences(heredoc, PsiReferenceService.Hints.NO_HINTS).isEmpty());
            assertNull(new VasDirectNavigationProvider().getNavigationElement(heredoc));
            assertActualUsageOffsets(markedIdentifier(source, "heredoc-declaration"), source, "heredoc-use");
            assertActualUsageOffsets(markedIdentifier(source, "numeric-local-declaration"), source,
                "numeric-integer-use", "numeric-fraction-use", "numeric-exponent-use", "numeric-dot-use");
            assertActualUsageOffsets(markedIdentifier(source, "literal-name-declaration"), source, "literal-name-use");
            assertActualUsageOffsets(markedIdentifier(source, "octal-name-declaration"), source);
            assertActualUsageOffsets(markedIdentifier(source, "decimal-name-declaration"), source);
            assertActualUsageOffsets(markedIdentifier(source, "lifecycle-global-declaration"), source, "lifecycle-global-use");
            assertActualUsageOffsets(markedIdentifier(source, "constructor-parameter-declaration"), source, "constructor-parameter-use");
            assertActualUsageOffsets(markedIdentifier(source, "constructor-local-declaration"), source, "constructor-local-use");
            assertActualUsageOffsets(markedIdentifier(source, "destructor-local-declaration"), source, "destructor-local-use");
            assertActualUsageOffsets(markedIdentifier(source, "handle-parameter-declaration"), source, "handle-parameter-use");
            assertActualUsageOffsets(markedIdentifier(source, "handle-local-declaration"), source, "handle-local-use");
            assertResolvesTo(source, "handle-parameter-field-use", source, "handle-b-field");
            assertResolvesTo(source, "handle-local-field-use", source, "handle-b-field");
            assertRenameAllowed(markedIdentifier(source, "lifecycle-global-declaration"), "LifecycleGlobalRenamed");
            assertRenameAllowed(markedIdentifier(source, "constructor-local-declaration"), "ConstructorLocalRenamed");

            String before = source.getText();
            renameProcessor(project, markedIdentifier(source, "literal-name-declaration"), "BitsVariableRenamed").run();
            documents.commitAllDocuments();
            assertEquals(before.replace("/*literal-name-declaration*/b101", "/*literal-name-declaration*/BitsVariableRenamed")
                .replace("/*literal-name-use*/b101", "/*literal-name-use*/BitsVariableRenamed"), source.getText(),
                "rename must leave native based literals and their identifier-like suffixes untouched");
            UndoManager undo = UndoManager.getInstance(project);
            assertTrue(undo.isUndoAvailable(null));
            undo.undo(null);
            documents.commitAllDocuments();
            assertEquals(before, source.getText());

            renameProcessor(project, markedIdentifier(source, "heredoc-declaration"), "HeredocCodeRenamed").run();
            documents.commitAllDocuments();
            assertEquals(before.replace("/*heredoc-declaration*/heredocValue", "/*heredoc-declaration*/HeredocCodeRenamed")
                .replace("/*heredoc-use*/heredocValue", "/*heredoc-use*/HeredocCodeRenamed"), source.getText(),
                "rename must preserve every byte of the heredoc's interior");
            undo.undo(null);
            documents.commitAllDocuments();
            assertEquals(before, source.getText());
            Document sourceDocument = documents.getDocument(source);
            assertNotNull(sourceDocument);
            try {
                String unfinished = before + "\nstring unfinished = \"\"\"\nheredocValue";
                replaceFixtureText(project, sourceDocument, unfinished);
                RuntimeException rejected = assertThrows(RuntimeException.class,
                    () -> renameProcessor(project, markedIdentifier(source, "heredoc-declaration"), "HeredocRejected").run());
                assertTrue(rejected.getMessage().contains("Include dependencies"), rejected.toString());
                assertEquals(unfinished, source.getText());
            } finally {
                replaceFixtureText(project, sourceDocument, before);
            }
        });
    }

    @Test
    @Tag("season/vas")
    void renamesNativeIncludeConsumersAndExpressionOperandsWithoutPartialEdits() {
        Project project = getSolutionApiFacade().getProject();
        Path sourceDirectory = getSolutionApiFacade().getActiveSolutionDirectory().resolve("src");
        runOnEdtWithWriteIntent(() -> {
            DumbService.getInstance(project).completeJustSubmittedTasks();
            PsiFile source = fixture(project, sourceDirectory, "include-forms.vas");
            PsiFile api = fixture(project, sourceDirectory, "include-forms-api.vas");
            PsiFile single = fixture(project, sourceDirectory, "include-forms-single.vas");
            PsiFile compact = fixture(project, sourceDirectory, "include-forms-compact.vas");
            PsiFile multiline = fixture(project, sourceDirectory, "include-forms-multiline.vas");
            assertProjectIndexedFile(project, source, "IncludeForms");
            assertProjectIndexedFile(project, api, "IncludeStable");
            assertProjectIndexedFile(project, single, "SingleInclude");
            assertProjectIndexedFile(project, compact, "CompactInclude");
            assertProjectIndexedFile(project, multiline, "MultilineInclude");
            for (PsiFile consumer : List.of(single, compact, multiline)) {
                PsiElement directive = consumer.findElementAt(consumer.getText().indexOf("#include"));
                assertNotNull(directive);
                PsiElement includeDestination = new VasDirectNavigationProvider().getNavigationElement(directive);
                assertNotNull(includeDestination, consumer.getName());
                assertTrue(api.isEquivalentTo(includeDestination), consumer.getName());
            }
            PsiDocumentManager documents = PsiDocumentManager.getInstance(project);
            List<PsiFile> files = List.of(source, api, single, compact, multiline);
            for (PsiFile file : files) {
                assertNotNull(documents.getDocument(file));
            }
            documents.commitAllDocuments();

            assertActualUsageOffsets(markedIdentifier(source, "expression-b-declaration"), source,
                "expression-and-use", "expression-or-use", "expression-initializer-use");
            assertActualUsageOffsets(markedIdentifier(source, "expression-mask-declaration"), source,
                "expression-mask-use", "expression-mask-initializer-use");
            assertActualUsageOffsets(markedIdentifier(source, "metadata-global-declaration"), source);
            assertActualUsageOffsets(markedIdentifier(source, "metadata-field-declaration"), source, "metadata-field-use");
            assertResolvesTo(source, "metadata-field-use", source, "metadata-field-declaration");
            assertActualUsageOffsets(markedIdentifier(source, "expression-call-declaration"), source, "expression-call-use");
            assertActualUsageOffsets(markedIdentifier(source, "expression-bit-call-declaration"), source, "expression-bit-call-use");
            for (List<String> markers : List.of(
                List.of("expression-b-declaration", "expression-and-use", "expression-or-use", "expression-initializer-use"),
                List.of("expression-mask-declaration", "expression-mask-use", "expression-mask-initializer-use"),
                List.of("expression-call-declaration", "expression-call-use"),
                List.of("expression-bit-call-declaration", "expression-bit-call-use"),
                List.of("metadata-field-declaration", "metadata-field-use"))) {
                String before = source.getText();
                PsiElement target = markedIdentifier(source, markers.getFirst());
                String expected = before;
                for (String marker : markers) {
                    expected = expected.replace("/*" + marker + "*/" + target.getText(), "/*" + marker + "*/OperandRenamed");
                }
                renameProcessor(project, target, "OperandRenamed").run();
                documents.commitAllDocuments();
                assertEquals(expected, source.getText(), "logical and bitwise RHS occurrences must be renamed");
                UndoManager.getInstance(project).undo(null);
                documents.commitAllDocuments();
                assertEquals(before, source.getText());
            }

            PsiElement includeTarget = markedIdentifier(api, "include-target");
            Collection<PsiReference> uses = ReferencesSearch.search(includeTarget).findAll();
            assertEquals(3, uses.size(), "all compiler-supported include forms must participate in default reference search");
            for (PsiElement expected : List.of(markedIdentifier(single, "include-single-use"),
                markedIdentifier(compact, "include-compact-use"), markedIdentifier(multiline, "include-multiline-use"))) {
                assertTrue(uses.stream().anyMatch(reference -> reference.getElement().isEquivalentTo(expected)));
            }
            List<String> before = files.stream().map(PsiFile::getText).toList();
            renameProcessor(project, includeTarget, "IncludeRenamed").run();
            documents.commitAllDocuments();
            for (int index = 0; index < files.size(); index++) {
                assertEquals(before.get(index).replace("IncludeStable", "IncludeRenamed"), files.get(index).getText());
            }
            UndoManager.getInstance(project).undo(null);
            documents.commitAllDocuments();
            for (int index = 0; index < files.size(); index++) {
                assertEquals(before.get(index), files.get(index).getText());
            }

            Document singleDocument = documents.getDocument(single);
            assertNotNull(singleDocument);
            try {
                for (String incomplete : List.of(
                    before.get(2).replace("include-forms-api.vas", "missing-include-target.vas"),
                    "#if UNKNOWN_FEATURE\n" + before.get(2) + "#endif\n")) {
                    replaceFixtureText(project, singleDocument, incomplete);
                    RuntimeException rejected = assertThrows(RuntimeException.class,
                        () -> renameProcessor(project, markedIdentifier(api, "include-target"), "IncludeRejected").run());
                    assertTrue(rejected.getMessage().contains("Include dependencies"), rejected.toString());
                    assertEquals(incomplete, single.getText());
                    assertEquals(before.get(1), api.getText());
                    assertEquals(before.get(3), compact.getText());
                    assertEquals(before.get(4), multiline.getText());
                }
                String unrelated = "#include \"missing-include-target.vas\"\nvoid IrrelevantDependency() {}\n";
                replaceFixtureText(project, singleDocument, unrelated);
                renameProcessor(project, markedIdentifier(api, "include-target"), "IncludeRenamed").run();
                documents.commitAllDocuments();
                assertEquals(unrelated, single.getText(), "a name-free broken module must not block or join this rename");
                assertEquals(before.get(1).replace("IncludeStable", "IncludeRenamed"), api.getText());
                assertEquals(before.get(3).replace("IncludeStable", "IncludeRenamed"), compact.getText());
                assertEquals(before.get(4).replace("IncludeStable", "IncludeRenamed"), multiline.getText());
                UndoManager.getInstance(project).undo(null);
                documents.commitAllDocuments();
                assertEquals(before.get(1), api.getText());
                assertEquals(before.get(3), compact.getText());
                assertEquals(before.get(4), multiline.getText());
            } finally {
                replaceFixtureText(project, singleDocument, before.get(2));
            }
        });
    }

    @Test
    @Tag("season/vas")
    void refusesUnboundSiblingSectionsButPreservesProvenLocalShadowing() {
        Project project = getSolutionApiFacade().getProject();
        Path sourceDirectory = getSolutionApiFacade().getActiveSolutionDirectory().resolve("src");
        runOnEdtWithWriteIntent(() -> {
            DumbService.getInstance(project).completeJustSubmittedTasks();
            PsiFile root = fixture(project, sourceDirectory, "shared-unit-root.vas");
            PsiFile api = fixture(project, sourceDirectory, "shared-unit-api.vas");
            PsiFile consumer = fixture(project, sourceDirectory, "shared-unit-consumer.vas");
            PsiFile unrelated = fixture(project, sourceDirectory, "shared-unit-unrelated.vas");
            assertProjectIndexedFile(project, root, "SharedUnitRoot");
            assertProjectIndexedFile(project, api, "SharedContextOnly");
            assertProjectIndexedFile(project, consumer, "ConsumeShared");
            assertProjectIndexedFile(project, unrelated, "UnrelatedUnit");
            PsiDocumentManager documents = PsiDocumentManager.getInstance(project);
            List<PsiFile> files = List.of(root, api, consumer, unrelated);
            for (PsiFile file : files) {
                assertNotNull(documents.getDocument(file));
            }
            documents.commitAllDocuments();
            List<String> before = files.stream().map(PsiFile::getText).toList();

            PsiElement target = markedIdentifier(api, "shared-unit-declaration");
            assertUnresolved(consumer, "shared-unit-usage");
            assertTrue(ReferencesSearch.search(target).findAll().isEmpty(),
                "an outbound-only binding must not claim a sibling usage without a selected compilation context");
            RuntimeException rejected = assertThrows(RuntimeException.class,
                () -> renameProcessor(project, target, "SharedContextRejected").run());
            assertTrue(rejected.getMessage().contains("shares a compilation root"), rejected.toString());
            for (int index = 0; index < files.size(); index++) {
                assertEquals(before.get(index), files.get(index).getText(), "sibling coverage rejection must be atomic");
            }

            Document consumerDocument = documents.getDocument(consumer);
            assertNotNull(consumerDocument);
            try {
                String locallyBound = "void ConsumeShared() { int SharedContextOnly = 1; int copy = SharedContextOnly; }\n";
                replaceFixtureText(project, consumerDocument, locallyBound);
                renameProcessor(project, markedIdentifier(api, "shared-unit-declaration"), "SharedContextRenamed").run();
                documents.commitAllDocuments();
                assertEquals(before.get(1).replace("SharedContextOnly", "SharedContextRenamed"), api.getText());
                assertEquals(locallyBound, consumer.getText(), "a sibling's proven local variable is a different symbol");
                assertEquals(before.get(0), root.getText());
                assertEquals(before.get(3), unrelated.getText(), "same-name code outside a common root is a separate module");
                UndoManager.getInstance(project).undo(null);
                documents.commitAllDocuments();
                assertEquals(before.get(1), api.getText());
                assertEquals(locallyBound, consumer.getText());
                assertEquals(before.get(3), unrelated.getText());
            } finally {
                replaceFixtureText(project, consumerDocument, before.get(2));
            }
        });
    }

    @Test
    @Tag("season/vas")
    void asciiRenamePreservesDistinctNativeUnicodeIdentifiers() {
        Project project = getSolutionApiFacade().getProject();
        Path sourceDirectory = getSolutionApiFacade().getActiveSolutionDirectory().resolve("src");
        runOnEdtWithWriteIntent(() -> {
            DumbService.getInstance(project).completeJustSubmittedTasks();
            PsiFile source = fixture(project, sourceDirectory, "unicode-identifiers.vas");
            assertProjectIndexedFile(project, source, "UnicodeIdentifierUses");
            PsiDocumentManager documents = PsiDocumentManager.getInstance(project);
            assertNotNull(documents.getDocument(source));
            documents.commitAllDocuments();
            PsiElement ascii = markedIdentifier(source, "unicode-ascii-declaration");
            assertActualUsageOffsets(ascii, source, "unicode-ascii-use");
            for (String kind : List.of("emoji", "currency", "combining", "spacing", "embedded-bom", "contextual")) {
                PsiElement use = markedIdentifier(source, "unicode-" + kind + "-use");
                PsiElement declaration = markedIdentifier(source, "unicode-" + kind + "-declaration");
                assertTrue(use.getText().length() > "value".length(), kind);
                assertNotNull(use.getReference(), kind);
                assertTrue(use.getReference().isReferenceTo(declaration), kind);
                assertFalse(use.getReference().isReferenceTo(ascii), kind);
                assertResolvesTo(source, "unicode-" + kind + "-use", source, "unicode-" + kind + "-declaration");
            }
            assertResolvesTo(source, "unicode-bom-member-use", source, "unicode-bom-member-declaration");
            String before = source.getText();
            renameProcessor(project, ascii, "UnicodeSafeValue").run();
            documents.commitAllDocuments();
            assertEquals(before.replace("/*unicode-ascii-declaration*/value", "/*unicode-ascii-declaration*/UnicodeSafeValue")
                .replace("/*unicode-ascii-use*/value", "/*unicode-ascii-use*/UnicodeSafeValue"), source.getText(),
                "ASCII rename must preserve entire Unicode identifier spellings and the BOM-separated member");
            UndoManager.getInstance(project).undo(null);
            documents.commitAllDocuments();
            assertEquals(before, source.getText());
        });
    }

    private static void replaceFixtureText(Project project, Document document, String text) {
        WriteCommandAction.runWriteCommandAction(project, () -> {
            document.setText(text);
            PsiDocumentManager.getInstance(project).commitDocument(document);
        });
    }

    private static void assertProjectIndexedFile(Project project, PsiFile file, String symbol) {
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        assertTrue(scope.contains(file.getVirtualFile()),
            "Rider fixture content roots must include " + file.getName() + " in the default rename project scope");
        assertTrue(FileBasedIndex.getInstance().getContainingFiles(VasSymbolIndex.NAME, symbol, scope)
            .contains(file.getVirtualFile()), "Rider must index " + file.getName() + " before reference search and mutation");
    }

    private static RenameProcessor renameProcessor(Project project, PsiElement target, String newName) {
        RenameProcessor processor = new RenameProcessor(project, target, newName, false, false) {
            @Override
            protected boolean isGlobalUndoAction() {
                return true;
            }

            @Override
            protected UndoConfirmationPolicy getUndoConfirmationPolicy() {
                return UndoConfirmationPolicy.DO_NOT_REQUEST_CONFIRMATION;
            }
        };
        processor.setPreviewUsages(false);
        return processor;
    }

    private static void assertActualUsageOffsets(PsiElement target, PsiFile expectedFile, String... markers) {
        Collection<PsiReference> references = ReferencesSearch.search(target).findAll();
        assertEquals(markers.length, references.size(), "actual ReferencesSearch must return only bound code usages");
        for (String marker : markers) {
            PsiElement expected = markedIdentifier(expectedFile, marker);
            assertTrue(references.stream().anyMatch(reference -> reference.getElement().isEquivalentTo(expected)), marker);
        }
    }

    private static void runOnEdtWithWriteIntent(Runnable action) {
        Application application = ApplicationManager.getApplication();
        Runnable guarded = () -> WriteIntentReadAction.run(action);
        if (application.isDispatchThread()) {
            guarded.run();
        } else {
            application.invokeAndWait(guarded);
        }
    }

    @Test
    @Tag("season/vas")
    void refusesPartialDeclarationFamilyRenamesWithoutDisablingStandaloneMembers() {
        Project project = getSolutionApiFacade().getProject();
        Path sourceDirectory = getSolutionApiFacade().getActiveSolutionDirectory().resolve("src");
        PsiFile families = fixture(project, sourceDirectory, "safety-families.vas");
        String before = families.getText();

        // No call sites are needed: linked declarations themselves make these
        // operations unsafe until the refactoring can update the whole family.
        assertRenameRejected(markedIdentifier(families, "lifecycle-type-declaration"), "LifecycleRenamed");
        assertRenameRejected(markedIdentifier(families, "interface-family-declaration"), "PerformRenamed");
        assertRenameRejected(markedIdentifier(families, "interface-implementation-declaration"), "PerformRenamed");
        assertRenameRejected(markedIdentifier(families, "base-family-declaration"), "RunRenamed");
        assertRenameRejected(markedIdentifier(families, "base-implementation-declaration"), "RunRenamed");
        assertRenameAllowed(markedIdentifier(families, "unrelated-method-declaration"), "UnrelatedPerformRenamed");
        assertRenameAllowed(markedIdentifier(families, "standalone-field-declaration"), "StandaloneValueRenamed");
        assertRenameAllowed(markedIdentifier(families, "implicit-lifecycle-type-declaration"), "ImplicitLifecycleRenamed");
        assertEquals(before, families.getText(), "declaration-family preflight must not change any declaration");
    }

    private static void assertRenameRejected(PsiElement target, String newName) {
        assertTrue(target instanceof PsiCheckedRenameElement, "VAS identifiers must expose the platform preflight hook");
        assertThrows(IncorrectOperationException.class, () -> RenameUtil.checkRename(target, newName));
    }

    private static PsiFile fixture(Project project, Path directory, String name) {
        VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory.resolve(name));
        assertNotNull(file, name);
        assertEquals(VasFileType.INSTANCE, file.getFileType(), name);
        PsiFile psi = PsiManager.getInstance(project).findFile(file);
        assertNotNull(psi, name);
        return psi;
    }

    private static PsiElement markedIdentifier(PsiFile file, String marker) {
        String text = file.getText();
        String annotation = "/*" + marker + "*/";
        int markerOffset = text.indexOf(annotation);
        assertTrue(markerOffset >= 0, "missing fixture marker: " + marker);
        assertEquals(markerOffset, text.lastIndexOf(annotation), "duplicate fixture marker: " + marker);
        int offset = markerOffset + annotation.length();
        while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) {
            offset++;
        }
        PsiElement identifier = file.findElementAt(offset);
        assertNotNull(identifier, marker);
        assertEquals(VasTypes.IDENTIFIER, identifier.getNode().getElementType(), marker);
        return identifier;
    }

    private static void assertResolvesTo(
        PsiFile source,
        String usageMarker,
        PsiFile expectedFile,
        String declarationMarker
    ) {
        PsiElement usage = markedIdentifier(source, usageMarker);
        PsiElement expected = markedIdentifier(expectedFile, declarationMarker);
        List<PsiElement> declarations = VasSymbolResolver.findDeclarations(usage);
        assertEquals(1, declarations.size(), usageMarker);
        assertTrue(expected.isEquivalentTo(declarations.get(0)), usageMarker);
        VasReference reference = new VasReference(usage);
        assertNotNull(reference.resolve(), usageMarker);
        assertTrue(expected.isEquivalentTo(reference.resolve()), usageMarker);
        assertTrue(reference.isReferenceTo(expected), usageMarker);
        PsiElement direct = new VasDirectNavigationProvider().getNavigationElement(usage);
        assertNotNull(direct, usageMarker);
        assertTrue(expected.isEquivalentTo(direct), usageMarker);
    }

    private static void assertUnresolved(PsiFile source, String marker) {
        PsiElement usage = markedIdentifier(source, marker);
        assertTrue(VasSymbolResolver.findDeclarations(usage).isEmpty(), marker);
        assertNull(new VasReference(usage).resolve(), marker);
        assertEquals(0, new VasReference(usage).multiResolve(false).length, marker);
        assertNull(new VasDirectNavigationProvider().getNavigationElement(usage), marker);
    }

    private static void assertAmbiguous(PsiFile source, String marker, List<PsiElement> expected) {
        PsiElement usage = markedIdentifier(source, marker);
        List<PsiElement> declarations = VasSymbolResolver.findDeclarations(usage);
        assertEquals(expected.size(), declarations.size(), marker);
        VasReference reference = new VasReference(usage);
        assertNull(reference.resolve(), marker);
        assertNull(new VasDirectNavigationProvider().getNavigationElement(usage), marker);
        assertNull(VasCallNavigation.resolveCallable(usage), marker);
        for (PsiElement declaration : expected) {
            assertTrue(declarations.stream().anyMatch(declaration::isEquivalentTo), marker);
            assertFalse(reference.isReferenceTo(declaration),
                marker + ": ambiguous references must not participate in rename for either candidate");
        }
    }
}
