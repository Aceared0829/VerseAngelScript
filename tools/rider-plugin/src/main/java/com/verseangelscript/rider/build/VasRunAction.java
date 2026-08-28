package com.verseangelscript.rider.build;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class VasRunAction extends AnAction {
    private static final String NOTIFICATION_GROUP = "VAS Run";
    private static final int RUN_TIMEOUT_MS = 120_000;

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile sourceFile = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || sourceFile == null || project.getBasePath() == null) {
            return;
        }

        Path projectRoot = Path.of(project.getBasePath()).toAbsolutePath().normalize();
        String configured = VasSettingsState.getInstance(project).runnerPath.trim();
        Path runner = configured.isEmpty()
            ? defaultRunner(projectRoot)
            : resolve(projectRoot, configured);
        Path source = Path.of(sourceFile.getPath());
        if (!Files.isRegularFile(runner)) {
            notify(
                project,
                "VAS runner not found",
                "Build <code>vasrun</code> or configure it under <b>Settings | Tools | VAS</b>.<br/><code>"
                    + escape(runner.toString()) + "</code>",
                NotificationType.ERROR
            );
            return;
        }

        ProgressManager.getInstance().run(
            new Task.Backgroundable(project, "Running " + sourceFile.getName(), true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    execute(project, runner, source);
                }
            }
        );
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean isVasFile = file != null && "vas".equals(
            file.getExtension() == null ? "" : file.getExtension().toLowerCase(Locale.ROOT)
        );
        event.getPresentation().setEnabledAndVisible(event.getProject() != null && isVasFile);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private static void execute(Project project, Path runner, Path source) {
        try {
            GeneralCommandLine commandLine = new GeneralCommandLine(runner.toString())
                .withParameters(source.toString())
                .withWorkDirectory(source.getParent().toFile())
                .withCharset(StandardCharsets.UTF_8);
            ProcessOutput output = new CapturingProcessHandler(commandLine).runProcess(RUN_TIMEOUT_MS);
            String text = (output.getStdout() + output.getStderr()).trim();
            NotificationType type = !output.isTimeout() && output.getExitCode() == 0
                ? NotificationType.INFORMATION
                : NotificationType.ERROR;
            String title = type == NotificationType.INFORMATION
                ? "VAS run completed"
                : "VAS run failed";
            String prefix = output.isTimeout()
                ? "vasrun timed out.<br/>"
                : "Exit code: " + output.getExitCode() + "<br/>";
            notify(project, title, prefix + format(text), type);
        } catch (ExecutionException exception) {
            notify(project, "VAS run failed", escape(exception.getMessage()), NotificationType.ERROR);
        }
    }

    private static Path resolve(Path projectRoot, String value) {
        Path path = Path.of(value);
        return path.isAbsolute() ? path.normalize() : projectRoot.resolve(path).normalize();
    }

    private static Path defaultRunner(Path projectRoot) {
        Path bundled = projectRoot.resolve(".vas/bin/vasrun.exe").normalize();
        return Files.isRegularFile(bundled)
            ? bundled
            : projectRoot.resolve("out/build/windows-msvc-v145-cxx23/Release/vasrun.exe").normalize();
    }

    private static void notify(Project project, String title, String content, NotificationType type) {
        new Notification(NOTIFICATION_GROUP, title, content, type).notify(project);
    }

    private static String format(String text) {
        String value = text.isBlank() ? "No program output." : text;
        if (value.length() > 4_000) {
            value = value.substring(value.length() - 4_000);
        }
        return escape(value).replace("\r\n", "<br/>").replace("\n", "<br/>");
    }

    private static String escape(String value) {
        return StringUtil.escapeXmlEntities(value == null ? "" : value);
    }
}
