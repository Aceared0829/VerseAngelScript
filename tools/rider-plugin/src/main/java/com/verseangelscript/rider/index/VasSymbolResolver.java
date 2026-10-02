package com.verseangelscript.rider.index;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
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

        List<VasSymbolSelection.Candidate<PsiElement>> source = candidatesInFile(file);
        List<VasSymbolSelection.Candidate<PsiElement>> included = new ArrayList<>();
        collectIncludedCandidates(file, new HashSet<>(), included);
        return VasSymbolSelection.select(source, included, usage.getText(), usage.getTextOffset(),
            VasSymbolScanner.usageContext(file.getText(), usage.getTextOffset()));
    }

    private static List<VasSymbolSelection.Candidate<PsiElement>> candidatesInFile(PsiFile file) {
        List<VasSymbolSelection.Candidate<PsiElement>> candidates = new ArrayList<>();
        for (VasSymbol symbol : VasSymbolScanner.scan(file.getText())) {
            PsiElement element = file.findElementAt(symbol.offset());
            if (element != null) {
                candidates.add(new VasSymbolSelection.Candidate<>(symbol, element));
            }
        }
        return candidates;
    }

    static @NotNull List<PsiElement> findIncludedDeclarations(
        @NotNull PsiFile sourceFile, @NotNull String name
    ) {
        List<VasSymbolSelection.Candidate<PsiElement>> candidates = new ArrayList<>();
        collectIncludedCandidates(sourceFile, new HashSet<>(), candidates);
        return candidates.stream().filter(candidate -> candidate.symbol().isProjectVisible()
            && candidate.symbol().name().equals(name)).map(VasSymbolSelection.Candidate::target).toList();
    }

    private static void collectIncludedCandidates(
        PsiFile sourceFile, Set<VirtualFile> visited,
        List<VasSymbolSelection.Candidate<PsiElement>> candidates
    ) {
        VirtualFile source = sourceFile.getVirtualFile();
        if (source == null || source.getParent() == null || !visited.add(source)) {
            return;
        }
        PsiManager psiManager = PsiManager.getInstance(sourceFile.getProject());
        // Scan actual preprocessor tokens; text inside comments/strings is never an include.
        com.verseangelscript.rider.lang.VasLexer lexer = new com.verseangelscript.rider.lang.VasLexer();
        String text = sourceFile.getText();
        lexer.start(text);
        while (lexer.getTokenType() != null) {
            if (lexer.getTokenType() == com.verseangelscript.rider.lang.VasTypes.PREPROCESSOR) {
                Matcher matcher = INCLUDE_PATTERN.matcher(text.substring(lexer.getTokenStart(), lexer.getTokenEnd()));
                if (matcher.find()) {
                    VirtualFile included = source.getParent()
                        .findFileByRelativePath(matcher.group(1).replace('\\', '/'));
                    if (included != null && !visited.contains(included)
                        && included.getFileType() == com.verseangelscript.rider.VasFileType.INSTANCE) {
                        PsiFile includedPsi = psiManager.findFile(included);
                        if (includedPsi != null) {
                            candidates.addAll(candidatesInFile(includedPsi));
                            collectIncludedCandidates(includedPsi, visited, candidates);
                        }
                    }
                }
            }
            lexer.advance();
        }
    }

    public static @NotNull List<PsiElement> findModuleDeclarations(
        @NotNull PsiFile source, @NotNull String name
    ) {
        List<PsiElement> declarations = new ArrayList<>();
        candidatesInFile(source).stream().filter(candidate -> candidate.symbol().isProjectVisible()
            && candidate.symbol().name().equals(name)).map(VasSymbolSelection.Candidate::target)
            .forEach(declarations::add);
        declarations.addAll(findIncludedDeclarations(source, name));
        return declarations;
    }

    /** Module visibility for conservative rename preflight; never uses project-name fallback. */
    public static boolean isDeclarationVisibleFrom(
        @NotNull PsiFile source, @NotNull PsiElement declaration
    ) {
        PsiFile targetFile = declaration.getContainingFile();
        if (targetFile == null) {
            return false;
        }
        if (source.isEquivalentTo(targetFile)) {
            return true;
        }
        return findSymbol(declaration).filter(VasSymbol::isProjectVisible).isPresent()
            && findIncludedDeclarations(source, declaration.getText()).stream()
                .anyMatch(candidate -> candidate.isEquivalentTo(declaration));
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
        if (target.isEmpty()) {
            return List.of();
        }

        if (target.get().kind() == VasSymbolKind.CLASS
            || target.get().kind() == VasSymbolKind.INTERFACE) {
            return findDerivedTypes(declaration.getProject(), target.get().name());
        }
        if (target.get().kind() != VasSymbolKind.FUNCTION) {
            return List.of();
        }

        List<PsiElement> implementations = new ArrayList<>();
        for (PsiElement candidate : findProjectDeclarations(
            declaration.getProject(),
            target.get().name()
        )) {
            Optional<VasSymbol> symbol = findSymbol(candidate);
            if (symbol.isPresent() && symbol.get().kind() == VasSymbolKind.FUNCTION
                && symbol.get().definition()
                && symbol.get().parameterCount() == target.get().parameterCount()
                && compatibleContainers(target.get().container(), symbol.get().container())
                && !candidate.isEquivalentTo(declaration)) {
                implementations.add(candidate);
            }
        }
        return implementations;
    }

    public static @NotNull List<PsiElement> findDeclarationsForSymbol(
        @NotNull PsiElement definition
    ) {
        Optional<VasSymbol> target = findSymbol(definition);
        if (target.isEmpty()) {
            return List.of();
        }
        return findProjectDeclarations(definition.getProject(), target.get().name()).stream()
            .filter(candidate -> !candidate.isEquivalentTo(definition))
            .filter(candidate -> findSymbol(candidate).map(symbol ->
                symbol.kind() == target.get().kind()
                    && !symbol.definition()
                    && (symbol.kind() != VasSymbolKind.FUNCTION
                        || symbol.parameterCount() == target.get().parameterCount())
                    && compatibleContainers(symbol.container(), target.get().container())
            ).orElse(false))
            .toList();
    }

    private static boolean compatibleContainers(String left, String right) {
        return left.isEmpty() || right.isEmpty() || left.equals(right)
            || left.endsWith("::" + right) || right.endsWith("::" + left);
    }

    public static @NotNull List<PsiElement> findDerivedTypes(
        @NotNull Project project,
        @NotNull String baseName
    ) {
        if (DumbService.isDumb(project)) {
            return List.of();
        }

        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        Collection<VirtualFile> files = FileBasedIndex.getInstance()
            .getContainingFiles(VasInheritanceIndex.BASE_TYPE, baseName, scope);
        List<PsiElement> derived = new ArrayList<>();
        PsiManager psiManager = PsiManager.getInstance(project);
        for (VirtualFile file : files) {
            PsiFile psiFile = psiManager.findFile(file);
            if (psiFile == null) {
                continue;
            }
            for (VasSymbol symbol : VasSymbolScanner.scan(psiFile.getText())) {
                if (!symbol.baseTypes().contains(baseName)) {
                    continue;
                }
                PsiElement candidate = psiFile.findElementAt(symbol.offset());
                if (candidate != null) {
                    derived.add(candidate);
                }
            }
        }
        return derived;
    }

    public static @NotNull Optional<PsiElement> findEnclosingFunction(
        @NotNull PsiElement element
    ) {
        PsiFile file = element.getContainingFile();
        if (file == null) {
            return Optional.empty();
        }
        int offset = element.getTextOffset();
        return VasSymbolScanner.scan(file.getText()).stream()
            .filter(symbol -> symbol.kind() == VasSymbolKind.FUNCTION && symbol.definition())
            .filter(symbol -> symbol.scopeStart() >= 0
                && offset >= symbol.scopeStart() && offset <= symbol.scopeEnd())
            .min(Comparator.comparingInt(symbol -> symbol.scopeEnd() - symbol.scopeStart()))
            .map(symbol -> file.findElementAt(symbol.offset()));
    }

    public static @NotNull List<PsiElement> findCallers(@NotNull PsiElement callable) {
        LinkedHashSet<PsiElement> callers = new LinkedHashSet<>();
        for (PsiReference reference : ReferencesSearch.search(callable).findAll()) {
            findEnclosingFunction(reference.getElement()).ifPresent(callers::add);
        }
        return List.copyOf(callers);
    }

    public static @NotNull List<PsiElement> findCallees(@NotNull PsiElement callable) {
        Optional<VasSymbol> function = findSymbol(callable);
        PsiFile file = callable.getContainingFile();
        if (function.isEmpty() || file == null
            || function.get().kind() != VasSymbolKind.FUNCTION
            || function.get().scopeStart() < 0) {
            return List.of();
        }

        LinkedHashSet<PsiElement> callees = new LinkedHashSet<>();
        PsiElement[] identifiers = PsiTreeUtil.collectElements(file, candidate ->
            candidate.getNode().getElementType() == com.verseangelscript.rider.lang.VasTypes.IDENTIFIER
                && candidate.getTextOffset() >= function.get().scopeStart()
                && candidate.getTextOffset() <= function.get().scopeEnd()
        );
        for (PsiElement identifier : identifiers) {
            if (findSymbol(identifier).isPresent()) {
                continue;
            }
            List<PsiElement> targets = findDeclarations(identifier);
            if (targets.size() != 1) {
                continue;
            }
            for (PsiElement target : targets) {
                if (findSymbol(target).map(symbol -> symbol.kind() == VasSymbolKind.FUNCTION)
                    .orElse(false)) {
                    callees.add(target);
                }
            }
        }
        return List.copyOf(callees);
    }
}
