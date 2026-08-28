package com.verseangelscript.rider.index;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class VasSymbolResolver {
    private VasSymbolResolver() {
    }

    public static @NotNull Collection<String> allProjectNames(@NotNull Project project) {
        if (DumbService.isDumb(project)) {
            return List.of();
        }
        return FileBasedIndex.getInstance().getAllKeys(VasSymbolIndex.NAME, project);
    }

    public static @NotNull List<PsiElement> findProjectDeclarations(
        @NotNull Project project,
        @NotNull String name
    ) {
        if (DumbService.isDumb(project)) {
            return List.of();
        }

        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        Collection<VirtualFile> files = FileBasedIndex.getInstance()
            .getContainingFiles(VasSymbolIndex.NAME, name, scope);
        List<PsiElement> declarations = new ArrayList<>();
        PsiManager psiManager = PsiManager.getInstance(project);
        for (VirtualFile file : files) {
            PsiFile psiFile = psiManager.findFile(file);
            if (psiFile == null) {
                continue;
            }
            for (VasSymbol symbol : VasSymbolScanner.scan(psiFile.getText())) {
                if (symbol.name().equals(name) && symbol.isProjectVisible()) {
                    PsiElement element = psiFile.findElementAt(symbol.offset());
                    if (element != null) {
                        declarations.add(element);
                    }
                }
            }
        }
        return declarations;
    }
}
