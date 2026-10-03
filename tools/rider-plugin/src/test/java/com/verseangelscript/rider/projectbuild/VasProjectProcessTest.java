package com.verseangelscript.rider.projectbuild;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

/** Portable integration checks using the native JVM and a Java child, never a shell. */
public final class VasProjectProcessTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private static final long TEST_TIMEOUT_MILLIS = 8000;
    private static final long OUTPUT_LIMIT = 8L * 1024 * 1024;

    @Test
    public void validatesTheActualNativeJvmExecutable() throws Exception {
        assertEquals(javaExecutable().toRealPath(),
            VasProjectProcess.validateNativeCompiler(javaExecutable().toString()));
    }

    @Test
    public void rejectsMissingRelativeDirectoryAndScriptCompilerSettings() throws Exception {
        for (String invalid : Arrays.asList(null, "", "  ", "vasbuild", "./vasbuild",
            temporary.getRoot().toPath().resolve("missing.exe").toString(),
            temporary.getRoot().toString())) {
            expectIOException(() -> VasProjectProcess.validateNativeCompiler(invalid));
        }
        for (String script : List.of("#!/bin/sh\necho no\n", "@echo off\r\n", "powershell.exe something")) {
            Path path = temporary.newFile("script" + Math.abs(script.hashCode()) + ".exe").toPath();
            Files.writeString(path, script);
            makeExecutable(path);
            assertTrue(expectIOException(() -> VasProjectProcess.validateNativeCompiler(path.toString()))
                .getMessage().contains("native"));
        }
    }

    @Test
    public void recognizesNativeExecutableHeaders() throws Exception {
        for (byte[] header : List.of(
            new byte[]{0x7f, 'E', 'L', 'F'}, new byte[]{'M', 'Z', 0, 0},
            new byte[]{(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xce},
            new byte[]{(byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe},
            new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe})) {
            Path path = temporary.newFile("native" + Arrays.hashCode(header) + ".exe").toPath();
            Files.write(path, header);
            makeExecutable(path);
            assertEquals(path.toRealPath(), VasProjectProcess.validateNativeCompiler(path.toString()));
        }
    }

    @Test
    public void rejectsNonExecutableNativeFile() throws Exception {
        assumeFalse(isWindows());
        Path path = temporary.newFile("not-executable").toPath();
        Files.write(path, new byte[]{0x7f, 'E', 'L', 'F'});
        Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ));
        expectIOException(() -> VasProjectProcess.validateNativeCompiler(path.toString()));
    }

    @Test
    public void passesArgumentsWithoutAShellAndKeepsStderrSeparate() throws Exception {
        String argument = "space ; $(echo injected) & | > < \" quote 漢字";
        VasProjectProcess.Result result = run("arguments", OUTPUT_LIMIT, null, () -> {}, argument);
        assertEquals(argument + "\n", utf8(result.stdout()));
        assertEquals("diagnostic 漢字😀\n", result.stderr());
        assertEquals(7, result.exitCode());
    }

    @Test
    public void usesRequestedWorkingDirectoryAndClosesStdin() throws Exception {
        VasProjectProcess.Result result = run("environment", OUTPUT_LIMIT, null, () -> {});
        assertEquals(temporary.getRoot().toPath().toRealPath() + "\n-1\n", utf8(result.stdout()));
    }

    @Test
    public void collectsABoundedSingleDocumentWithoutRequiringLf() throws Exception {
        assertEquals("{\"plan\":true}", utf8(run("document", OUTPUT_LIMIT, null, () -> {}).stdout()));
    }

    @Test
    public void streamsOnlyCompleteLinesAndDoesNotRetainStdout() throws Exception {
        List<String> lines = new ArrayList<>();
        AtomicBoolean observedDuringExecution = new AtomicBoolean();
        AtomicBoolean firstDelivered = new AtomicBoolean();
        VasProjectProcess.Result result = run("stream", OUTPUT_LIMIT, bytes -> {
            lines.add(utf8(bytes));
            firstDelivered.set(true);
        }, () -> {
            if (firstDelivered.get() && lines.size() == 1) {
                observedDuringExecution.set(true);
            }
        });
        assertEquals(List.of("first 漢字😀", "second", ""), lines);
        assertTrue("The first line was delivered while the compiler was still running", observedDuringExecution.get());
        assertEquals(0, result.stdout().length);
        assertEquals(0, result.exitCode());
    }

    @Test
    public void rejectsTruncatedStreamingOutput() throws Exception {
        List<String> lines = new ArrayList<>();
        IOException failure = expectIOException(() -> run("truncated", OUTPUT_LIMIT,
            bytes -> lines.add(utf8(bytes)), () -> {}));
        assertTrue(failure.getMessage(), failure.getMessage().contains("missing LF"));
        assertEquals(List.of("complete"), lines);
    }

    @Test
    public void rejectsLinesAboveOneMebibyteAndTerminatesTheProcess() throws Exception {
        AtomicLong pid = new AtomicLong();
        IOException failure = expectIOException(() -> run("long-line", OUTPUT_LIMIT,
            bytes -> pid.set(Long.parseLong(utf8(bytes))), () -> {}));
        assertTrue(failure.getMessage(), failure.getMessage().contains("1 MiB"));
        assertTerminated(pid.get());
    }

    @Test
    public void acceptsExactlyOneMebibyteLine() throws Exception {
        AtomicInteger length = new AtomicInteger();
        run("maximum-line", OUTPUT_LIMIT, bytes -> length.set(bytes.length), () -> {});
        assertEquals(1024 * 1024, length.get());
    }

    @Test
    public void boundsBufferedAndStreamingTotalStdout() throws Exception {
        for (Consumer<byte[]> sink : Arrays.<Consumer<byte[]>>asList(null, bytes -> {})) {
            IOException failure = expectIOException(() -> run("stdout-flood", 4096, sink, () -> {}));
            assertTrue(failure.getMessage(), failure.getMessage().contains("stdout exceeded"));
        }
    }

    @Test
    public void drainsStderrConcurrentlyAndRetainsOnlySixteenKibibytes() throws Exception {
        VasProjectProcess.Result result = run("stderr-large", OUTPUT_LIMIT, null, () -> {});
        assertEquals("done\n", utf8(result.stdout()));
        assertEquals(16 * 1024, result.stderr().getBytes(StandardCharsets.UTF_8).length);
        assertTrue(result.stderr().chars().allMatch(value -> value == 'e'));
    }

    @Test
    public void rejectsStderrAboveFourMebibytes() throws Exception {
        IOException failure = expectIOException(() -> run("stderr-flood", OUTPUT_LIMIT, null, () -> {}));
        assertTrue(failure.getMessage(), failure.getMessage().contains("stderr exceeded 4 MiB"));
    }

    @Test
    public void guardCanPreventAnyProcessLaunch() throws Exception {
        Path marker = temporary.getRoot().toPath().resolve("launched");
        RuntimeException rejected = new IllegalStateException("untrusted");
        try {
            run("marker", OUTPUT_LIMIT, null, () -> { throw rejected; }, marker.toString());
            fail("Expected guard rejection");
        } catch (RuntimeException actual) {
            assertSame(rejected, actual);
        }
        assertFalse(Files.exists(marker));
    }

    @Test
    public void timeoutTerminatesTheCompilerAndItsChild() throws Exception {
        List<Long> pids = new ArrayList<>();
        long start = System.nanoTime();
        IOException failure = expectIOException(() -> runWithTimeout("tree", 1400, OUTPUT_LIMIT,
            bytes -> pids.add(Long.parseLong(utf8(bytes))), () -> {}));
        assertTrue(failure.getMessage(), failure.getMessage().contains("timed out"));
        assertEquals("Both child processes started", 2, pids.size());
        for (long pid : pids) {
            assertTerminated(pid);
        }
        assertTrue("Cleanup must be bounded", elapsedMillis(start) < 5000);
    }

    @Test
    public void cancellationTerminatesTheCompilerAndItsChild() throws Exception {
        List<Long> pids = new ArrayList<>();
        AtomicInteger lines = new AtomicInteger();
        RuntimeException cancelled = new IllegalStateException("cancelled stale disposed or untrusted");
        long start = System.nanoTime();
        try {
            run("tree", OUTPUT_LIMIT, bytes -> {
                pids.add(Long.parseLong(utf8(bytes)));
                lines.incrementAndGet();
            }, () -> {
                if (lines.get() == 2) {
                    throw cancelled;
                }
            });
            fail("Expected cancellation");
        } catch (RuntimeException actual) {
            assertSame(cancelled, actual);
        }
        assertEquals(2, pids.size());
        for (long pid : pids) {
            assertTerminated(pid);
        }
        assertTrue("Cancellation must be prompt", elapsedMillis(start) < 5000);
    }

    @Test
    public void parserExceptionTerminatesTheCompilerAndItsChild() throws Exception {
        List<Long> pids = new ArrayList<>();
        RuntimeException rejected = new IllegalArgumentException("invalid compiler event");
        try {
            run("tree", OUTPUT_LIMIT, bytes -> {
                pids.add(Long.parseLong(utf8(bytes)));
                if (pids.size() == 2) {
                    throw rejected;
                }
            }, () -> {});
            fail("Expected parser rejection");
        } catch (RuntimeException actual) {
            assertSame(rejected, actual);
        }
        assertEquals(2, pids.size());
        for (long pid : pids) {
            assertTerminated(pid);
        }
    }

    @Test
    public void cancellationTerminatesGrandchildrenAndReapsForcedChildren() throws Exception {
        for (String mode : List.of("deep-tree", "stubborn-tree")) {
            List<Long> pids = new ArrayList<>();
            AtomicInteger lines = new AtomicInteger();
            int expected = mode.equals("deep-tree") ? 3 : 2;
            RuntimeException cancelled = new IllegalStateException("cancelled");
            try {
                run(mode, OUTPUT_LIMIT, bytes -> {
                    pids.add(Long.parseLong(utf8(bytes)));
                    lines.incrementAndGet();
                }, () -> {
                    if (lines.get() == expected) {
                        throw cancelled;
                    }
                });
                fail("Expected cancellation");
            } catch (RuntimeException actual) {
                assertSame(cancelled, actual);
            }
            assertEquals(mode, expected, pids.size());
            for (long pid : pids) {
                assertTerminated(pid);
            }
        }
    }

    @Test
    public void interruptionTerminatesTheCompiler() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicLong pid = new AtomicLong();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                run("sleep", OUTPUT_LIMIT, bytes -> {
                    pid.set(Long.parseLong(utf8(bytes)));
                    started.countDown();
                }, () -> {});
                failure.set(new AssertionError("Expected interruption"));
            } catch (Throwable result) {
                failure.set(result);
            }
        }, "vas-test-runner");
        runner.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            runner.interrupt();
            runner.join(5000);
            assertFalse("Runner stopped after interruption", runner.isAlive());
            assertTrue(String.valueOf(failure.get()), failure.get() instanceof InterruptedException);
            assertTerminated(pid.get());
        } finally {
            runner.interrupt();
            if (pid.get() != 0) {
                ProcessHandle.of(pid.get()).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    public void repeatedFailuresDoNotLeaveOutputReaderThreads() throws Exception {
        long before = readerThreadCount();
        for (int attempt = 0; attempt < 5; attempt++) {
            expectIOException(() -> run("truncated", OUTPUT_LIMIT, bytes -> {}, () -> {}));
        }
        assertEquals(before, readerThreadCount());
    }

    private VasProjectProcess.Result run(String mode, long limit, Consumer<byte[]> sink,
                                         Runnable guard, String... extra) throws Exception {
        return runWithTimeout(mode, TEST_TIMEOUT_MILLIS, limit, sink, guard, extra);
    }

    private VasProjectProcess.Result runWithTimeout(String mode, long timeoutMillis, long limit,
                                                    Consumer<byte[]> sink, Runnable guard,
                                                    String... extra) throws Exception {
        List<String> args = fixtureArgs(mode);
        args.addAll(List.of(extra));
        return VasProjectProcess.run(javaExecutable(), args, temporary.getRoot().toPath(),
            timeoutMillis, limit, guard, sink);
    }

    private static List<String> fixtureArgs(String mode) {
        // The child uses a different cwd, so every classpath entry must be absolute.
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(
            java.util.regex.Pattern.quote(System.getProperty("path.separator"))))
            .map(entry -> Path.of(entry).toAbsolutePath().toString())
            .collect(java.util.stream.Collectors.joining(System.getProperty("path.separator")));
        return new ArrayList<>(List.of("-cp", classpath, Fixture.class.getName(), mode));
    }

    private static Path javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
            .toAbsolutePath();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    private static void makeExecutable(Path path) throws Exception {
        if (!isWindows()) {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        }
    }

    private static void assertTerminated(long pid) {
        assertTrue("Fixture provided its PID", pid > 0);
        assertFalse("Process still alive: " + pid, ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static long readerThreadCount() {
        return Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.isAlive() && thread.getName().startsWith("vas-project-"))
            .count();
    }

    private static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }

    private static IOException expectIOException(CheckedAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected IOException");
        } catch (IOException expected) {
            return expected;
        }
    }

    /** A native java executable runs this fixture; it is not a compiler path shell wrapper. */
    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "arguments" -> {
                    out(args[1] + "\n");
                    System.err.write("diagnostic 漢字😀\n".getBytes(StandardCharsets.UTF_8));
                    System.exit(7);
                }
                case "environment" -> out(Path.of(".").toRealPath() + "\n" + System.in.read() + "\n");
                case "document" -> out("{\"plan\":true}");
                case "stream" -> {
                    byte[] unicode = "first 漢字😀\n".getBytes(StandardCharsets.UTF_8);
                    // Split inside both the line and a multibyte UTF-8 sequence.
                    System.out.write(unicode, 0, 7);
                    System.out.flush();
                    Thread.sleep(75);
                    System.out.write(unicode, 7, unicode.length - 7);
                    System.out.flush();
                    Thread.sleep(250);
                    out("second\n\n");
                }
                case "truncated" -> out("complete\ntruncated");
                case "long-line" -> {
                    out(ProcessHandle.current().pid() + "\n");
                    writeBytes(System.out, 1024 * 1024 + 1, 'x');
                    Thread.sleep(30000);
                }
                case "maximum-line" -> {
                    writeBytes(System.out, 1024 * 1024, 'x');
                    out("\n");
                }
                case "stdout-flood" -> {
                    writeBytes(System.out, 1024 * 1024, '\n');
                    Thread.sleep(30000);
                }
                case "stderr-large" -> {
                    writeBytes(System.err, 1024 * 1024, 'e');
                    out("done\n");
                }
                case "stderr-flood" -> {
                    writeBytes(System.err, 4 * 1024 * 1024 + 1, 'e');
                    Thread.sleep(30000);
                }
                case "marker" -> Files.writeString(Path.of(args[1]), "launched");
                case "tree", "deep-tree", "stubborn-tree" -> {
                    out(ProcessHandle.current().pid() + "\n");
                    List<String> command = new ArrayList<>();
                    command.add(javaExecutable().toString());
                    command.addAll(fixtureArgs(switch (args[0]) {
                        case "deep-tree" -> "tree";
                        case "stubborn-tree" -> "stubborn";
                        default -> "sleep";
                    }));
                    Process child = new ProcessBuilder(command).inheritIO().start();
                    // Keep the parent available to reap children during cancellation.
                    child.waitFor();
                }
                case "sleep", "stubborn" -> {
                    if (args[0].equals("stubborn")) {
                        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                            try {
                                Thread.sleep(30000);
                            } catch (InterruptedException ignored) {
                            }
                        }));
                    }
                    out(ProcessHandle.current().pid() + "\n");
                    Thread.sleep(30000);
                }
                default -> throw new IllegalArgumentException(args[0]);
            }
        }

        private static void out(String value) throws IOException {
            System.out.write(value.getBytes(StandardCharsets.UTF_8));
            System.out.flush();
        }

        private static void writeBytes(java.io.OutputStream output, int count, char value) throws IOException {
            byte[] block = new byte[8192];
            Arrays.fill(block, (byte) value);
            while (count > 0) {
                int size = Math.min(count, block.length);
                output.write(block, 0, size);
                count -= size;
            }
            output.flush();
        }
    }
}
