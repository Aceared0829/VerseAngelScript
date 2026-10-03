# Test-only Windows 10+ process containment. No execution-policy changes.
# Atomic job assignment avoids the suspended-create/assignment crash gap:
# https://devblogs.microsoft.com/oldnewthing/20230209-00/?p=107812
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$request = $env:VAS_WINDOWS_JOB_REQUEST | ConvertFrom-Json
Remove-Item Env:VAS_WINDOWS_JOB_REQUEST
$result = $null
try {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public sealed class VasJobResult {
    public int ExitCode = 125;
    public bool Launched, LeaderExited, TimedOut, Quiescent;
    public uint LeaderExitCode;
    public string Error;
}

public static class VasTestJob {
    const uint WAIT_OBJECT_0 = 0, WAIT_TIMEOUT = 258, CREATE_SUSPENDED = 4;
    const uint EXTENDED_STARTUPINFO_PRESENT = 0x00080000, STARTF_USESTDHANDLES = 0x100;
    const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000, DUPLICATE_SAME_ACCESS = 2;
    const int ShutdownMs = 5000;

    [StructLayout(LayoutKind.Sequential)] struct BasicLimits {
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass, SchedulingClass;
    }
    [StructLayout(LayoutKind.Sequential)] struct IoCounters {
        public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount;
        public ulong ReadTransferCount, WriteTransferCount, OtherTransferCount;
    }
    [StructLayout(LayoutKind.Sequential)] struct ExtendedLimits {
        public BasicLimits BasicLimitInformation;
        public IoCounters IoInfo;
        public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }
    [StructLayout(LayoutKind.Sequential)] struct Accounting {
        public long TotalUserTime, TotalKernelTime, ThisPeriodTotalUserTime, ThisPeriodTotalKernelTime;
        public uint TotalPageFaultCount, TotalProcesses, ActiveProcesses, TotalTerminatedProcesses;
    }
    [StructLayout(LayoutKind.Sequential)] struct StartupInfo {
        public uint cb;
        public IntPtr lpReserved, lpDesktop, lpTitle;
        public uint dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
        public ushort wShowWindow, cbReserved2;
        public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
    }
    [StructLayout(LayoutKind.Sequential)] struct StartupInfoEx {
        public StartupInfo StartupInfo;
        public IntPtr lpAttributeList;
    }
    [StructLayout(LayoutKind.Sequential)] struct ProcessInfo {
        public IntPtr hProcess, hThread;
        public uint dwProcessId, dwThreadId;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
    static extern IntPtr CreateJobObjectW(IntPtr attributes, string name);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool SetInformationJobObject(IntPtr job, int infoClass, ref ExtendedLimits info, uint length);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool QueryInformationJobObject(IntPtr job, int infoClass, out Accounting info, uint length, IntPtr returned);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool InitializeProcThreadAttributeList(IntPtr list, int count, int flags, ref IntPtr size);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool UpdateProcThreadAttribute(IntPtr list, uint flags, IntPtr attribute, IntPtr value, IntPtr size, IntPtr previous, IntPtr returned);
    [DllImport("kernel32.dll")] static extern void DeleteProcThreadAttributeList(IntPtr list);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
    static extern bool CreateProcessW(string executable, StringBuilder command, IntPtr processAttributes,
        IntPtr threadAttributes, bool inheritHandles, uint flags, IntPtr environment, string directory,
        ref StartupInfoEx startup, out ProcessInfo process);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool result);
    [DllImport("kernel32.dll", SetLastError = true)] static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll", SetLastError = true)] static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool GetExitCodeProcess(IntPtr process, out uint code);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool TerminateJobObject(IntPtr job, uint code);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool TerminateProcess(IntPtr process, uint code);
    [DllImport("kernel32.dll")] static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr GetStdHandle(int kind);
    [DllImport("kernel32.dll", SetLastError = true)]
    static extern bool DuplicateHandle(IntPtr sourceProcess, IntPtr source, IntPtr targetProcess,
        out IntPtr target, uint access, bool inherit, uint options);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool CloseHandle(IntPtr handle);

    static void Check(bool success, string operation) {
        if (!success) throw new Win32Exception(Marshal.GetLastWin32Error(), operation);
    }
    static void Close(ref IntPtr handle) {
        if (handle != IntPtr.Zero && handle != new IntPtr(-1)) { CloseHandle(handle); handle = IntPtr.Zero; }
    }
    static string Quote(string value) {
        var text = new StringBuilder("\"");
        int slashes = 0;
        foreach (char character in value) {
            if (character == '\\') { slashes++; continue; }
            text.Append('\\', character == '"' ? slashes * 2 + 1 : slashes);
            text.Append(character);
            slashes = 0;
        }
        return text.Append('\\', slashes * 2).Append('"').ToString();
    }
    static IntPtr InheritedStandardHandle(int kind) {
        IntPtr copy;
        Check(DuplicateHandle(GetCurrentProcess(), GetStdHandle(kind), GetCurrentProcess(),
            out copy, 0, true, DUPLICATE_SAME_ACCESS), "DuplicateHandle(stdio)");
        return copy;
    }

    public static VasJobResult Run(string executable, string[] args, int timeoutMs) {
        var result = new VasJobResult();
        IntPtr job = IntPtr.Zero, attributes = IntPtr.Zero, jobList = IntPtr.Zero, handleList = IntPtr.Zero;
        bool attributesInitialized = false, membershipVerified = false;
        var process = new ProcessInfo();
        var startup = new StartupInfoEx();
        try {
            // The job handle is private/non-inheritable, and breakaway is never enabled.
            job = CreateJobObjectW(IntPtr.Zero, null);
            Check(job != IntPtr.Zero, "CreateJobObjectW");
            var limits = new ExtendedLimits();
            limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            Check(SetInformationJobObject(job, 9, ref limits, (uint)Marshal.SizeOf(typeof(ExtendedLimits))), "SetInformationJobObject");
            IntPtr size = IntPtr.Zero;
            InitializeProcThreadAttributeList(IntPtr.Zero, 2, 0, ref size);
            if (size == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error(), "InitializeProcThreadAttributeList(size)");
            attributes = Marshal.AllocHGlobal(size);
            Check(InitializeProcThreadAttributeList(attributes, 2, 0, ref size), "InitializeProcThreadAttributeList");
            attributesInitialized = true;
            jobList = Marshal.AllocHGlobal(IntPtr.Size);
            Marshal.WriteIntPtr(jobList, job);
            // PROC_THREAD_ATTRIBUTE_JOB_LIST assigns the Job during creation,
            // before any child instruction can run. Unsupported hosts fail closed.
            Check(UpdateProcThreadAttribute(attributes, 0, new IntPtr(0x0002000D), jobList,
                new IntPtr(IntPtr.Size), IntPtr.Zero, IntPtr.Zero), "UpdateProcThreadAttribute(JOB_LIST)");
            startup.StartupInfo.cb = (uint)Marshal.SizeOf(typeof(StartupInfoEx));
            startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
            startup.StartupInfo.hStdInput = InheritedStandardHandle(-10);
            startup.StartupInfo.hStdOutput = InheritedStandardHandle(-11);
            startup.StartupInfo.hStdError = InheritedStandardHandle(-12);
            handleList = Marshal.AllocHGlobal(3 * IntPtr.Size);
            Marshal.WriteIntPtr(handleList, 0, startup.StartupInfo.hStdInput);
            Marshal.WriteIntPtr(handleList, IntPtr.Size, startup.StartupInfo.hStdOutput);
            Marshal.WriteIntPtr(handleList, 2 * IntPtr.Size, startup.StartupInfo.hStdError);
            Check(UpdateProcThreadAttribute(attributes, 0, new IntPtr(0x00020002), handleList,
                new IntPtr(3 * IntPtr.Size), IntPtr.Zero, IntPtr.Zero), "UpdateProcThreadAttribute(HANDLE_LIST)");
            startup.lpAttributeList = attributes;
            var command = new StringBuilder(Quote(executable));
            foreach (string argument in args) command.Append(' ').Append(Quote(argument));
            Check(CreateProcessW(executable, command, IntPtr.Zero, IntPtr.Zero, true,
                CREATE_SUSPENDED | EXTENDED_STARTUPINFO_PRESENT, IntPtr.Zero, null, ref startup, out process), "CreateProcessW");
            result.Launched = true;
            Close(ref startup.StartupInfo.hStdInput);
            Close(ref startup.StartupInfo.hStdOutput);
            Close(ref startup.StartupInfo.hStdError);
            bool member;
            Check(IsProcessInJob(process.hProcess, job, out member), "IsProcessInJob");
            if (!member) throw new InvalidOperationException("New suspended process was not assigned to its owned Job");
            membershipVerified = true;
            Check(ResumeThread(process.hThread) != uint.MaxValue, "ResumeThread");
            Close(ref process.hThread);
            uint wait = WaitForSingleObject(process.hProcess, (uint)timeoutMs);
            if (wait == WAIT_TIMEOUT) { result.TimedOut = true; result.ExitCode = 124; }
            else {
                Check(wait == WAIT_OBJECT_0, "WaitForSingleObject(leader)");
                Check(GetExitCodeProcess(process.hProcess, out result.LeaderExitCode), "GetExitCodeProcess");
                result.LeaderExited = true;
                result.ExitCode = unchecked((int)result.LeaderExitCode);
            }
        } catch (Exception error) {
            result.Error = error.Message;
            result.ExitCode = 125;
        } finally {
            try {
                // If membership verification itself fails, the original process
                // handle still identifies only the suspended process we created.
                if (process.hProcess != IntPtr.Zero && !membershipVerified &&
                    WaitForSingleObject(process.hProcess, 0) != WAIT_OBJECT_0) {
                    Check(TerminateProcess(process.hProcess, 125), "TerminateProcess(unverified suspended child)");
                }
                if (job != IntPtr.Zero) {
                    Check(TerminateJobObject(job, 125), "TerminateJobObject");
                    if (process.hProcess != IntPtr.Zero) {
                        Check(WaitForSingleObject(process.hProcess, ShutdownMs) == WAIT_OBJECT_0, "WaitForSingleObject(cleanup)");
                    }
                    // Release our process references before inspecting job accounting.
                    Close(ref process.hThread);
                    Close(ref process.hProcess);
                    var deadline = Stopwatch.StartNew();
                    while (true) {
                        Accounting accounting;
                        Check(QueryInformationJobObject(job, 1, out accounting,
                            (uint)Marshal.SizeOf(typeof(Accounting)), IntPtr.Zero), "QueryInformationJobObject");
                        if (accounting.ActiveProcesses == 0) break;
                        if (deadline.ElapsedMilliseconds >= ShutdownMs) throw new TimeoutException("Owned Job still has active processes");
                        Thread.Sleep(25);
                    }
                } else if (result.Launched) throw new InvalidOperationException("Launched process has no owned Job");
                result.Quiescent = true;
            } catch (Exception error) {
                result.Error = (result.Error == null ? "" : result.Error + "; ") + "cleanup: " + error.Message;
                result.ExitCode = 125;
            }
            Close(ref process.hThread);
            Close(ref process.hProcess);
            Close(ref startup.StartupInfo.hStdInput);
            Close(ref startup.StartupInfo.hStdOutput);
            Close(ref startup.StartupInfo.hStdError);
            if (attributesInitialized) DeleteProcThreadAttributeList(attributes);
            if (attributes != IntPtr.Zero) Marshal.FreeHGlobal(attributes);
            if (jobList != IntPtr.Zero) Marshal.FreeHGlobal(jobList);
            if (handleList != IntPtr.Zero) Marshal.FreeHGlobal(handleList);
            // KILL_ON_JOB_CLOSE is the final containment fallback, never proof of quiescence.
            Close(ref job);
        }
        return result;
    }
}
'@
    $result = [VasTestJob]::Run([string]$request.executable, [string[]]$request.args, [int]$request.timeoutMs)
} catch {
    $result = [PSCustomObject]@{ ExitCode = 125; Launched = $false; LeaderExited = $false;
        LeaderExitCode = 0; TimedOut = $false; Quiescent = $false; Error = $_.Exception.Message }
}
# A private result file is separate from the launched program's stdout/stderr.
# The request environment variable was removed before the child was created.
[IO.File]::WriteAllText([string]$request.resultFile, ($result | ConvertTo-Json -Compress), (New-Object Text.UTF8Encoding($false)))
exit $result.ExitCode
