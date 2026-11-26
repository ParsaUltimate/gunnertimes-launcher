using System;
using System.Diagnostics;
using System.IO;
using System.Security.Cryptography;
using System.Windows.Forms;

internal static class UpdateHelper
{
    [STAThread]
    private static int Main(string[] args)
    {
        if (args.Length != 7) return 2;
        var installed = args[1] == "INSTALLED";
        string payload = args[2], target = args[3], java = args[4], hash = args[5];
        string incoming = null, backup = null;
        bool replaced = false;
        try
        {
            Process parent = null;
            try { parent = Process.GetProcessById(int.Parse(args[0])); }
            catch (ArgumentException) { }
            using (parent)
                if (parent != null && !parent.WaitForExit(60000))
                    throw new IOException("Gunnertimes is still running.");
            Verify(payload, hash);
            if (installed)
            {
                string directory = Path.GetDirectoryName(target);
                foreach (string file in Directory.EnumerateFiles(directory, "*", SearchOption.AllDirectories))
                    using (File.Open(file, FileMode.Open, FileAccess.Read, FileShare.None)) { }
                using (var setup = Start(payload, "/quiet /norestart", Path.GetDirectoryName(payload)))
                {
                    setup.WaitForExit();
                    if (setup.ExitCode != 0)
                        throw new IOException("Setup exited with code " + setup.ExitCode + ".");
                }
            }
            else
            {
                string suffix = Guid.NewGuid().ToString("N");
                incoming = target + ".incoming-" + suffix;
                backup = target + ".previous-" + suffix;
                File.Copy(payload, incoming);
                Verify(incoming, hash);
                File.Replace(incoming, target, backup, false);
                replaced = true;
            }
            using (Relaunch(installed, target, java)) { }
            TryDelete(backup);
            return 0;
        }
        catch (Exception failure)
        {
            string detail = failure.Message;
            if (replaced && File.Exists(backup))
            {
                try { File.Replace(backup, target, null); }
                catch { detail += Environment.NewLine + "Previous JAR: " + backup; }
            }
            try { File.WriteAllText(payload + ".failure.log", detail); }
            catch (IOException) { }
            string message = args[6] == "true"
                ? "به‌روزرسانی لانچر انجام نشد. دسترسی پوشه را بررسی کنید و دوباره تلاش کنید."
                : "The launcher update could not finish. Check folder access and try again.";
            MessageBox.Show(message + "\r\n\r\n" + detail, "Gunnertimes",
                MessageBoxButtons.OK, MessageBoxIcon.Error);
            if (File.Exists(target))
            {
                try { using (Relaunch(installed, target, java)) { } }
                catch { }
            }
            return 1;
        }
        finally
        {
            TryDelete(incoming);
            TryDelete(payload);
        }
    }

    private static Process Relaunch(bool installed, string target, string java)
    {
        return installed ? Start(target, "", Path.GetDirectoryName(target))
            : Start(java, "-jar \"" + target + "\"", Path.GetDirectoryName(target));
    }

    private static Process Start(string executable, string arguments, string directory)
    {
        return Process.Start(new ProcessStartInfo(executable, arguments) {
            WorkingDirectory = directory, UseShellExecute = false
        });
    }

    private static void Verify(string path, string expected)
    {
        using (var source = File.OpenRead(path))
        using (var sha = SHA512.Create())
            if (!string.Equals(BitConverter.ToString(sha.ComputeHash(source)).Replace("-", ""),
                    expected, StringComparison.OrdinalIgnoreCase))
                throw new IOException("The downloaded file failed SHA-512 verification.");
    }

    private static void TryDelete(string path)
    {
        if (path == null) return;
        try { File.Delete(path); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }
}
