package com.verseangelscript.rider.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReferenceBase;
import com.verseangelscript.rider.index.VasIncludeScanner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class VasIncludeReference extends PsiReferenceBase<PsiElement> {
    private final String includePath;

    private VasIncludeReference(
        @NotNull PsiElement element,
        @NotNull TextRange range,
        @NotNull String includePath
    ) {
        super(element, range, true);
        this.includePath = includePath;
    }

    public static @Nullable VasIncludeReference create(@NotNull PsiElement element) {
        VasIncludeScanner.Include include = extractInclude(element.getText());
        if (include == null) {
            return null;
        }
        return new VasIncludeReference(
            element,
            new TextRange(include.pathStart(), include.pathEnd()),
            include.path()
        );
    }

    /**
     * Resolves an include directly for Go To Declaration paths that do not request
     * PSI references from the preprocessor leaf.
     */
    public static @Nullable PsiElement resolveTarget(@NotNull PsiElement element) {
        VasIncludeReference reference = create(element);
        return reference == null ? null : reference.resolve();
    }

    static @Nullable String extractIncludePath(@NotNull String text) {
        VasIncludeScanner.Include include = extractInclude(text);
        return include == null ? null : include.path();
    }

    private static @Nullable VasIncludeScanner.Include extractInclude(@NotNull String text) {
        VasIncludeScanner.Result result = VasIncludeScanner.scan(text);
        return result.complete() && result.includes().size() == 1 ? result.includes().getFirst() : null;
    }

    @Override
    public @Nullable PsiElement resolve() {
        PsiFile sourceFile = myElement.getContainingFile();
        if (sourceFile == null || sourceFile.getVirtualFile() == null) {
            return null;
        }
        VirtualFile directory = sourceFile.getVirtualFile().getParent();
        if (directory == null) {
            return null;
        }
        if (includePath.startsWith("/") || includePath.indexOf(':') >= 0
            || java.io.File.separatorChar != '\\' && includePath.indexOf('\\') >= 0) {
            return null;
        }
        VirtualFile target = directory.findFileByRelativePath(includePath.replace('\\', '/'));
        return target == null ? null : PsiManager.getInstance(myElement.getProject()).findFile(target);
    }
}
