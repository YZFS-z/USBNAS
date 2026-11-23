using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;

// USBNASLink 的 Windows 启动器。
//
// 为什么不用 jpackage / Launch4j：
//   - jpackage 在这台机器上跑不出 app-image（先报 "path exceeding 32000 characters"，
//     换短路径后直接挂住不返回），JDK 自带的打包链路不可用；
//   - Launch4j 需要外网下载 + 依赖 MinGW 工具链，构建不可复现。
// 所以自己写一个 60 行的 C# 启动器，用 csc.exe（.NET Framework 自带）编译成单个 exe：
//   1) 找 JDK/JRE：环境变量 USBNASLINK_JAVA_HOME → 注册表 → PATH → 常见安装位置
//   2) 找不到就用同目录下的 runtime\ 便携 JRE（如果打包时带上了）
//   3) 用 javaw.exe 起 jar，传 --main-class，java.exe 缺失时给出人话提示
//
// 编译（build.ps1 里调用）：
//   csc /target:winexe /out:USBNASLink.exe /win32icon:io.ico Launcher.cs
internal static class Launcher
{
    private const string JarName = "USBNASLink.jar";
    private const string MainClass = "com.littlesauce.usbnaslink.desktop.Main";
    // 和 USBNASLink.exe 同级的 lib\ 目录，放 vlcj / jna 那几个 jar
    private const string LibDirName = "lib";

    [STAThread]
    private static int Main(string[] args)
    {
        string exeDir = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
        string jar = Path.Combine(exeDir, JarName);
        if (!File.Exists(jar))
        {
            Error("找不到 " + JarName + "\n\n它应该和 USBNASLink.exe 放在同一个目录：\n" + exeDir);
            return 2;
        }

        string javaHome = FindJavaHome(exeDir);
        string javaexe = javaHome == null ? null : Path.Combine(javaHome, "bin", "javaw.exe");
        if (javaexe == null || !File.Exists(javaexe))
        {
            Error("没有找到 Java 运行时（需要 17 或更高）。\n\n"
                + "请二选一：\n"
                + "  1) 安装 JDK 17（https://adoptium.net）\n"
                + "  2) 设置环境变量 USBNASLINK_JAVA_HOME 指向 JDK 目录\n"
                + "  3) 把便携 JRE 放到本程序目录下的 runtime\\ 里");
            return 3;
        }

        var psi = new ProcessStartInfo(javaexe);
        psi.UseShellExecute = false;
        psi.WorkingDirectory = exeDir;
        psi.Arguments = BuildArgs(exeDir, jar, args);
        try
        {
            Process p = Process.Start(psi);
            if (p == null) return 4;
            return 0;
        }
        catch (Exception ex)
        {
            Error("启动失败：" + ex.Message);
            return 5;
        }
    }

    private static string BuildArgs(string exeDir, string jar, string[] args)
    {
        var sb = new StringBuilder();
        sb.Append("-Dfile.encoding=UTF-8 -Xmx256m ");
        // VlcEngine reads this first, so the bundled runtime\vlc\libvlc.dll wins
        // over whatever VLC version happens to be installed on the machine.
        string vlc = Path.Combine(exeDir, "vlc");
        if (File.Exists(Path.Combine(vlc, "libvlc.dll")))
        {
            sb.Append("-Dusbnaslink.vlc=\"").Append(vlc).Append("\" ");
        }
        sb.Append("-Dusbnaslink.home=\"").Append(exeDir).Append("\" ");
        // Class-Path in the manifest is useless once the launcher pins -cp, so
        // every runtime dependency is listed here explicitly.
        var cp = new StringBuilder(jar);
        foreach (string name in new[] { "vlcj-4.8.3.jar", "vlcj-natives-4.8.3.jar", "jna-5.13.0.jar", "jna-platform-5.13.0.jar" })
        {
            string lib = Path.Combine(exeDir, LibDirName, name);
            if (File.Exists(lib)) cp.Append(';').Append(lib);
        }
        sb.Append("-cp \"").Append(cp).Append("\" ");
        sb.Append(MainClass).Append(' ');
        if (args != null)
        {
            foreach (string a in args)
            {
                sb.Append('"').Append((a ?? string.Empty).Replace("\"", "\\\"")).Append("\" ");
            }
        }
        return sb.ToString();
    }

    private static string FindJavaHome(string exeDir)
    {
        // 1) 显式指定
        string env = Environment.GetEnvironmentVariable("USBNASLINK_JAVA_HOME");
        if (LooksLikeJdk(env)) return env;

        // 2) 同目录便携 JRE
        string portable = Path.Combine(exeDir, "runtime");
        if (LooksLikeJdk(portable)) return portable;

        // 3) PATH 上的 java.exe —— 反推 home（…\bin\java.exe → …）
        string fromPath = FindOnPath();
        if (LooksLikeJdk(fromPath)) return fromPath;

        // 4) 注册表：Oracle / Adoptium / Zulu 的 JDK 都会写这些键
        foreach (string hive in new[] { @"SOFTWARE\JavaSoft\JDK", @"SOFTWARE\JavaSoft\Java Development Kit" })
        {
            string h = FromRegistry(hive);
            if (LooksLikeJdk(h)) return h;
        }

        // 5) 常见安装目录（按新到旧）
        string[] guesses =
        {
            @"C:\Program Files\Java\jdk-21",
            @"C:\Program Files\Java\jdk-17",
            @"C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot",
            @"C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot",
        };
        foreach (string g in guesses)
        {
            if (LooksLikeJdk(g)) return g;
        }

        // 6) JAVA_HOME 兜底
        string jh = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (LooksLikeJdk(jh)) return jh;
        return null;
    }

    private static bool LooksLikeJdk(string home)
    {
        if (string.IsNullOrEmpty(home)) return false;
        try
        {
            return File.Exists(Path.Combine(home, "bin", "javaw.exe"));
        }
        catch
        {
            return false;
        }
    }

    private static string FindOnPath()
    {
        string path = Environment.GetEnvironmentVariable("PATH");
        if (string.IsNullOrEmpty(path)) return null;
        foreach (string part in path.Split(Path.PathSeparator))
        {
            if (string.IsNullOrEmpty(part)) continue;
            try
            {
                string cand = Path.Combine(part.Trim(), "java.exe");
                if (File.Exists(cand))
                {
                    return Path.GetFullPath(Path.Combine(part.Trim(), ".."));
                }
            }
            catch
            {
            }
        }
        return null;
    }

    private static string FromRegistry(string subKey)
    {
        try
        {
            using (var baseKey = Microsoft.Win32.Registry.LocalMachine.OpenSubKey(subKey))
            {
                if (baseKey == null) return null;
                // 取版本号最大的子键
                string best = null, bestHome = null;
                foreach (string name in baseKey.GetSubKeyNames())
                {
                    using (var v = baseKey.OpenSubKey(name))
                    {
                        if (v == null) continue;
                        object home = v.GetValue("JavaHome");
                        if (home == null) continue;
                        if (best == null || string.CompareOrdinal(name, best) > 0)
                        {
                            best = name;
                            bestHome = home.ToString();
                        }
                    }
                }
                return bestHome;
            }
        }
        catch
        {
            return null;
        }
    }

    private static void Error(string msg)
    {
        try
        {
            // 不引 WinForms，避免加 /r:System.Windows.Forms 的编译依赖；
            // MessageBox 通过 user32 导出函数直接调。
            MessageBoxW(IntPtr.Zero, msg, "USBNASLink", 0x10);
        }
        catch
        {
            Console.Error.WriteLine(msg);
        }
    }

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int MessageBoxW(IntPtr hWnd, string text, string caption, uint type);
}
