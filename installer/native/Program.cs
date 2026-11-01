using Microsoft.UI.Xaml;
using System.Threading;
using System.Runtime.InteropServices;

namespace PTVInstaller;
public sealed partial class NativeApp : Application
{
#if RUNTIME_LITE
    public const string Edition = "轻量版（不含运行库）";
#else
    public const string Edition = "完整版（含运行库）";
#endif
    MainWindow? window;
    Mutex? instance;
    public NativeApp()
    {
        UnhandledException += (_, e) => { try { File.AppendAllText(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PTVInstaller-native-error.log"), e.Exception.ToString()+"\n"); } catch { } };
        InitializeComponent();
    }
    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        instance = new Mutex(true, @"Local\PTVInstallerNative", out bool created);
        if (!created) { var existing=FindWindow(null,"PTV 安装助手"); if(existing!=IntPtr.Zero){ShowWindow(existing,9);SetForegroundWindow(existing);} instance.Dispose(); Exit(); return; }
        window = new MainWindow();
        window.Closed += (_, _) => { instance.ReleaseMutex(); instance.Dispose(); };
        window.Activate();
    }
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] static extern IntPtr FindWindow(string? className,string title);
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr window);
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr window,int command);
}
