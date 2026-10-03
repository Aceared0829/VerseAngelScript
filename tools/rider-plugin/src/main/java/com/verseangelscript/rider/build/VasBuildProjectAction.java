package com.verseangelscript.rider.build;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.verseangelscript.rider.projectbuild.VasProjectBuildService;
import org.jetbrains.annotations.NotNull;

/** Only an explicit action may enter the descriptor/build pipeline. */
public final class VasBuildProjectAction extends AnAction {
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        if (event.getProject() != null) VasProjectBuildService.getInstance(event.getProject()).start();
    }
    @Override public void update(@NotNull AnActionEvent event) {
        var project = event.getProject();
        event.getPresentation().setEnabledAndVisible(project != null && !project.isDisposed() && project.getBasePath() != null);
    }
    @Override public @NotNull ActionUpdateThread getActionUpdateThread() { return ActionUpdateThread.BGT; }
}
