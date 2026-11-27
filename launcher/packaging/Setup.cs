using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Windows.Forms;

internal static class Setup
{
    [STAThread]
    private static int Main(string[] args)
    {
        bool quiet = Array.Exists(args, arg => string.Equals(arg, "/quiet", StringComparison.OrdinalIgnoreCase));
        string work = Path.Combine(Path.GetTempPath(), "Gunnertimes-Setup-" + Guid.NewGuid().ToString("N"));
        string log = null;
        try
        {
            string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string logs = Path.Combine(local, "Gunnertimes", "setup-logs");
            Directory.CreateDirectory(logs);
            log = Path.Combine(logs, Path.GetFileName(work) + ".log");
            Directory.CreateDirectory(work);
            string msi = Path.Combine(work, "Gunnertimes.msi");
            using (Stream payload = Assembly.GetExecutingAssembly().GetManifestResourceStream("Gunnertimes.msi"))
            using (var destination = new FileStream(msi, FileMode.CreateNew, FileAccess.Write))
                payload.CopyTo(destination);

            var installer = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "msiexec.exe"),
                "/i \"" + msi + "\" /norestart /L*v \"" + log + "\" " + (quiet ? "/qn" : "/qb!")) {
                UseShellExecute = false
            };
            int result;
            using (var process = Process.Start(installer))
            {
                process.WaitForExit();
                result = process.ExitCode;
            }
            if (result == 3010)
            {
                if (!quiet) MessageBox.Show("Setup completed. Restart Windows, then open Gunnertimes from Start.",
                    "Gunnertimes Setup", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return result;
            }
            if (result != 0)
            {
                if (!quiet) ShowError("Windows Installer returned error " + result + ".", log);
                return result;
            }
            if (!quiet)
            {
                string directory = Path.Combine(local, "Gunnertimes Launcher");
                var launcher = new ProcessStartInfo(Path.Combine(directory, "Gunnertimes.exe")) {
                    WorkingDirectory = directory, UseShellExecute = false
                };
                using (Process.Start(launcher)) { }
            }
            return 0;
        }
        catch (Exception failure)
        {
            if (!quiet) ShowError(failure.Message, log);
            return 1;
        }
        finally
        {
            try { if (Directory.Exists(work)) Directory.Delete(work, true); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    private static void ShowError(string reason, string log)
    {
        string message = reason;
        if (log != null && File.Exists(log)) message += "\r\n\r\nSetup log\r\n" + log;
        MessageBox.Show(message, "Gunnertimes Setup", MessageBoxButtons.OK, MessageBoxIcon.Error);
    }
}
