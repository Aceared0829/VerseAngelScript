using System;
using System.IO;

namespace VerseAngelScript.VisualStudio.Build
{
    internal enum InputEventEffect { None, Verify, Invalidate }

    internal static class InputEvents
    {
        internal static InputEventEffect Classify(string alias, string path, WatcherChangeTypes change)
        {
            alias = alias.Replace('\\', '/').TrimEnd('/');
            path = path.Replace('\\', '/').TrimEnd('/');
            if (string.Equals(alias, path, StringComparison.OrdinalIgnoreCase)) return InputEventEffect.Invalidate;
            if (!alias.StartsWith(path + "/", StringComparison.OrdinalIgnoreCase)) return InputEventEffect.None;
            // Windows reports a directory LastWrite change when a child is added.
            // Output, compiler logs and other siblings do not change our inputs.
            // Verify ancestor metadata hints against saved identities; structural
            // ancestor events still invalidate immediately, including both rename paths.
            return change == WatcherChangeTypes.Changed ? InputEventEffect.Verify : InputEventEffect.Invalidate;
        }
    }
}
