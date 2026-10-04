package com.verseangelscript.rider.index;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class VasCompletionSelectionTest {
    @Test
    public void enumValuesResolveThroughNamespaceAndEnumQualification() {
        String source = "namespace Arena { enum ERole { Warrior = Host(1, 2), Medic } void Main() { print(Warrior); print(ERole::Warrior); } }";
        var symbols = VasSymbolScanner.scan(source);
        assertEquals(2, symbols.stream().filter(symbol -> symbol.kind() == VasSymbolKind.ENUM_MEMBER).count());
        int plain = source.indexOf("Warrior);");
        var local = symbols.stream().map(symbol -> new VasSymbolSelection.Candidate<>(symbol, symbol)).toList();
        var target = VasSymbolSelection.select(local, List.of(), "Warrior", plain, VasSymbolScanner.usageContext(source, plain));
        assertEquals(1, target.size());
        assertEquals(VasSymbolKind.ENUM_MEMBER, target.getFirst().kind());
        int qualified = source.lastIndexOf("Warrior);");
        assertEquals(1, VasSymbolSelection.select(local, List.of(), "Warrior", qualified, VasSymbolScanner.usageContext(source, qualified)).size());
        assertTrue(VasCompletionSelection.select(symbols, List.of(), qualified, VasSymbolScanner.usageContext(source, qualified))
            .stream().anyMatch(symbol -> symbol.name().equals("Medic")));
    }

    @Test
    public void filtersNamespacesTypedMembersAndLocalsByVisibility() {
        String library = "namespace Arena { class FHero { int Health; void Heal(int Amount) {} } int RunDemo() { return 1; } }";
        String source = "void Main() { Arena::FHero Hero; { int Hidden; } Hero.Heal(); Arena::RunDemo(); }";
        var local = VasSymbolScanner.scan(source);
        var imported = VasSymbolScanner.scan(library);
        int member = source.indexOf("Heal()");
        List<VasSymbol> members = VasCompletionSelection.select(local, imported, member, VasSymbolScanner.usageContext(source, member));
        assertTrue(members.stream().anyMatch(symbol -> symbol.name().equals("Heal")));
        assertTrue(members.stream().anyMatch(symbol -> symbol.name().equals("Health")));
        assertFalse(members.stream().anyMatch(symbol -> symbol.name().equals("RunDemo")));
        int namespace = source.indexOf("RunDemo()");
        var qualified = VasCompletionSelection.select(local, imported, namespace, VasSymbolScanner.usageContext(source, namespace));
        assertTrue(qualified.stream().anyMatch(symbol -> symbol.name().equals("RunDemo")));
        assertFalse(qualified.stream().anyMatch(symbol -> symbol.name().equals("Health")));
        var plain = VasCompletionSelection.select(local, imported, member, VasUsageContext.PLAIN);
        assertFalse(plain.stream().anyMatch(symbol -> symbol.name().equals("Hidden")));
    }
}
