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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class VasBuildAction extends AnAction {
    private static final String NOTIFICATION_GROUP = "VAS Build";
    private static final int BUILD_TIMEOUT_MS = 120_000;

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile sourceFile = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || sourceFile == null || project.getBasePath() == null) {
            return;
        }

        BuildPaths paths = resolvePaths(project, sourceFile);
        String validationError = paths.validate();
        if (validationError != null) {
            notify(project, "VAS build configuration", validationError, NotificationType.ERROR);
            return;
        }

        ProgressManager.getInstance().run(
            new Task.Backgroundable(project, "Building " + sourceFile.getName(), true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    runBuilder(project, paths);
                }
            }
        );
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        boolean isVasFile = file != null
            && "vas".equals(file.getExtension() == null
                ? ""
                : file.getExtension().toLowerCase(Locale.ROOT));
        event.getPresentation().setEnabledAndVisible(event.getProject() != null && isVasFile);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    private static void runBuilder(Project project, BuildPaths paths) {
        try {
            Files.createDirectories(paths.outputFile().getParent());
            GeneralCommandLine commandLine = new GeneralCommandLine(paths.builder().toString())
                .withParameters(
                    paths.config().toString(),
                    paths.source().toString(),
                    paths.outputFile().toString()
                )
                .withWorkDirectory(paths.repositoryRoot().toFile())
                .withCharset(StandardCharsets.UTF_8);

            ProcessOutput output = new CapturingProcessHandler(commandLine)
                .runProcess(BUILD_TIMEOUT_MS);

            String compilerOutput = output.getStdout() + output.getStderr();
            if (!output.isTimeout() && output.getExitCode() == 0) {
                notify(
                    project,
                    "VAS build succeeded",
                    "Generated <code>" + escape(paths.outputFile().toString()) + "</code>",
                    NotificationType.INFORMATION
                );
            } else {
                String prefix = output.isTimeout()
                    ? "vasbuild timed out.<br/>"
                    : "vasbuild exited with code " + output.getExitCode() + ".<br/>";
                notify(
                    project,
                    "VAS build failed",
                    prefix + formatOutput(compilerOutput),
                    NotificationType.ERROR
                );
            }
        } catch (ExecutionException | IOException exception) {
            notify(
                project,
                "VAS build failed",
                escape(exception.getMessage() == null ? exception.toString() : exception.getMessage()),
                NotificationType.ERROR
            );
        }
    }

    private static BuildPaths resolvePaths(Project project, VirtualFile sourceFile) {
        VasSettingsState settings = VasSettingsState.getInstance(project);
        Path repositoryRoot = Path.of(project.getBasePath()).toAbsolutePath().normalize();

        Path builder = resolveConfiguredPath(
            repositoryRoot,
            settings.builderPath,
            Files.isRegularFile(repositoryRoot.resolve(".vas/bin/vasbuild.exe"))
                ? ".vas/bin/vasbuild.exe"
                : "out/build/windows-msvc-v145-cxx23/Release/vasbuild.exe"
        );
        Path config = resolveConfiguredPath(
            repositoryRoot,
            settings.configPath,
            Files.isRegularFile(repositoryRoot.resolve(".vas/vasbuild.config.txt"))
                ? ".vas/vasbuild.config.txt"
                : "tests/vasbuild/fixtures/minimal-config.txt"
        );
        Path outputDirectory = resolveConfiguredPath(
            repositoryRoot,
            settings.outputDirectory,
            "out/rider"
        );

        String sourceName = sourceFile.getName();
        int extensionIndex = sourceName.lastIndexOf('.');
        String baseName = extensionIndex > 0 ? sourceName.substring(0, extensionIndex) : sourceName;
        Path outputFile = outputDirectory.resolve(baseName + ".vasbc");

        return new BuildPaths(
            repositoryRoot,
            builder,
            config,
            Path.of(sourceFile.getPath()),
            outputFile
        );
    }

    private static Path resolveConfiguredPath(
        Path repositoryRoot,
        String configuredValue,
        String defaultRelativePath
    ) {
        String value = configuredValue == null ? "" : configuredValue.trim();
        Path path = value.isEmpty() ? Path.of(defaultRelativePath) : Path.of(value);
        return path.isAbsolute() ? path.normalize() : repositoryRoot.resolve(path).normalize();
    }

    private static void notify(
        Project project,
        String title,
        String content,
        NotificationType type
    ) {
        new Notification(NOTIFICATION_GROUP, title, content, type).notify(project);
    }

    private static String formatOutput(String output) {
        String value = output == null || output.isBlank() ? "No compiler output." : output.trim();
        if (value.length() > 4_000) {
            value = value.substring(value.length() - 4_000);
        }
        return escape(value).replace("\r\n", "<br/>").replace("\n", "<br/>");
    }

    private static String escape(String value) {
        return StringUtil.escapeXmlEntities(value);
    }

    private record BuildPaths(
        Path repositoryRoot,
        Path builder,
        Path config,
        Path source,
        Path outputFile
    ) {
        String validate() {
            if (!Files.isRegularFile(builder)) {
                return "vasbuild was not found at <code>" + escape(builder.toString())
                    + "</code>.<br/>Build <code>VerseAngelScript.sln</code> first or configure "
                    + "the path in <b>Settings | Tools | VAS</b>.";
            }
            if (!Files.isRegularFile(config)) {
                return "The VAS interface config was not found at <code>"
                    + escape(config.toString()) + "</code>.";
            }
            if (!Files.isRegularFile(source)) {
                return "The current VAS source file no longer exists.";
            }
            return null;
        }
    }
}
