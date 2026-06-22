using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

class SfxLauncher
{
    const string APP_NAME = "F24Installer";
    const string MAGIC = "F24SFX00";
    const string VERSION = "%%VERSION%%";

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    static extern int MessageBox(IntPtr hWnd, string text, string caption, uint type);

    static int Main()
    {
        try
        {
            string appData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string cacheDir = Path.Combine(appData, APP_NAME);
            string versionFile = Path.Combine(cacheDir, ".sfx-version");
            string appExe = Path.Combine(cacheDir, APP_NAME + ".exe");
            string selfPath = Process.GetCurrentProcess().MainModule.FileName;

            bool needsExtract = !File.Exists(appExe)
                || !File.Exists(versionFile)
                || File.ReadAllText(versionFile).Trim() != VERSION;

            if (needsExtract)
            {
                string tempZip = Path.Combine(Path.GetTempPath(),
                    "f24_sfx_" + Guid.NewGuid().ToString("N").Substring(0, 8) + ".zip");

                using (var fs = new FileStream(selfPath, FileMode.Open, FileAccess.Read, FileShare.Read))
                {
                    long fileLen = fs.Length;
                    if (fileLen < 16)
                    {
                        ShowError("Archivo corrupto: demasiado pequeno.");
                        return 1;
                    }

                    byte[] trailer = new byte[16];
                    fs.Seek(-16, SeekOrigin.End);
                    fs.Read(trailer, 0, 16);

                    long zipSize = BitConverter.ToInt64(trailer, 0);
                    string magic = Encoding.ASCII.GetString(trailer, 8, 8);

                    if (magic != MAGIC || zipSize <= 0 || zipSize > fileLen - 16)
                    {
                        ShowError("Archivo corrupto: datos invalidos.");
                        return 1;
                    }

                    long zipStart = fileLen - 16 - zipSize;
                    fs.Seek(zipStart, SeekOrigin.Begin);

                    using (var dest = File.Create(tempZip))
                    {
                        byte[] buffer = new byte[81920];
                        long remaining = zipSize;
                        while (remaining > 0)
                        {
                            int toRead = (int)Math.Min(buffer.Length, remaining);
                            int read = fs.Read(buffer, 0, toRead);
                            if (read == 0) break;
                            dest.Write(buffer, 0, read);
                            remaining -= read;
                        }
                    }
                }

                if (Directory.Exists(cacheDir))
                {
                    try { Directory.Delete(cacheDir, true); }
                    catch { Thread.Sleep(500); try { Directory.Delete(cacheDir, true); } catch { } }
                }
                Directory.CreateDirectory(cacheDir);

                var psi = new ProcessStartInfo
                {
                    FileName = "powershell.exe",
                    Arguments = string.Format(
                        "-NoProfile -NoLogo -Command \"Expand-Archive -LiteralPath '{0}' -DestinationPath '{1}' -Force\"",
                        tempZip, cacheDir),
                    UseShellExecute = false,
                    CreateNoWindow = true
                };
                var proc = Process.Start(psi);
                proc.WaitForExit(120000);

                try { File.Delete(tempZip); } catch { }

                if (!File.Exists(appExe))
                {
                    ShowError("Error al extraer la aplicacion.\nVerifica que tienes permisos de escritura.");
                    return 1;
                }

                File.WriteAllText(versionFile, VERSION);
            }

            Process.Start(new ProcessStartInfo(appExe) { UseShellExecute = true });
            return 0;
        }
        catch (Exception ex)
        {
            ShowError("Error inesperado:\n" + ex.Message);
            return 1;
        }
    }

    static void ShowError(string msg)
    {
        MessageBox(IntPtr.Zero, msg, "F24 Installer", 0x10);
    }
}
