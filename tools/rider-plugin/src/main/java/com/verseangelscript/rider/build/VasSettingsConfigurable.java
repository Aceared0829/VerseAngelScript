package com.verseangelscript.rider.build;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBLabel;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;

public final class VasSettingsConfigurable implements Configurable {
    private final Project project;
    private JPanel panel;
    private TextFieldWithBrowseButton builderField;
    private TextFieldWithBrowseButton runnerField;
    private TextFieldWithBrowseButton configField;
    private TextFieldWithBrowseButton outputField;

    public VasSettingsConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @Nls String getDisplayName() {
        return "VAS";
    }

    @Override
    public @Nullable JComponent createComponent() {
        panel = new JPanel(new GridBagLayout());
        panel.setBorder(JBUI.Borders.empty(8));

        builderField = new TextFieldWithBrowseButton();
        runnerField = new TextFieldWithBrowseButton();
        configField = new TextFieldWithBrowseButton();
        outputField = new TextFieldWithBrowseButton();

        configureFileChooser(builderField, FileChooserDescriptorFactory.createSingleFileDescriptor());
        configureFileChooser(runnerField, FileChooserDescriptorFactory.createSingleFileDescriptor());
        configureFileChooser(configField, FileChooserDescriptorFactory.createSingleFileDescriptor());
        configureFileChooser(outputField, FileChooserDescriptorFactory.createSingleFolderDescriptor());

        addRow(0, "vasbuild executable:", builderField);
        addRow(1, "vasrun executable:", runnerField);
        addRow(2, "Interface config file:", configField);
        addRow(3, "Bytecode output directory:", outputField);

        GridBagConstraints spacer = new GridBagConstraints();
        spacer.gridx = 0;
        spacer.gridy = 4;
        spacer.gridwidth = 2;
        spacer.weighty = 1;
        spacer.fill = GridBagConstraints.VERTICAL;
        panel.add(new JPanel(), spacer);

        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        VasSettingsState settings = VasSettingsState.getInstance(project);
        return !builderField.getText().trim().equals(settings.builderPath)
            || !runnerField.getText().trim().equals(settings.runnerPath)
            || !configField.getText().trim().equals(settings.configPath)
            || !outputField.getText().trim().equals(settings.outputDirectory);
    }

    @Override
    public void apply() {
        VasSettingsState settings = VasSettingsState.getInstance(project);
        settings.builderPath = builderField.getText().trim();
        settings.runnerPath = runnerField.getText().trim();
        settings.configPath = configField.getText().trim();
        settings.outputDirectory = outputField.getText().trim();
    }

    @Override
    public void reset() {
        if (builderField == null) {
            return;
        }
        VasSettingsState settings = VasSettingsState.getInstance(project);
        builderField.setText(settings.builderPath);
        runnerField.setText(settings.runnerPath);
        configField.setText(settings.configPath);
        outputField.setText(settings.outputDirectory);
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        builderField = null;
        runnerField = null;
        configField = null;
        outputField = null;
    }

    private void addRow(int row, String label, JComponent component) {
        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.WEST;
        labelConstraints.insets = JBUI.insets(4, 0, 4, 8);
        panel.add(new JBLabel(label), labelConstraints);

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.weightx = 1;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.insets = JBUI.insets(4, 0);
        panel.add(component, fieldConstraints);
    }

    private void configureFileChooser(
        TextFieldWithBrowseButton field,
        FileChooserDescriptor descriptor
    ) {
        field.addActionListener(event -> {
            VirtualFile selected = FileChooser.chooseFile(
                descriptor,
                project,
                currentSelection(field)
            );
            if (selected != null) {
                field.setText(selected.getPath());
            }
        });
    }

    private static @Nullable VirtualFile currentSelection(TextFieldWithBrowseButton field) {
        String path = field.getText().trim();
        return path.isEmpty() ? null : LocalFileSystem.getInstance().findFileByPath(path);
    }
}
