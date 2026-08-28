package com.verseangelscript.rider.lang;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public final class VasShowCalleesAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        VasCallNavigation.show(event, false);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(VasCallNavigation.isAvailable(event));
    }
}
