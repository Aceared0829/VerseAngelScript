package com.verseangelscript.rider.index;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

public final class VasSymbolResolver {
    public record DependencyProblem(PsiFile source, int offset, String message) { }

    /** Includes are deduplicated and exclude the source file, even in a cycle. */
    public record DependencyClosure(List<PsiFile> includedFiles, List<DependencyProblem> problems) {
        public DependencyClosure {
            includedFiles = List.copyOf(includedFiles);
            problems = List.copyOf(problems);
        }

        public boolean complete() {
            return problems.isEmpty();
        }
    }

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
            for (VasMacroScanner.Macro macro : VasMacroScanner.scan(psiFile.getText())) {
                if (macro.name().equals(name)) {
                    PsiElement element = psiFile.findElementAt(macro.offset());
                    if (element != null) declarations.add(element);
                }
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
        return selectDeclarations(usage, true);
    }

    /** Navigation may show known source candidates; destructive rename still needs complete coverage. */
    public static @NotNull List<PsiElement> findNavigationDeclarations(@NotNull PsiElement usage) {
        List<PsiElement> symbols = selectDeclarations(usage, false);
        if (!symbols.isEmpty() || usage.getContainingFile() == null) return symbols;
        return findMacroDeclarations(usage.getContainingFile(), usage.getText());
    }

    public static List<PsiElement> findMacroDeclarations(PsiFile source, String name) {
        List<PsiElement> macros = new ArrayList<>();
        for (PsiFile file : navigationFiles(source)) {
            for (VasMacroScanner.Macro macro : VasMacroScanner.scan(file.getText())) {
                if (macro.name().equals(name)) {
                    PsiElement target = file.findElementAt(macro.offset());
                    if (target != null) macros.add(target);
                }
            }
        }
        return macros;
    }

    public static List<PsiFile> navigationFiles(PsiFile source) {
        List<PsiFile> files = new ArrayList<>();
        files.add(source);
        files.addAll(inspectDependencyClosure(source).includedFiles());
        return files;
    }

    private static List<PsiElement> selectDeclarations(PsiElement usage, boolean requireComplete) {
        PsiFile file = usage.getContainingFile();
        if (file == null) return List.of();
        DependencyClosure closure = inspectDependencyClosure(file);
        if (requireComplete && !closure.complete()) return List.of();
        List<VasSymbolSelection.Candidate<PsiElement>> source = candidatesInFile(file);
        List<VasSymbolSelection.Candidate<PsiElement>> included = new ArrayList<>();
        closure.includedFiles().forEach(includedFile -> included.addAll(candidatesInFile(includedFile)));
        return VasSymbolSelection.select(source, included, usage.getText(), usage.getTextOffset(),
            VasSymbolScanner.usageContext(file.getText(), usage.getTextOffset()));
    }

    private static List<VasSymbolSelection.Candidate<PsiElement>> candidatesInFile(PsiFile file) {
        return CachedValuesManager.getCachedValue(file, () -> CachedValueProvider.Result.create(computeCandidates(file), file));
    }

    private static List<VasSymbolSelection.Candidate<PsiElement>> computeCandidates(PsiFile file) {
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
        DependencyClosure closure = inspectDependencyClosure(sourceFile);
        if (!closure.complete()) {
            return List.of();
        }
        List<VasSymbolSelection.Candidate<PsiElement>> candidates = new ArrayList<>();
        closure.includedFiles().forEach(file -> candidates.addAll(candidatesInFile(file)));
        return candidates.stream().filter(candidate -> candidate.symbol().isProjectVisible()
            && candidate.symbol().name().equals(name)).map(VasSymbolSelection.Candidate::target).toList();
    }

    /**
     * Inspect before rejecting a possible consumer on visibility grounds: an
     * incomplete graph cannot establish that it is unrelated to a rename target.
     * This models relative file includes, not application-provided include/pragma
     * callbacks or conditional compilation configuration.
     */
    public static @NotNull DependencyClosure inspectDependencyClosure(@NotNull PsiFile sourceFile) {
        return CachedValuesManager.getCachedValue(sourceFile, () -> CachedValueProvider.Result.create(
            computeDependencyClosure(sourceFile), PsiModificationTracker.MODIFICATION_COUNT, VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS));
    }

    private static DependencyClosure computeDependencyClosure(PsiFile sourceFile) {
        List<PsiFile> includedFiles = new ArrayList<>();
        List<DependencyProblem> problems = new ArrayList<>();
        Set<VirtualFile> visited = new HashSet<>();
        ArrayDeque<PsiFile> pending = new ArrayDeque<>();
        if (sourceFile.getVirtualFile() != null) {
            visited.add(sourceFile.getVirtualFile());
        }
        pending.add(sourceFile);
        PsiManager psiManager = PsiManager.getInstance(sourceFile.getProject());
        VirtualFile entryDirectory = sourceFile.getVirtualFile() == null ? null : sourceFile.getVirtualFile().getParent();
        int sourceBytes = 0;
        while (!pending.isEmpty()) {
            ProgressManager.checkCanceled();
            PsiFile file = pending.removeFirst();
            sourceBytes += file.getTextLength();
            if (visited.size() > 256 || sourceBytes > 16 * 1024 * 1024) {
                problems.add(new DependencyProblem(file, 0, "Editor dependency budget exceeded"));
                break;
            }
            VasIncludeScanner.Result scan = VasIncludeScanner.scan(file.getText());
            for (VasIncludeScanner.Problem problem : scan.problems()) {
                problems.add(new DependencyProblem(file, problem.offset(), problem.message()));
            }
            VirtualFile virtualFile = file.getVirtualFile();
            for (VasIncludeScanner.Include include : scan.includes()) {
                ProgressManager.checkCanceled();
                if (virtualFile == null || virtualFile.getParent() == null) {
                    problems.add(new DependencyProblem(file, include.offset(), "Include source has no directory"));
                    continue;
                }
                String path = include.path();
                if (path.startsWith("/") || path.indexOf(':') >= 0
                    || java.io.File.separatorChar != '\\' && path.indexOf('\\') >= 0) {
                    problems.add(new DependencyProblem(file, include.offset(), "Unsupported include path: " + path));
                    continue;
                }
                VirtualFile included = VasDependencyPaths.resolve(file, entryDirectory, path, include.kind());
                if (included == null || !included.isValid() || included.isDirectory()) {
                    problems.add(new DependencyProblem(file, include.offset(), "Unresolved include: " + path));
                    continue;
                }
                VirtualFile local = virtualFile.getParent().findFileByRelativePath(path.replace('\\', '/'));
                VirtualFile entry = entryDirectory == null ? null : entryDirectory.findFileByRelativePath(path.replace('\\', '/'));
                if (include.kind() == VasIncludeScanner.Kind.SYSTEM && System.getenv("VAS_INCLUDE_PATH") != null
                    || !included.equals(entry) && (include.kind() == VasIncludeScanner.Kind.SYSTEM || !included.equals(local))) {
                    problems.add(new DependencyProblem(file, include.offset(), "Editor search hints require compiler entry-root verification"));
                }
                if (included.getFileType() != com.verseangelscript.rider.VasFileType.INSTANCE) {
                    problems.add(new DependencyProblem(file, include.offset(), "Include is not a VAS source: " + path));
                    continue;
                }
                if (visited.add(included)) {
                    PsiFile includedPsi = psiManager.findFile(included);
                    if (includedPsi == null) {
                        problems.add(new DependencyProblem(file, include.offset(), "Include PSI is unavailable: " + path));
                    } else {
                        includedFiles.add(includedPsi);
                        pending.addLast(includedPsi);
                    }
                }
            }
        }
        return new DependencyClosure(includedFiles, problems);
    }

    public static @NotNull List<PsiElement> findModuleDeclarations(
        @NotNull PsiFile source, @NotNull String name
    ) {
        DependencyClosure closure = inspectDependencyClosure(source);
        if (!closure.complete()) {
            return List.of();
        }
        List<PsiElement> declarations = new ArrayList<>();
        candidatesInFile(source).stream().filter(candidate -> candidate.symbol().isProjectVisible()
            && candidate.symbol().name().equals(name)).map(VasSymbolSelection.Candidate::target)
            .forEach(declarations::add);
        for (PsiFile included : closure.includedFiles()) {
            candidatesInFile(included).stream().filter(candidate -> candidate.symbol().isProjectVisible()
                && candidate.symbol().name().equals(name)).map(VasSymbolSelection.Candidate::target)
                .forEach(declarations::add);
        }
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
        return candidatesInFile(file).stream().map(VasSymbolSelection.Candidate::symbol)
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
        VasUsageSearch.process(callable, com.intellij.psi.search.PsiSearchHelper.getInstance(callable.getProject()).getUseScope(callable),
            usage -> { findEnclosingFunction(usage).ifPresent(callers::add); return true; });
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
            List<PsiElement> targets = findNavigationDeclarations(identifier);
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
