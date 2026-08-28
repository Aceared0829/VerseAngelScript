package com.verseangelscript.rider.lang;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.DumbService;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.util.ProcessingContext;
import com.verseangelscript.rider.index.VasSymbol;
import com.verseangelscript.rider.index.VasSymbolResolver;
import com.verseangelscript.rider.index.VasSymbolScanner;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;

public final class VasCompletionContributor extends CompletionContributor {
    public VasCompletionContributor() {
        extend(
            CompletionType.BASIC,
            // plugin.xml already restricts this contributor to VAS. Completion uses a
            // synthetic PSI position whose language may temporarily be ANY, so an
            // additional withLanguage(VAS) predicate suppresses otherwise valid results.
            PlatformPatterns.psiElement(),
            new KeywordProvider()
        );
    }

    private static final class KeywordProvider extends CompletionProvider<CompletionParameters> {
        @Override
        protected void addCompletions(
            @NotNull CompletionParameters parameters,
            @NotNull ProcessingContext context,
            @NotNull CompletionResultSet result
        ) {
            Set<String> added = new HashSet<>();
            for (String keyword : VasKeywords.ALL) {
                result.addElement(
                    LookupElementBuilder.create(keyword)
                        .bold()
                        .withTypeText("VAS keyword", true)
                );
                added.add(keyword);
            }

            for (VasSymbol symbol : VasSymbolScanner.scan(parameters.getOriginalFile().getText())) {
                if (added.add(symbol.name())) {
                    result.addElement(symbolLookup(symbol.name(), symbol.kind().displayName(), true));
                }
            }

            for (var runtimeSymbol : VasRuntimeSymbols.ALL.entrySet()) {
                if (added.add(runtimeSymbol.getKey())) {
                    result.addElement(
                        LookupElementBuilder.create(runtimeSymbol.getKey())
                            .withIcon(com.verseangelscript.rider.VasIcons.FILE)
                            .withTailText("  " + runtimeSymbol.getValue(), true)
                            .withTypeText("VAS runtime", true)
                    );
                }
            }

            if (!DumbService.isDumb(parameters.getPosition().getProject())) {
                for (String name : VasSymbolResolver.allProjectNames(
                    parameters.getPosition().getProject()
                )) {
                    if (added.add(name)) {
                        result.addElement(symbolLookup(name, "project symbol", false));
                    }
                }
            }
        }

        private static LookupElementBuilder symbolLookup(
            String name,
            String kind,
            boolean localFile
        ) {
            return LookupElementBuilder.create(name)
                .withIcon(com.verseangelscript.rider.VasIcons.FILE)
                .withTypeText("VAS " + kind + (localFile ? " · current file" : ""), true);
        }
    }
}
