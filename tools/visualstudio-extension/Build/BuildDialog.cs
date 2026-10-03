using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using Microsoft.VisualStudio.PlatformUI;
using Microsoft.VisualStudio.Shell;
using Microsoft.VisualStudio.Threading;

namespace VerseAngelScript.VisualStudio.Build
{
    internal sealed class BuildDialog : DialogWindow
    {
        private readonly Button read;
        private readonly Button build;
        private readonly ListBox units;
        private readonly TextBlock status;
        private readonly TextBox details;
        private bool closed;
        internal CompilationUnit SelectedUnit { get; private set; }
        internal Descriptor Descriptor { get; private set; }
        internal JoinableTask PendingRead { get; private set; }

        internal BuildDialog(string root, string manifest, string compiler, Func<Task<Descriptor>> describe, Action cancel, JoinableTaskFactory factory)
        {
            Title = "Build VAS Project";
            Width = 720; Height = 590; MinWidth = 560; MinHeight = 460;
            WindowStartupLocation = WindowStartupLocation.CenterOwner;
            AutomationProperties.SetAutomationId(this, "VasBuildProjectDialog");
            var panel = new DockPanel { Margin = new Thickness(16) };
            Content = panel;
            var footer = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
            DockPanel.SetDock(footer, Dock.Bottom); panel.Children.Add(footer);
            read = Button("Read Project Units", "ReadProjectUnits");
            build = Button("Build Selected Unit", "BuildSelectedUnit"); build.IsEnabled = false;
            var close = Button("Cancel", "CancelVasProject"); close.IsCancel = true;
            footer.Children.Add(read); footer.Children.Add(build); footer.Children.Add(close);
            var body = new StackPanel(); panel.Children.Add(body);
            body.Children.Add(Label("Workspace", root, "ProjectRoot"));
            body.Children.Add(Label("Manifest", manifest, "ProjectManifest"));
            body.Children.Add(Label("Compiler executable", compiler, "ConfiguredCompiler"));
            status = new TextBlock { Text = "Read Project Units runs this configured compiler once to describe the manifest.", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 8) };
            AutomationProperties.SetAutomationId(status, "ProjectBuildStatus"); body.Children.Add(status);
            units = new ListBox { Height = 100, DisplayMemberPath = "Id", SelectedIndex = -1 };
            AutomationProperties.SetAutomationId(units, "ProjectUnits"); body.Children.Add(units);
            details = new TextBox { IsReadOnly = true, TextWrapping = TextWrapping.Wrap, Height = 105, Margin = new Thickness(0, 8, 0, 0), VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
            AutomationProperties.SetAutomationId(details, "SelectedUnitDetails"); body.Children.Add(details);
            units.SelectionChanged += (s, e) =>
            {
                var unit = units.SelectedItem as CompilationUnit;
                build.IsEnabled = unit != null;
                details.Text = unit == null ? "" : "Unit: " + unit.Id + "\r\nEntry: " + unit.Entry + "\r\nHost API: " + unit.Config + "\r\nOutput: " + unit.Output;
            };
            read.Click += (s, e) => PendingRead = factory.RunAsync(async () =>
            {
                read.IsEnabled = false; build.IsEnabled = false; units.ItemsSource = null;
                status.Text = "Reading native project descriptor...";
                try
                {
                    var descriptor = await describe();
                    if (closed) return;
                    Descriptor = descriptor;
                    units.ItemsSource = descriptor.Units; units.SelectedIndex = -1;
                    status.Text = (descriptor.Warnings.Count == 0 ? "" : string.Join("\n", descriptor.Warnings) + "\n") + "Select a compilation unit. Build Selected Unit runs the compiler once with the paths below.";
                }
                catch (Exception ex)
                {
                    if (!closed) status.Text = ex is OperationCanceledException ? "Operation cancelled. Close and reopen to prepare a new build." : ex.Message;
                }
            });
            build.Click += (s, e) =>
            {
                SelectedUnit = units.SelectedItem as CompilationUnit;
                if (SelectedUnit != null) DialogResult = true;
            };
            Closed += (s, e) => { closed = true; if (SelectedUnit == null) cancel(); };
        }

        internal void Invalidate(string reason)
        {
            read.IsEnabled = false; build.IsEnabled = false; units.IsEnabled = false; status.Text = reason;
        }

        private static Button Button(string caption, string id)
        {
            var button = new Button { Content = caption, Margin = new Thickness(6, 12, 0, 0), Padding = new Thickness(12, 6, 12, 6) };
            AutomationProperties.SetAutomationId(button, id); return button;
        }
        private static FrameworkElement Label(string caption, string value, string id)
        {
            var panel = new StackPanel(); panel.Children.Add(new TextBlock { Text = caption, Margin = new Thickness(0, 4, 0, 2) });
            var text = new TextBox { Text = value, IsReadOnly = true }; AutomationProperties.SetAutomationId(text, id); panel.Children.Add(text); return panel;
        }
    }
}
