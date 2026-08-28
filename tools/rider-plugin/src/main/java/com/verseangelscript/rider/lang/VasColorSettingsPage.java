package com.verseangelscript.rider.lang;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import com.verseangelscript.rider.VasIcons;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Map;

public final class VasColorSettingsPage implements ColorSettingsPage {
    private static final AttributesDescriptor[] DESCRIPTORS = {
        new AttributesDescriptor("Keyword", VasSyntaxHighlighter.KEYWORD),
        new AttributesDescriptor("Identifier", VasSyntaxHighlighter.IDENTIFIER),
        new AttributesDescriptor("Number", VasSyntaxHighlighter.NUMBER),
        new AttributesDescriptor("String", VasSyntaxHighlighter.STRING),
        new AttributesDescriptor("Comment", VasSyntaxHighlighter.COMMENT),
        new AttributesDescriptor("Preprocessor directive", VasSyntaxHighlighter.PREPROCESSOR),
        new AttributesDescriptor("Operator", VasSyntaxHighlighter.OPERATOR),
        new AttributesDescriptor("Braces", VasSyntaxHighlighter.BRACES),
        new AttributesDescriptor("Parentheses", VasSyntaxHighlighter.PARENTHESES),
        new AttributesDescriptor("Brackets", VasSyntaxHighlighter.BRACKETS),
        new AttributesDescriptor("Invalid character", VasSyntaxHighlighter.BAD_CHARACTER)
    };

    @Override
    public @Nullable Icon getIcon() {
        return VasIcons.FILE;
    }

    @Override
    public @NotNull SyntaxHighlighter getHighlighter() {
        return new VasSyntaxHighlighter();
    }

    @Override
    public @NotNull String getDemoText() {
        return """
            #include "shared.vas"

            // Verse AngelScript example
            class Player {
                private int health = 100;

                void ApplyDamage(int amount) {
                    health = max(0, health - amount);
                }
            }
            """;
    }

    @Override
    public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return null;
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        return DESCRIPTORS;
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return ColorDescriptor.EMPTY_ARRAY;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "VAS";
    }
}
