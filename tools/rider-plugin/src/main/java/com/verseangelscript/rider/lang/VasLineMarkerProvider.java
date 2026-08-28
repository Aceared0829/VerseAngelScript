package com.verseangelscript.rider.lang;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiElement;
import com.verseangelscript.rider.index.VasSymbol;
import com.verseangelscript.rider.index.VasSymbolKind;
import com.verseangelscript.rider.index.VasSymbolResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

public final class VasLineMarkerProvider implements LineMarkerProvider {
    @Override
    public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (element.getNode().getElementType() != VasTypes.IDENTIFIER) {
            return null;
        }
        Optional<VasSymbol> symbol = VasSymbolResolver.findSymbol(element);
        if (symbol.isEmpty()) {
            return null;
        }

        List<PsiElement> targets;
        String tooltip;
        if (symbol.get().definition() && symbol.get().kind() == VasSymbolKind.FUNCTION) {
            targets = VasSymbolResolver.findDeclarationsForSymbol(element);
            tooltip = "Go to VAS declaration";
        } else {
            targets = VasSymbolResolver.findImplementations(element);
            tooltip = symbol.get().kind() == VasSymbolKind.FUNCTION
                ? "Go to VAS implementation"
                : "Go to VAS inheritors";
        }
        if (targets.isEmpty()) {
            return null;
        }

        return NavigationGutterIconBuilder.create(
                symbol.get().definition()
                    ? AllIcons.Gutter.ImplementingMethod
                    : AllIcons.Gutter.ImplementedMethod
            )
            .setTargets(targets)
            .setTooltipText(tooltip)
            .createLineMarkerInfo(element);
    }
}
