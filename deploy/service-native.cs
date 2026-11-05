using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace HomeNas.Deployment
{
    // Keep Unicode paths and a real NULL password for the virtual service account.
    public static class NativeService
    {
        [DllImport("advapi32.dll", EntryPoint = "OpenSCManagerW", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
        private static extern IntPtr OpenSCManager(string machine, string database, uint access);
        [DllImport("advapi32.dll", EntryPoint = "CreateServiceW", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
        private static extern IntPtr CreateService(IntPtr manager, string name, string displayName,
            uint access, uint type, uint start, uint error, string binaryPath, string group,
            IntPtr tag, string dependencies, string account, string password);
        [DllImport("advapi32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool CloseServiceHandle(IntPtr handle);
        [DllImport("advapi32.dll", EntryPoint = "OpenServiceW", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
        private static extern IntPtr OpenService(IntPtr manager, string name, uint access);
        [DllImport("advapi32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool DeleteService(IntPtr service);

        public sealed class ProbeResult
        {
            public string Name;
            public string Account;
            public string BinaryPath;
            public string Stage;
            public int CreateCode;
            public bool Created;
            public bool Deleted;
            public int DeleteCode;
            public bool Started; // Always false: probes must never execute anything.
        }

        public static ProbeResult PlanDisabledProbe(bool virtualAccount)
        {
            string name = "lw.HomeNAS.Probe." + Guid.NewGuid().ToString("N") + (virtualAccount ? ".V" : ".L");
            return new ProbeResult { Name = name,
                Account = virtualAccount ? "NT SERVICE\\" + name : "NT AUTHORITY\\LocalService",
                BinaryPath = "\"" + Path.Combine(Environment.SystemDirectory, "cmd.exe") + "\" /c exit 0",
                Stage = "Plan", CreateCode = -1, DeleteCode = -1 };
        }

        public static ProbeResult ProbeDisabledService(bool virtualAccount)
        {
            ProbeResult result = PlanDisabledProbe(virtualAccount);
            result.Stage = "OpenSCManagerW";
            IntPtr manager = OpenSCManager(null, null, 0x0001 | 0x0002);
            if (manager == IntPtr.Zero) { result.CreateCode = Marshal.GetLastWin32Error(); return result; }
            try
            {
                result.Stage = "CreateServiceW(disabled)";
                // Fresh GUID, disabled start, harmless system command, NULL password.
                // DELETE | QUERY_STATUS on this exact newly created handle, never delete by name.
                IntPtr service = CreateService(manager, result.Name, result.Name, 0x00010000 | 0x0004,
                    0x0010, 4, 1, result.BinaryPath, null, IntPtr.Zero, null, result.Account, null);
                if (service == IntPtr.Zero) { result.CreateCode = Marshal.GetLastWin32Error(); return result; }
                result.Created = true;
                result.CreateCode = 0;
                try
                {
                    result.Stage = "DeleteService(created handle)";
                    result.Deleted = DeleteService(service);
                    result.DeleteCode = result.Deleted ? 0 : Marshal.GetLastWin32Error();
                    if (result.Deleted) result.Stage = "Complete";
                }
                finally { CloseServiceHandle(service); }
                return result;
            }
            finally { CloseServiceHandle(manager); }
        }

        public sealed class CommandResult
        {
            public int ExitCode;
            public string Output;
            public string Error;
        }

        // Quote one argument for the Windows command-line parser, including embedded quotes.
        // Do not send these arguments through cmd.exe or PowerShell's native argument reserialization.
        public static string QuoteArgument(string value)
        {
            if (value == null || value.IndexOf('\0') >= 0) throw new ArgumentException("Invalid command argument.");
            StringBuilder result = new StringBuilder("\"");
            int slashes = 0;
            foreach (char character in value)
            {
                if (character == '\\') { slashes++; continue; }
                result.Append('\\', character == '"' ? slashes * 2 + 1 : slashes);
                result.Append(character);
                slashes = 0;
            }
            result.Append('\\', slashes * 2);
            return result.Append('"').ToString();
        }

        public static string BuildScCreateArguments(string name, string binaryPath, string account)
        {
            // Omit password= entirely: the virtual account requires a NULL password.
            string[] values = { "create", name, "binPath=", binaryPath, "start=", "auto",
                "type=", "own", "error=", "normal", "obj=", account, "DisplayName=", name };
            return String.Join(" ", Array.ConvertAll(values, QuoteArgument));
        }

        public static bool Exists(string name)
        {
            IntPtr manager = OpenSCManager(null, null, 0x0001);
            if (manager == IntPtr.Zero)
            {
                int code = Marshal.GetLastWin32Error();
                throw new Win32Exception(code, DescribeError("OpenSCManagerW(check existing)", code));
            }
            try
            {
                IntPtr service = OpenService(manager, name, 0x0004);
                if (service != IntPtr.Zero) { CloseServiceHandle(service); return true; }
                int code = Marshal.GetLastWin32Error();
                if (code == 1060) return false; // ERROR_SERVICE_DOES_NOT_EXIST only.
                throw new Win32Exception(code, DescribeError("OpenServiceW(check existing)", code));
            }
            finally { CloseServiceHandle(manager); }
        }

        public static CommandResult CreateWithSc(string name, string binaryPath, string account)
        {
            ProcessStartInfo start = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "sc.exe"),
                BuildScCreateArguments(name, binaryPath, account));
            start.UseShellExecute = false;
            start.CreateNoWindow = true;
            start.RedirectStandardOutput = true;
            start.RedirectStandardError = true;
            // Numeric exit codes are authoritative even if a customized OS uses another output codepage.
            start.StandardOutputEncoding = Encoding.GetEncoding(System.Globalization.CultureInfo.CurrentCulture.TextInfo.OEMCodePage);
            start.StandardErrorEncoding = start.StandardOutputEncoding;
            using (Process process = Process.Start(start))
            {
                var output = process.StandardOutput.ReadToEndAsync();
                var error = process.StandardError.ReadToEndAsync();
                process.WaitForExit();
                return new CommandResult { ExitCode = process.ExitCode, Output = output.Result, Error = error.Result };
            }
        }

        public static string DescribeError(string operation, int code)
        {
            return operation + " failed, Win32 error " + code + ": " + new Win32Exception(code).Message;
        }

        public static void Create(string name, string binaryPath, string account)
        {
            // CONNECT | CREATE_SERVICE. Request only QUERY_STATUS on the returned service handle.
            IntPtr manager = OpenSCManager(null, null, 0x0001 | 0x0002);
            if (manager == IntPtr.Zero)
            {
                int code = Marshal.GetLastWin32Error();
                throw new Win32Exception(code, DescribeError("OpenSCManagerW", code));
            }
            try
            {
                // OWN_PROCESS, AUTO_START, ERROR_NORMAL. Never replace an existing service.
                IntPtr service = CreateService(manager, name, name, 0x0004, 0x0010, 2, 1,
                    binaryPath, null, IntPtr.Zero, null, account, null);
                if (service == IntPtr.Zero)
                {
                    int code = Marshal.GetLastWin32Error();
                    throw new Win32Exception(code, DescribeError("CreateServiceW", code));
                }
                CloseServiceHandle(service);
            }
            finally { CloseServiceHandle(manager); }
        }
    }
}
