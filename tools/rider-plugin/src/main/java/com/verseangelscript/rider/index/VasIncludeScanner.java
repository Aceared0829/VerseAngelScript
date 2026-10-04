package com.verseangelscript.rider.index;

import com.verseangelscript.rider.lang.VasKeywords;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Bounded extraction of VAS module imports and quoted/angle includes.
 * Directive names touch '#'; a single quoted value may follow immediately or after
 * whitespace, including newlines. Comments are not whitespace within a directive.
 * Unknown preprocessing is reported, never treated as a complete dependency graph.
 */
public final class VasIncludeScanner {
    public enum Kind { QUOTED, SYSTEM, MODULE }
    public record Include(String path, int offset, int pathStart, int pathEnd, int end, Kind kind) {
        public Include(String path, int offset, int pathStart, int pathEnd, int end) {
            this(path, offset, pathStart, pathEnd, end, Kind.QUOTED);
        }
    }
    public record Problem(int offset, String message) { }
    public record Result(List<Include> includes, List<Problem> problems) {
        public Result {
            includes = List.copyOf(includes);
            problems = List.copyOf(problems);
        }

        public boolean complete() {
            return problems.isEmpty();
        }
    }

    private record Quoted(int end, boolean terminated, boolean heredoc) { }

    private VasIncludeScanner() { }

    public static Result scan(CharSequence source) {
        List<Include> includes = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        int length = source.length();
        int offset = 0;
        int bracketDepth = 0;
        int parenthesisDepth = 0;
        ArrayDeque<Boolean> containerBlocks = new ArrayDeque<>();
        int statementBlockDepth = 0;
        boolean statementStart = true;
        boolean containerHeader = false;
        while (offset < length) {
            char character = source.charAt(offset);
            if (isWhitespace(character)) {
                offset++;
            } else if (startsWith(source, offset, "//")) {
                offset = lineEnd(source, offset + 2);
            } else if (startsWith(source, offset, "/*")) {
                int end = offset + 2;
                while (end < length && !startsWith(source, end, "*/")) {
                    end++;
                }
                if (end == length) {
                    problems.add(new Problem(offset, "Unterminated block comment"));
                }
                offset = Math.min(length, end + 2);
            } else if (character == '\'' || character == '"') {
                Quoted quoted = quoted(source, offset);
                if (!quoted.terminated()) {
                    problems.add(new Problem(offset, "Unterminated string literal"));
                }
                offset = quoted.end();
                if (bracketDepth == 0) statementStart = false;
            } else if (isDigit(character, 10)
                || character == '.' && offset + 1 < length && isDigit(source.charAt(offset + 1), 10)) {
                // A numeric separator is not the start of a single-quoted string.
                offset = numberEnd(source, offset);
                if (bracketDepth == 0) statementStart = false;
            } else if (character == '#') {
                int start = offset++;
                if (bracketDepth > 0 || parenthesisDepth > 0 || statementBlockDepth > 0 || !statementStart) {
                    problems.add(new Problem(start, "Preprocessing inside a statement or metadata is unsupported"));
                }
                if (offset < length && source.charAt(offset) == '!') {
                    offset = lineEnd(source, offset + 1);
                    continue;
                }
                int nameStart = offset;
                while (offset < length && isIdentifierPart(source.charAt(offset))) {
                    offset++;
                }
                String directive = source.subSequence(nameStart, offset).toString();
                if (!directive.equals("include")) {
                    String reason = switch (directive) {
                        case "if", "ifdef", "ifndef", "elif", "else", "endif" ->
                            "Conditional preprocessing requires compiler configuration";
                        case "" -> "Malformed preprocessor directive";
                        default -> "Unsupported preprocessor directive #" + directive;
                    };
                    problems.add(new Problem(start, reason));
                    offset = lineEnd(source, offset);
                    continue;
                }
                // scriptbuilder skips one tokenizer whitespace token. A BOM is
                // a separate token, so do not collapse ASCII whitespace plus BOM.
                if (offset < length && source.charAt(offset) == '\uFEFF') {
                    offset++;
                } else {
                    while (offset < length && isAsciiWhitespace(source.charAt(offset))) {
                        offset++;
                    }
                }
                if (offset < length && source.charAt(offset) == '<') {
                    int pathStart = ++offset;
                    while (offset < length && source.charAt(offset) != '>' && !isWhitespace(source.charAt(offset))) offset++;
                    if (offset < length && offset > pathStart && source.charAt(offset) == '>') {
                        includes.add(new Include(source.subSequence(pathStart, offset).toString(), start, pathStart, offset, ++offset, Kind.SYSTEM));
                    } else {
                        problems.add(new Problem(start, "Invalid angle include path"));
                    }
                    continue;
                }
                if (offset == length || source.charAt(offset) != '\'' && source.charAt(offset) != '"') {
                    problems.add(new Problem(start, "Expected a quoted include path"));
                    continue;
                }
                int valueStart = offset;
                Quoted quoted = quoted(source, offset);
                offset = quoted.end();
                if (!quoted.terminated() || quoted.heredoc()) {
                    problems.add(new Problem(start, "Unsupported or unterminated include path"));
                    continue;
                }
                // scriptbuilder passes the literal's raw interior to its loader;
                // it does not decode backslash escapes as a language string would.
                String path = source.subSequence(valueStart + 1, offset - 1).toString();
                if (path.isEmpty() || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0
                    || path.indexOf('\0') >= 0) {
                    problems.add(new Problem(start, "Empty or multiline include path"));
                } else {
                    includes.add(new Include(path, start, valueStart + 1, offset - 1, offset));
                }
            } else if (isIdentifierPart(character)) {
                int start = offset;
                offset = identifierEnd(source, offset);
                String scannedWord = source.subSequence(start, offset).toString();
                if (scannedWord.equals("import")) {
                    int nameStart = skipTrivia(source, offset);
                    int cursor = nameStart;
                    StringBuilder path = new StringBuilder();
                    boolean completeName = false;
                    while (cursor < length && isIdentifierPart(source.charAt(cursor)) && !isDigit(source.charAt(cursor), 10)) {
                        int nameEnd = identifierEnd(source, cursor);
                        String part = source.subSequence(cursor, nameEnd).toString();
                        if (VasKeywords.RESERVED.contains(part)) break;
                        if (!path.isEmpty()) path.append('/');
                        path.append(part);
                        cursor = skipTrivia(source, nameEnd);
                        if (cursor < length && source.charAt(cursor) == '.') cursor = skipTrivia(source, cursor + 1);
                        else { completeName = true; break; }
                    }
                    if (completeName && !path.isEmpty() && cursor < length && source.charAt(cursor) == ';') {
                        if (bracketDepth > 0 || parenthesisDepth > 0 || !containerBlocks.isEmpty()) {
                            problems.add(new Problem(start, "Module imports must be at file scope"));
                        } else {
                            int nameEnd = cursor;
                            while (nameEnd > nameStart && isWhitespace(source.charAt(nameEnd - 1))) nameEnd--;
                            includes.add(new Include(path + ".vas", start, nameStart, nameEnd, cursor + 1, Kind.MODULE));
                        }
                        offset = cursor + 1;
                        statementStart = true;
                        continue;
                    }
                }
                if (bracketDepth == 0) {
                    String token = scannedWord;
                    if (statementStart && (token.equals("class") || token.equals("interface")
                        || token.equals("namespace"))) {
                        containerHeader = true;
                    }
                    if (!(statementStart && (token.equals("shared") || token.equals("abstract")
                        || token.equals("mixin") || token.equals("external")))) {
                        statementStart = false;
                    }
                }
            } else {
                if (character == '[') bracketDepth++;
                if (character == ']') bracketDepth = Math.max(0, bracketDepth - 1);
                if (character == '(') parenthesisDepth++;
                if (character == ')') parenthesisDepth = Math.max(0, parenthesisDepth - 1);
                if (bracketDepth == 0 && parenthesisDepth == 0) {
                    if (character == '{') {
                        boolean container = containerHeader && statementBlockDepth == 0;
                        containerBlocks.addLast(container);
                        if (!container) statementBlockDepth++;
                        containerHeader = false;
                        statementStart = container;
                    } else if (character == '}') {
                        if (!containerBlocks.isEmpty() && !containerBlocks.removeLast()) statementBlockDepth--;
                        containerHeader = false;
                        statementStart = statementBlockDepth == 0;
                    } else if (character == ';') {
                        containerHeader = false;
                        statementStart = statementBlockDepth == 0;
                    } else if (!isWhitespace(character) && character != ']') {
                        statementStart = false;
                    }
                }
                offset++;
            }
        }
        return new Result(includes, problems);
    }

    private static int skipTrivia(CharSequence source, int offset) {
        for (;;) {
            while (offset < source.length() && isWhitespace(source.charAt(offset))) offset++;
            if (startsWith(source, offset, "//")) { offset = lineEnd(source, offset + 2); continue; }
            if (startsWith(source, offset, "/*")) {
                int end = offset + 2;
                while (end < source.length() && !startsWith(source, end, "*/")) end++;
                offset = Math.min(source.length(), end + 2);
                continue;
            }
            return offset;
        }
    }

    private static Quoted quoted(CharSequence source, int start) {
        int length = source.length();
        boolean heredoc = startsWith(source, start, "\"\"\"");
        int offset = start + (heredoc ? 3 : 1);
        while (offset < length) {
            if (heredoc && startsWith(source, offset, "\"\"\"")) {
                return new Quoted(offset + 3, true, true);
            }
            if (!heredoc && source.charAt(offset) == source.charAt(start)) {
                return new Quoted(offset + 1, true, false);
            }
            if (!heredoc && source.charAt(offset) == '\\' && offset + 1 < length) {
                offset++;
            }
            offset++;
        }
        return new Quoted(length, false, heredoc);
    }

    private static int numberEnd(CharSequence source, int start) {
        int length = source.length();
        if (source.charAt(start) == '0' && start + 1 < length) {
            int radix = switch (source.charAt(start + 1)) {
                case 'b', 'B' -> 2;
                case 'o', 'O' -> 8;
                case 'd', 'D' -> 10;
                case 'x', 'X' -> 16;
                default -> 0;
            };
            if (radix > 0) {
                return digitsEnd(source, start + 2, radix);
            }
        }
        int offset = digitsEnd(source, start, 10);
        boolean floating = false;
        if (offset < length && source.charAt(offset) == '.') {
            floating = true;
            offset = digitsEnd(source, offset + 1, 10);
        }
        if (offset < length && (source.charAt(offset) == 'e' || source.charAt(offset) == 'E')) {
            floating = true;
            offset++;
            if (offset < length && (source.charAt(offset) == '+' || source.charAt(offset) == '-')) {
                offset++;
            }
            offset = digitsEnd(source, offset, 10);
        }
        if (floating && offset < length && (source.charAt(offset) == 'f' || source.charAt(offset) == 'F')) {
            offset++;
        }
        return offset;
    }

    private static int digitsEnd(CharSequence source, int start, int radix) {
        int offset = start;
        while (offset < source.length()) {
            char character = source.charAt(offset);
            if (isDigit(character, radix) || character == '\'' && offset > start
                && offset + 1 < source.length() && isDigit(source.charAt(offset - 1), radix)
                && isDigit(source.charAt(offset + 1), radix)) {
                offset++;
            } else {
                break;
            }
        }
        return offset;
    }

    private static boolean isDigit(char character, int radix) {
        return character >= '0' && character <= '9' && character - '0' < radix
            || character >= 'a' && character <= 'f' && character - 'a' + 10 < radix
            || character >= 'A' && character <= 'F' && character - 'A' + 10 < radix;
    }

    private static int lineEnd(CharSequence source, int start) {
        int offset = start;
        while (offset < source.length() && source.charAt(offset) != '\n') {
            offset++;
        }
        return offset;
    }

    private static boolean isWhitespace(char character) {
        return isAsciiWhitespace(character) || character == '\uFEFF';
    }

    private static boolean isAsciiWhitespace(char character) {
        return character == ' ' || character == '\t' || character == '\r' || character == '\n';
    }

    private static int identifierEnd(CharSequence source, int start) {
        int asciiEnd = start;
        while (asciiEnd < source.length() && source.charAt(asciiEnd) < 128 && isIdentifierPart(source.charAt(asciiEnd))) {
            asciiEnd++;
        }
        if (asciiEnd > start && asciiEnd < source.length() && source.charAt(asciiEnd) >= 128
            && VasKeywords.RESERVED.contains(source.subSequence(start, asciiEnd).toString())) {
            return asciiEnd;
        }
        int end = start + 1;
        while (end < source.length() && isIdentifierPart(source.charAt(end))) {
            end++;
        }
        return end;
    }

    private static boolean isIdentifierPart(char character) {
        return character == '_' || character >= 'a' && character <= 'z'
            || character >= 'A' && character <= 'Z' || character >= '0' && character <= '9' || character >= 128;
    }

    private static boolean startsWith(CharSequence source, int offset, String value) {
        if (offset + value.length() > source.length()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (source.charAt(offset + index) != value.charAt(index)) {
                return false;
            }
        }
        return true;
    }
}
