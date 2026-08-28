package com.verseangelscript.rider.index;

import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter;
import com.intellij.util.indexing.FileContent;
import com.intellij.util.indexing.FileBasedIndex;
import com.intellij.util.indexing.ID;
import com.intellij.util.indexing.ScalarIndexExtension;
import com.intellij.util.io.EnumeratorStringDescriptor;
import com.intellij.util.io.KeyDescriptor;
import com.verseangelscript.rider.VasFileType;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps a base type name to VAS files that declare a direct derived type. */
public final class VasInheritanceIndex extends ScalarIndexExtension<String> {
    public static final ID<String, Void> BASE_TYPE = ID.create(
        "com.verseangelscript.inheritance.baseType"
    );

    @Override
    public @NotNull ID<String, Void> getName() {
        return BASE_TYPE;
    }

    @Override
    public @NotNull DataIndexer<String, Void, FileContent> getIndexer() {
        return inputData -> {
            Map<String, Void> baseTypes = new LinkedHashMap<>();
            for (VasSymbol symbol : VasSymbolScanner.scan(inputData.getContentAsText())) {
                for (String baseType : symbol.baseTypes()) {
                    baseTypes.put(baseType, null);
                }
            }
            return baseTypes;
        };
    }

    @Override
    public @NotNull KeyDescriptor<String> getKeyDescriptor() {
        return EnumeratorStringDescriptor.INSTANCE;
    }

    @Override
    public @NotNull FileBasedIndex.InputFilter getInputFilter() {
        return new DefaultFileTypeSpecificInputFilter(VasFileType.INSTANCE);
    }

    @Override
    public boolean dependsOnFileContent() {
        return true;
    }

    @Override
    public int getVersion() {
        return 1;
    }
}
