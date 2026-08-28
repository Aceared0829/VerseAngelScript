package com.verseangelscript.rider.index;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test
    public void indexesProjectSymbolsButKeepsParametersAndLocalsScoped() {
        String source = """
            int globalCount;
            class Player {
                int health;
                void ApplyDamage(int amount) {
                    int remaining = health - amount;
                }
            }
            """;

        List<VasSymbol> symbols = VasSymbolScanner.scan(source);
        assertTrue(find(symbols, "globalCount").isProjectVisible());
        assertTrue(find(symbols, "health").isProjectVisible());
        assertTrue(find(symbols, "ApplyDamage").isProjectVisible());
        assertFalse(find(symbols, "amount").isProjectVisible());
        assertFalse(find(symbols, "remaining").isProjectVisible());
    }

    @Test
    public void distinguishesFunctionDeclarationsFromImplementations() {
        String source = """
            interface Runnable { void Run(); }
            void Run() {}
            """;

        List<VasSymbol> runSymbols = VasSymbolScanner.scan(source).stream()
            .filter(symbol -> symbol.name().equals("Run"))
            .toList();
        assertEquals(2, runSymbols.size());
        assertEquals(1, runSymbols.stream().filter(VasSymbol::definition).count());
    }

    @Test
    public void recordsContainersSignaturesInheritanceAndDeclaredTypes() {
        String source = """
            namespace Game {
                interface Damageable {}
                class Player : Damageable {
                    int health;
                    void Apply(int amount, float scale) {}
                }
            }
            void Player::Reset() {}
            """;

        List<VasSymbol> symbols = VasSymbolScanner.scan(source);
        VasSymbol health = find(symbols, "health");
        VasSymbol apply = find(symbols, "Apply");
        VasSymbol player = find(symbols, "Player");
        VasSymbol reset = find(symbols, "Reset");

        assertEquals("Game::Player", health.container());
        assertEquals("int", health.declaredType());
        assertEquals("Game::Player::Apply/2", apply.signature());
        assertEquals(List.of("Damageable"), player.baseTypes());
        assertEquals("Player", reset.container());
        assertEquals("void", reset.declaredType());
    }

    private static VasSymbol find(List<VasSymbol> symbols, String name) {
        return symbols.stream()
            .filter(symbol -> symbol.name().equals(name))
            .findFirst()
            .orElseThrow();
    }

    private static void assertSymbol(List<VasSymbol> symbols, String name, VasSymbolKind kind) {
        long matches = symbols.stream()
            .filter(symbol -> symbol.name().equals(name) && symbol.kind() == kind)
            .count();
        assertEquals("Expected one " + kind + " named " + name, 1, matches);
    }
}
