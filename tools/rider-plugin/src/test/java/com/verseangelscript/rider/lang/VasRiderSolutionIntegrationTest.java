package com.verseangelscript.rider.lang;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiCheckedRenameElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.refactoring.rename.RenameUtil;
import com.intellij.util.IncorrectOperationException;
import com.jetbrains.rider.test.annotations.Solution;
import com.jetbrains.rider.test.annotations.TestSettings;
import com.jetbrains.rider.test.enums.BuildTool;
import com.jetbrains.rider.test.enums.Mono;
import com.jetbrains.rider.test.enums.sdk.SdkVersion;
import com.jetbrains.rider.test.junit5.base.PerTestSolutionTestBase;
import com.verseangelscript.rider.VasFileType;
import com.verseangelscript.rider.index.VasSymbolResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;
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
@TestSettings(buildTool = BuildTool.NONE, mono = Mono.NONE, sdkVersion = SdkVersion.NONE)
public final class VasRiderSolutionIntegrationTest extends PerTestSolutionTestBase {
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
        assertRenameRejected(markedIdentifier(source, "local-order-declaration"), "after");
        assertRenameRejected(markedIdentifier(source, "parameter-declaration"), "CycleLeaf");
        assertRenameRejected(markedIdentifier(source, "global-order-declaration"), "print");
        assertEquals(sourceBefore, source.getText(), "rename preflight must not mutate the source");
        assertEquals(apiBefore, api.getText(), "rename preflight must not mutate direct includes");
        assertEquals(nestedBefore, nested.getText(), "rename preflight must not mutate nested includes");
    }

    private static void assertRenameAllowed(PsiElement target, String newName) {
        assertTrue(target instanceof PsiCheckedRenameElement, "VAS declarations must expose the platform preflight hook");
        assertDoesNotThrow(() -> RenameUtil.checkRename(target, newName));
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
