using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;

internal static class Setup
{
    private const string UpgradeCode = "{EC49F39F-8138-4F13-8CD2-FFDC70ACCC99}";

    [DllImport("msi.dll", CharSet = CharSet.Unicode)]
    private static extern uint MsiEnumRelatedProducts(string upgradeCode, uint reserved, uint index, StringBuilder product);

    [DllImport("msi.dll", CharSet = CharSet.Unicode)]
    private static extern uint MsiGetProductInfoEx(string product, string sid, uint context,
        string property, StringBuilder value, ref uint length);

    [STAThread]
    private static int Main(string[] args)
    {
        bool quiet = Array.Exists(args, arg => string.Equals(arg, "/quiet", StringComparison.OrdinalIgnoreCase));
        string work = Path.Combine(Path.GetTempPath(), "Gunnertimes-Setup-" + Guid.NewGuid().ToString("N"));
        string log = null;
        try
        {
            string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string installed = InstalledDirectory();
            string directory = installed ?? Path.Combine(local, "Gunnertimes Launcher");
            if (!quiet)
            {
                Application.EnableVisualStyles();
                using (var dialog = new DestinationDialog(directory, installed != null))
                {
                    if (dialog.ShowDialog() != DialogResult.OK) return 1602;
                    directory = dialog.Destination;
                }
            }
            directory = ValidateDirectory(directory, installed == null);
            string logs = Path.Combine(local, "Gunnertimes", "setup-logs");
            Directory.CreateDirectory(logs);
            log = Path.Combine(logs, Path.GetFileName(work) + ".log");
            Directory.CreateDirectory(work);
            string msi = Path.Combine(work, "Gunnertimes.msi");
            using (Stream payload = Assembly.GetExecutingAssembly().GetManifestResourceStream("Gunnertimes.msi"))
            using (var destination = new FileStream(msi, FileMode.CreateNew, FileAccess.Write))
                payload.CopyTo(destination);

            var installer = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory, "msiexec.exe"),
                "/i \"" + msi + "\" INSTALLDIR=\"" + directory + "\" /norestart /L*v \"" + log
                    + "\" " + (quiet ? "/qn" : "/qb!")) {
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

    private static string InstalledDirectory()
    {
        var product = new StringBuilder(39);
        for (uint index = 0; ; index++)
        {
            uint result = MsiEnumRelatedProducts(UpgradeCode, 0, index, product);
            if (result == 259) return null;
            if (result != 0) throw new IOException("Unable to query the existing installation. Error " + result + ".");
            var location = new StringBuilder(32768);
            uint length = (uint)location.Capacity;
            // Context 2 restricts the lookup to the current user's unmanaged MSI installation.
            result = MsiGetProductInfoEx(product.ToString(), null, 2, "InstallLocation", location, ref length);
            if (result == 1605) continue;
            if (result != 0 || location.Length == 0) throw new IOException("Windows Installer did not provide the existing installation folder.");
            return location.ToString();
        }
    }

    private static string ValidateDirectory(string value, bool fresh)
    {
        value = value.Trim();
        if (value.Length < 3 || !char.IsLetter(value[0]) || value[1] != ':' ||
            (value[2] != '\\' && value[2] != '/') || value.IndexOf('"') >= 0)
            throw new IOException("Choose a full folder path on a local drive.");
        string directory = Path.GetFullPath(value).TrimEnd('\\', '/');
        if (directory.Length <= 2) throw new IOException("Choose a folder inside the drive.");
        if (File.Exists(directory)) throw new IOException("The installation path points to a file.");
        if (fresh && Directory.Exists(directory))
        {
            using (var entries = Directory.EnumerateFileSystemEntries(directory).GetEnumerator())
                if (entries.MoveNext()) throw new IOException("Choose an empty or new folder for Gunnertimes.");
        }
        string parent = directory;
        while (!Directory.Exists(parent))
        {
            if (File.Exists(parent)) throw new IOException("A parent folder points to a file.");
            parent = Path.GetDirectoryName(parent);
            if (parent == null) throw new IOException("The selected drive is unavailable.");
        }
        string probe = Path.Combine(parent, ".gunnertimes-" + Guid.NewGuid().ToString("N"));
        using (new FileStream(probe, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1, FileOptions.DeleteOnClose)) { }
        return directory;
    }

    private sealed class DestinationDialog : Form
    {
        internal string Destination { get; private set; }

        internal DestinationDialog(string directory, bool upgrade)
        {
            Text = "Gunnertimes Setup";
            ClientSize = new System.Drawing.Size(560, 162);
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MaximizeBox = false;
            MinimizeBox = false;
            Icon = System.Drawing.Icon.ExtractAssociatedIcon(Application.ExecutablePath);

            var heading = new Label { Text = upgrade ? "Upgrade Gunnertimes in its current folder." : "Choose where to install Gunnertimes.",
                AutoSize = true, Left = 18, Top = 18 };
            var path = new TextBox { Text = directory, ReadOnly = upgrade, Left = 18, Top = 48, Width = 430,
                AccessibleName = "Installation folder" };
            var browse = new Button { Text = "&Browse…", Left = 454, Top = 46, Width = 88, Enabled = !upgrade };
            browse.Click += (sender, args) => {
                using (var folder = new FolderBrowserDialog { Description = "Select the Gunnertimes installation folder.",
                    SelectedPath = Directory.Exists(path.Text) ? path.Text : "" })
                    if (folder.ShowDialog(this) == DialogResult.OK) path.Text = folder.SelectedPath;
            };
            var error = new Label { Left = 18, Top = 80, Width = 524, Height = 37, ForeColor = System.Drawing.Color.Firebrick };
            var install = new Button { Text = upgrade ? "&Upgrade" : "&Install", Left = 354, Top = 119, Width = 88 };
            var cancel = new Button { Text = "Cancel", Left = 454, Top = 119, Width = 88, DialogResult = DialogResult.Cancel };
            install.Click += (sender, args) => {
                try
                {
                    Destination = ValidateDirectory(path.Text, !upgrade);
                    DialogResult = DialogResult.OK;
                }
                catch (Exception failure)
                {
                    error.Text = failure.Message;
                    path.Focus();
                }
            };
            AcceptButton = install;
            CancelButton = cancel;
            Controls.AddRange(new Control[] { heading, path, browse, error, install, cancel });
        }
    }

    private static void ShowError(string reason, string log)
    {
        string message = reason;
        if (log != null && File.Exists(log)) message += "\r\n\r\nSetup log\r\n" + log;
        MessageBox.Show(message, "Gunnertimes Setup", MessageBoxButtons.OK, MessageBoxIcon.Error);
    }
}
