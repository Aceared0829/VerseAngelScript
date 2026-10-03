package com.verseangelscript.rider.projectbuild;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A native java executable runs this fixture; it is not a compiler path shell wrapper. */
public final class VasProjectProcessFixture {
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "classpath" -> out(System.getProperty("java.class.path"));
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

    private VasProjectProcessFixture() {
    }

    private static List<String> fixtureArgs(String mode) throws java.net.URISyntaxException {
        // Descendants inherit only this helper's own directory, never the test runner's
        // potentially enormous Gradle/Rider classpath. The helper needs only the JDK.
        Path classes = Path.of(VasProjectProcessFixture.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).toAbsolutePath();
        return List.of("-cp", classes.toString(), VasProjectProcessFixture.class.getName(), mode);
    }

    private static Path javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
            .startsWith("windows");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
            .toAbsolutePath();
    }
}
