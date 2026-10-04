package com.verseangelscript.rider.lang;

import com.intellij.psi.impl.source.tree.LeafElement;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class VasAstFactoryTest {
    private final VasAstFactory factory = new VasAstFactory();

    @Test
    public void createsCustomPsiOnlyForIdentifiers() {
        LeafElement identifier = factory.createLeaf(VasTypes.IDENTIFIER, "Player");

        assertTrue(identifier instanceof VasIdentifierPsiElement);
        assertTrue(identifier instanceof com.intellij.psi.ContributedReferenceHost);
        assertEquals("Player", ((VasIdentifierPsiElement) identifier).getName());
        assertNull(factory.createLeaf(VasTypes.KEYWORD, "class"));
        assertNull(factory.createLeaf(VasTypes.OPERATOR, "+"));
    }

    @Test
    public void extractsQuotedAngleAndModulePaths() {
        assertEquals("shared/math.vas", VasIncludeReference.extractIncludePath(
            "  #include \"shared/math.vas\""
        ));
        assertEquals("shared/math.vas", VasIncludeReference.extractIncludePath("#include <shared/math.vas>"));
        assertEquals("Arena/Demo.vas", VasIncludeReference.extractIncludePath("import Arena.Demo;"));
        assertNull(VasIncludeReference.extractIncludePath("import int Fn() from \"module\";"));
        assertNull(VasIncludeReference.extractIncludePath("#define path \"math.vas\""));
        assertEquals("shared/math.vas", VasIncludeReference.extractIncludePath("#include'shared/math.vas'"));
        assertEquals("shared/math.vas", VasIncludeReference.extractIncludePath("#include\"shared/math.vas\""));
        assertEquals("shared/math.vas", VasIncludeReference.extractIncludePath("#include\n'shared/math.vas'"));
        assertNull(VasIncludeReference.extractIncludePath("#included \"math.vas\""));
        assertNull(VasIncludeReference.extractIncludePath("// #include 'math.vas'"));
        assertNull(VasIncludeReference.extractIncludePath("#include /* comment */'math.vas'"));
    }
}
