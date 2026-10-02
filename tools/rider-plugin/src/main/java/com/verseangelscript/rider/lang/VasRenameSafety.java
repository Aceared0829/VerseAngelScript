package com.verseangelscript.rider.lang;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.PsiSearchScopeUtil;
import com.intellij.psi.search.SearchScope;
import com.intellij.util.IncorrectOperationException;
import com.verseangelscript.rider.VasFileType;
import com.verseangelscript.rider.index.VasSymbol;
import com.verseangelscript.rider.index.VasSymbolKind;
import com.verseangelscript.rider.index.VasSymbolResolver;
import com.verseangelscript.rider.index.VasSymbolScanner;
import com.verseangelscript.rider.index.VasUsageContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Operation-wide, nonmutating rename preflight for PsiCheckedRenameElement.
 * A reference omitted because its binding is uncertain must block the operation,
 * rather than allow the declaration to change while a possible use stays unchanged.
 * RenameProcessor calls checkSetName before its write phase. Individual setName calls
 * intentionally remain unguarded while the approved declaration and uses are updated.
 */
public final class VasRenameSafety {
    private VasRenameSafety() {
    }

    public static void checkRename(@NotNull PsiElement target, @NotNull String newName) {
        SearchScope useScope = PsiSearchHelper.getInstance(target.getProject()).getUseScope(target);
        // Pinned Build 262 RenameUtil intersects nonlocal use scopes with the
        // default RenameProcessor project scope. allScope resolution alone cannot
        // make an out-of-project occurrence part of that mutation.
        SearchScope mutationScope = useScope instanceof LocalSearchScope ? useScope
            : GlobalSearchScope.projectScope(target.getProject()).intersectWith(useScope);
        checkRename(target, newName, occurrence -> PsiSearchScopeUtil.isInScope(mutationScope, occurrence));
    }

    public static void checkRename(
        @NotNull PsiElement target, @NotNull String newName, @NotNull Predicate<PsiElement> usageCovered
    ) {
        VasSymbol symbol = VasSymbolResolver.findSymbol(target).orElse(null);
        PsiFile targetFile = target.getContainingFile();
        if (symbol == null || targetFile == null) {
            refuse("Select a uniquely identified VAS declaration before renaming.");
        }
        if (DumbService.isDumb(target.getProject())) {
            refuse("Wait for indexing to finish before renaming VAS symbols.");
        }
        if (!isValidNewName(newName)) {
            refuse("The new VAS name must use ASCII letters, digits and underscores, "
                + "start with a letter or underscore, and not be a keyword. "
                + "Unicode identifier support depends on the compiler configuration.");
        }
        if (symbol.kind() == VasSymbolKind.CLASS
            && VasSymbolScanner.hasExplicitLifecycleDeclaration(targetFile.getText(), symbol)) {
            refuse("This VAS class has explicit constructor or destructor declarations; "
                + "rename cannot safely update that declaration family yet.");
        }
        if (!symbol.isProjectVisible() && (symbol.scopeStart() < 0
            || symbol.scopeEnd() > targetFile.getTextLength() || symbol.scopeEnd() <= symbol.scopeStart())) {
            refuse("Finish the declaration's scope before renaming this VAS symbol.");
        }
        String lexicalContainer = declarationContainer(targetFile, symbol);

        Set<PsiFile> files = new LinkedHashSet<>();
        files.add(targetFile);
        if (symbol.isProjectVisible()) {
            PsiManager manager = PsiManager.getInstance(target.getProject());
            for (VirtualFile file : FileTypeIndex.getFiles(
                VasFileType.INSTANCE, GlobalSearchScope.allScope(target.getProject())
            )) {
                ProgressManager.checkCanceled();
                PsiFile psi = manager.findFile(file);
                if (psi == null) {
                    refuse("A VAS source file could not be inspected; rename was cancelled.");
                }
                files.add(psi);
            }
        }

        for (PsiFile file : files) {
            ProgressManager.checkCanceled();
            if (!VasSymbolResolver.isDeclarationVisibleFrom(file, target)) {
                continue;
            }
            if (symbol.isProjectVisible()) {
                checkDeclarationCollisions(file, target, symbol);
            }
            String text = file.getText();
            List<VasSymbol> newNameDeclarations = new ArrayList<>();
            if (!newName.equals(symbol.name())) {
                VasSymbolScanner.scan(text).stream()
                    .filter(candidate -> !candidate.isProjectVisible() && candidate.name().equals(newName))
                    .forEach(newNameDeclarations::add);
                for (PsiElement declaration : VasSymbolResolver.findModuleDeclarations(file, newName)) {
                    VasSymbol candidate = VasSymbolResolver.findSymbol(declaration).orElse(null);
                    if (candidate == null) {
                        refuse("A possible new-name collision could not be inspected; rename was cancelled.");
                    }
                    newNameDeclarations.add(candidate);
                }
                if (newNameDeclarations.stream().anyMatch(candidate -> candidate.isProjectVisible()
                    ? isEnclosingContainer(lexicalContainer, candidate.container())
                    : !symbol.isProjectVisible() && scopesOverlap(symbol, candidate))) {
                    refuse("The new VAS name '" + newName + "' conflicts with an existing declaration.");
                }
            }
            VasLexer lexer = new VasLexer();
            lexer.start(text);
            while (lexer.getTokenType() != null) {
                ProgressManager.checkCanceled();
                int offset = lexer.getTokenStart();
                if (!newName.equals(symbol.name()) && lexer.getTokenType() == VasTypes.IDENTIFIER
                    && text.substring(offset, lexer.getTokenEnd()).equals(newName)) {
                    VasUsageContext context = VasSymbolScanner.usageContext(text, offset);
                    boolean inBindingScope = symbol.isProjectVisible()
                        ? isEnclosingContainer(context.container(), symbol.container()) : symbol.isVisibleAt(offset);
                    if (inBindingScope && context.access() == VasUsageContext.Access.UNQUALIFIED) {
                        PsiElement occurrence = file.findElementAt(offset);
                        if (occurrence == null || VasSymbolResolver.findSymbol(occurrence).isEmpty()) {
                            // Even an unused declaration must not capture an existing
                            // name, including runtime APIs unknown to this scanner.
                            refuse("The new VAS name '" + newName
                                + "' could shadow an existing usage; rename was cancelled.");
                        }
                    }
                }
                if (lexer.getTokenType() == VasTypes.IDENTIFIER
                    && (symbol.isProjectVisible() || symbol.isVisibleAt(offset))
                    && text.substring(offset, lexer.getTokenEnd()).equals(symbol.name())) {
                    PsiElement occurrence = file.findElementAt(offset);
                    if (occurrence == null) {
                        refuse("A possible VAS usage could not be inspected; rename was cancelled.");
                    }
                    if (VasSymbolResolver.findSymbol(occurrence).isEmpty()) {
                        List<PsiElement> candidates = VasSymbolResolver.findDeclarations(occurrence);
                        if (candidates.isEmpty() || candidates.size() > 1
                            && candidates.stream().anyMatch(target::isEquivalentTo)) {
                            refuse("A possible usage of '" + symbol.name()
                                + "' is unresolved or ambiguous; rename was cancelled before changing files.");
                        }
                        if (candidates.size() == 1 && target.isEquivalentTo(candidates.get(0))) {
                            if (!usageCovered.test(occurrence)) {
                                refuse("The requested VAS rename does not cover every known usage; "
                                    + "rename was cancelled before changing files.");
                            }
                            VasUsageContext context = VasSymbolScanner.usageContext(text, offset);
                            if (context.access() == VasUsageContext.Access.UNQUALIFIED
                                && newNameDeclarations.stream().anyMatch(candidate ->
                                    candidate.isVisibleAt(offset) && (!candidate.isProjectVisible()
                                        || isEnclosingContainer(context.container(), candidate.container())))) {
                                refuse("The new VAS name '" + newName
                                    + "' could change the binding of a usage; rename was cancelled.");
                            }
                        }
                    }
                }
                lexer.advance();
            }
        }
    }

    static boolean isValidNewName(String name) {
        // VAS rejects '$'; Unicode identifiers additionally require engine property 25.
        // Until that per-project property is verified, propose only this valid subset.
        return name.matches("[A-Za-z_][A-Za-z0-9_]*") && !VasKeywords.SET.contains(name);
    }

    private static void checkDeclarationCollisions(PsiFile file, PsiElement target, VasSymbol symbol) {
        for (PsiElement candidate : VasSymbolResolver.findModuleDeclarations(file, symbol.name())) {
            if (target.isEquivalentTo(candidate)) {
                continue;
            }
            VasSymbol other = VasSymbolResolver.findSymbol(candidate).orElse(null);
            if (other == null) {
                refuse("A VAS declaration could not be inspected; rename was cancelled.");
            }
            if (other.qualifiedName().equals(symbol.qualifiedName())
                && !haveDisjointFunctionArities(symbol, other)) {
                refuse("Multiple declarations may identify '" + symbol.qualifiedName()
                    + "'; rename was cancelled before changing files.");
            }
            if (symbol.kind() == VasSymbolKind.FUNCTION && other.kind() == VasSymbolKind.FUNCTION
                && !haveDisjointFunctionArities(symbol, other)
                && haveRelatedOwners(file, symbol.container(), other.container())) {
                refuse("The VAS method '" + symbol.qualifiedName()
                    + "' may belong to an interface or inheritance declaration family; "
                    + "rename cannot safely update that family yet.");
            }
        }
    }

    private static boolean haveRelatedOwners(PsiFile file, String left, String right) {
        if (left.isEmpty() || right.isEmpty() || left.equals(right)) {
            return false;
        }
        List<VasSymbol> leftTypes = typesNamed(file, simpleName(left)).stream()
            .filter(type -> type.qualifiedName().equals(left)).toList();
        List<VasSymbol> rightTypes = typesNamed(file, simpleName(right)).stream()
            .filter(type -> type.qualifiedName().equals(right)).toList();
        for (VasSymbol leftType : leftTypes) {
            for (VasSymbol rightType : rightTypes) {
                if (possiblyInherits(file, leftType, rightType.qualifiedName(), new LinkedHashSet<>())
                    || possiblyInherits(file, rightType, leftType.qualifiedName(), new LinkedHashSet<>())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean possiblyInherits(
        PsiFile file, VasSymbol derived, String baseIdentity, Set<String> visited
    ) {
        ProgressManager.checkCanceled();
        if (derived.qualifiedName().equals(baseIdentity)) {
            return true;
        }
        if (!visited.add(derived.qualifiedName())) {
            return false;
        }
        for (String baseName : derived.baseTypes()) {
            // The scanner does not fully bind base-type syntax. If multiple visible
            // types share its spelling, each remains a possible family relationship.
            for (VasSymbol candidate : typesNamed(file, simpleName(baseName))) {
                if (possiblyInherits(file, candidate, baseIdentity, visited)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<VasSymbol> typesNamed(PsiFile file, String name) {
        List<VasSymbol> types = new ArrayList<>();
        for (PsiElement declaration : VasSymbolResolver.findModuleDeclarations(file, name)) {
            VasSymbol type = VasSymbolResolver.findSymbol(declaration).orElse(null);
            if (type == null) {
                refuse("A possible VAS declaration family could not be inspected; rename was cancelled.");
            }
            if (type.kind() == VasSymbolKind.CLASS || type.kind() == VasSymbolKind.INTERFACE) {
                types.add(type);
            }
        }
        return types;
    }

    private static String simpleName(String name) {
        int separator = name.lastIndexOf("::");
        return separator < 0 ? name : name.substring(separator + 2);
    }

    private static boolean haveDisjointFunctionArities(VasSymbol left, VasSymbol right) {
        return left.kind() == VasSymbolKind.FUNCTION && right.kind() == VasSymbolKind.FUNCTION
            && left.requiredParameterCount() >= 0 && right.requiredParameterCount() >= 0
            && (left.parameterCount() < right.requiredParameterCount()
                || right.parameterCount() < left.requiredParameterCount());
    }

    private static boolean scopesOverlap(VasSymbol left, VasSymbol right) {
        return Math.max(left.offset(), left.scopeStart()) <= right.scopeEnd()
            && Math.max(right.offset(), right.scopeStart()) <= left.scopeEnd();
    }

    private static String declarationContainer(PsiFile file, VasSymbol symbol) {
        if (symbol.isProjectVisible()) {
            return symbol.container();
        }
        // A parameter is before the opening brace, so its use-site context alone
        // cannot recover an out-of-line method's owner. Its body scope can.
        return VasSymbolScanner.scan(file.getText()).stream()
            .filter(candidate -> candidate.kind() == VasSymbolKind.FUNCTION && candidate.scopeStart() >= 0
                && candidate.scopeStart() <= symbol.scopeStart() && candidate.scopeEnd() >= symbol.scopeEnd())
            .min(Comparator.comparingInt(candidate -> candidate.scopeEnd() - candidate.scopeStart()))
            .map(VasSymbol::container)
            .orElseGet(() -> VasSymbolScanner.usageContext(file.getText(), symbol.offset()).container());
    }

    private static boolean isEnclosingContainer(String lexicalContainer, String candidateContainer) {
        return candidateContainer.isEmpty() || lexicalContainer.equals(candidateContainer)
            || lexicalContainer.startsWith(candidateContainer + "::");
    }

    private static void refuse(String message) {
        throw new IncorrectOperationException(message);
    }
}
