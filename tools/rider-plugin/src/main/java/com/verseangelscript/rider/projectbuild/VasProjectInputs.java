package com.verseangelscript.rider.projectbuild;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/** Saved-file observations, not an atomic filesystem snapshot. */
public final class VasProjectInputs {
    private static final int LIMIT = 16 * 1024 * 1024;
    private VasProjectInputs() {}

    /** An escaped display path is not a filesystem identity or a verified input. */
    public static Path resolveObservedPath(String cwd, VasProjectProtocol.Identity identity) throws IOException {
        if (!identity.bindable())
            throw new IOException("Compiler reported a source path that cannot be verified. Use valid Unicode source filenames and build again.");
        try {
            Path value = Path.of(identity.display());
            Path resolved = (value.isAbsolute() ? value : Path.of(cwd).resolve(value)).normalize();
            if (!resolved.isAbsolute()) throw new IOException("Compiler reported a source path without a verifiable absolute identity.");
            return resolved;
        } catch (InvalidPathException exception) {
            throw new IOException("Compiler reported a source path that this system cannot verify.", exception);
        }
    }

    public record Snapshot(Path path, String stamp, byte[] digest, String text, int byteLength) {
        public String editorText() {
            return text == null ? null : normalizeEditor(text);
        }
        public boolean unchanged(boolean content) throws IOException {
            if (!stamp.equals(VasProjectInputs.stamp(path))) return false;
            return !content || Arrays.equals(digest, capture(path).digest);
        }
    }

    public static Snapshot capture(Path path) throws IOException {
        String before = stamp(path);
        if (!Files.isRegularFile(path)) return new Snapshot(path, before, null, null, 0);
        long size = Files.size(path);
        if (size > LIMIT) throw new IOException("VAS input exceeds the 16 MiB client limit: " + path);
        byte[] bytes;
        try (SeekableByteChannel channel = Files.newByteChannel(path)) {
            var output = new java.io.ByteArrayOutputStream((int) size);
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) != -1) {
                if (output.size() + buffer.position() > LIMIT) throw new IOException("VAS input exceeds the client limit: " + path);
                output.write(buffer.array(), 0, buffer.position());
                buffer.clear();
            }
            bytes = output.toByteArray();
        }
        if (!before.equals(stamp(path))) throw new IOException("VAS input changed while reading: " + path);
        String text = null;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ignored) { /* Invalid source bytes cannot map to editor text. */ }
        try { return new Snapshot(path, before, MessageDigest.getInstance("SHA-256").digest(bytes), text, bytes.length); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    public static String stamp(Path path) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return attributes.isRegularFile() + ":" + attributes.fileKey() + ":" + attributes.size()
                + ":" + attributes.lastModifiedTime() + ":" + attributes.creationTime();
        } catch (java.nio.file.NoSuchFileException exception) { return "missing"; }
    }

    private static String normalizeEditor(String text) {
        String value = text.startsWith("\uFEFF") ? text.substring(1) : text;
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** Exact one-based UTF-8 byte point to UTF-16 offset. No token range is invented. */
    public static int offset(String text, int row, int column) {
        if (text == null || row <= 0 || column <= 0) return -1;
        int start = 0;
        for (int line = 1; line < row; ++line) {
            int newline = text.indexOf('\n', start);
            if (newline < 0) return -1;
            start = newline + 1;
        }
        long wanted = (long) column - 1, bytes = 0;
        for (int index = start; ; ) {
            if (bytes == wanted) return normalizeEditor(text.substring(0, index)).length();
            if (index >= text.length() || text.charAt(index) == '\n') return -1;
            int cp = text.codePointAt(index);
            int width = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (bytes + width > wanted) return -1;
            bytes += width;
            index += Character.charCount(cp);
        }
    }
}
