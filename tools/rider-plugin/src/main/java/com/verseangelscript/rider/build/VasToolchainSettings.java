package com.verseangelscript.rider.build;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import org.jetbrains.annotations.NotNull;

/** Explicit user choice; project-shared vas.xml never grants executable authority. */
@Service(Service.Level.APP)
@State(name = "VasProjectToolchain", storages = @Storage(value = "vas-project-toolchain.xml", roamingType = RoamingType.DISABLED))
public final class VasToolchainSettings implements PersistentStateComponent<VasToolchainSettings> {
    public String compilerPath = "";
    public static VasToolchainSettings getInstance() {
        return ApplicationManager.getApplication().getService(VasToolchainSettings.class);
    }
    @Override public @NotNull VasToolchainSettings getState() { return this; }
    @Override public void loadState(@NotNull VasToolchainSettings state) { compilerPath = state.compilerPath == null ? "" : state.compilerPath; }
}
