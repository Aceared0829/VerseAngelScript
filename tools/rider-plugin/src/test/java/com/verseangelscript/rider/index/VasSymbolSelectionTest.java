package com.verseangelscript.rider.index;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class VasSymbolSelectionTest {
    @Test
    public void rejectsWrongArityWithoutRestoringCandidates() {
        assertTrue(select("void act(int a) {} void run() { /*use*/act(); }").isEmpty());
        assertTrue(select("void act() {} void run() { /*use*/act(1); }").isEmpty());
    }

    @Test
    public void includesAreFilteredTogetherWithSameFileOverloads() {
        List<VasSymbol> targets = select("void act() {} void run() { /*use*/act(1, 2); }",
            "void act(int a, int b) {}");
        assertEquals(1, targets.size());
        assertEquals(2, targets.get(0).parameterCount());
    }

    @Test
    public void sameFileDoesNotHideIncludedEqualArityOverload() {
        assertEquals(2, select("void act(int a) {} void run() { /*use*/act(1); }",
            "void act(float a) {}").size());
    }

    @Test
    public void equalArityTypesRemainAmbiguous() {
        assertEquals(2, select("void act(int a) {} void act(string a) {} void run() { /*use*/act(1); }").size());
    }

    @Test
    public void trailingDefaultArgumentsAreViableButOverlappingRangesStayAmbiguous() {
        assertEquals(1, select("void act(int a, int b = 0) {} void run() { /*use*/act(1); }").size());
        assertEquals(1, select("void act(int a = 0) {} void run() { /*use*/act(); }").size());
        assertTrue(select("void act(int a, int b = 0) {} void run() { /*use*/act(); }").isEmpty());
        assertEquals(2, select("void act(int a) {} void run() { /*use*/act(1); }",
            "void act(int a, int b = 0) {}").size());
    }

    @Test
    public void wrongOrUnknownMemberOwnersNeverFallBackToNames() {
        String declarations = "class A { void act() {} } class B {} ";
        assertTrue(select(declarations + "void run() { B object; object./*use*/act(); }").isEmpty());
        assertTrue(select(declarations + "void run() { unknown./*use*/act(); }").isEmpty());
        assertTrue(select(declarations + "void run() { /*use*/act(); }").isEmpty());
    }

    @Test
    public void memberLookupDoesNotBindAnUnrelatedLocalOfSameName() {
        VasSymbol target = only("class A { int value; } void run() { A object; int value; object./*use*/value; }");
        assertEquals("A", target.container());
        assertTrue(target.isProjectVisible());
    }

    @Test
    public void receiverUsesLexicalDepthRatherThanTextDistance() {
        VasSymbol target = only("class A { int value; } class B { int value; } "
            + "void run() { A object; { B object; int padding; object./*use*/value; } }");
        assertEquals("B", target.container());
    }

    @Test
    public void closestLocalShadowsGlobalAndExitedScopesDoNot() {
        VasSymbol local = only("int value; void run() { int value; { int value; /*use*/value; } }");
        assertEquals(2, local.braceDepth());
        VasSymbol outer = only("int value; void run() { { int value; } /*use*/value; }");
        assertTrue(outer.isProjectVisible());
    }

    @Test
    public void laterLocalDoesNotShadowEarlierUse() {
        assertTrue(only("int value; void run() { /*use*/value; int value; }").isProjectVisible());
    }

    @Test
    public void declarationsNeverReferToSiblingDeclarations() {
        assertTrue(select("int value; void run() { int /*use*/value; }").isEmpty());
        assertTrue(select("void act(int a) {} void /*use*/act(string a) {}").isEmpty());
    }

    @Test
    public void recursiveUsesStillResolveToTheirOwnDeclaration() {
        assertEquals("act", only("void act() { /*use*/act(); }").name());
    }

    @Test
    public void plainLookupUsesTheEnclosingContainer() {
        VasSymbol target = only("void act() {} namespace N { void act() {} void run() { /*use*/act(); } }");
        assertEquals("N", target.container());
    }

    @Test
    public void qualifiedNamespacesAreExactNotSuffixMatches() {
        String definitions = "namespace A { namespace N { void act() {} } } namespace B { namespace N { void act() {} } } ";
        assertEquals("A::N", only(definitions + "void run() { A::N::/*use*/act(); }").container());
        assertTrue(select(definitions + "void run() { N::/*use*/act(); }").isEmpty());
    }

    @Test
    public void qualifiedDeclaredTypesRetainTheirIdentity() {
        String definitions = "namespace A { class T { int value; } } namespace B { class T { int value; } } ";
        assertEquals("B::T", only(definitions + "void run() { B::T object; object./*use*/value; }").container());
        assertTrue(select(definitions + "void run() { T object; object./*use*/value; }").isEmpty());
    }

    @Test
    public void relativeTypeNamesUseTheirDeclarationContainer() {
        VasSymbol target = only("namespace N { class T { int value; } T object; void run() { object./*use*/value; } }");
        assertEquals("N::T", target.container());
    }

    @Test
    public void complexAndIncompleteCallsDeclineResolution() {
        for (String expression : List.of("a.object./*use*/act()", "factory()./*use*/act()",
            "object[0]./*use*/act()", "/*use*/act(")) {
            assertTrue(expression, select("class A { void act() {} } void act() {} void run() { A object; "
                + expression + " }").isEmpty());
        }
    }

    @Test
    public void aLocalWithTheSameNameBlocksCallableFallback() {
        assertTrue(select("void act() {} void run() { int act; /*use*/act(); }").isEmpty());
    }

    @Test
    public void unknownGenericSignaturesDoNotInventAnArity() {
        assertTrue(select("void act(array<int> a, int b) {} void run() { /*use*/act(1, 2); }").isEmpty());
    }

    @Test
    public void loopLocalsDoNotEscapeTheirControlledStatement() {
        assertTrue(only("int value; void run() { for (int value = 0; value < 2; value++) {} /*use*/value; }")
            .isProjectVisible());
        assertTrue(only("int value; void run() { for (int value = 0; value < 2; value++) print(value); /*use*/value; }")
            .isProjectVisible());
        assertTrue(select("void run() { for (int value = 0; value < 2; value++) {} /*use*/value; }").isEmpty());
    }

    @Test
    public void unknownSignaturesBlockFalseUniquenessOfOtherOverloads() {
        assertTrue(select("void act(array<int> a) {} void act(int a) {} void run() { array<int> xs; /*use*/act(xs); }").isEmpty());
    }

    @Test
    public void unsupportedReceiverTypesStillShadowOuterVariables() {
        for (String type : List.of("array<int>", "int[]")) {
            assertTrue(type, select("class A { void act() {} } A object; void run() { "
                + type + " object; object./*use*/act(); }").isEmpty());
        }
    }

    @Test
    public void outOfLineLocalsUseTheMethodOwnerNamespace() {
        assertEquals("N::T", only("class T { void act() {} } namespace N { class T { void act() {} } class Owner {} } "
            + "void N::Owner::run() { T object; object./*use*/act(); }").container());
    }

    @Test
    public void genericArgumentCommasDoNotCreateFalseArity() {
        assertTrue(select("void act(int a, int b) {} void run() { /*use*/act(dictionary<string,int>()); }").isEmpty());
    }

    @Test
    public void commaSeparatedLocalsStillShadowOuterSymbols() {
        assertEquals(false, only("int value; void run() { int first = make(1, 2), value; /*use*/value; }").isProjectVisible());
        assertTrue(select("class A { void act() {} } A object; void run() { int first, object; object./*use*/act(); }").isEmpty());
    }

    @Test
    public void callsAfterElseAreNotDeclarations() {
        assertEquals(1, select("void act() {} void run() { if (true) {} else act(); /*use*/act(); }").size());
    }

    @Test
    public void unsupportedInheritedLookupDoesNotBindGlobalNames() {
        assertTrue(select("void act() {} class A { void act() {} } class B : A { void run() { /*use*/act(); } }").isEmpty());
    }

    @Test
    public void incompleteDefaultsDoNotMakeZeroArgumentCallsViable() {
        assertTrue(select("void act(int value = ) {} void run() { /*use*/act(); }").isEmpty());
    }

    @Test
    public void unsupportedReturnTypesDoNotHideOverloadDeclarations() {
        for (String type : List.of("array<int>", "int[]")) {
            assertEquals(type, 2, select(type + " act(int a) {} void act(string a) {} void run() { /*use*/act(1); }").size());
        }
    }

    @Test
    public void loopScopeIncludesAnUnbracedElseBranch() {
        assertEquals(false, only("int value; void run() { for(int value=0; value<1; value++) if(true) value++; else /*use*/value++; }")
            .isProjectVisible());
    }

    @Test
    public void relativeOutOfLineOwnersKeepTheirEnclosingNamespace() {
        assertEquals("N::T", only("class T { void act() {} } namespace N { class T { void act() {} } class Owner {} "
            + "void Owner::run() { T object; object./*use*/act(); } }").container());
    }

    @Test
    public void genericReturnTypesOnQualifiedDeclarationsKeepTheOverloadSet() {
        assertEquals(2, select("class A {} array<int> A::act(int a) {} void A::act(string a) {} "
            + "void run() { A object; object./*use*/act(1); }").size());
    }

    @Test
    public void classConstructionBindsTheTypeRatherThanAConstructorOverload() {
        assertEquals(VasSymbolKind.CLASS, only("class Foo {} void run() { Foo@ value = /*use*/Foo(); }").kind());
        assertEquals(VasSymbolKind.CLASS, only("class Foo { Foo(int x) {} } void run() { Foo@ value = /*use*/Foo(1); }").kind());
        assertEquals("N", only("namespace N { class Foo {} } void run() { N::Foo@ value = N::/*use*/Foo(); }").container());
    }

    @Test
    public void interfaceInstantiationDoesNotMasqueradeAsConstruction() {
        assertTrue(select("interface Task {} void run() { Task@ value = /*use*/Task(); }").isEmpty());
    }

    @Test
    public void classConstructionStillRespectsLexicalShadowing() {
        assertTrue(select("class Foo {} void run() { int Foo; /*use*/Foo(); }").isEmpty());
    }

    @Test
    public void referenceDirectionParametersShadowGlobals() {
        for (String direction : List.of("in", "out", "inout")) {
            VasSymbol value = only("int value; void run(int &" + direction + " value) { /*use*/value; }");
            assertEquals(false, value.isProjectVisible());
            assertEquals("int", value.declaredType());
            VasSymbol member = only("class A { int field; } class B { int field; } A object; "
                + "void run(B &" + direction + " object) { object./*use*/field; }");
            assertEquals("B", member.container());
        }
    }

    @Test
    public void qualifiedReferenceParameterTypesRemainExact() {
        assertEquals("N::B", only("class B { int field; } namespace N { class B { int field; } } "
            + "void run(const N::B &in object) { object./*use*/field; }").container());
    }

    @Test
    public void qualifiedNamespaceDeclarationsDoNotLeakMembersIntoParents() {
        assertEquals("", only("int value; namespace N::Inner { int value; } namespace N { void run() { /*use*/value; } }").container());
        assertEquals("N::Inner", only("int value; namespace N::Inner { int value; void run() { /*use*/value; } }").container());
        assertEquals("N::Inner", only("namespace N::Inner { void act() {} } void run() { N::Inner::/*use*/act(); }").container());
    }

    @Test
    public void handleReferenceParameterStillShadowsOuterObject() {
        assertEquals("B", only("class A { int field; } class B { int field; } A object; "
            + "void run(B@ &in object) { object./*use*/field; }").container());
    }

    @Test
    public void constHandleParametersAndLocalsRetainTheirOwner() {
        String declarations = "class A { int field; } class B { int field; } A object; ";
        for (String type : List.of("B@const", "const B@const", "B@const &in", "const B@const &in")) {
            assertEquals(type, "B", only(declarations
                + "void run(" + type + " object) { object./*use*/field; }").container());
        }
        for (String type : List.of("B@const", "const B@const")) {
            assertEquals(type, "B", only(declarations
                + "void run() { " + type + " object = B(); object./*use*/field; }").container());
        }
        assertEquals("N::B", only("class A { int field; } namespace N { class B { int field; } } A object; "
            + "void run(N::B@const object) { object./*use*/field; }").container());
        assertTrue(select(declarations
            + "void run(array<int>@const object) { object./*use*/field; }").isEmpty());
    }

    @Test
    public void lifecycleLocalsAndParametersDoNotLeakIntoOtherMethods() {
        for (String lifecycle : List.of("C() { int value; }", "~C() { int value; }", "C(int value) {}", "[tag] C() { int value; }", "[tag] ~C() { int value; }")) {
            VasSymbol target = only("int value; class C { " + lifecycle + " void run() { /*use*/value; } }");
            assertEquals(lifecycle, "", target.container());
            assertEquals(lifecycle, 4, target.offset());
        }
    }

    @Test
    public void lifecycleLocalsAndParametersStillResolveInsideTheirBodies() {
        assertEquals(false, only("int value; class C { C(int value) { /*use*/value; } }").isProjectVisible());
        assertEquals(false, only("int value; class C { C() { int value; /*use*/value; } }").isProjectVisible());
        assertEquals(false, only("int value; class C { ~C() { int value; /*use*/value; } }").isProjectVisible());
        assertEquals("B", only("class A { int field; } class B { int field; } A object; "
            + "class C { C(B@const object) { object./*use*/field; } }").container());
    }

    @Test
    public void expressionOperatorsDoNotTurnRightOperandsIntoDeclarations() {
        String declarations = "bool a; bool b; int flags; int mask; ";
        for (String expression : List.of("if(a && /*use*/b) {}", "if(a || /*use*/b) {}",
            "bool result = a && /*use*/b;", "consume(a && /*use*/b);")) {
            assertEquals(expression, 13, only(declarations + "void run() { " + expression + " }").offset());
        }
        for (String expression : List.of("if((flags & /*use*/mask) != 0) {}", "flags & /*use*/mask;",
            "int result = flags & /*use*/mask;", "consume(flags & /*use*/mask);")) {
            assertEquals(expression, 31, only(declarations + "void run() { " + expression + " }").offset());
        }
    }

    @Test
    public void logicalAndBitwiseFunctionCallsAreNotFunctionDeclarations() {
        for (String expression : List.of("return a && /*use*/check();", "a && /*use*/check();",
            "a & /*use*/check();", "return a & /*use*/check();")) {
            assertEquals(expression, 5, only("bool check() { return true; } bool a; "
                + "bool run() { " + expression + " }").offset());
        }
    }

    @Test
    public void declarationHeadsKeepUnknownTypesAsShadowingBlockers() {
        String declarations = "class A { int field; } A object; ";
        for (String declaration : List.of("Unknown object;", "Unknown@const object;", "array<Unknown> object;",
            "Unknown[] object;", "const Unknown object;", "Unknown object();", "Unknown object(1);", "::Unknown object;")) {
            assertTrue(declaration, select(declarations + "void run() { " + declaration
                + " object./*use*/field; }").isEmpty());
        }
        assertTrue(select(declarations
            + "void run() { for (Unknown object; true; ) { object./*use*/field; } }").isEmpty());
        assertEquals("B", only("class A { int field; } class B { B(int input) {} int field; } A object; "
            + "void run() { B object(1); object./*use*/field; }").container());
    }

    @Test
    public void declarationMetadataDoesNotHideMembersOrOverloads() {
        assertEquals("C", only("int value; class C { [tag] int value; void run() { /*use*/value; } }").container());
        assertEquals("C", only("int value; class C { [tag(1)][other] private int value; void run() { /*use*/value; } }").container());
        assertEquals(2, select("[tag] void act(int value) {} void act(string value) {} "
            + "void run() { /*use*/act(1); }").size());
        assertTrue(select("[tag] void act(array<int> value) {} void act(int value) {} "
            + "void run() { /*use*/act(1); }").isEmpty());
    }

    private static VasSymbol only(String source) {
        List<VasSymbol> targets = select(source);
        assertEquals(targets.toString(), 1, targets.size());
        return targets.get(0);
    }

    private static List<VasSymbol> select(String source, String... includes) {
        int offset = source.indexOf("/*use*/") + "/*use*/".length();
        String name = source.substring(offset).split("[^a-zA-Z0-9_]", 2)[0];
        List<VasSymbolSelection.Candidate<VasSymbol>> local = candidates(source);
        List<VasSymbolSelection.Candidate<VasSymbol>> included = new ArrayList<>();
        for (String include : includes) {
            included.addAll(candidates(include));
        }
        return VasSymbolSelection.select(local, included, name, offset, VasSymbolScanner.usageContext(source, offset));
    }

    private static List<VasSymbolSelection.Candidate<VasSymbol>> candidates(String source) {
        return VasSymbolScanner.scan(source).stream()
            .map(symbol -> new VasSymbolSelection.Candidate<>(symbol, symbol)).toList();
    }
}
