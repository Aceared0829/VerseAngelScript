using System;

namespace VerseAngelScript.VisualStudio.Build
{
    internal static class WorkspaceEvents
    {
        internal static bool Invalidates(bool closing, string sessionRoot, string currentRoot)
        {
            if (sessionRoot == null) return false;
            // A close always ends the old operation, even if the same root is
            // reopened. Completion notifications can arrive after its root is
            // already visible, and must not cancel work prepared for that root.
            return closing || currentRoot == null || !string.Equals(
                sessionRoot.Replace('\\', '/').TrimEnd('/'), currentRoot.Replace('\\', '/').TrimEnd('/'),
                StringComparison.OrdinalIgnoreCase);
        }
    }
}
