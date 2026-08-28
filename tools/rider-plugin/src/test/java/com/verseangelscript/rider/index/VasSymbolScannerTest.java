package com.verseangelscript.rider.index;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class VasSymbolScannerTest {
    @Test
    public void discoversTypesFunctionsFieldsAndLocals() {
        String source = """
            namespace Gameplay {
                class Player {
                    int health;
                    void ApplyDamage(int amount) {
                        int remaining = health - amount;
                    }
                }
            }

            float Calculate(float value) { return value; }
            """;

        List<VasSymbol> symbols = VasSymbolScanner.scan(source);
        assertSymbol(symbols, "Gameplay", VasSymbolKind.NAMESPACE);
        assertSymbol(symbols, "Player", VasSymbolKind.CLASS);
        assertSymbol(symbols, "health", VasSymbolKind.VARIABLE);
        assertSymbol(symbols, "ApplyDamage", VasSymbolKind.FUNCTION);
        assertSymbol(symbols, "amount", VasSymbolKind.VARIABLE);
        assertSymbol(symbols, "remaining", VasSymbolKind.VARIABLE);
        assertSymbol(symbols, "Calculate", VasSymbolKind.FUNCTION);
    }

    @Test
    public void doesNotTreatCallsAsDeclarations() {
        List<VasSymbol> symbols = VasSymbolScanner.scan("void main() { Calculate(42); object.Run(); }");
        assertSymbol(symbols, "main", VasSymbolKind.FUNCTION);
        assertTrue(symbols.stream().noneMatch(symbol -> symbol.name().equals("Calculate")));
        assertTrue(symbols.stream().noneMatch(symbol -> symbol.name().equals("Run")));
    }

    private static void assertSymbol(List<VasSymbol> symbols, String name, VasSymbolKind kind) {
        long matches = symbols.stream()
            .filter(symbol -> symbol.name().equals(name) && symbol.kind() == kind)
            .count();
        assertEquals("Expected one " + kind + " named " + name, 1, matches);
    }
}
