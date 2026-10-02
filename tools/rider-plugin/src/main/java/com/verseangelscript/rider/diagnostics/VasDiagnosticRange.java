package com.verseangelscript.rider.diagnostics;

/** Maps compiler byte columns to code-point-safe UTF-16 annotation ranges. */
final class VasDiagnosticRange {
    private VasDiagnosticRange() {
    }

    /**
     * The bounds exclude the line separator and refer to the exact document text
     * written to the temporary UTF-8 source. AngelScript's ConvertPosToRowCol counts
     * bytes, including a tab as one byte. No disk BOM adjustment is needed: a BOM
     * is counted only if U+FEFF actually occurs in the submitted document text.
     */
    static Range forLine(CharSequence text, int lineStart, int lineEnd, int oneBasedByteColumn) {
        if (lineStart == lineEnd) {
            // In particular, an empty last line must not extend beyond EOF.
            return new Range(lineStart, lineStart);
        }

        long targetBytes = Math.max(0L, (long) oneBasedByteColumn - 1);
        long bytes = 0;
        int offset = lineStart;
        while (offset < lineEnd) {
            int codePoint = Character.codePointAt(text, offset);
            int width = utf8Width(codePoint);
            if (bytes + width > targetBytes) {
                // A column inside a multibyte sequence belongs to that code point.
                break;
            }
            bytes += width;
            offset += Character.charCount(codePoint);
        }

        while (offset < lineEnd && Character.isWhitespace(Character.codePointAt(text, offset))) {
            offset += Character.charCount(Character.codePointAt(text, offset));
        }
        if (offset == lineEnd) {
            // Retain the existing last-character fallback, including both halves
            // of a supplementary character rather than splitting its surrogate pair.
            int last = Character.codePointBefore(text, lineEnd);
            return new Range(lineEnd - Character.charCount(last), lineEnd);
        }

        int start = offset;
        int end = offset;
        if (isIdentifierCharacter(Character.codePointAt(text, offset))) {
            while (start > lineStart) {
                int previous = Character.codePointBefore(text, start);
                if (!isIdentifierCharacter(previous)) {
                    break;
                }
                start -= Character.charCount(previous);
            }
            while (end < lineEnd && isIdentifierCharacter(Character.codePointAt(text, end))) {
                end += Character.charCount(Character.codePointAt(text, end));
            }
        } else {
            end += Character.charCount(Character.codePointAt(text, end));
        }
        return new Range(start, end);
    }

    private static int utf8Width(int codePoint) {
        return codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
    }

    private static boolean isIdentifierCharacter(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    record Range(int startOffset, int endOffset) {
    }
}
