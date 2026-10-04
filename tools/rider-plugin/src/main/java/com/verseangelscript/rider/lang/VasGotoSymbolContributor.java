package com.verseangelscript.rider.lang;

import com.intellij.navigation.ChooseByNameContributor;
import com.intellij.navigation.ItemPresentation;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.verseangelscript.rider.VasIcons;
import com.verseangelscript.rider.index.VasMacroScanner;
import com.verseangelscript.rider.index.VasSymbolResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/** Exposes the VAS file index to Rider's native Search Everywhere / Go to Symbol UI. */
public final class VasGotoSymbolContributor implements ChooseByNameContributor {
    @Override
    public String @NotNull [] getNames(Project project, boolean includeNonProjectItems) {
        return VasSymbolResolver.allProjectNames(project).toArray(String[]::new);
    }

    @Override
    public NavigationItem @NotNull [] getItemsByName(String name, String pattern, Project project, boolean includeNonProjectItems) {
        return VasSymbolResolver.findProjectDeclarations(project, name).stream()
            .map(element -> new Declaration(name, element)).toArray(NavigationItem[]::new);
    }

    private record Declaration(String name, PsiElement element) implements NavigationItem {
        @Override public @Nullable String getName() { return name; }
        @Override public @Nullable ItemPresentation getPresentation() {
            return new ItemPresentation() {
                @Override public @Nullable String getPresentableText() {
                    return VasSymbolResolver.findSymbol(element).map(symbol -> symbol.qualifiedName()).orElse(name);
                }
                @Override public @Nullable String getLocationString() { return element.getContainingFile().getVirtualFile().getPresentableUrl(); }
                @Override public @Nullable Icon getIcon(boolean unused) { return VasIcons.FILE; }
            };
        }
        @Override public boolean canNavigate() { return element.isValid() && element.getContainingFile().getVirtualFile() != null; }
        @Override public boolean canNavigateToSource() { return canNavigate(); }
        @Override public void navigate(boolean requestFocus) {
            if (!canNavigate()) return;
            int offset = element.getTextOffset();
            if (element.getNode().getElementType() == VasTypes.PREPROCESSOR) {
                offset = VasMacroScanner.scan(element.getContainingFile().getText()).stream().filter(macro -> macro.name().equals(name))
                    .findFirst().map(VasMacroScanner.Macro::offset).orElse(offset);
            }
            new OpenFileDescriptor(element.getProject(), element.getContainingFile().getVirtualFile(), offset).navigate(requestFocus);
        }
    }
}
