package com.verseangelscript.rider.projectbuild;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decodes the compiler-owned project and report contracts without reading or
 * interpreting a manifest. This class deliberately has no IntelliJ dependency.
 * All paths are the native compiler's exact lexical identities, not realpaths.
 */
public final class VasProjectProtocol {
    public static final int MAX_DESCRIPTOR_BYTES = 16 * 1024 * 1024;
    public static final int MAX_RECORD_BYTES = 1024 * 1024;
    public static final int MAX_REPORT_BYTES = 64 * 1024 * 1024;
    public static final int MAX_EVENTS = 100_000;
    private static final int MAX_JSON_DEPTH = 64;
    private static final Pattern UNIT_ID = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}");
    private static final Pattern INTEGER = Pattern.compile("-?(?:0|[1-9][0-9]*)");
    private static final Set<String> PHASES = Set.of("arguments", "engine", "config", "load", "compile", "output");

    private VasProjectProtocol() { }

    public record Unit(String id, String entry, String config, String output) { }

    public record Descriptor(String project, String root, Integer schemaVersion, boolean legacy,
                             List<Unit> units, List<String> warnings) {
        public Descriptor {
            units = List.copyOf(units);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Positions are signed, one-based UTF-8 byte positions; zero stays unknown.
     * Bindable only describes filename encoding. The caller must additionally
     * establish that a section is a file and that exact source bytes are current.
     */
    public record Diagnostic(String severity, String message, String section,
                             int row, int column, boolean bindable) { }

    /** An invalid-byte filename has an exact raw identity but no usable JVM path. */
    public record Identity(String display, String rawHex) {
        public boolean bindable() { return rawHex == null && !display.isEmpty() && display.indexOf('\0') < 0; }
    }

    public static final class ProtocolException extends IllegalArgumentException {
        public ProtocolException(String message) { super(message); }
        private ProtocolException(String message, Throwable cause) { super(message, cause); }
    }

    /** The response must contain exactly one object and its terminating LF. */
    public static Descriptor describe(byte[] stdout, int exit) {
        require(stdout != null && stdout.length > 1 && stdout.length <= MAX_DESCRIPTOR_BYTES,
            "Missing or oversized VAS project descriptor.");
        require(stdout[stdout.length - 1] == '\n', "Truncated VAS project descriptor (missing final LF).");
        JsonObject object = object(parseLine(stdout, stdout.length - 1, "VAS project descriptor"), "descriptor");
        require("vas-project".equals(string(object, "protocol")) && integer(object, "version") == 1,
            "Unsupported VAS project protocol. Select a vasbuild compiler supporting native project protocol v1.");
        boolean success = bool(object, "success");
        require((exit == 0) == success, "VAS project descriptor disagrees with the compiler exit status.");
        JsonArray units = array(object, "compilationUnits");
        JsonArray warnings = array(object, "warnings");
        JsonArray errors = array(object, "errors");
        boolean legacy = bool(object, "legacyProject");
        Integer schema = nullableInteger(object, "projectSchemaVersion");
        nullableString(object, "name");
        String project = nullableString(object, "project");
        String root = nullableString(object, "projectRoot");
        require(project == null || absoluteIdentity(project), "Invalid VAS project path identity.");
        require(root == null || absoluteIdentity(root), "Invalid VAS project root identity.");
        List<String> warningMessages = new ArrayList<>();
        int legacyWarnings = 0;
        for (JsonElement element : warnings) {
            JsonObject warning = object(element, "warning");
            if ("legacy_project".equals(string(warning, "code"))) legacyWarnings++;
            warningMessages.add(string(warning, "message"));
        }
        List<String> errorMessages = new ArrayList<>();
        for (JsonElement element : errors) {
            JsonObject error = object(element, "error");
            string(error, "code");
            String field = string(error, "field");
            String message = string(error, "message");
            string(error, "section");
            require(integer(error, "row") >= 0 && integer(error, "column") >= 0,
                "Invalid VAS project error position.");
            JsonElement offset = required(error, "byteOffset");
            require(offset.isJsonNull() || positiveLong(offset), "Invalid VAS project error byte offset.");
            errorMessages.add((field.isEmpty() ? "project" : field) + ": " + message);
        }
        if (!success) {
            require(units.isEmpty() && !errorMessages.isEmpty() && schema == null,
                "Invalid failed VAS project descriptor.");
            throw new ProtocolException(String.join("\n", errorMessages));
        }
        require(project != null && root != null && parent(project).equals(root),
            "The VAS descriptor project root does not match the manifest's lexical parent.");
        require(errors.isEmpty() && (legacy ? schema == null : Integer.valueOf(1).equals(schema)),
            "Invalid VAS project descriptor metadata.");
        require(units.size() >= 1 && units.size() <= 256, "Invalid VAS compilation unit count.");
        require(!legacy || (units.size() == 1 && legacyWarnings == 1), "Invalid VAS legacy project descriptor.");
        Set<String> ids = new HashSet<>();
        Set<String> outputs = new HashSet<>();
        List<Unit> decoded = new ArrayList<>();
        for (JsonElement element : units) {
            JsonObject unit = object(element, "compilation unit");
            String id = string(unit, "id");
            require(UNIT_ID.matcher(id).matches() && ids.add(id), "Invalid or duplicate VAS compilation unit ID.");
            String entry = path(unit, "entry");
            String config = path(object(required(unit, "hostApi"), "hostApi"), "config");
            String output = path(unit, "output");
            require(within(root, entry) && within(root, config) && within(root, output),
                "VAS compilation unit paths must remain within the descriptor's project root.");
            require(entry.endsWith(".vas"), "VAS compilation unit entry must end with lowercase .vas.");
            String outputKey = windowsIdentity(root) ? output.toLowerCase(Locale.ROOT) : output;
            require(outputs.add(outputKey), "Duplicate VAS compilation unit output identity.");
            require(!legacy || "main".equals(id), "Invalid VAS legacy compilation unit ID.");
            decoded.add(new Unit(id, entry, config, output));
        }
        return new Descriptor(project, root, schema, legacy, decoded, warningMessages);
    }

    /**
     * Bounded incremental report decoder. Calls must be serialized. The transport
     * owns LF splitting and must reject an unterminated final fragment; it must
     * never pass such a fragment to acceptLine. Every accepted line counts its LF
     * towards the total byte limit. Validation failures permanently poison the
     * invocation, while partial dependency observations remain available.
     */
    public static final class Report {
        private final Descriptor descriptor;
        private final Unit unit;
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private final Set<Identity> observed = new LinkedHashSet<>();
        private final Set<Identity> invalidSources = new LinkedHashSet<>();
        private final Set<Integer> attempts = new HashSet<>();
        private long bytes;
        private int sequence;
        private String cwd;
        private String phase;
        private boolean terminal;
        private boolean terminalSuccess;
        private boolean terminalDependenciesComplete;
        private boolean complete;
        private ProtocolException failure;
        private Identity lastObserved;
        private boolean lastObservationLoaded;

        public Report(Descriptor descriptor, Unit unit) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            this.unit = Objects.requireNonNull(unit, "unit");
            require(descriptor.units().contains(unit), "Selected VAS unit is not in this descriptor.");
        }

        public void acceptLine(byte[] raw) {
            lastObserved = null;
            lastObservationLoaded = false;
            if (failure != null) throw failure;
            try {
                require(!terminal && !complete, "VAS report contains a record after its terminal result.");
                require(raw != null && raw.length > 0 && raw.length <= MAX_RECORD_BYTES,
                    "Empty or oversized VAS report record.");
                bytes += (long) raw.length + 1;
                require(bytes <= MAX_REPORT_BYTES, "VAS report exceeded the total byte limit.");
                require(sequence < MAX_EVENTS, "VAS report exceeded the event limit.");
                JsonObject record = object(parseLine(raw, raw.length, "VAS report"), "report record");
                require("vasbuild".equals(string(record, "protocol")) && integer(record, "version") == 1,
                    "Unsupported VAS build report protocol. Select a vasbuild compiler supporting report protocol v1.");
                int next = integer(record, "seq");
                require(next == sequence + 1, "VAS report sequence is not consecutive.");
                Set<String> invalid = encodingMetadata(record);
                String type = string(record, "type");
                require(sequence != 0 || "start".equals(type), "VAS report is missing its start record.");
                switch (type) {
                    case "start" -> start(record, invalid);
                    case "diagnostic" -> diagnostic(record, invalid);
                    case "section_loaded" -> {
                        String section = string(record, "section");
                        boolean utf8Valid = bool(record, "utf8Valid");
                        Identity identity = identity(record, "section", section, invalid);
                        observed.add(identity);
                        lastObserved = identity;
                        lastObservationLoaded = true;
                        if (!utf8Valid) invalidSources.add(identity);
                    }
                    case "include_attempt" -> {
                        string(record, "from");
                        string(record, "requested");
                        String resolved = nullableString(record, "resolved");
                        attempts.add(next);
                        if (resolved != null) {
                            lastObserved = identity(record, "resolved", resolved, invalid);
                            observed.add(lastObserved);
                        }
                    }
                    case "include_result" -> {
                        int attempt = integer(record, "attemptSeq");
                        String status = string(record, "status");
                        require(Set.of("loaded", "skipped", "failed", "rejected").contains(status)
                                && attempts.remove(attempt), "Invalid or duplicate VAS include result.");
                    }
                    case "result" -> result(record);
                    default -> throw new ProtocolException("Unknown VAS report record type: " + type);
                }
                sequence = next;
            } catch (ProtocolException error) {
                failure = error;
                complete = false;
                throw error;
            }
        }

        private void start(JsonObject record, Set<String> invalid) {
            require(sequence == 0 && "vasbuild".equals(string(record, "compiler"))
                    && "utf-8-bytes".equals(string(record, "positionEncoding"))
                    && integer(record, "positionBase") == 1, "Invalid VAS start record.");
            string(record, "compilerVersion");
            equalIdentity(record, "project", descriptor.project(), invalid);
            equalIdentity(record, "unit", unit.id(), invalid);
            equalIdentity(record, "entry", unit.entry(), invalid);
            equalIdentity(record, "config", unit.config(), invalid);
            equalIdentity(record, "output", unit.output(), invalid);
            require(Objects.equals(nullableInteger(record, "projectSchemaVersion"), descriptor.schemaVersion())
                    && bool(record, "legacyProject") == descriptor.legacy(), "VAS project schema identity changed before build.");
            String directory = nullableString(record, "cwd");
            require(directory != null && nativeAbsolute(directory) && directory.indexOf('\0') < 0 && !invalid.contains("cwd"),
                "VAS report has no usable native working directory.");
            cwd = directory;
        }

        private void diagnostic(JsonObject record, Set<String> invalid) {
            String severity = string(record, "severity");
            require(Set.of("error", "warning", "information").contains(severity), "Invalid VAS diagnostic severity.");
            String message = string(record, "message");
            String section = string(record, "section");
            int row = integer(record, "row");
            int column = integer(record, "column");
            boolean bindable = !section.isEmpty() && section.indexOf('\0') < 0 && !invalid.contains("section");
            diagnostics.add(new Diagnostic(severity, message, section, row, column, bindable));
        }

        private void result(JsonObject record) {
            boolean success = bool(record, "success");
            boolean dependencies = bool(record, "dependenciesComplete");
            String lastPhase = string(record, "phase");
            require(PHASES.contains(lastPhase) && attempts.isEmpty()
                    && (!success || ("output".equals(lastPhase) && dependencies))
                    && (!dependencies || Set.of("compile", "output").contains(lastPhase)), "Invalid VAS terminal result.");
            terminal = true;
            terminalSuccess = success;
            terminalDependenciesComplete = dependencies;
            phase = lastPhase;
        }

        /** Success and authoritative discovery are unavailable until process exit. */
        // Java exposes Windows native return -1 as a signed nonzero exit value.
        public void finish(int exit) {
            if (failure != null) throw failure;
            try {
                require(!complete && sequence > 0 && terminal, "Truncated or already-finished VAS report.");
                require((exit == 0) == terminalSuccess, "VAS report result disagrees with the compiler exit status.");
                complete = true;
            } catch (ProtocolException error) {
                failure = error;
                complete = false;
                throw error;
            }
        }

        public List<Diagnostic> diagnostics() { return List.copyOf(diagnostics); }
        public Identity lastObserved() { return lastObserved; }
        public boolean lastObservationLoaded() { return lastObservationLoaded; }
        public Set<Identity> observed() { return immutableSet(observed); }
        public Set<Identity> invalidUtf8SourceIdentities() { return immutableSet(invalidSources); }
        public Set<String> observedPaths() { return bindablePaths(observed); }
        public Set<String> invalidUtf8Sources() { return bindablePaths(invalidSources); }
        public String cwd() { return cwd; }
        public String phase() { return phase; }
        public boolean complete() { return complete && failure == null; }
        public boolean success() { return complete() && terminalSuccess; }
        public boolean dependenciesComplete() { return complete() && terminalDependenciesComplete; }
        public String failure() { return failure == null ? null : failure.getMessage(); }
    }

    private static Set<String> bindablePaths(Set<Identity> identities) {
        Set<String> paths = new LinkedHashSet<>();
        for (Identity identity : identities) if (identity.bindable()) paths.add(identity.display());
        return Collections.unmodifiableSet(paths);
    }

    private static <T> Set<T> immutableSet(Set<T> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static void equalIdentity(JsonObject record, String field, String expected, Set<String> invalid) {
        require(expected.equals(string(record, field)) && !invalid.contains(field),
            "VAS build " + field + " identity differs from the selected descriptor.");
    }

    private static Identity identity(JsonObject record, String field, String display, Set<String> invalid) {
        return new Identity(display, invalid.contains(field) ? string(object(required(record, "rawBytes"), "rawBytes"), field) : null);
    }

    private static Set<String> encodingMetadata(JsonObject record) {
        JsonArray fields = array(record, "invalidUtf8Fields");
        JsonObject raw = object(required(record, "rawBytes"), "rawBytes");
        Set<String> invalid = new HashSet<>();
        for (JsonElement element : fields) {
            String field = string(element, "invalidUtf8Fields entry");
            require(invalid.add(field), "Duplicate VAS invalidUtf8Fields entry.");
            String display = string(record, field);
            String hex = string(raw, field);
            require(!hex.isEmpty() && (hex.length() & 1) == 0, "Invalid VAS rawBytes hexadecimal data.");
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                int high = hexDigit(hex.charAt(i * 2));
                int low = hexDigit(hex.charAt(i * 2 + 1));
                require(high >= 0 && low >= 0, "Invalid VAS rawBytes hexadecimal data.");
                bytes[i] = (byte) ((high << 4) | low);
            }
            require(display.equals(invalidByteDisplay(bytes)), "VAS rawBytes does not match its invalid UTF-8 display field.");
        }
        require(raw.keySet().equals(invalid), "VAS rawBytes keys do not match invalidUtf8Fields.");
        return invalid;
    }

    private static int hexDigit(char value) {
        return value >= '0' && value <= '9' ? value - '0' : value >= 'a' && value <= 'f' ? value - 'a' + 10 : -1;
    }

    /** Match the native writer: replace each invalid byte, not each malformed run. */
    private static String invalidByteDisplay(byte[] bytes) {
        boolean invalid = false;
        StringBuilder display = new StringBuilder();
        for (int i = 0; i < bytes.length;) {
            int length = scalarLength(bytes, i);
            if (length == 0) { invalid = true; display.append('\ufffd'); i++; }
            else {
                int scalar = bytes[i] & (length == 1 ? 0x7f : 0x7f >> length);
                for (int j = 1; j < length; j++) scalar = (scalar << 6) | (bytes[i + j] & 0x3f);
                display.appendCodePoint(scalar);
                i += length;
            }
        }
        require(invalid, "VAS rawBytes incorrectly labels valid UTF-8 as invalid.");
        return display.toString();
    }

    private static int scalarLength(byte[] bytes, int offset) {
        int first = bytes[offset] & 0xff;
        if (first < 0x80) return 1;
        int length = first >= 0xc2 && first <= 0xdf ? 2 : first >= 0xe0 && first <= 0xef ? 3 : first >= 0xf0 && first <= 0xf4 ? 4 : 0;
        if (length == 0 || bytes.length - offset < length) return 0;
        for (int i = 1; i < length; i++) if ((bytes[offset + i] & 0xc0) != 0x80) return 0;
        int second = bytes[offset + 1] & 0xff;
        if ((first == 0xe0 && second < 0xa0) || (first == 0xed && second >= 0xa0)
            || (first == 0xf0 && second < 0x90) || (first == 0xf4 && second >= 0x90)) return 0;
        return length;
    }

    private static JsonElement parseLine(byte[] raw, int length, String context) {
        require(length > 0, "Empty " + context + " record.");
        for (int i = 0; i < length; i++) require(raw[i] != '\n' && raw[i] != '\r', "Invalid " + context + " line framing.");
        final String line;
        try {
            line = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw, 0, length)).toString();
        } catch (CharacterCodingException error) {
            throw new ProtocolException("Invalid UTF-8 in " + context + ".", error);
        }
        require(!line.startsWith("\ufeff"), "Unexpected BOM in " + context + ".");
        try (JsonReader reader = new JsonReader(new StringReader(line))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement result = readJson(reader, 0);
            require(reader.peek() == JsonToken.END_DOCUMENT, "Trailing data in " + context + ".");
            return result;
        } catch (IOException | IllegalStateException | NumberFormatException error) {
            throw new ProtocolException("Malformed " + context + ": " + error.getMessage(), error);
        }
    }

    /** Gson's tree parser silently replaces duplicate keys; reject them here. */
    private static JsonElement readJson(JsonReader reader, int depth) throws IOException {
        require(depth <= MAX_JSON_DEPTH, "VAS JSON nesting limit exceeded.");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    require(scalarString(name) && !object.has(name), "Invalid or duplicate VAS JSON object key.");
                    object.add(name, readJson(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readJson(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case STRING -> {
                String value = reader.nextString();
                require(scalarString(value), "Invalid Unicode scalar in VAS JSON string.");
                yield new JsonPrimitive(value);
            }
            case NUMBER -> new JsonPrimitive(new WireNumber(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new ProtocolException("Expected a VAS JSON value.");
        };
    }

    private static final class WireNumber extends Number {
        private final String value;
        private WireNumber(String value) { this.value = value; }
        @Override public int intValue() { return Integer.parseInt(value); }
        @Override public long longValue() { return Long.parseLong(value); }
        @Override public float floatValue() { return Float.parseFloat(value); }
        @Override public double doubleValue() { return Double.parseDouble(value); }
        @Override public String toString() { return value; }
    }

    private static boolean scalarString(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i == value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
            } else if (Character.isLowSurrogate(ch)) return false;
        }
        return true;
    }

    private static JsonElement required(JsonObject object, String field) {
        require(object.has(field), "Missing VAS field: " + field);
        return object.get(field);
    }

    private static JsonObject object(JsonElement value, String label) {
        require(value != null && value.isJsonObject(), "Expected VAS object: " + label);
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String field) {
        JsonElement value = required(object, field);
        require(value.isJsonArray(), "Expected VAS array: " + field);
        return value.getAsJsonArray();
    }

    private static String string(JsonObject object, String field) { return string(required(object, field), field); }

    private static String string(JsonElement value, String field) {
        require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Expected VAS string: " + field);
        return value.getAsString();
    }

    private static String nullableString(JsonObject object, String field) {
        JsonElement value = required(object, field);
        return value.isJsonNull() ? null : string(value, field);
    }

    private static boolean bool(JsonObject object, String field) {
        JsonElement value = required(object, field);
        require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(), "Expected VAS boolean: " + field);
        return value.getAsBoolean();
    }

    private static int integer(JsonObject object, String field) { return integer(required(object, field), field); }

    private static int integer(JsonElement value, String field) {
        require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && INTEGER.matcher(value.getAsString()).matches(),
            "Expected VAS integer: " + field);
        try { return Integer.parseInt(value.getAsString()); }
        catch (NumberFormatException error) { throw new ProtocolException("Out-of-range VAS integer: " + field, error); }
    }

    private static Integer nullableInteger(JsonObject object, String field) {
        JsonElement value = required(object, field);
        return value.isJsonNull() ? null : integer(value, field);
    }

    private static boolean positiveLong(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !INTEGER.matcher(value.getAsString()).matches()) return false;
        try { return Long.parseLong(value.getAsString()) > 0; }
        catch (NumberFormatException ignored) { return false; }
    }

    private static String path(JsonObject object, String field) {
        String value = string(object, field);
        require(absoluteIdentity(value), "Expected normalized absolute VAS path: " + field);
        return value;
    }

    private static boolean absoluteIdentity(String path) {
        if (!nativeAbsolute(path) || path.indexOf('\\') >= 0) return false;
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            if (ch < 0x20 || (ch >= 0x7f && ch <= 0x9f)) return false;
        }
        int begin = path.startsWith("//") ? 2 : path.startsWith("/") ? 1 : 3;
        if (begin == path.length()) return begin != 2;
        String[] parts = path.substring(begin).split("/", -1);
        if (begin == 2 && parts.length < 2) return false;
        for (String part : parts) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return true;
    }

    private static boolean nativeAbsolute(String path) {
        return path.startsWith("/") || path.startsWith("\\\\") || (path.length() >= 3
            && ((path.charAt(0) >= 'A' && path.charAt(0) <= 'Z') || (path.charAt(0) >= 'a' && path.charAt(0) <= 'z'))
            && path.charAt(1) == ':' && (path.charAt(2) == '/' || path.charAt(2) == '\\'));
    }

    private static boolean windowsIdentity(String path) { return path.startsWith("//") || (path.length() >= 3 && path.charAt(1) == ':'); }
    private static boolean within(String root, String path) { return path.startsWith(root.endsWith("/") ? root : root + "/") && !path.equals(root); }
    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        if (slash == 0) return "/";
        if (slash == 2 && path.charAt(1) == ':') return path.substring(0, 3);
        return path.substring(0, slash);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new ProtocolException(message); }
}
