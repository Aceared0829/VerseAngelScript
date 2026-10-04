using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace VerseAngelScript.VisualStudio.Tests
{
    // Test fixtures sometimes overlap the production reader's short ShareRead
    // snapshot. Acquire an existing file once, then perform exactly one append.
    // This helper is compiled into test executables only, never the VSIX.
    internal static class FixtureMutation
    {
        internal static bool IsSharingConflict(IOException error) =>
            error.HResult == unchecked((int)0x80070020) || error.HResult == unchecked((int)0x80070021);

        internal static Task AppendOnceAsync(string path, string suffix, TimeSpan timeout, CancellationToken cancellation,
            Action<IOException> contention = null, Func<string, Stream> open = null, Func<TimeSpan> elapsed = null)
        {
            if (timeout <= TimeSpan.Zero) throw new ArgumentOutOfRangeException(nameof(timeout));
            var bytes = new UTF8Encoding(false, true).GetBytes(suffix);
            var clock = Stopwatch.StartNew();
            var age = elapsed ?? (() => clock.Elapsed);
            var acquire = open ?? (name => new FileStream(name, FileMode.Open, FileAccess.Write, FileShare.Read));
            return Task.Run(async () =>
            {
                IOException lastConflict = null;
                Stream stream;
                while (true)
                {
                    cancellation.ThrowIfCancellationRequested();
                    if (age() >= timeout) throw new TimeoutException("Timed out acquiring fixture write access: " + path, lastConflict);
                    try { stream = acquire(path); }
                    catch (IOException error) when (IsSharingConflict(error))
                    {
                        lastConflict = error;
                        contention?.Invoke(error);
                        // Requeue only after a verified pre-write sharing conflict.
                        // Yield the worker between attempts without resetting the
                        // original deadline or assuming the reader has released it.
                        var remaining = timeout - age();
                        if (remaining > TimeSpan.Zero)
                            await Task.Delay(remaining < TimeSpan.FromMilliseconds(10) ? remaining : TimeSpan.FromMilliseconds(10), cancellation);
                        continue;
                    }
                    break;
                }
                using (stream)
                {
                    cancellation.ThrowIfCancellationRequested();
                    if (age() >= timeout) throw new TimeoutException("Fixture write access arrived after its deadline: " + path, lastConflict);
                    // Never retry any operation after acquisition: a write or flush
                    // failure can follow a partial mutation, even with a lock HRESULT.
                    stream.Seek(0, SeekOrigin.End);
                    stream.Write(bytes, 0, bytes.Length);
                    stream.Flush();
                }
            }, cancellation);
        }
    }
}
