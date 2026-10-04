using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace VerseAngelScript.VisualStudio.Build
{
    internal sealed class FileSnapshot
    {
        public string Path { get; }
        public string CanonicalPath { get; }
        public string PhysicalKey { get; }
        public long Length { get { return Bytes.LongLength; } }
        public DateTime LastWriteUtc { get; }
        public string Sha256 { get; }
        public byte[] Bytes { get; }
        public string Text { get; }
        internal string Stamp { get; }
        internal FileSnapshot(string path, string canonical, string key, DateTime writeTime, string stamp, byte[] bytes)
        {
            Path = path; CanonicalPath = canonical; PhysicalKey = key; LastWriteUtc = writeTime; Stamp = stamp; Bytes = bytes;
            using (var sha = SHA256.Create()) Sha256 = BitConverter.ToString(sha.ComputeHash(bytes)).Replace("-", "").ToLowerInvariant();
            try { Text = new UTF8Encoding(false, true).GetString(bytes); } catch (DecoderFallbackException) { Text = null; }
        }
        public bool MatchesProof(SourceProof proof) { return proof != null && Length == proof.ByteLength && Sha256 == proof.Sha256; }
        public bool Matches() { return FileIdentity.Matches(this); }

        /// <summary>Exact point only. Native rows follow LF; columns count UTF-8 bytes including a BOM.</summary>
        public bool TryPosition(int row, int byteColumn, out int line, out int utf16Column)
        {
            line = utf16Column = 0;
            if (Text == null || Text.IndexOfAny(new[] { '\0', '\u0085', '\u2028', '\u2029' }) >= 0 || row <= 0 || byteColumn <= 0) return false;
            // Native source rows use LF only; VS also recognizes lone CR as a
            // line separator. Such a file has no safe direct line mapping.
            for (int i = 0; i < Bytes.Length; ++i)
                if (Bytes[i] == 13 && (i + 1 == Bytes.Length || Bytes[i + 1] != 10)) return false;
            int start = 0;
            for (int r = 1; r < row; ++r)
            {
                int newline = Array.IndexOf(Bytes, (byte)10, start);
                if (newline < 0) return false;
                start = newline + 1;
            }
            long target = (long)start + byteColumn - 1;
            if (target > Bytes.Length) return false;
            int column = 0;
            for (int at = start; at < target;)
            {
                if (Bytes[at] == 10) return false;
                int width = Protocol.ScalarLength(Bytes, at);
                if (width == 0 || at + width > target) return false;
                // VS removes the initial encoding marker from the text buffer.
                if (!(at == 0 && width == 3 && Bytes[0] == 0xef && Bytes[1] == 0xbb && Bytes[2] == 0xbf)) column += width == 4 ? 2 : 1;
                at += width;
            }
            // A position after CR but before LF is a physical byte boundary, not a
            // position in the visible line text. Leave it unbound rather than clamp.
            if (target > start && Bytes[target - 1] == 13 && target < Bytes.Length && Bytes[target] == 10) return false;
            line = row - 1; utf16Column = column; return true;
        }
    }

    /// <summary>Windows handle observations. These are client observations, not compiler handle identities.</summary>
    internal static class FileIdentity
    {
        public const int MaxInputBytes = 16 * 1024 * 1024;
        private const uint GenericRead = 0x80000000, ShareRead = 1, ShareWrite = 2, ShareDelete = 4,
            OpenExisting = 3, BackupSemantics = 0x02000000, SequentialScan = 0x08000000;
        public static FileSnapshot Observe(string path, int maxBytes = MaxInputBytes)
        {
            Windows();
            if (maxBytes < 0) throw new ArgumentOutOfRangeException(nameof(maxBytes));
            string absolute = System.IO.Path.GetFullPath(path);
            // Share-read only holds writes/replacements off this file while capturing.
            // A concurrent existing writer causes a failure, never a speculative proof.
            using (var handle = Open(absolute, GenericRead, ShareRead, SequentialScan))
            {
                var before = Metadata(handle);
                if (before.Length > maxBytes) throw new IOException("VAS input exceeds the " + maxBytes + " byte client limit: " + absolute);
                string canonical = FinalPath(handle), key = Physical(handle);
                byte[] bytes;
                using (var stream = new FileStream(handle, FileAccess.Read, 8192, false))
                using (var result = new MemoryStream((int)before.Length))
                {
                    var buffer = new byte[8192]; int count;
                    while ((count = stream.Read(buffer, 0, buffer.Length)) != 0)
                    {
                        if (count > maxBytes - result.Length) throw new IOException("VAS input grew beyond its client limit: " + absolute);
                        result.Write(buffer, 0, count);
                    }
                    var after = Metadata(handle);
                    if (before.Stamp != after.Stamp || result.Length != after.Length || Physical(handle) != key)
                        throw new IOException("VAS input changed while reading: " + absolute);
                    // Detect a retargeted parent junction even while the original file
                    // remains locked. Never equate a lexical path with a physical file.
                    using (var current = Open(absolute, 0, ShareRead | ShareWrite | ShareDelete, BackupSemantics))
                        if (Physical(current) != key || FinalPath(current) != canonical)
                            throw new IOException("VAS input path changed while reading: " + absolute);
                    bytes = result.ToArray();
                }
                return new FileSnapshot(absolute, canonical, key, before.LastWriteUtc, before.Stamp, bytes);
            }
        }
        public static bool Matches(FileSnapshot snapshot)
        {
            if (snapshot == null) return false;
            try
            {
                var now = Observe(snapshot.Path);
                return snapshot.PhysicalKey == now.PhysicalKey && snapshot.CanonicalPath == now.CanonicalPath
                    && snapshot.Stamp == now.Stamp && snapshot.Length == now.Length && snapshot.Sha256 == now.Sha256;
            }
            catch (IOException) { return false; }
            catch (UnauthorizedAccessException) { return false; }
        }
        public static bool SameFile(string first, string second)
        {
            Windows();
            using (var a = Open(System.IO.Path.GetFullPath(first), 0, ShareRead | ShareWrite | ShareDelete, BackupSemantics))
            using (var b = Open(System.IO.Path.GetFullPath(second), 0, ShareRead | ShareWrite | ShareDelete, BackupSemantics))
                return Physical(a) == Physical(b);
        }
        public static IReadOnlyList<string> WatchAliases(string path)
        {
            Windows();
            string absolute = System.IO.Path.GetFullPath(path), ancestor = absolute;
            var missing = new Stack<string>();
            while (true)
            {
                using (var handle = CreateFile(ancestor, 0, ShareRead | ShareWrite | ShareDelete, IntPtr.Zero, OpenExisting, BackupSemantics, IntPtr.Zero))
                {
                    if (!handle.IsInvalid)
                    {
                        string canonical = FinalPath(handle);
                        while (missing.Count > 0) canonical = System.IO.Path.Combine(canonical, missing.Pop());
                        return string.Equals(absolute, canonical, StringComparison.OrdinalIgnoreCase)
                            ? new[] { absolute } : new[] { absolute, canonical };
                    }
                    int error = Marshal.GetLastWin32Error();
                    // Only actual absence permits walking upward. Access denial and
                    // malformed/non-file paths are not evidence of a missing include.
                    if (error != 2 && error != 3) throw Error("Cannot resolve VAS watch path", ancestor, error);
                }
                string parent = System.IO.Path.GetDirectoryName(ancestor);
                if (string.IsNullOrEmpty(parent) || parent == ancestor) throw new IOException("No existing ancestor for VAS watch path: " + absolute);
                missing.Push(System.IO.Path.GetFileName(ancestor)); ancestor = parent;
            }
        }
        public static string ResolveObservedPath(string cwd, PathIdentity identity)
        {
            if (identity == null || !identity.Bindable) throw new IOException("Compiler path has invalid filename bytes and cannot be bound to a Unicode editor.");
            return System.IO.Path.GetFullPath(System.IO.Path.IsPathRooted(identity.Display) ? identity.Display : System.IO.Path.Combine(cwd, identity.Display));
        }
        private static void Windows()
        { if (Environment.OSVersion.Platform != PlatformID.Win32NT) throw new PlatformNotSupportedException("Windows handle identity requires Windows; no lexical identity fallback is used."); }
        private static SafeFileHandle Open(string path, uint access, uint share, uint flags)
        {
            var handle = CreateFile(path, access, share, IntPtr.Zero, OpenExisting, flags, IntPtr.Zero);
            if (handle.IsInvalid)
            {
                int error = Marshal.GetLastWin32Error(); handle.Dispose();
                if (error == 2) throw new FileNotFoundException("VAS input does not exist.", path);
                if (error == 3) throw new DirectoryNotFoundException("VAS input parent does not exist: " + path);
                if (error == 5) throw new UnauthorizedAccessException("Access denied to VAS input: " + path);
                throw Error("Cannot open VAS input", path, error);
            }
            return handle;
        }
        private static IOException Error(string message, string path, int code)
        { return new IOException(message + ": " + path + " (" + new Win32Exception(code).Message + ")", new Win32Exception(code)); }
        private sealed class Info
        {
            public long Length; public DateTime LastWriteUtc; public string Stamp;
        }
        private static Info Metadata(SafeFileHandle handle)
        {
            ByHandleInfo info;
            if (GetFileType(handle) != 1 || !GetFileInformationByHandle(handle, out info)) throw Error("Cannot inspect regular VAS input", "handle", Marshal.GetLastWin32Error());
            if ((info.Attributes & 0x10) != 0) throw new IOException("VAS input is a directory, not a regular file.");
            long length = ((long)info.SizeHigh << 32) | info.SizeLow;
            long write = ((long)info.WriteHigh << 32) | info.WriteLow;
            return new Info { Length = length, LastWriteUtc = DateTime.FromFileTimeUtc(write),
                Stamp = Physical(handle) + ":" + length + ":" + write + ":" + info.CreationHigh + ":" + info.CreationLow };
        }
        private static string Physical(SafeFileHandle handle)
        {
            FileIdInfo id;
            if (!GetFileInformationByHandleEx(handle, 18, out id, (uint)Marshal.SizeOf(typeof(FileIdInfo))))
                throw Error("Cannot inspect Windows file identity", "handle", Marshal.GetLastWin32Error());
            return id.Volume.ToString("x16") + ":" + id.High.ToString("x16") + id.Low.ToString("x16");
        }
        private static string FinalPath(SafeFileHandle handle)
        {
            var buffer = new StringBuilder(512);
            uint count = GetFinalPathNameByHandle(handle, buffer, (uint)buffer.Capacity, 0);
            if (count == 0) throw Error("Cannot resolve Windows handle path", "handle", Marshal.GetLastWin32Error());
            if (count >= buffer.Capacity)
            {
                if (count > 32768) throw new IOException("VAS canonical path exceeds the Windows client limit.");
                buffer.Capacity = (int)count + 1;
                count = GetFinalPathNameByHandle(handle, buffer, (uint)buffer.Capacity, 0);
                if (count == 0 || count >= buffer.Capacity) throw new IOException("Cannot read stable Windows handle path.");
            }
            string result = buffer.ToString();
            if (result.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase)) return @"\\" + result.Substring(8);
            if (result.StartsWith(@"\\?\", StringComparison.Ordinal)) return result.Substring(4);
            return result;
        }
        [StructLayout(LayoutKind.Sequential)] private struct FileIdInfo { public ulong Volume, Low, High; }
        [StructLayout(LayoutKind.Sequential)] private struct ByHandleInfo
        {
            public uint Attributes, CreationLow, CreationHigh, AccessLow, AccessHigh, WriteLow, WriteHigh,
                Volume, SizeHigh, SizeLow, Links, IndexHigh, IndexLow;
        }
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true, EntryPoint = "CreateFileW")]
        private static extern SafeFileHandle CreateFile(string path, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern uint GetFileType(SafeFileHandle handle);
        [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool GetFileInformationByHandle(SafeFileHandle handle, out ByHandleInfo info);
        [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool GetFileInformationByHandleEx(SafeFileHandle handle, int infoClass, out FileIdInfo info, uint size);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true, EntryPoint = "GetFinalPathNameByHandleW")]
        private static extern uint GetFinalPathNameByHandle(SafeFileHandle handle, StringBuilder path, uint length, uint flags);
    }
}
