package com.verseangelscript.rider.lang;

import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import com.verseangelscript.rider.VasLanguage;

public final class VasTypes {
    public static final IFileElementType FILE = new IFileElementType(VasLanguage.INSTANCE);

    public static final IElementType KEYWORD = new VasTokenType("KEYWORD");
    public static final IElementType IDENTIFIER = new VasTokenType("IDENTIFIER");
    public static final IElementType NUMBER = new VasTokenType("NUMBER");
    public static final IElementType STRING = new VasTokenType("STRING");
    public static final IElementType COMMENT = new VasTokenType("COMMENT");
    public static final IElementType PREPROCESSOR = new VasTokenType("PREPROCESSOR");
    public static final IElementType OPERATOR = new VasTokenType("OPERATOR");
    public static final IElementType LBRACE = new VasTokenType("LBRACE");
    public static final IElementType RBRACE = new VasTokenType("RBRACE");
    public static final IElementType LPAREN = new VasTokenType("LPAREN");
    public static final IElementType RPAREN = new VasTokenType("RPAREN");
    public static final IElementType LBRACKET = new VasTokenType("LBRACKET");
    public static final IElementType RBRACKET = new VasTokenType("RBRACKET");

    private VasTypes() {
    }
}
