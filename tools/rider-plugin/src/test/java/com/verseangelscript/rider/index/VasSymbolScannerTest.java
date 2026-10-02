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
    public void lifecycleScopesExcludeLocalsAndParametersFromTheProjectIndex() {
        List<VasSymbol> symbols = VasSymbolScanner.scan("class C { "
            + "C(int input) { int constructed; } ~C() { int destroyed; } int field; }");
        for (String name : List.of("input", "constructed", "destroyed")) {
            assertFalse(name, find(symbols, name).isProjectVisible());
            assertTrue(name, find(symbols, name).scopeEnd() > find(symbols, name).scopeStart());
        }
        assertTrue(find(symbols, "field").isProjectVisible());
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

    @Test
    public void readsCallArityAndMemberQualifier() {
        String source = "void main() { player.Apply(10, 0.5f); Reset(); }";
        int applyOffset = source.indexOf("Apply");
        int resetOffset = source.indexOf("Reset");

        assertEquals(new VasUsageContext(2, "player", VasUsageContext.Access.MEMBER, ""),
            VasSymbolScanner.usageContext(source, applyOffset));
        assertEquals(new VasUsageContext(0, "", VasUsageContext.Access.UNQUALIFIED, ""),
            VasSymbolScanner.usageContext(source, resetOffset));
    }

    @Test
    public void recordsDefaultRangesAndNestedInitializerArguments() {
        String source = "void send(int a, int b = make(1, 2), int c = 3) {}";
        VasSymbol send = find(VasSymbolScanner.scan(source), "send");
        assertEquals(3, send.parameterCount());
        assertEquals(1, send.requiredParameterCount());
        String call = "send(1, {2, 3}, values[at(1, 2)])";
        assertEquals(3, VasSymbolScanner.usageContext(call, 0).argumentCount());
    }

    @Test
    public void incompleteAndMalformedArgumentsAreUnknownNotZero() {
        for (String call : List.of("send(", "send(1,)", "send(,1)", "send([1,2)")) {
            assertEquals(call, VasUsageContext.UNKNOWN_ARGUMENTS,
                VasSymbolScanner.usageContext(call, 0).argumentCount());
        }
    }

    @Test
    public void distinguishesQualifiedCallsAndRetainsQualifiedTypes() {
        String source = "namespace A { class B {} } void A::B::reset() {} void run() { A::B item; A::B::reset(); }";
        List<VasSymbol> symbols = VasSymbolScanner.scan(source);
        assertEquals(1, symbols.stream().filter(symbol -> symbol.name().equals("reset")).count());
        assertEquals("A::B", find(symbols, "reset").container());
        assertEquals("A::B", find(symbols, "item").declaredType());
        VasUsageContext context = VasSymbolScanner.usageContext(source, source.lastIndexOf("reset"));
        assertEquals("A::B", context.qualifier());
        assertEquals(VasUsageContext.Access.QUALIFIED, context.access());
    }

    @Test
    public void unsupportedReceiverChainsStayQualified() {
        for (String call : List.of("a.b.run()", "factory().run()", "items[0].run()")) {
            assertEquals(call, VasUsageContext.Access.UNSUPPORTED,
                VasSymbolScanner.usageContext(call, call.lastIndexOf("run")).access());
        }
    }

    @Test
    public void adjacentUnaryOperatorsDoNotHideSeparatorsOrDefaults() {
        VasSymbol function = find(VasSymbolScanner.scan("void send(int a,int b=-1) {}"), "send");
        assertEquals(2, function.parameterCount());
        assertEquals(1, function.requiredParameterCount());
        assertEquals(2, VasSymbolScanner.usageContext("send(1,-2)", 0).argumentCount());
    }

    @Test
    public void localVariablesAreNotVisibleBeforeTheirDeclaration() {
        String source = "void run() { use(count); int count; use(count); }";
        VasSymbol count = find(VasSymbolScanner.scan(source), "count");
        assertFalse(count.isVisibleAt(source.indexOf("count")));
        assertTrue(count.isVisibleAt(source.lastIndexOf("count")));
    }

    @Test
    public void findsExplicitLifecycleFamiliesWithoutConfusingConstructorUses() {
        for (String body : List.of("Foo() {}", "Foo(int value) {}", "~Foo() {}", "private Foo() {}", "[tag] Foo() {}", "[tag] explicit Foo(int value) {}", "[tag] ~Foo() {}", "void run() {} ~Foo() {}")) {
            String source = "namespace N { class Foo { " + body + " } }";
            assertTrue(body, VasSymbolScanner.hasExplicitLifecycleDeclaration(source, find(VasSymbolScanner.scan(source), "Foo")));
        }
        for (String body : List.of("", "Foo@ field = Foo();", "void run() { Foo@ value = Foo(); }", "void run() { Foo(); }")) {
            String source = "class Foo { " + body + " }";
            assertFalse(body, VasSymbolScanner.hasExplicitLifecycleDeclaration(source, find(VasSymbolScanner.scan(source), "Foo")));
        }
    }

    @Test
    public void qualifiedNamespaceComponentsAreDeclarationsNotBaseTypes() {
        List<VasSymbol> symbols = VasSymbolScanner.scan("namespace N::Inner { int value; }");
        assertEquals("N", find(symbols, "N").qualifiedName());
        assertEquals("N::Inner", find(symbols, "Inner").qualifiedName());
        assertEquals("N::Inner", find(symbols, "value").container());
        assertTrue(find(symbols, "N").baseTypes().isEmpty());
        assertTrue(find(symbols, "Inner").baseTypes().isEmpty());
    }

    @Test
    public void ordinaryExpressionsDoNotDeclareVariables() {
        String source = "bool a; bool b; int flags; int mask; void run() { "
            + "if (a && b) {} if (a || b) {} if ((flags & mask) != 0) {} "
            + "consume(flags & mask); flags & mask; bool result = a && b; int bits = flags & mask; }";
        List<VasSymbol> symbols = VasSymbolScanner.scan(source);
        for (String name : List.of("a", "b", "flags", "mask")) {
            assertEquals(name, 1, symbols.stream().filter(symbol -> symbol.name().equals(name)).count());
        }
        assertFalse(find(symbols, "result").isProjectVisible());
        assertFalse(find(symbols, "bits").isProjectVisible());
    }

    @Test
    public void includesDoNotHideFollowingDeclarationHeads() {
        for (String directive : List.of("#include 'api.vas'", "#include\"api.vas\"", "#include\n\"api.vas\"")) {
            List<VasSymbol> symbols = VasSymbolScanner.scan("\uFEFF" + directive + " int value; void run() { value; }");
            assertEquals(2, symbols.size());
            assertTrue(find(symbols, "value").isProjectVisible());
        }
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
