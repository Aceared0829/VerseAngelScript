package com.verseangelscript.rider.index;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;

/** Informational measurements, with symbol-count correctness as the stable assertion. */
public final class VasIndexPerformanceTest {
    @Test
    public void measuresArenaAndLargeDeclarationIndexing() throws Exception {
        Path arena = Path.of("../../examples/arena");
        List<String> files;
        try (var paths = Files.walk(arena)) {
            files = paths.filter(path -> path.toString().endsWith(".vas")).sorted().map(path -> {
                try { return Files.readString(path); } catch (Exception error) { throw new RuntimeException(error); }
            }).toList();
        }
        assertEquals(9, files.size());
        StringBuilder synthetic = new StringBuilder("namespace Perf {\n");
        for (int index = 0; index < 1500; index++) synthetic.append("int32 Function").append(index).append("(int32 Value) { int32 Result = Value + 1; return Result; }\n");
        synthetic.append('}');
        String large = synthetic.toString();
        long[] arenaTimes = new long[11], largeTimes = new long[5];
        int arenaSymbols = 0;
        for (int repeat = 0; repeat < arenaTimes.length; repeat++) {
            long start = System.nanoTime();
            arenaSymbols = files.stream().mapToInt(file -> VasSymbolScanner.scan(file).size()).sum();
            arenaTimes[repeat] = System.nanoTime() - start;
        }
        for (int repeat = 0; repeat < largeTimes.length; repeat++) {
            long start = System.nanoTime();
            assertEquals(4501, VasSymbolScanner.scan(large).size());
            largeTimes[repeat] = System.nanoTime() - start;
        }
        Arrays.sort(arenaTimes); Arrays.sort(largeTimes);
        String result = "{\"arenaFiles\":9,\"arenaSymbols\":" + arenaSymbols + ",\"arenaMedianMs\":" + arenaTimes[5] / 1e6
            + ",\"syntheticFunctions\":1500,\"syntheticSymbols\":4501,\"syntheticMedianMs\":" + largeTimes[2] / 1e6 + "}";
        Path evidence = Path.of("../../out/verification/ide/rider-index-" + System.getProperty("vas.performance.phase", "current") + ".json");
        Files.createDirectories(evidence.getParent()); Files.writeString(evidence, result);
        System.out.println("VAS index performance " + result);
    }
}
