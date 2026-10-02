package com.verseangelscript.rider.diagnostics;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.LightVirtualFile;
import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertNull;

/** A disposed project must be rejected before accessing its settings or services. */
public final class VasExternalAnnotatorDisposalTest {
    @Test
    public void doesNotCollectFromDisposedProject() {
        Project project = disposedProject();
        PsiFile file = (PsiFile) Proxy.newProxyInstance(PsiFile.class.getClassLoader(),
            new Class<?>[] {PsiFile.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("getProject")) {
                    return project;
                }
                throw new AssertionError("Unexpected disposed-file access: " + method.getName());
            });
        assertNull(new VasExternalAnnotator().collectInformation(file));
    }

    @Test
    public void rejectsQueuedRequestForDisposedProject() {
        assertNull(new VasExternalAnnotator().doAnnotate(new VasExternalAnnotator.Request(
            disposedProject(), new LightVirtualFile("queued.vas"), "void main() {}")));
    }

    private static Project disposedProject() {
        return (Project) Proxy.newProxyInstance(Project.class.getClassLoader(),
            new Class<?>[] {Project.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("isDisposed")) {
                    return true;
                }
                throw new AssertionError("Unexpected disposed-project access: " + method.getName());
            });
    }
}
