package com.verseangelscript.rider.lang;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
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
        VasSymbol symbol = VasSymbolResolver.findSymbol(target).orElse(null);
        PsiFile targetFile = target.getContainingFile();
        if (symbol == null || targetFile == null) {
            refuse("Select a uniquely identified VAS declaration before renaming.");
        }
        if (DumbService.isDumb(target.getProject())) {
            refuse("Wait for indexing to finish before renaming VAS symbols.");
        }
        VasLexer nameLexer = new VasLexer();
        nameLexer.start(newName);
        if (nameLexer.getTokenType() != VasTypes.IDENTIFIER || nameLexer.getTokenEnd() != newName.length()) {
            refuse("The new VAS name must be a single non-keyword identifier.");
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
        }
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
