package com.verseangelscript.rider.index;

import com.verseangelscript.rider.lang.VasLexer;
import com.verseangelscript.rider.lang.VasTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Source declarations only: conditional activation and expansion belong to the compiler. */
public final class VasMacroScanner {
    public record Macro(String name, int offset, String declaration) { }
    private static final Pattern DEFINE = Pattern.compile("^#define[ \\t]+([A-Za-z_][A-Za-z_0-9]*)(.*)");

    private VasMacroScanner() { }

    public static List<Macro> scan(CharSequence source) {
        List<Macro> result = new ArrayList<>();
        VasLexer lexer = new VasLexer();
        lexer.start(source);
        while (lexer.getTokenType() != null) {
            if (lexer.getTokenType() == VasTypes.PREPROCESSOR) {
                String text = source.subSequence(lexer.getTokenStart(), lexer.getTokenEnd()).toString();
                var match = DEFINE.matcher(text);
                if (match.find()) {
                    result.add(new Macro(match.group(1), lexer.getTokenStart() + match.start(1), text));
                }
            }
            lexer.advance();
        }
        return List.copyOf(result);
    }
}
