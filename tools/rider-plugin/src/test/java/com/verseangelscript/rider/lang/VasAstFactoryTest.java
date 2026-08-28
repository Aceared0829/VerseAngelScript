package com.verseangelscript.rider.lang;

import com.intellij.psi.impl.source.tree.LeafElement;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class VasAstFactoryTest {
    private final VasAstFactory factory = new VasAstFactory();

    @Test
    public void createsCustomPsiOnlyForIdentifiers() {
        LeafElement identifier = factory.createLeaf(VasTypes.IDENTIFIER, "Player");

        assertTrue(identifier instanceof VasIdentifierPsiElement);
        assertNull(factory.createLeaf(VasTypes.KEYWORD, "class"));
        assertNull(factory.createLeaf(VasTypes.OPERATOR, "+"));
    }
}
