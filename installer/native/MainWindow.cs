using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI;
using System.Diagnostics;
using System.Text;
using System.Text.Json;
using Windows.Graphics;

namespace PTVInstaller;

sealed partial class MainWindow : Window
{
    readonly Grid root = new();
    readonly TextBox address = new() { PlaceholderText = "192.168.1.20:5555", MinWidth = 260 };
    readonly StackPanel history = new() { Spacing = 2, Visibility = Visibility.Collapsed };
    readonly ComboBox devices = new() { PlaceholderText = "选择 USB 或已连接的电视", HorizontalAlignment = HorizontalAlignment.Stretch };
    readonly TextBox pairAddress = new() { PlaceholderText = "电视显示的 IP:配对端口" };
    readonly TextBox pairCode = new() { PlaceholderText = "6 位配对码", MaxLength = 6, Width = 150 };
    readonly TextBlock deviceInfo = Text("尚未连接", 14);
    readonly TextBlock phase = Text("准备连接电视", 13, true);
    readonly TextBlock compatibility = Text("", 11, color: "977C57");
    readonly ProgressBar progress = new() { Maximum = 100, Height = 5 };
    readonly InfoBar error = new() { Severity = InfoBarSeverity.Error, IsClosable = true };
    readonly StackPanel checks = new() { Spacing = 10 };
    readonly TextBox logs = new() { IsReadOnly = true, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, MaxHeight = 250 };
    readonly List<(Button Button, string Requirement)> buttons = new();
    readonly Backend backend = new();
    readonly TextBox releaseRepo = new() { PlaceholderText = "https://github.com/用户名/仓库" };
    readonly TextBox releaseAsset = new() { Text = "PTV.apk", PlaceholderText = "Release 附件文件名，例如 PTV.apk" };
    readonly CheckBox onlineSource = new() { Content = "安装时从 GitHub 获取最新正式版" };
    readonly TextBlock releaseInfo = Text("", 11, color: "798291");
    string releaseSourceKey = "";
    JsonElement state;
    bool hasState, busy, connected, eligible, backup, updatingDevices, allowClose;
    string historyKey = "", devicesKey = "", checksKey = "";
    List<string> addresses = new();

    public MainWindow()
    {
        Title = "PTV 安装助手";
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "PTV.ico"));
        SizeForDisplay();
        try { SystemBackdrop = new MicaBackdrop(); } catch { }
        BuildFrame();
        var content = pageContent;
        var connect = SectionHeading("连接你的电视", "电脑与电视连接同一局域网，并在电视设置中开启 ADB 调试。");
        connect.Children.Add(Text("电视地址", 12, color: "555D6D"));
        var addressRow = Row(address, Button("连接电视", "idle", () => Send("connect", ("address", address.Text)), true));
        connect.Children.Add(addressRow);
        connect.Children.Add(new ScrollViewer { Content = history, MaxHeight = 280, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled });
        connect.Children.Add(Text("首次连接时，请在电视上选择“允许调试”。历史地址右侧 × 可删除记录。", 11, color: "8F95A1"));
        connect.Children.Add(Row(devices, Button("刷新设备", "idle", () => Send("scan"))));
        connect.Children.Add(deviceInfo);
        var pair = new StackPanel { Spacing = 10 };
        pair.Children.Add(Row(pairAddress, pairCode));
        pair.Children.Add(Button("配对", "idle", () => { Send("pair", ("address", pairAddress.Text), ("code", pairCode.Text)); pairCode.Text = ""; }));
        pair.Children.Add(Text("配对后，请使用电视显示的调试端口在上方连接。", 12));
        connect.Children.Add(Disclosure("使用无线配对码连接", pair));
        var connectionCard = Card(connect); content.Children.Add(connectionCard);
        address.GotFocus += (_, _) => RenderHistory(true);
        address.TextChanged += (_, _) => RenderHistory(true);
        address.KeyDown += (_, e) => { if (e.Key == Windows.System.VirtualKey.Enter) { Send("connect", ("address", address.Text)); e.Handled = true; } else if (e.Key == Windows.System.VirtualKey.Escape) history.Visibility = Visibility.Collapsed; };
        devices.SelectionChanged += (_, _) => { if (!updatingDevices && devices.SelectedItem is ComboBoxItem item && item.Tag is string serial) Send("select", ("serial", serial)); };

        var setup = new StackPanel { Spacing = 8 };
        setup.Children.Add(Row(Text("一次配置，即刻开始", 16, true), Text("保留现有 PTV 数据", 10, color: "959BA6")));
        setup.Children.Add(Feature("download", "4C8CE5", "EDF4FF", "自动检查 PTV", "已安装则直接配置，未安装则自动安装"));
        setup.Children.Add(Divider());
        setup.Children.Add(Feature("tv", "9571CF", "F1EDFC", "设为默认桌面", "主页键与开机启动入口"));
        setup.Children.Add(Divider());
        setup.Children.Add(Feature("shield", "5AAB70", "EDF8EF", "开启遥控器快捷功能", "无障碍授权、长按返回与服务自动恢复"));
        setup.Children.Add(compatibility);
        setup.Children.Add(Divider());
        setup.Children.Add(Text("连接电视后即可开始。已有 PTV 时保留版本和数据；未安装时自动安装。", 11, color: "8B929F"));
        var online = new StackPanel { Spacing = 10 };
        online.Children.Add(onlineSource);
        online.Children.Add(Text("GitHub 公开仓库", 12)); online.Children.Add(releaseRepo);
        online.Children.Add(Text("PTV APK 附件文件名（需与 Release 完全一致）", 12)); online.Children.Add(releaseAsset);
        online.Children.Add(Wrap(Button("保存安装来源", "idle", () => Send("saveReleaseSource", ("releaseSource", new { enabled = onlineSource.IsChecked == true, repository = releaseRepo.Text, asset = releaseAsset.Text }))), Button("检查最新版本", "idle", () => Send("checkRelease"))));
        online.Children.Add(Text("修改后请先保存。关闭在线来源后使用内置 APK。在线安装失败时不会自动改装旧版；兼容入口仍使用内置版本。", 11, color: "798291"));
        online.Children.Add(releaseInfo);
        setup.Children.Add(Disclosure("安装来源与在线更新", online));
        setup.Children.Add(Wrap(
            Button("安装／更新 PTV", "tv", async () => { if (await Confirm("安装／更新 PTV", (state.TryGetProperty("releaseSource", out var rs) && B(rs, "enabled") ? "从已保存的 GitHub 仓库下载最新正式版 PTV 并保留数据。" : "安装内置 PTV 版本并保留数据。") + "此操作不修改默认桌面或快捷功能；签名不一致或版本不兼容时停止，不会卸载清除数据。")) Send("installOnly"); }),
            Button("一键配置", "tv", async () => { if (await Confirm("配置并启用 PTV", "保留已安装的 PTV 和数据，重新启用桌面、权限及快捷功能。未安装时自动安装。" + (eligible ? "\n这台电视会启用小米兼容模式，停用原生桌面并设置 PTV 系统入口。" : ""))) Send("configure"); }, true)));
        ((FrameworkElement)setup.Children[^1]).HorizontalAlignment = HorizontalAlignment.Right;
        var setupCard = Card(setup); content.Children.Add(setupCard);

        var status = new StackPanel { Spacing = 12 };
        status.Children.Add(Row(phase, percentage)); progress.Foreground = Brush("007AFF"); status.Children.Add(progress); status.Children.Add(error); status.Children.Add(checks);
        status.Children.Add(Wrap(Button("重新检查", "tv", () => Send("verify")),
            Button("重启验证", "tv", async () => { if (await Confirm("重启电视", "电视将立即重启，当前播放会中断。开机后重新连接并检查。")) Send("reboot"); }),
            Button("电视桌面设置", "tv", () => Send("homeSettings"))));
        var logPanel = new StackPanel { Spacing = 8 }; logPanel.Children.Add(logs);
        logPanel.Children.Add(Button("保存日志", "idle", async () => {
            try { string path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "PTV-安装记录-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".txt"); await File.WriteAllTextAsync(path, logs.Text, Encoding.UTF8); await Message("日志已保存", path); }
            catch (Exception ex) { ShowError(ex.Message); }
        }));
        status.Children.Add(Disclosure("查看操作记录", logPanel));
        var resultsCard = Card(status); content.Children.Add(resultsCard);

        var restore = SectionHeading("一键还原原生系统", "恢复小米原生桌面，关闭 PTV 接管；可选择保留或卸载 PTV。", "reset");
        restore.Children.Add(Text("默认停用 PTV 并保留应用和数据，之后可一键配置重新启用。也可选择卸载并删除 PTV 数据。其他应用、账号与电视设置保留。", 11, color: "798291"));
        restore.Children.Add(Wrap(Button("一键还原", "native", RestoreNative), Button("恢复安装前备份", "backup", async () => { if (await Confirm("恢复安装前备份", "备份本身可能已使用 PTV 或 HomeBridge，因此不一定回到小米桌面。若要回到小米桌面，请使用一键还原。\n现有 PTV 数据保留。")) Send("restore"); })));
        restore.Children.Add(Text("原生还原目前仅适配 MiTV-MFTR0 / OS3.0.102.0.UFTMATV。", 12));
        content.Children.Add(Card(restore));
        content.Children.Add(Wrap(Button("版本与许可", "idle", async () => await Message("PTV 安装助手 · WinUI 3", "原生 WinUI 3 界面。\nPTV：PolyForm Noncommercial 1.0.0\nADB：Google Platform Tools\n第三方许可位于本机 PTVInstaller/payload 目录。\n当前 EXE 未做代码签名。")), Button("退出助手", "idle", () => Close())));
        BuildSidebar(connectionCard, setupCard, resultsCard);
        Content = root;
        AppWindow.Closing += async (_, e) => {
            if (!busy || allowClose) return;
            e.Cancel = true;
            if (await Confirm("操作正在进行", "退出会中断当前配置，可能留下部分设置。确认退出后，可重新打开检查或重试。", "退出")) { allowClose = true; Close(); }
        };
        Closed += (_, _) => backend.Dispose();
        backend.StateReceived += s => DispatcherQueue.TryEnqueue(() => ApplyState(s));
        backend.Failed += message => DispatcherQueue.TryEnqueue(() => ShowError(message));
        SetEnabled();
        try { backend.Start(); } catch (Exception ex) { ShowError("无法启动配置服务：" + ex.Message); }
    }

    static TextBlock Text(string text, double size, bool bold = false, string color = "24262D") => new() { Text = text, FontSize = size, Foreground = Brush(color), FontWeight = bold ? Microsoft.UI.Text.FontWeights.SemiBold : Microsoft.UI.Text.FontWeights.Normal, TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center };
    static Border Card(UIElement child) => new() { Child = child, Padding = new Thickness(24), CornerRadius = new CornerRadius(18), Background = Brush("C7FFFFFF"), BorderBrush = Brush("FFFFFF"), BorderThickness = new Thickness(1) };
    static StackPanel Disclosure(string title, UIElement child) {
        var body = new Border { Child = child, Visibility = Visibility.Collapsed, Padding = new(0, 12, 0, 0) };
        var toggle = new Button { Content = "▸ " + title, Style = (Style)Application.Current.Resources["QuietButtonStyle"], Padding = new(0, 7, 0, 7) };
        toggle.Click += (_, _) => { bool open = body.Visibility != Visibility.Visible; body.Visibility = open ? Visibility.Visible : Visibility.Collapsed; toggle.Content = (open ? "▾ " : "▸ ") + title; };
        var panel = new StackPanel(); panel.Children.Add(Divider()); panel.Children.Add(toggle); panel.Children.Add(body); return panel;
    }
    static Grid Row(FrameworkElement left, FrameworkElement right) { var grid = new Grid { ColumnSpacing = 10 }; grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); grid.Children.Add(left); Grid.SetColumn(right, 1); grid.Children.Add(right); return grid; }
    static StackPanel Wrap(params UIElement[] children) { var panel = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10 }; foreach (var child in children) panel.Children.Add(child); return panel; }
    Button Button(string text, string requirement, Action click, bool accent = false)
    {
        var button = new Button { Content = text, Style = (Style)Application.Current.Resources[accent ? "PrimaryButtonStyle" : "SoftButtonStyle"] };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, text);
        if (text is "版本与许可" or "退出助手") button.Style = (Style)Application.Current.Resources["QuietButtonStyle"];
        if (accent) button.Content = Wrap(Text(text, 13, true, "FFFFFF"), Icon("arrow", "FFFFFF", 18));
        button.Click += (_, _) => { try { click(); } catch (Exception ex) { ShowError(ex.Message); } };
        buttons.Add((button, requirement)); return button;
    }
    void ShowError(string message) { error.Message = message; error.IsOpen = true; }
    void Send(string action, params (string Key, object Value)[] args)
    {
        if (busy) return;
        if (action != "deleteHistory") history.Visibility = Visibility.Collapsed;
        var command = new Dictionary<string, object> { ["action"] = action }; foreach (var pair in args) command[pair.Key] = pair.Value;
        try { busy = true; SetEnabled(); backend.Send(command); } catch (Exception ex) { busy = false; SetEnabled(); ShowError(ex.Message); }
    }
    static string S(JsonElement e, string name) => e.TryGetProperty(name, out var p) && p.ValueKind == JsonValueKind.String ? p.GetString() ?? "" : "";
    static bool B(JsonElement e, string name) => e.TryGetProperty(name, out var p) && p.ValueKind == JsonValueKind.True;
    void ApplyState(JsonElement s)
    {
        if (s.TryGetProperty("releaseSource", out var source)) {
            var key = source.GetRawText();
            if (key != releaseSourceKey) { releaseSourceKey = key; onlineSource.IsChecked = B(source, "enabled"); releaseRepo.Text = S(source, "repository"); releaseAsset.Text = S(source, "asset") is { Length: > 0 } name ? name : "PTV.apk"; }
        }
        releaseInfo.Text = S(s, "releaseInfo");
        state = s; hasState = true; busy = B(s, "busy"); backup = B(s, "backup");
        connected = s.TryGetProperty("tv", out var tv) && tv.ValueKind == JsonValueKind.Object; eligible = connected && B(tv, "bridgeEligible");
        phase.Text = S(s, "phase"); if (s.TryGetProperty("progress", out var p)) { progress.Value = p.GetInt32(); percentage.Text = p.GetInt32() + "%"; }
        connectionLabel.Text = connected ? "已连接" : "尚未连接"; connectionDot.Background = Brush(connected ? "34C759" : "ACB0BA");
        deviceInfo.Visibility = connected ? Visibility.Visible : Visibility.Collapsed;
        string issue = S(s, "error"); error.Message = issue; error.IsOpen = issue.Length > 0;
        deviceInfo.Text = connected ? $"{S(tv, "manufacturer")} {S(tv, "model")} · Android {S(tv, "android")} · {S(tv, "serial")}" : "尚未连接";
        compatibility.Visibility = eligible ? Visibility.Visible : Visibility.Collapsed;
        compatibility.Text = eligible ? "已适配此电视：一键配置自动启用小米兼容模式。" : "默认桌面与快捷功能的可用性取决于电视固件。";
        string newHistory = s.TryGetProperty("connectionHistory", out var h) ? h.GetRawText() : "[]";
        if (newHistory != historyKey) { historyKey = newHistory; addresses = h.ValueKind == JsonValueKind.Array ? h.EnumerateArray().Select(v => v.GetString() ?? "").ToList() : new(); RenderHistory(history.Visibility == Visibility.Visible); }
        string newDevices = s.TryGetProperty("devices", out var ds) ? ds.GetRawText() : "[]";
        if (newDevices != devicesKey) {
            devicesKey = newDevices; updatingDevices = true; devices.Items.Clear();
            if (ds.ValueKind == JsonValueKind.Array) foreach (var d in ds.EnumerateArray()) { string serial = S(d, "serial"); devices.Items.Add(new ComboBoxItem { Content = serial + " · " + S(d, "state"), Tag = serial }); }
            updatingDevices = false;
        }
        string newChecks = s.TryGetProperty("checks", out var cs) ? cs.GetRawText() : "[]";
        if (newChecks != checksKey) { checksKey = newChecks; checks.Children.Clear(); if (cs.ValueKind == JsonValueKind.Array) foreach (var c in cs.EnumerateArray()) { var line = new StackPanel { Spacing = 3 }; line.Children.Add(Text((S(c, "status") == "ok" ? "✓  " : "•  ") + S(c, "name"), 14, true)); line.Children.Add(Text(S(c, "detail"), 12)); checks.Children.Add(line); } }
        if (s.TryGetProperty("logs", out var ls) && ls.ValueKind == JsonValueKind.Array) { string next = string.Join("\n", ls.EnumerateArray().Select(v => v.GetString())); if (logs.Text != next) logs.Text = next; }
        SetEnabled();
    }
    void SetEnabled()
    {
        address.IsEnabled = devices.IsEnabled = pairAddress.IsEnabled = pairCode.IsEnabled = !busy;
        foreach (var (button, requirement) in buttons) button.IsEnabled = !busy && hasState && (requirement switch { "tv" => connected, "native" => eligible, "backup" => connected && backup, _ => true });
    }
    void RenderHistory(bool show)
    {
        history.Children.Clear();
        if (!show || busy) { history.Visibility = Visibility.Collapsed; return; }
        foreach (var value in addresses.Where(a => a.Contains(address.Text.Trim(), StringComparison.OrdinalIgnoreCase)).Take(50)) {
            var choose = new Button { Style = (Style)Application.Current.Resources["QuietButtonStyle"], Content = value, HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Left };
            choose.Click += (_, _) => { address.Text = value; Send("connect", ("address", value)); };
            var delete = new Button { Style = (Style)Application.Current.Resources["QuietButtonStyle"], Padding = new(4), Content = "×", Width = 36 }; ToolTipService.SetToolTip(delete, "删除此连接记录");
            delete.Click += (_, _) => Send("deleteHistory", ("address", value));
            history.Children.Add(Row(choose, delete));
        }
        history.Visibility = history.Children.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
    }
}

sealed class Backend : IDisposable
{
    Process? process;
    volatile bool disposed;
    public event Action<JsonElement>? StateReceived;
    public event Action<string>? Failed;
    public void Start()
    {
        var start = new ProcessStartInfo(Path.Combine(AppContext.BaseDirectory, "ptv-backend.exe")) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true, StandardOutputEncoding = Encoding.UTF8, StandardInputEncoding = new UTF8Encoding(false), StandardErrorEncoding = Encoding.UTF8 };
        process = Process.Start(start) ?? throw new InvalidOperationException("无法创建配置服务进程");
        _ = Task.Run(async () => { try { while (await process.StandardOutput.ReadLineAsync() is string line) { using var document = JsonDocument.Parse(line); StateReceived?.Invoke(document.RootElement.Clone()); } if (!disposed) Failed?.Invoke("配置服务已结束，请关闭助手后重新打开。"); } catch (Exception ex) { if (!disposed) Failed?.Invoke(ex.Message); } });
        _ = Task.Run(async () => { string details = await process.StandardError.ReadToEndAsync(); if (!disposed && details.Length > 0) Failed?.Invoke(details); });
    }
    public void Send(object request) { if (disposed || process == null || process.HasExited) throw new InvalidOperationException("配置服务未运行"); process.StandardInput.WriteLine(JsonSerializer.Serialize(request)); process.StandardInput.Flush(); }
    public void Dispose()
    {
        if (disposed) return; disposed = true;
        if (process != null) { try { process.StandardInput.Close(); if (!process.WaitForExit(1500)) process.Kill(); } catch { } process.Dispose(); }
    }
}
