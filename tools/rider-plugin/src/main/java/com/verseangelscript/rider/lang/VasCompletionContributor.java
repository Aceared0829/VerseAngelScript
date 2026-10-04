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
import com.verseangelscript.rider.index.VasSymbolKind;
import com.verseangelscript.rider.index.VasUsageContext;
import com.verseangelscript.rider.index.VasCompletionSelection;
import java.util.ArrayList;
import com.verseangelscript.rider.index.VasMacroScanner;
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
            var original = parameters.getOriginalFile();
            String text = original.getText();
            int offset = Math.min(parameters.getOffset(), text.length());
            int wordStart = offset;
            while (wordStart > 0 && Character.isJavaIdentifierPart(text.charAt(wordStart - 1))) wordStart--;
            var usage = VasSymbolScanner.usageContext(text.substring(0, wordStart) + "__VasCompletion__;", wordStart);
            boolean plain = usage.access() == VasUsageContext.Access.UNQUALIFIED;
            var local = VasSymbolScanner.scan(text);
            var dependencies = new ArrayList<VasSymbol>();
            var sourceTexts = new java.util.HashMap<VasSymbol, String>();
            for (var symbol : local) sourceTexts.put(symbol, text);
            var files = VasSymbolResolver.navigationFiles(original);
            for (var file : files) if (!file.equals(original)) {
                String sourceText = file.getText(); var symbols = VasSymbolScanner.scan(sourceText);
                dependencies.addAll(symbols);
                for (var symbol : symbols) sourceTexts.putIfAbsent(symbol, sourceText);
            }
            for (var symbol : VasCompletionSelection.select(local, dependencies, wordStart, usage)) {
                String identity = symbol.kind() == VasSymbolKind.FUNCTION ? symbol.signature() : symbol.name();
                if (added.add(identity)) {
                    var item = symbolLookup(symbol.name(), symbol.kind().displayName(), local.contains(symbol));
                    if (symbol.kind() == VasSymbolKind.FUNCTION) {
                        String label = VasCallSupport.signature(sourceTexts.get(symbol), symbol).label();
                        item = item.withTailText("  " + label, true).withInsertHandler((insertion, selected) -> insertCall(insertion, symbol.requiredParameterCount() > 0));
                    } else item = item.withTailText("  " + symbol.qualifiedName(), true);
                    result.addElement(item);
                }
            }
            if (plain) {
                for (String keyword : VasKeywords.ALL) if (added.add(keyword)) result.addElement(LookupElementBuilder.create(keyword).bold().withTypeText("VAS keyword", true));
                for (var file : files) for (var macro : VasMacroScanner.scan(file.getText())) {
                    if (added.add(macro.name())) result.addElement(LookupElementBuilder.create(macro.name())
                        .withTypeText("VAS macro · " + file.getName(), true).withTailText("  " + macro.declaration(), true));
                }
            }

            for (var runtimeSymbol : (plain ? VasRuntimeSymbols.ALL : java.util.Map.<String, String>of()).entrySet()) {
                if (added.add(runtimeSymbol.getKey())) {
                    result.addElement(
                        LookupElementBuilder.create(runtimeSymbol.getKey())
                            .withIcon(com.verseangelscript.rider.VasIcons.FILE)
                            .withTailText("  " + runtimeSymbol.getValue(), true)
                            .withTypeText("VAS runtime", true)
                            .withInsertHandler((insertion, selected) -> {
                                if (runtimeSymbol.getValue().contains("(")) insertCall(insertion,
                                    !runtimeSymbol.getValue().contains("()"));
                            })
                    );
                }
            }

            if (plain && parameters.getInvocationCount() > 1 && !DumbService.isDumb(parameters.getPosition().getProject())) {
                for (String name : VasSymbolResolver.allProjectNames(
                    parameters.getPosition().getProject()
                )) {
                    if (added.add(name)) {
                        result.addElement(symbolLookup(name, "project symbol", false));
                    }
                }
            }
        }

        private static void insertCall(com.intellij.codeInsight.completion.InsertionContext context, boolean arguments) {
            var document = context.getDocument(); int tail = context.getTailOffset(), next = tail;
            while (next < document.getTextLength() && Character.isWhitespace(document.getCharsSequence().charAt(next))) next++;
            if (next >= document.getTextLength() || document.getCharsSequence().charAt(next) != '(') {
                document.insertString(tail, "()"); context.setTailOffset(tail + 2);
                context.getEditor().getCaretModel().moveToOffset(tail + (arguments ? 1 : 2));
            } else context.getEditor().getCaretModel().moveToOffset(next + 1);
            if (context.getCompletionChar() == '(') context.setAddCompletionChar(false);
            com.intellij.codeInsight.AutoPopupController.getInstance(context.getProject()).autoPopupParameterInfo(context.getEditor(), null);
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
