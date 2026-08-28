package com.verseangelscript.rider.project;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.facet.ui.ValidationResult;
import com.intellij.platform.DirectoryProjectGenerator;
import com.verseangelscript.rider.VasIcons;
import com.verseangelscript.rider.build.VasSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.io.IOException;
import java.io.InputStream;

public final class VasProjectGenerator implements DirectoryProjectGenerator<Object> {
    @Override
    public @NotNull String getName() {
        return "Verse AngelScript";
    }

    @Override
    public @Nullable Icon getLogo() {
        return VasIcons.FILE;
    }

    @Override
    public @NotNull ValidationResult validate(@NotNull String baseDirPath) {
        return ValidationResult.OK;
    }

    @Override
    public void generateProject(
        @NotNull Project project,
        @NotNull VirtualFile baseDir,
        @Nullable Object settings,
        @NotNull Module module
    ) {
        WriteAction.run(() -> {
            try {
                VirtualFile sourceDirectory = VfsUtil.createDirectoryIfMissing(baseDir, "src");
                if (sourceDirectory == null) {
                    throw new IOException("Could not create the src directory");
                }
                write(sourceDirectory, "main.vas", MAIN_SOURCE);
                write(sourceDirectory, "math.vas", MATH_SOURCE);
                VirtualFile runtimeDirectory = VfsUtil.createDirectoryIfMissing(baseDir, ".vas/bin");
                if (runtimeDirectory == null) {
                    throw new IOException("Could not create the .vas/bin directory");
                }
                copyResource(runtimeDirectory, "vasrun.exe", "/runtime/windows-x64/vasrun.exe");
                copyResource(runtimeDirectory, "vasbuild.exe", "/runtime/windows-x64/vasbuild.exe");
                VirtualFile vasDirectory = runtimeDirectory.getParent();
                if (vasDirectory == null) {
                    throw new IOException("Could not locate the .vas directory");
                }
                copyResource(
                    vasDirectory,
                    "vasbuild.config.txt",
                    "/runtime/windows-x64/vasbuild.config.txt"
                );
                write(baseDir, "vas-project.json", PROJECT_CONFIGURATION);
                write(baseDir, ".gitignore", "out/\n.idea/\n");
                write(baseDir, "README.md", README);
                configureBundledToolchain(project, baseDir);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not create the VAS starter project", exception);
            }
        });
        baseDir.refresh(false, true);
    }

    private static void configureBundledToolchain(@NotNull Project project, @NotNull VirtualFile baseDir) {
        VasSettingsState settings = VasSettingsState.getInstance(project);
        String projectPath = baseDir.getPath();
        settings.builderPath = projectPath + "/.vas/bin/vasbuild.exe";
        settings.runnerPath = projectPath + "/.vas/bin/vasrun.exe";
        settings.configPath = projectPath + "/.vas/vasbuild.config.txt";
        settings.outputDirectory = projectPath + "/out/rider";
    }

    private static void write(VirtualFile directory, String name, String content) throws IOException {
        VirtualFile file = directory.findChild(name);
        if (file == null) {
            file = directory.createChildData(VasProjectGenerator.class, name);
        }
        VfsUtil.saveText(file, content);
    }

    private static void copyResource(
        VirtualFile directory,
        String name,
        String resourcePath
    ) throws IOException {
        try (InputStream input = VasProjectGenerator.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("Missing bundled VAS runtime: " + resourcePath);
            }
            VirtualFile file = directory.findChild(name);
            if (file == null) {
                file = directory.createChildData(VasProjectGenerator.class, name);
            }
            file.setBinaryContent(input.readAllBytes());
        }
    }

    private static final String MAIN_SOURCE = """
        #include "math.vas"

        void main()
        {
            int result = add(20, 22);
            print("Hello from VAS in Rider!\\n");
            print("20 + 22 = " + result + "\\n");
        }
        """;

    private static final String MATH_SOURCE = """
        int add(int left, int right)
        {
            return left + right;
        }
        """;

    private static final String PROJECT_CONFIGURATION = """
        {
          "name": "VAS Starter",
          "entry": "src/main.vas",
          "runner": ".vas/bin/vasrun.exe",
          "builder": ".vas/bin/vasbuild.exe",
          "builderConfig": ".vas/vasbuild.config.txt",
          "bytecodeOutput": "out/vas/main.vasbc"
        }
        """;

    private static final String README = """
        # VAS Starter Project

        Open `src/main.vas`, then use **Run | Run Current VAS File** in JetBrains Rider.
        Configure the VAS SDK runner under **Settings | Tools | VAS** when this project is outside the SDK repository.
        """;
}
