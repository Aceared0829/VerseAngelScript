using System;
using Microsoft.VisualStudio;
using Microsoft.VisualStudio.Shell;
using Microsoft.VisualStudio.Shell.Interop;

namespace VerseAngelScript.VisualStudio.Build
{
    // Public VS18 solution/folder events. Opening a root never grants native trust.
    internal sealed class HostEvents : IVsSolutionEvents, IVsSolutionEvents7, IVsRunningDocTableEvents, IDisposable
    {
        private readonly Action<bool, string> workspaceChanged;
        private readonly Action documentsChanged;
        private readonly IVsSolution solution;
        private readonly IVsRunningDocumentTable documents;
        private readonly uint solutionCookie, documentCookie;
        internal HostEvents(Action<bool, string> workspaceChanged, Action documentsChanged)
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            this.workspaceChanged = workspaceChanged; this.documentsChanged = documentsChanged;
            solution = (IVsSolution)Package.GetGlobalService(typeof(SVsSolution));
            documents = (IVsRunningDocumentTable)Package.GetGlobalService(typeof(SVsRunningDocumentTable));
            ErrorHandler.ThrowOnFailure(solution.AdviseSolutionEvents(this, out solutionCookie));
            try { ErrorHandler.ThrowOnFailure(documents.AdviseRunningDocTableEvents(this, out documentCookie)); }
            catch { solution.UnadviseSolutionEvents(solutionCookie); throw; }
        }
        public void Dispose()
        {
            ThreadHelper.ThrowIfNotOnUIThread();
            solution.UnadviseSolutionEvents(solutionCookie); documents.UnadviseRunningDocTableEvents(documentCookie);
        }
        public void OnAfterOpenFolder(string path) => workspaceChanged(false, nameof(OnAfterOpenFolder));
        public void OnBeforeCloseFolder(string path) => workspaceChanged(true, nameof(OnBeforeCloseFolder));
        public void OnQueryCloseFolder(string path, ref int cancel) { }
        public void OnAfterCloseFolder(string path) => workspaceChanged(false, nameof(OnAfterCloseFolder));
        public void OnAfterLoadAllDeferredProjects() { }
        public int OnAfterOpenSolution(object reserved, int isNew) { workspaceChanged(false, nameof(OnAfterOpenSolution)); return VSConstants.S_OK; }
        public int OnBeforeCloseSolution(object reserved) { workspaceChanged(true, nameof(OnBeforeCloseSolution)); return VSConstants.S_OK; }
        public int OnAfterCloseSolution(object reserved) { workspaceChanged(false, nameof(OnAfterCloseSolution)); return VSConstants.S_OK; }
        public int OnAfterOpenProject(IVsHierarchy hierarchy, int added) => VSConstants.S_OK;
        public int OnQueryCloseProject(IVsHierarchy hierarchy, int removing, ref int cancel) => VSConstants.S_OK;
        public int OnBeforeCloseProject(IVsHierarchy hierarchy, int removed) => VSConstants.S_OK;
        public int OnAfterLoadProject(IVsHierarchy stub, IVsHierarchy real) => VSConstants.S_OK;
        public int OnQueryUnloadProject(IVsHierarchy real, ref int cancel) => VSConstants.S_OK;
        public int OnBeforeUnloadProject(IVsHierarchy real, IVsHierarchy stub) => VSConstants.S_OK;
        public int OnQueryCloseSolution(object reserved, ref int cancel) => VSConstants.S_OK;
        public int OnAfterFirstDocumentLock(uint cookie, uint lockType, uint readLocks, uint editLocks) { documentsChanged(); return VSConstants.S_OK; }
        public int OnBeforeLastDocumentUnlock(uint cookie, uint lockType, uint readLocks, uint editLocks) { documentsChanged(); return VSConstants.S_OK; }
        public int OnAfterSave(uint cookie) { documentsChanged(); return VSConstants.S_OK; }
        public int OnAfterAttributeChange(uint cookie, uint attributes) { documentsChanged(); return VSConstants.S_OK; }
        public int OnBeforeDocumentWindowShow(uint cookie, int firstShow, IVsWindowFrame frame) => VSConstants.S_OK;
        public int OnAfterDocumentWindowHide(uint cookie, IVsWindowFrame frame) => VSConstants.S_OK;
    }
}
