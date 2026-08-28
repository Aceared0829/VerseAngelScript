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
import java.util.Comparator;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

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

        // Rider can open VAS files that belong to a nested/generated solution without
        // attaching that solution to the current .NET project model. Those files are
        // still indexed, but projectScope() filters them out during navigation.
        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
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

    public static @NotNull List<PsiElement> findDeclarations(@NotNull PsiElement usage) {
        PsiFile file = usage.getContainingFile();
        if (file == null) {
            return List.of();
        }

        String name = usage.getText();
        int usageOffset = usage.getTextOffset();
        List<VasSymbol> scoped = VasSymbolScanner.scan(file.getText()).stream()
            .filter(symbol -> symbol.name().equals(name))
            .filter(symbol -> !symbol.isProjectVisible())
            .filter(symbol -> symbol.isVisibleAt(usageOffset))
            .filter(symbol -> symbol.offset() != usageOffset)
            .sorted(Comparator
                .comparingInt((VasSymbol symbol) -> symbol.scopeEnd() - symbol.scopeStart())
                .thenComparingInt(symbol -> Math.abs(usageOffset - symbol.offset())))
            .toList();
        if (!scoped.isEmpty()) {
            PsiElement declaration = file.findElementAt(scoped.get(0).offset());
            return declaration == null ? List.of() : List.of(declaration);
        }

        return findProjectDeclarations(usage.getProject(), name);
    }

    public static @NotNull Optional<VasSymbol> findSymbol(@NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        if (file == null) {
            return Optional.empty();
        }
        int offset = element.getTextOffset();
        return VasSymbolScanner.scan(file.getText()).stream()
            .filter(symbol -> symbol.offset() == offset)
            .findFirst();
    }

    public static @NotNull List<PsiElement> findImplementations(@NotNull PsiElement declaration) {
        Optional<VasSymbol> target = findSymbol(declaration);
        if (target.isEmpty() || target.get().kind() != VasSymbolKind.FUNCTION) {
            return List.of();
        }

        List<PsiElement> implementations = new ArrayList<>();
        for (PsiElement candidate : findProjectDeclarations(
            declaration.getProject(),
            target.get().name()
        )) {
            Optional<VasSymbol> symbol = findSymbol(candidate);
            if (symbol.isPresent() && symbol.get().kind() == VasSymbolKind.FUNCTION
                && symbol.get().definition() && !candidate.isEquivalentTo(declaration)) {
                implementations.add(candidate);
            }
        }
        return implementations;
    }
}
