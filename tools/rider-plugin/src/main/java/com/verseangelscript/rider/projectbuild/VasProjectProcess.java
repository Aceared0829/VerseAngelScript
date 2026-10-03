package com.verseangelscript.rider.projectbuild;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Bounded, shell-free transport for the explicitly configured native compiler. */
public final class VasProjectProcess {
    private static final int MAX_LINE_BYTES = 1024 * 1024;
    private static final int STDERR_RETAIN_BYTES = 16 * 1024;
    private static final long STDERR_TOTAL_BYTES = 4L * 1024 * 1024;
    private static final int MAX_DESCENDANTS = 256;
    private static final long POLL_MILLIS = 50;
    private static final long CLEANUP_MILLIS = 2000;

    private VasProjectProcess() {
    }

    public record Result(byte[] stdout, String stderr, int exitCode) {
    }

    /**
     * Checks the configured path, without PATH lookup, shell wrappers or scripts.
     * This is a format check, not a statement that a binary or project is trusted.
     */
    public static Path validateNativeCompiler(String configuredPath) throws IOException {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IOException("Configure an absolute path to the native VAS compiler");
        }
        final Path path;
        try {
            path = Path.of(configuredPath);
        } catch (InvalidPathException e) {
            throw new IOException("Invalid native compiler path", e);
        }
        if (!path.isAbsolute()) {
            throw new IOException("The native compiler path must be absolute");
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path) || !Files.isExecutable(path)) {
            throw new IOException("The native compiler must be an existing, readable executable file");
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")
            && !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")) {
            throw new IOException("The native compiler must be an .exe file on Windows");
        }
        byte[] header;
        try (InputStream input = Files.newInputStream(path)) {
            header = input.readNBytes(4);
        }
        if (!hasNativeHeader(header)) {
            throw new IOException("The compiler must be a native ELF, PE or Mach-O executable, not a script");
        }
        return path.toRealPath();
    }

    private static boolean hasNativeHeader(byte[] header) {
        if (header.length != 4) {
            return false;
        }
        int magic = (header[0] & 0xff) << 24 | (header[1] & 0xff) << 16
            | (header[2] & 0xff) << 8 | (header[3] & 0xff);
        return magic == 0x7f454c46 || (magic >>> 16) == 0x4d5a
            || magic == 0xfeedface || magic == 0xcefaedfe
            || magic == 0xfeedfacf || magic == 0xcffaedfe
            || magic == 0xcafebabe || magic == 0xbebafeca
            || magic == 0xcafebabf || magic == 0xbfbafeca;
    }

    /**
     * Runs one process with independently drained stdout and stderr. In streaming mode,
     * the sink is called on the stdout reader with one complete, LF-free byte array per
     * line; it must return promptly and may throw to reject the stream. There is no event
     * queue. Buffered mode returns the raw bytes, for a single bounded JSON document.
     * The guard runs on the calling thread just before launch and throughout execution.
     */
    public static Result run(Path executable, List<String> args, Path cwd,
                             long timeoutMillis, long stdoutLimit, Runnable guard,
                             Consumer<byte[]> lineSink) throws IOException, InterruptedException {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(guard, "guard");
        if (!executable.isAbsolute()) {
            throw new IOException("The compiler executable path must be absolute");
        }
        if (timeoutMillis <= 0 || stdoutLimit < 0
            || (lineSink == null && stdoutLimit > Integer.MAX_VALUE - 8L)) {
            throw new IllegalArgumentException("Invalid process timeout or stdout limit");
        }
        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable.toString());
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        boolean allowAmbiguousCommands = !"false".equalsIgnoreCase(
            System.getProperty("jdk.lang.Process.allowAmbiguousCommands", "true"));
        for (String argument : args) {
            command.add(windows ? encodeWindowsArgument(argument, allowAmbiguousCommands) : argument);
        }
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile());
        builder.redirectErrorStream(false);
        guard.run();
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Compiler launch interrupted");
        }
        Process process = builder.start();
        long started = System.nanoTime();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Capture capture = new Capture(process, stdoutLimit, lineSink);
        Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
        Thread stdout = reader("vas-project-stdout", process.getInputStream(),
            capture::readStdout, capture);
        Thread stderr = reader("vas-project-stderr", process.getErrorStream(),
            capture::readStderr, capture);
        Throwable failure = null;
        try {
            // The compiler protocol never accepts stdin. Closing immediately also lets
            // accidentally interactive binaries fail instead of hanging for input.
            process.getOutputStream().close();
            stdout.start();
            stderr.start();
            while (true) {
                guard.run();
                rememberDescendants(process, descendants);
                rethrow(capture.failure.get());
                if (!process.isAlive() && capture.done.getCount() == 0) {
                    rethrow(capture.failure.get());
                    // This check gates publication of the last bytes as well as launch.
                    guard.run();
                    return new Result(capture.stdout.toByteArray(),
                        capture.stderr.toString(StandardCharsets.UTF_8), process.exitValue());
                }
                if (System.nanoTime() - started >= timeoutNanos) {
                    throw new IOException("VAS compiler timed out after " + timeoutMillis + " ms");
                }
                if (process.isAlive()) {
                    process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS);
                } else {
                    capture.done.await(POLL_MILLIS, TimeUnit.MILLISECONDS);
                }
            }
        } catch (IOException | InterruptedException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            capture.stopping.set(true);
            IOException cleanupFailure = cleanup(process, descendants, stdout, stderr);
            if (cleanupFailure != null) {
                if (failure != null) {
                    failure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    /**
     * OpenJDK/JBR 25 ProcessImpl's default Windows LEGACY mode does not escape
     * interior quotes. Supply a fully CRT-quoted token, which that mode preserves,
     * so the native compiler receives the original argument rather than extra argv
     * entries. This is native command-line encoding, never shell escaping.
     *
     * Strict mode already performs CRT escaping and must receive raw arguments.
     * Its surrounding-quote convention cannot represent literal surrounding quotes;
     * reject that case rather than silently changing the argument. Do not mutate the
     * process-wide jdk.lang.Process.allowAmbiguousCommands property in the IDE.
     *
     * See OpenJDK jdk25u, src/java.base/windows/classes/java/lang/ProcessImpl.java,
     * createCommandLine/needsEscaping; and Microsoft's C command-line parsing rules.
     */
    static String encodeWindowsArgument(String argument, boolean allowAmbiguousCommands) throws IOException {
        Objects.requireNonNull(argument, "argument");
        if (!allowAmbiguousCommands) {
            if (argument.length() >= 2 && argument.startsWith("\"") && argument.endsWith("\"")) {
                throw new IOException("Cannot preserve literal surrounding quotes in a native compiler argument "
                    + "under Windows strict process mode");
            }
            return argument;
        }
        StringBuilder encoded = new StringBuilder(argument.length() + 2);
        encoded.append('"');
        int backslashes = 0;
        for (int index = 0; index < argument.length(); index++) {
            char value = argument.charAt(index);
            if (value == '\\') {
                backslashes++;
                continue;
            }
            int escapedSlashes = value == '"' ? backslashes * 2 + 1 : backslashes;
            for (int slash = 0; slash < escapedSlashes; slash++) {
                encoded.append('\\');
            }
            encoded.append(value);
            backslashes = 0;
        }
        // A trailing backslash must not escape the closing delimiter.
        for (int slash = 0; slash < backslashes * 2; slash++) {
            encoded.append('\\');
        }
        return encoded.append('"').toString();
    }

    @FunctionalInterface
    private interface Drain {
        void read(InputStream input) throws IOException;
    }

    private static Thread reader(String name, InputStream input, Drain drain, Capture capture) {
        Thread reader = new Thread(() -> {
            try (input) {
                drain.read(input);
            } catch (Throwable failure) {
                capture.failure.compareAndSet(null, failure);
            } finally {
                capture.done.countDown();
            }
        }, name);
        // A faulty third-party callback must not prevent the IDE JVM from exiting.
        reader.setDaemon(true);
        return reader;
    }

    private static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException io) {
            throw io;
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IOException("Could not read compiler output", failure);
        }
    }

    private static final class Capture {
        private final Process process;
        private final long stdoutLimit;
        private final Consumer<byte[]> lineSink;
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicBoolean stopping = new AtomicBoolean();
        private final CountDownLatch done = new CountDownLatch(2);

        private Capture(Process process, long stdoutLimit, Consumer<byte[]> lineSink) {
            this.process = process;
            this.stdoutLimit = stdoutLimit;
            this.lineSink = lineSink;
        }

        private void readStdout(InputStream input) throws IOException {
            byte[] buffer = new byte[8192];
            byte[] line = lineSink == null ? null : new byte[MAX_LINE_BYTES];
            int lineLength = 0;
            long total = 0;
            int count;
            while ((count = readAvailable(input, buffer)) != -1) {
                if (count > stdoutLimit - total) {
                    throw new IOException("VAS compiler stdout exceeded its " + stdoutLimit + " byte limit");
                }
                total += count;
                if (lineSink == null) {
                    stdout.write(buffer, 0, count);
                    continue;
                }
                for (int index = 0; index < count; index++) {
                    byte value = buffer[index];
                    if (value == '\n') {
                        lineSink.accept(Arrays.copyOf(line, lineLength));
                        lineLength = 0;
                    } else {
                        if (lineLength == MAX_LINE_BYTES) {
                            throw new IOException("VAS compiler stdout line exceeded 1 MiB");
                        }
                        line[lineLength++] = value;
                    }
                }
            }
            if (lineLength != 0 && !stopping.get()) {
                throw new IOException("VAS compiler stdout ended with a truncated line (missing LF)");
            }
        }

        private void readStderr(InputStream input) throws IOException {
            byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = readAvailable(input, buffer)) != -1) {
                if (count > STDERR_TOTAL_BYTES - total) {
                    throw new IOException("VAS compiler stderr exceeded 4 MiB");
                }
                total += count;
                int retained = Math.min(count, STDERR_RETAIN_BYTES - stderr.size());
                stderr.write(buffer, 0, retained);
            }
        }

        private int readAvailable(InputStream input, byte[] buffer) throws IOException {
            while (!stopping.get()) {
                int available = input.available();
                if (available > 0) {
                    // Reading only available bytes avoids holding the JDK pipe lock
                    // indefinitely if an escaped descendant retains the write end.
                    return input.read(buffer, 0, Math.min(buffer.length, available));
                }
                if (!process.isAlive()) {
                    // Recheck after observing exit: the last write may have happened
                    // between available() and the process completion observation.
                    available = input.available();
                    return available > 0
                        ? input.read(buffer, 0, Math.min(buffer.length, available)) : -1;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (stopping.get()) {
                        return -1;
                    }
                    throw new IOException("Compiler output reader interrupted", e);
                }
            }
            return -1;
        }
    }

    private static void rememberDescendants(Process process, Map<Long, ProcessHandle> known) throws IOException {
        known.values().removeIf(child -> !child.isAlive());
        boolean exceeded = false;
        try (Stream<ProcessHandle> children = process.descendants()) {
            Iterator<ProcessHandle> iterator = children.iterator();
            while (iterator.hasNext()) {
                ProcessHandle child = iterator.next();
                if (!known.containsKey(child.pid())) {
                    if (known.size() == MAX_DESCENDANTS) {
                        child.destroyForcibly();
                        exceeded = true;
                        continue;
                    }
                    known.put(child.pid(), child);
                }
            }
        }
        if (exceeded) {
            throw new IOException("VAS compiler exceeded the descendant process limit");
        }
    }

    /** Bounded even if a descendant retains a pipe or cancellation interrupts cleanup. */
    private static IOException cleanup(Process process, Map<Long, ProcessHandle> descendants,
                                       Thread stdout, Thread stderr) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLEANUP_MILLIS);
        IOException failure = null;
        try {
            try {
                rememberDescendants(process, descendants);
            } catch (IOException e) {
                failure = e;
            }
            // Stop children first, leaving the compiler briefly alive to reap them.
            for (ProcessHandle child : descendants.values()) {
                child.destroy();
            }
            long graceDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150));
            while (descendants.values().stream().anyMatch(ProcessHandle::isAlive)
                && System.nanoTime() < graceDeadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            try {
                // Include any children created while graceful termination was pending.
                rememberDescendants(process, descendants);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            for (ProcessHandle child : descendants.values()) {
                if (child.isAlive()) {
                    child.destroyForcibly();
                }
            }
            process.destroyForcibly();
            stdout.interrupt();
            stderr.interrupt();
            while ((process.isAlive() || stdout.isAlive() || stderr.isAlive()
                || descendants.values().stream().anyMatch(ProcessHandle::isAlive))
                && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            closeQuietly(process.getOutputStream());
            // The readers own their streams and close them in finally. In particular,
            // do not wait on their pipe locks after the bounded cleanup deadline.
            if (!stdout.isAlive()) {
                closeQuietly(process.getInputStream());
            }
            if (!stderr.isAlive()) {
                closeQuietly(process.getErrorStream());
            }
            if (process.isAlive() || stdout.isAlive() || stderr.isAlive()
                || descendants.values().stream().anyMatch(ProcessHandle::isAlive)) {
                IOException incomplete = new IOException("VAS compiler process or output reader did not terminate during cleanup");
                if (failure == null) {
                    failure = incomplete;
                } else {
                    failure.addSuppressed(incomplete);
                }
            }
            return failure;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeQuietly(java.io.Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The process has already been terminated; closing is best effort.
        }
    }
}
