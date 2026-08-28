package com.verseangelscript.rider.lang;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiBuilder;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.extapi.psi.ASTWrapperPsiElement;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import org.jetbrains.annotations.NotNull;

public final class VasParserDefinition implements ParserDefinition {
    private static final TokenSet COMMENTS = TokenSet.create(VasTypes.COMMENT);
    private static final TokenSet STRINGS = TokenSet.create(VasTypes.STRING);

    @Override
    public @NotNull Lexer createLexer(Project project) {
        return new VasLexer();
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        return new FlatVasParser();
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return VasTypes.FILE;
    }

    @Override
    public @NotNull TokenSet getWhitespaceTokens() {
        return TokenSet.create(TokenType.WHITE_SPACE);
    }

    @Override
    public @NotNull TokenSet getCommentTokens() {
        return COMMENTS;
    }

    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return STRINGS;
    }

    @Override
    public @NotNull PsiElement createElement(ASTNode node) {
        return new ASTWrapperPsiElement(node);
    }

    @Override
    public @NotNull PsiFile createFile(@NotNull FileViewProvider viewProvider) {
        return new VasFile(viewProvider);
    }

    @Override
    public @NotNull SpaceRequirements spaceExistenceTypeBetweenTokens(
        @NotNull ASTNode left,
        @NotNull ASTNode right
    ) {
        return SpaceRequirements.MAY;
    }

    private static final class FlatVasParser implements PsiParser {
        @Override
        public @NotNull ASTNode parse(
            @NotNull com.intellij.psi.tree.IElementType root,
            @NotNull PsiBuilder builder
        ) {
            PsiBuilder.Marker file = builder.mark();
            while (!builder.eof()) {
                builder.advanceLexer();
            }
            file.done(root);
            return builder.getTreeBuilt();
        }
    }
}
