package com.verseangelscript.rider.lang;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.jetbrains.rider.test.annotations.Solution;
import com.jetbrains.rider.test.annotations.TestSettings;
import com.jetbrains.rider.test.enums.BuildTool;
import com.jetbrains.rider.test.enums.Mono;
import com.jetbrains.rider.test.enums.sdk.SdkVersion;
import com.jetbrains.rider.test.junit5.base.PerTestSolutionTestBase;
import com.verseangelscript.rider.VasFileType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
