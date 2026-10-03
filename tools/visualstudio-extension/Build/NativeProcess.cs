using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace VerseAngelScript.VisualStudio.Build
{
    internal sealed class ProcessResult
    {
        public byte[] Stdout { get; }
        public string Stderr { get; }
        public int ExitCode { get; }
        internal ProcessResult(byte[] stdout, string stderr, int exitCode) { Stdout = stdout; Stderr = stderr; ExitCode = exitCode; }
    }

    /// <summary>Bounded byte pipes. Cancels only the process this invocation owns, not a process tree.</summary>
    internal static class NativeProcess
    {
        private const int StderrRetain = 16 * 1024, StderrLimit = 4 * 1024 * 1024, CleanupMilliseconds = 2000;
        public static string ValidateCompiler(string configuredPath)
        {
            if (string.IsNullOrWhiteSpace(configuredPath) || !FullyQualifiedPath(configuredPath))
                throw new IOException("Configure an absolute path to a native vasbuild executable.");
            string path = Path.GetFullPath(configuredPath);
            if (Environment.OSVersion.Platform == PlatformID.Win32NT && !path.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
                throw new IOException("The native VAS compiler must be an .exe file on Windows.");
            using (var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                var header = new byte[64]; int count = input.Read(header, 0, header.Length);
                bool pe = count >= 64 && header[0] == 'M' && header[1] == 'Z';
                if (pe)
                {
                    int offset = BitConverter.ToInt32(header, 60);
                    pe = offset >= 64 && offset <= input.Length - 4;
                    if (pe) { input.Position = offset; pe = input.ReadByte() == 'P' && input.ReadByte() == 'E' && input.ReadByte() == 0 && input.ReadByte() == 0; }
                }
                bool elf = count >= 4 && header[0] == 0x7f && header[1] == 'E' && header[2] == 'L' && header[3] == 'F';
                if (!(Environment.OSVersion.Platform == PlatformID.Win32NT ? pe : pe || elf))
                    throw new IOException("The configured VAS compiler must be a native executable, not a script or shell wrapper.");
            }
            return path;
        }

        internal static bool FullyQualifiedPath(string path)
        {
            return Environment.OSVersion.Platform == PlatformID.Win32NT
                ? WindowsFullyQualifiedPath(path) : !string.IsNullOrEmpty(path) && path.StartsWith("/", StringComparison.Ordinal);
        }
        internal static bool WindowsFullyQualifiedPath(string path)
        {
            if (string.IsNullOrEmpty(path)) return false;
            var value = path.Replace('\\', '/');
            if (value.StartsWith("//?/", StringComparison.Ordinal))
            {
                value = value.Substring(4);
                if (value.StartsWith("UNC/", StringComparison.OrdinalIgnoreCase)) value = "//" + value.Substring(4);
            }
            if (value.Length >= 3 && ((value[0] >= 'A' && value[0] <= 'Z') || (value[0] >= 'a' && value[0] <= 'z'))
                && value[1] == ':' && value[2] == '/') return true;
            if (!value.StartsWith("//", StringComparison.Ordinal)) return false;
            var pieces = value.Substring(2).Split('/');
            return pieces.Length >= 2 && pieces[0].Length > 0 && pieces[1].Length > 0
                && pieces[0] != "." && pieces[0] != "?" && pieces[0] != ".." && pieces[1] != "." && pieces[1] != "..";
        }

        /// <summary>Windows CRT quoting, never shell quoting. Always quotes each token, including empty ones.</summary>
        public static string QuoteArgument(string value)
        {
            if (value == null || value.IndexOf('\0') >= 0) throw new ArgumentException("Invalid native compiler argument.");
            var result = new StringBuilder("\""); int slashes = 0;
            foreach (char c in value)
            {
                if (c == '\\') { ++slashes; continue; }
                result.Append('\\', c == '"' ? slashes * 2 + 1 : slashes); result.Append(c); slashes = 0;
            }
            return result.Append('\\', slashes * 2).Append('"').ToString();
        }
        public static string Arguments(IEnumerable<string> args) { return string.Join(" ", args.Select(QuoteArgument)); }

        /// <summary>The guard and line sink run on background threads; both must return promptly.</summary>
        public static Task<ProcessResult> RunAsync(string executable, IReadOnlyList<string> args, string cwd,
            TimeSpan timeout, long stdoutLimit, CancellationToken cancellationToken, Action guard = null, Action<byte[]> lineSink = null)
        {
            if (args == null) throw new ArgumentNullException(nameof(args));
            // Copy mutable callers' arrays before queuing work.
            string[] copy = args.ToArray();
            return Task.Run(() => Run(executable, copy, cwd, timeout, stdoutLimit, cancellationToken, guard, lineSink), cancellationToken);
        }

        private static ProcessResult Run(string executable, IReadOnlyList<string> args, string cwd,
            TimeSpan timeout, long stdoutLimit, CancellationToken token, Action guard, Action<byte[]> sink)
        {
            if (!FullyQualifiedPath(executable) || !FullyQualifiedPath(cwd)) throw new IOException("Compiler and working-directory paths must be absolute.");
            if (timeout <= TimeSpan.Zero || stdoutLimit < 0 || stdoutLimit > int.MaxValue) throw new ArgumentOutOfRangeException(nameof(timeout));
            token.ThrowIfCancellationRequested(); guard?.Invoke();
            var process = new Process { StartInfo = new ProcessStartInfo
            {
                FileName = executable, Arguments = Arguments(args), WorkingDirectory = cwd,
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true,
                RedirectStandardOutput = true, RedirectStandardError = true
            } };
            var capture = new Capture(stdoutLimit, sink);
            Thread stdout = null, stderr = null; bool launched = false;
            Exception primary = null;
            try
            {
                token.ThrowIfCancellationRequested();
                launched = process.Start();
                if (!launched) throw new IOException("The native VAS compiler could not be started.");
                var elapsed = Stopwatch.StartNew();
                // Never touch StreamReader text decoding. Framing is over the original bytes.
                stdout = Reader("VAS stdout", process.StandardOutput.BaseStream, capture.ReadStdout, capture);
                stderr = Reader("VAS stderr", process.StandardError.BaseStream, capture.ReadStderr, capture);
                stdout.Start(); stderr.Start(); process.StandardInput.Close();
                while (true)
                {
                    token.ThrowIfCancellationRequested(); guard?.Invoke(); capture.ThrowFailure();
                    if (process.HasExited && !stdout.IsAlive && !stderr.IsAlive)
                    {
                        capture.ThrowFailure(); token.ThrowIfCancellationRequested(); guard?.Invoke();
                        return new ProcessResult(capture.Output.ToArray(), capture.ErrorText(), process.ExitCode);
                    }
                    if (elapsed.Elapsed >= timeout) throw new IOException("VAS compiler timed out after " + timeout.TotalSeconds + " seconds.");
                    // HasExited is insufficient while another process retains a pipe handle.
                    // Keep waiting for both EOFs, under the same invocation deadline.
                    token.WaitHandle.WaitOne(25);
                }
            }
            catch (Exception e) { primary = e; throw; }
            finally
            {
                capture.Stop();
                Exception cleanup = null;
                if (launched)
                {
                    try { if (!process.HasExited) process.Kill(); }
                    catch (InvalidOperationException) { }
                    catch (Exception e) { cleanup = e; }
                    var elapsed = Stopwatch.StartNew();
                    while (elapsed.ElapsedMilliseconds < CleanupMilliseconds)
                    {
                        bool alive;
                        try { alive = !process.HasExited; } catch (InvalidOperationException) { alive = false; }
                        if (!alive && (stdout == null || !stdout.IsAlive) && (stderr == null || !stderr.IsAlive)) break;
                        Thread.Sleep(10);
                    }
                    if ((stdout != null && stdout.IsAlive) || (stderr != null && stderr.IsAlive))
                        cleanup = new IOException("VAS output pipes did not close during bounded cleanup; the owned process was stopped.", cleanup);
                }
                // Closing a pipe held by another process can block behind a synchronous reader.
                // Delegate disposal to a background thread when readers outlive cleanup.
                if ((stdout != null && stdout.IsAlive) || (stderr != null && stderr.IsAlive))
                    new Thread(() => { try { process.Dispose(); } catch { } }) { IsBackground = true, Name = "VAS pipe disposal" }.Start();
                else process.Dispose();
                if (cleanup != null && primary == null) throw new IOException("Could not clean up VAS compiler invocation.", cleanup);
                if (cleanup != null && primary != null) primary.Data["VAS cleanup"] = cleanup.Message;
            }
        }
        private static Thread Reader(string name, Stream input, Action<Stream> drain, Capture capture)
        {
            return new Thread(() =>
            {
                try { using (input) drain(input); }
                catch (Exception e) { capture.SetFailure(e); }
            }) { Name = name, IsBackground = true };
        }
        private sealed class Capture
        {
            internal readonly MemoryStream Output = new MemoryStream(), Error = new MemoryStream();
            private readonly long limit; private readonly Action<byte[]> sink;
            private Exception failure; private int stopping;
            internal Capture(long limit, Action<byte[]> sink) { this.limit = limit; this.sink = sink; }
            internal void Stop() { Interlocked.Exchange(ref stopping, 1); }
            internal void SetFailure(Exception value) { Interlocked.CompareExchange(ref failure, value, null); }
            internal void ThrowFailure() { var value = Volatile.Read(ref failure); if (value != null) throw new IOException("Invalid or unreadable VAS compiler output: " + value.Message, value); }
            internal void ReadStdout(Stream input)
            {
                var buffer = new byte[8192]; var line = sink == null ? null : new byte[Protocol.MaxRecordBytes];
                long total = 0; int length = 0, count;
                while (Volatile.Read(ref stopping) == 0 && (count = input.Read(buffer, 0, buffer.Length)) != 0)
                {
                    if (count > limit - total) throw new IOException("VAS compiler stdout exceeded its byte limit."); total += count;
                    if (sink == null) { Output.Write(buffer, 0, count); continue; }
                    for (int i = 0; i < count; ++i)
                    {
                        if (Volatile.Read(ref stopping) != 0) return;
                        if (buffer[i] == 10)
                        { var record = new byte[length]; Buffer.BlockCopy(line, 0, record, 0, length); sink(record); length = 0; }
                        else { if (length == line.Length) throw new IOException("VAS compiler report line exceeded 1 MiB."); line[length++] = buffer[i]; }
                    }
                }
                if (length != 0 && Volatile.Read(ref stopping) == 0) throw new IOException("Truncated VAS compiler output (missing final LF).");
            }
            internal string ErrorText()
            {
                byte[] bytes = Error.ToArray(); var utf8 = new UTF8Encoding(false, true);
                // The retained prefix may end inside a valid scalar. Full-stream
                // validation below already checked every byte, including the tail.
                for (int trimmed = 0; trimmed <= 3 && trimmed <= bytes.Length; ++trimmed)
                {
                    try { return utf8.GetString(bytes, 0, bytes.Length - trimmed); }
                    catch (DecoderFallbackException) { }
                }
                throw new IOException("Invalid UTF-8 retained from VAS stderr.");
            }
            internal void ReadStderr(Stream input)
            {
                var buffer = new byte[8192]; var chars = new char[8194];
                var decoder = new UTF8Encoding(false, true).GetDecoder();
                long total = 0; int count;
                while (Volatile.Read(ref stopping) == 0 && (count = input.Read(buffer, 0, buffer.Length)) != 0)
                {
                    if (count > StderrLimit - total) throw new IOException("VAS compiler stderr exceeded 4 MiB."); total += count;
                    decoder.GetChars(buffer, 0, count, chars, 0, false);
                    Error.Write(buffer, 0, Math.Min(count, StderrRetain - (int)Error.Length));
                }
                if (Volatile.Read(ref stopping) == 0) decoder.GetChars(new byte[0], 0, 0, chars, 0, true);
            }
        }
    }
}
