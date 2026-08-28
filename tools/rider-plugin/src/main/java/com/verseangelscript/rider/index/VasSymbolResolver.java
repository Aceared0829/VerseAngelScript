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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VasSymbolResolver {
    private static final Pattern INCLUDE_PATTERN = Pattern.compile(
        "(?m)^\\s*#include\\s+\"([^\"]+)\""
    );

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

        List<PsiElement> sameFile = declarationsInFile(file, name);
        if (!sameFile.isEmpty()) {
            return sameFile;
        }

        List<PsiElement> included = findIncludedDeclarations(file, name);
        if (!included.isEmpty()) {
            return included;
        }

        return findProjectDeclarations(usage.getProject(), name);
    }

    static @NotNull List<PsiElement> findIncludedDeclarations(
        @NotNull PsiFile sourceFile,
        @NotNull String name
    ) {
        return findIncludedDeclarations(sourceFile, name, new HashSet<>());
    }

    private static @NotNull List<PsiElement> findIncludedDeclarations(
        @NotNull PsiFile sourceFile,
        @NotNull String name,
        @NotNull Set<VirtualFile> visited
    ) {
        VirtualFile source = sourceFile.getVirtualFile();
        if (source == null || source.getParent() == null || !visited.add(source)) {
            return List.of();
        }

        List<PsiElement> declarations = new ArrayList<>();
        Matcher matcher = INCLUDE_PATTERN.matcher(sourceFile.getText());
        PsiManager psiManager = PsiManager.getInstance(sourceFile.getProject());
        while (matcher.find()) {
            VirtualFile included = source.getParent()
                .findFileByRelativePath(matcher.group(1).replace('\\', '/'));
            if (included == null || visited.contains(included)) {
                continue;
            }
            PsiFile includedPsi = psiManager.findFile(included);
            if (includedPsi == null) {
                continue;
            }
            declarations.addAll(declarationsInFile(includedPsi, name));
            if (declarations.isEmpty()) {
                declarations.addAll(findIncludedDeclarations(includedPsi, name, visited));
            }
        }
        return declarations;
    }

    private static @NotNull List<PsiElement> declarationsInFile(
        @NotNull PsiFile file,
        @NotNull String name
    ) {
        List<PsiElement> declarations = new ArrayList<>();
        for (VasSymbol symbol : VasSymbolScanner.scan(file.getText())) {
            if (!symbol.name().equals(name) || !symbol.isProjectVisible()) {
                continue;
            }
            PsiElement declaration = file.findElementAt(symbol.offset());
            if (declaration != null) {
                declarations.add(declaration);
            }
        }
        return declarations;
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
