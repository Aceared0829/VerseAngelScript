using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using VerseAngelScript.VisualStudio.Tests;

internal static class FixtureMutationTests
{
    private static readonly TimeSpan Budget = TimeSpan.FromSeconds(5);
    private static readonly Encoding Utf8 = new UTF8Encoding(false, true);
    internal static void Run(Action<bool, string> check) => RunAsync(check).GetAwaiter().GetResult();

    private static async Task RunAsync(Action<bool, string> check)
    {
        var stream = new RecordingStream();
        int opens = 0, conflicts = 0;
        await FixtureMutation.AppendOnceAsync("fixture", "\n漢😀", Budget, CancellationToken.None,
            error => { check(FixtureMutation.IsSharingConflict(error), "Only verified sharing conflicts notify acquisition retry"); ++conflicts; },
            path => { ++opens; if (opens <= 2) throw Sharing(opens == 1 ? 32 : 33); return stream; });
        check(opens == 3 && conflicts == 2, "Both exact Windows sharing/lock errors retry acquisition");
        check(stream.Writes == 1 && stream.Flushes == 1 && stream.Disposals == 1, "Acquired fixture is mutated, flushed and disposed once");
        check(Utf8.GetString(stream.ToArray()) == "before\n漢😀", "Retry produces exactly one UTF-8 append without a BOM");

        foreach (var failure in new Exception[] {
            new IOException("being used by another process"),
            new IOException("Wrong HRESULT facility", unchecked((int)0x80130020)),
            new IOException("I/O data error", unchecked((int)0x80070017)),
            new UnauthorizedAccessException("Denied") })
        {
            opens = 0;
            var actual = await Failure(() => FixtureMutation.AppendOnceAsync("fixture", "!", Budget, CancellationToken.None,
                open: path => { ++opens; throw failure; }));
            check(ReferenceEquals(actual, failure) && opens == 1, "Nonsharing acquisition failure propagates without retry: " + failure.GetType().Name);
        }

        foreach (var stage in new[] { "seek", "write", "flush", "dispose" })
        {
            var failure = Sharing(32);
            stream = new RecordingStream { FailStage = stage, Failure = failure };
            opens = 0;
            var actual = await Failure(() => FixtureMutation.AppendOnceAsync("fixture", "!mutation", Budget, CancellationToken.None,
                open: path => { ++opens; return stream; }));
            check(ReferenceEquals(actual, failure) && opens == 1, "Post-open " + stage + " error is never retried, even with sharing HRESULT");
            check(stream.Writes == (stage == "seek" ? 0 : 1), "Post-open " + stage + " cannot replay mutation");
            check(Utf8.GetString(stream.ToArray()) == (stage == "seek" ? "before" : stage == "write" ? "before!" : "before!mutation"), "Partial/complete " + stage + " mutation retained exactly once");
            check(stream.Disposals == 1, "Post-open " + stage + " disposes owned stream");
        }

        var elapsed = TimeSpan.Zero;
        opens = 0;
        var locked = Sharing(33);
        var timedOut = await Failure(() => FixtureMutation.AppendOnceAsync("fixture", "!", Budget, CancellationToken.None,
            open: path => { ++opens; elapsed += TimeSpan.FromTicks(Budget.Ticks / 2); throw locked; }, elapsed: () => elapsed));
        check(timedOut is TimeoutException && ReferenceEquals(timedOut.InnerException, locked) && opens == 2, "Retries share one bounded deadline and preserve their last verified conflict");

        elapsed = TimeSpan.Zero;
        stream = new RecordingStream();
        timedOut = await Failure(() => FixtureMutation.AppendOnceAsync("fixture", "!", Budget, CancellationToken.None,
            open: path => { elapsed = Budget; return stream; }, elapsed: () => elapsed));
        check(timedOut is TimeoutException && stream.Writes == 0 && stream.Disposals == 1, "Late acquisition is disposed without writing after the deadline");

        using (var cancel = new CancellationTokenSource())
        {
            cancel.Cancel(); opens = 0;
            var cancelled = await Failure(() => FixtureMutation.AppendOnceAsync("fixture", "!", Budget, cancel.Token,
                open: path => { ++opens; return new RecordingStream(); }));
            check(cancelled is OperationCanceledException && opens == 0, "Pre-cancelled mutation never opens the fixture");
        }
        using (var cancel = new CancellationTokenSource())
        {
            opens = 0;
            var cancelled = await Failure(() => FixtureMutation.AppendOnceAsync("fixture", "!", Budget, cancel.Token,
                contention: error => cancel.Cancel(), open: path => { ++opens; throw Sharing(32); }));
            check(cancelled is OperationCanceledException && opens == 1, "Cancellation stops acquisition retries before any write");
        }
        using (var cancel = new CancellationTokenSource())
        {
            stream = new RecordingStream();
            var cancelled = await Failure(() => FixtureMutation.AppendOnceAsync("fixture", "!", Budget, cancel.Token,
                open: path => { cancel.Cancel(); return stream; }));
            check(cancelled is OperationCanceledException && stream.Writes == 0 && stream.Disposals == 1, "Cancellation after acquisition disposes without mutation");
        }

        string root = Path.Combine(Path.GetTempPath(), "vas-fixture-mutation-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            string path = Path.Combine(root, "existing.txt");
            File.WriteAllText(path, "before", Utf8);
            await FixtureMutation.AppendOnceAsync(path, "!", Budget, CancellationToken.None);
            check(File.ReadAllText(path, Utf8) == "before!", "Real file receives exactly one append");
            string missing = Path.Combine(root, "missing.txt");
            var missingError = await Failure(() => FixtureMutation.AppendOnceAsync(missing, "!", Budget, CancellationToken.None));
            check(missingError is FileNotFoundException && !File.Exists(missing), "Missing fixture fails rather than being silently created");
            if (Environment.OSVersion.Platform == PlatformID.Win32NT) await WindowsReadLease(path, check);
            else Console.WriteLine("SKIP real Windows fixture sharing handles: non-Windows host.");
        }
        finally { Directory.Delete(root, true); }
    }

    private static async Task WindowsReadLease(string path, Action<bool, string> check)
    {
        var before = File.ReadAllBytes(path);
        var conflict = new TaskCompletionSource<IOException>(TaskCreationOptions.RunContinuationsAsynchronously);
        Task append;
        using (var cancel = new CancellationTokenSource())
        {
            var readLease = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read);
            append = FixtureMutation.AppendOnceAsync(path, "!", Budget, cancel.Token, error => conflict.TrySetResult(error));
            try
            {
                var first = await Task.WhenAny(conflict.Task, append);
                if (first == append) await append;
                check(ReferenceEquals(first, conflict.Task), "Held ShareRead lease reaches verified acquisition contention");
                check(FixtureMutation.IsSharingConflict(await conflict.Task) && !append.IsCompleted, "Read lease prevents early mutation");
                check(File.ReadAllBytes(path).SequenceEqual(before), "Bytes unchanged while write access is denied");
                readLease.Dispose(); readLease = null;
                await append;
            }
            finally
            {
                cancel.Cancel();
                readLease?.Dispose();
                try { await append; }
                catch (OperationCanceledException) when (cancel.IsCancellationRequested) { }
            }
        }
        check(File.ReadAllBytes(path).SequenceEqual(before.Concat(new[] { (byte)'!' })), "Release admits one real Windows append");

        before = File.ReadAllBytes(path);
        using (var readLease = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
        using (var cancel = new CancellationTokenSource())
        {
            conflict = new TaskCompletionSource<IOException>(TaskCreationOptions.RunContinuationsAsynchronously);
            append = FixtureMutation.AppendOnceAsync(path, "!", Budget, cancel.Token, error => conflict.TrySetResult(error));
            check(ReferenceEquals(await Task.WhenAny(conflict.Task, append), conflict.Task), "Real cancellation case reaches held read lease");
            cancel.Cancel();
            check(await Failure(() => append) is OperationCanceledException, "Real sharing wait cancels without mutation");
            check(File.ReadAllBytes(path).SequenceEqual(before), "Cancelled real acquisition preserves exact bytes");
        }
        using (var readLease = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
        {
            long expired = 0;
            var error = await Failure(() => FixtureMutation.AppendOnceAsync(path, "!", Budget, CancellationToken.None,
                contention: failure => Interlocked.Exchange(ref expired, 1),
                elapsed: () => Interlocked.Read(ref expired) == 0 ? TimeSpan.Zero : Budget));
            check(error is TimeoutException && error.InnerException is IOException sharing && FixtureMutation.IsSharingConflict(sharing), "Real Windows sharing conflict reaches the deterministic deadline");
            check(File.ReadAllBytes(path).SequenceEqual(before), "Timed out real acquisition preserves exact bytes");
        }
    }

    private static IOException Sharing(int code) => new IOException("Controlled sharing conflict", unchecked((int)(0x80070000u + code)));
    private static async Task<Exception> Failure(Func<Task> action)
    {
        try { await action(); }
        catch (Exception error) { return error; }
        throw new Exception("Expected fixture mutation failure was accepted.");
    }

    private sealed class RecordingStream : MemoryStream
    {
        internal int Writes, Flushes, Disposals;
        internal string FailStage;
        internal Exception Failure;
        internal RecordingStream() { var bytes = Utf8.GetBytes("before"); base.Write(bytes, 0, bytes.Length); }
        public override long Seek(long offset, SeekOrigin origin)
        { if (FailStage == "seek") throw Failure; return base.Seek(offset, origin); }
        public override void Write(byte[] buffer, int offset, int count)
        {
            ++Writes;
            // Simulate a failure after a partial write, not just an exception
            // before any bytes: replaying the operation would corrupt the fixture.
            base.Write(buffer, offset, FailStage == "write" ? Math.Min(1, count) : count);
            if (FailStage == "write") throw Failure;
        }
        public override void Flush() { ++Flushes; if (FailStage == "flush") throw Failure; base.Flush(); }
        protected override void Dispose(bool disposing)
        { ++Disposals; base.Dispose(disposing); if (FailStage == "dispose") throw Failure; }
    }
}
