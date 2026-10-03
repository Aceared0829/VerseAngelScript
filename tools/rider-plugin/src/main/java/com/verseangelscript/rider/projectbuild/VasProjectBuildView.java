package com.verseangelscript.rider.projectbuild;

import com.intellij.ide.trustedProjects.TrustedProjects;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Separate results, with point navigation. No duplicate editor annotator or inferred ranges. */
public final class VasProjectBuildView implements ToolWindowFactory {
    public static final String ID = "VAS Project Build";
    private final DefaultListModel<VasProjectBuildService.Item> model = new DefaultListModel<>();
    private final JLabel status = new JLabel("Use Build | Build VAS Project to compile an explicit unit");
    private final JTextArea detail = new JTextArea();
    private VasProjectBuildService.Outcome shown;

    @Override public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // Factories are extensions and may be reused across projects. Widgets and
        // displayed outcomes must belong to the concrete project's content only.
        new VasProjectBuildView().createView(project, toolWindow);
    }

    private void createView(Project project, ToolWindow toolWindow) {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JList<VasProjectBuildService.Item> list = new JList<>(model);
        list.setCellRenderer((values, item, index, selected, focus) -> {
            var diagnostic = item.diagnostic();
            JLabel label = new JLabel(diagnostic.severity() + ": " + diagnostic.message());
            label.setOpaque(true);
            label.setBackground(selected ? values.getSelectionBackground() : values.getBackground());
            label.setForeground(selected ? values.getSelectionForeground() : values.getForeground());
            return label;
        });
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        list.addListSelectionListener(event -> {
            var item = list.getSelectedValue();
            if (item != null) {
                var diagnostic = item.diagnostic();
                detail.setText(diagnostic.severity() + ": " + diagnostic.message() + "\n" + diagnostic.section()
                    + "\nNative byte position: " + diagnostic.row() + ":" + diagnostic.column()
                    + (item.location() == null ? "\nNo verified file location" : item.location().offset() < 0 ? "\nFile-level location" : ""));
            }
        });
        Runnable navigate = () -> {
            var item = list.getSelectedValue();
            if (item != null && shown != null) navigate(project, shown, item);
        };
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { if (event.getClickCount() == 2) navigate.run(); }
        });
        JButton open = new JButton("Open diagnostic");
        open.addActionListener(event -> navigate.run());
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> VasProjectBuildService.getInstance(project).cancel());
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(open); controls.add(cancel);
        JPanel header = new JPanel(new BorderLayout());
        header.add(status, BorderLayout.CENTER); header.add(controls, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);
        panel.add(new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(list), new JScrollPane(detail)), BorderLayout.CENTER);
        toolWindow.getContentManager().addContent(ContentFactory.getInstance().createContent(panel, "", false));
        project.putUserData(VIEW, this);
        var latest = VasProjectBuildService.getInstance(project).latest();
        if (latest != null) show(latest);
    }

    private static final com.intellij.openapi.util.Key<VasProjectBuildView> VIEW = com.intellij.openapi.util.Key.create("vas.projectBuildView");
    static VasProjectBuildService.Interaction interaction(Project project) {
        return new VasProjectBuildService.Interaction() {
            @Override public String select(VasProjectProtocol.Descriptor descriptor) {
                Selection dialog = new Selection(project, descriptor);
                return dialog.showAndGet() ? ((VasProjectProtocol.Unit) dialog.units.getSelectedItem()).id() : null;
            }
            @Override public void show(VasProjectBuildService.Outcome outcome) {
                ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID);
                if (toolWindow == null) return;
                toolWindow.show(() -> {
                    var view = project.getUserData(VIEW);
                    if (view != null && !project.isDisposed()
                        && VasProjectBuildService.getInstance(project).latest() == outcome) view.show(outcome);
                });
            }
        };
    }
    private void show(VasProjectBuildService.Outcome outcome) {
        shown = outcome;
        status.setText((outcome.unit().isEmpty() ? "" : outcome.unit() + ": ") + outcome.status().split("\n", 2)[0]);
        model.clear();
        outcome.items().forEach(model::addElement);
        detail.setText(outcome.status());
    }

    public static void navigate(Project project, VasProjectBuildService.Outcome outcome, VasProjectBuildService.Item item) {
        var location = item.location();
        if (location == null) return;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                var service = VasProjectBuildService.getInstance(project);
                if (!service.isCurrent(outcome) || !location.snapshot().unchanged(true)) return;
                // Refresh without holding the application read lock. Discovering an
                // unchanged native include in VFS is not a source mutation.
                var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(location.file());
                if (file == null || !service.isCurrent(outcome) || !location.snapshot().unchanged(true)) return;
                long inputRevision = service.inputRevision(outcome);
                if (inputRevision < 0) return;
                var manager = FileDocumentManager.getInstance();
                // Document preparation uses the platform's cancellable, write-priority
                // non-blocking read flow. Never hold our own blocking read action over
                // an uncached document load, and retain the returned document strongly.
                com.intellij.openapi.application.ReadAction.nonBlocking(() -> manager.getDocument(file))
                    .expireWith(project)
                    .expireWhen(() -> service.inputRevision(outcome) != inputRevision)
                    .coalesceBy(project, outcome, location.file())
                    .finishOnUiThread(com.intellij.openapi.application.ModalityState.nonModal(), document -> {
                        if (project.isDisposed() || !TrustedProjects.isProjectTrusted(project)
                            || service.inputRevision(outcome) != inputRevision || !file.isValid()
                            || document == null || manager.getCachedDocument(file) != document) return;
                        if (manager.isDocumentUnsaved(document)
                            || location.snapshot().text() != null && !document.getText().equals(location.snapshot().editorText())) return;
                        if (location.offset() < 0) new OpenFileDescriptor(project, file).navigate(true);
                        else new OpenFileDescriptor(project, file, location.offset()).navigate(true);
                    }).submit(com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService());
            } catch (Exception ignored) { /* Stale/deleted/invalid sources must not navigate to a fabricated point. */ }
        });
    }

    private static final class Selection extends DialogWrapper {
        final JComboBox<VasProjectProtocol.Unit> units;
        final VasProjectProtocol.Descriptor descriptor;
        Selection(Project project, VasProjectProtocol.Descriptor descriptor) {
            super(project);
            this.descriptor = descriptor;
            units = new JComboBox<>(descriptor.units().toArray(VasProjectProtocol.Unit[]::new));
            units.setRenderer((list, value, index, selected, focus) -> new JLabel(value == null ? "" : value.id() + "  →  " + value.entry()));
            setTitle("Build VAS Project"); setOKButtonText("Build selected unit"); init();
        }
        @Override protected @Nullable JComponent createCenterPanel() {
            JPanel panel = new JPanel(new BorderLayout(8,8));
            JTextArea explanation = new JTextArea(descriptor.project() + "\nBuilds saved files only.\n" + String.join("\n", descriptor.warnings()));
            explanation.setEditable(false);
            panel.add(explanation, BorderLayout.NORTH); panel.add(units, BorderLayout.CENTER);
            return panel;
        }
    }
}
