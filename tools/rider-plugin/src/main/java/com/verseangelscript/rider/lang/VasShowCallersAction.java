package com.verseangelscript.rider.lang;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import org.jetbrains.annotations.NotNull;

public final class VasShowCallersAction extends AnAction {
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        VasCallNavigation.show(event, true);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(VasCallNavigation.isAvailable(event));
    }
}
