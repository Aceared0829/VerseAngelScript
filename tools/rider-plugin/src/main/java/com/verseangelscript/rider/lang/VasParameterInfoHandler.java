package com.verseangelscript.rider.lang;

import com.intellij.lang.parameterInfo.*;
import com.intellij.psi.PsiFile;

public final class VasParameterInfoHandler implements ParameterInfoHandler<PsiFile, VasCallSupport.Signature> {
    @Override public PsiFile findElementForParameterInfo(CreateParameterInfoContext context) {
        var call = VasCallSupport.callAt(context.getFile().getText(), context.getOffset());
        var signatures = VasCallSupport.signatures(context.getFile(), call);
        if (signatures.isEmpty()) return null;
        context.setItemsToShow(signatures.toArray());
        return context.getFile();
    }
    @Override public void showParameterInfo(PsiFile file, CreateParameterInfoContext context) {
        var call = VasCallSupport.callAt(file.getText(), context.getOffset());
        if (call != null) context.showHint(file, call.open(), this);
    }
    @Override public PsiFile findElementForUpdatingParameterInfo(UpdateParameterInfoContext context) {
        return VasCallSupport.callAt(context.getFile().getText(), context.getOffset()) == null ? null : context.getFile();
    }
    @Override public void updateParameterInfo(PsiFile file, UpdateParameterInfoContext context) {
        var call = VasCallSupport.callAt(file.getText(), context.getOffset());
        if (call == null || call.open() != context.getParameterListStart()) { context.removeHint(); return; }
        context.setParameterOwner(file); context.setCurrentParameter(call.parameter());
    }
    @Override public void updateUI(VasCallSupport.Signature signature, ParameterInfoUIContext context) {
        int index = context.getCurrentParameterIndex();
        int[] range = index >= 0 && index < signature.parameters().size() ? signature.parameters().get(index) : new int[] {-1, -1};
        context.setupUIComponentPresentation(signature.label(), range[0], range[1], false, false, false, context.getDefaultParameterColor());
    }
}
