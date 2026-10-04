using System;
using System.ComponentModel;
using System.ComponentModel.Design;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.VisualStudio.Shell;
using Microsoft.VisualStudio.Settings;
using Microsoft.VisualStudio.Shell.Settings;
using VerseAngelScript.VisualStudio.Build;
using Task = System.Threading.Tasks.Task;

namespace VerseAngelScript.VisualStudio
{
    [PackageRegistration(UseManagedResourcesOnly = true, AllowsBackgroundLoading = true)]
    [ProvideMenuResource("Menus.ctmenu", 1)]
    [ProvideOptionPage(typeof(BuildOptions), "VerseAngelScript", "Toolchain", 0, 0, true)]
    [Guid(PackageGuid)]
    public sealed class VasPackage : AsyncPackage
    {
        public const string PackageGuid = "d3a6e112-5f40-4df1-8bb7-0b79f0e74226";
        public const string CommandSetGuid = "540e5ef6-e458-48d9-941d-990c7cb7da39";
        public const int BuildCommandId = 0x0100;
        public const int CancelCommandId = 0x0101;
        internal BuildCoordinator Coordinator { get; private set; }

        protected override async Task InitializeAsync(CancellationToken cancellationToken, IProgress<ServiceProgressData> progress)
        {
            await JoinableTaskFactory.SwitchToMainThreadAsync(cancellationToken);
            Coordinator = new BuildCoordinator(this, DisposalToken);
            var commands = (OleMenuCommandService)await GetServiceAsync(typeof(IMenuCommandService));
            if (commands == null) throw new InvalidOperationException("Visual Studio command service is unavailable.");
            commands.AddCommand(new OleMenuCommand((s, e) => JoinableTaskFactory.RunAsync(Coordinator.ShowBuildAsync).FileAndForget("VerseAngelScript/BuildProject"),
                new CommandID(new Guid(CommandSetGuid), BuildCommandId)));
            commands.AddCommand(new OleMenuCommand((s, e) => Coordinator.Cancel("Build cancelled"),
                new CommandID(new Guid(CommandSetGuid), CancelCommandId)));
        }

        internal string ReadCompilerPath()
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            var settings = new ShellSettingsManager(this).GetReadOnlySettingsStore(SettingsScope.UserSettings);
            return settings.CollectionExists(BuildOptions.Collection) && settings.PropertyExists(BuildOptions.Collection, "CompilerPath")
                ? settings.GetString(BuildOptions.Collection, "CompilerPath") : string.Empty;
        }

        protected override void Dispose(bool disposing)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            if (disposing) Coordinator?.Dispose();
            base.Dispose(disposing);
        }
    }

    [Guid("c7077791-e6e9-4ab6-a111-a5d4257cf236")]
    public sealed class BuildOptions : DialogPage
    {
        internal const string Collection = "VerseAngelScript/Toolchain";
        [Category("Compiler")]
        [DisplayName("Compiler executable")]
        [Description("Absolute path to your native vasbuild.exe. Project manifests never select an executable.")]
        public string CompilerPath { get; set; } = string.Empty;

        public override void LoadSettingsFromStorage()
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            var store = new ShellSettingsManager(Site).GetReadOnlySettingsStore(SettingsScope.UserSettings);
            CompilerPath = store.CollectionExists(Collection) && store.PropertyExists(Collection, "CompilerPath")
                ? store.GetString(Collection, "CompilerPath") : string.Empty;
        }
        public override void SaveSettingsToStorage()
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            var store = new ShellSettingsManager(Site).GetWritableSettingsStore(SettingsScope.UserSettings);
            if (!store.CollectionExists(Collection)) store.CreateCollection(Collection);
            store.SetString(Collection, "CompilerPath", CompilerPath ?? string.Empty);
        }
    }
}
