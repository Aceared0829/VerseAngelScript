package com.verseangelscript.rider.build;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.PROJECT)
@State(name = "VasSettings", storages = @Storage("vas.xml"))
public final class VasSettingsState implements PersistentStateComponent<VasSettingsState> {
    public String builderPath = "";
    public String runnerPath = "";
    public String configPath = "";
    public String outputDirectory = "";

    public static VasSettingsState getInstance(@NotNull Project project) {
        return project.getService(VasSettingsState.class);
    }

    @Override
    public @Nullable VasSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull VasSettingsState state) {
        XmlSerializerUtil.copyBean(state, this);
    }
}
