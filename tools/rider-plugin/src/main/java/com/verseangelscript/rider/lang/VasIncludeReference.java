package com.verseangelscript.rider.lang;

import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReferenceBase;
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
        String text = element.getText();
        if (!text.stripLeading().startsWith("#include")) {
            return null;
        }
        int quoteStart = text.indexOf('"');
        int quoteEnd = quoteStart < 0 ? -1 : text.indexOf('"', quoteStart + 1);
        if (quoteStart < 0 || quoteEnd <= quoteStart + 1) {
            return null;
        }
        return new VasIncludeReference(
            element,
            new TextRange(quoteStart + 1, quoteEnd),
            text.substring(quoteStart + 1, quoteEnd)
        );
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
        VirtualFile target = directory.findFileByRelativePath(includePath.replace('\\', '/'));
        return target == null ? null : PsiManager.getInstance(myElement.getProject()).findFile(target);
    }
}
