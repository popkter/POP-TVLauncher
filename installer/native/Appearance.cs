using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Markup;
using Windows.Foundation;

namespace PTVInstaller;

sealed partial class MainWindow
{
    [System.Runtime.InteropServices.DllImport("user32.dll")]
    static extern uint GetDpiForWindow(IntPtr window);
    void SizeForDisplay()
    {
        double scale = GetDpiForWindow(WinRT.Interop.WindowNative.GetWindowHandle(this)) / 96.0;
        var area = Microsoft.UI.Windowing.DisplayArea.GetFromWindowId(AppWindow.Id, Microsoft.UI.Windowing.DisplayAreaFallback.Primary).WorkArea;
        int width = Math.Min((int)(1120 * scale), area.Width - 32);
        int height = Math.Min((int)(920 * scale), area.Height - 32);
        AppWindow.MoveAndResize(new Windows.Graphics.RectInt32(area.X + (area.Width - width) / 2, area.Y + (area.Height - height) / 2, width, height));
    }
    readonly ScrollViewer pageScroll = new() {
        VerticalScrollMode = ScrollMode.Enabled, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
        HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
        ZoomMode = ZoomMode.Disabled, Background = Brush("00000000")
    };
    readonly TextBlock connectionLabel = Text("尚未连接", 11, color: "737782");
    readonly TextBlock percentage = Text("0%", 12, color: "818B9C");
    readonly Border connectionDot = new() { Width = 6, Height = 6, CornerRadius = new(3), Background = Brush("ACB0BA") };
    readonly List<(Button Button, Border Number, TextBlock Label, FrameworkElement Target)> navigation = new();
    readonly StackPanel pageContent = new() { Spacing = 18, Padding = new(40, 42, 40, 24), MaxWidth = 1100, HorizontalAlignment = HorizontalAlignment.Stretch, Background = Brush("00000000") };

    static Windows.UI.Color Color(string hex)
    {
        if (hex.Length == 6) hex = "FF" + hex;
        uint value = Convert.ToUInt32(hex, 16);
        return Windows.UI.Color.FromArgb((byte)(value >> 24), (byte)(value >> 16), (byte)(value >> 8), (byte)value);
    }
    static SolidColorBrush Brush(string hex) => new(Color(hex));
    static LinearGradientBrush Gradient(string start, string middle, string end) => new() {
        StartPoint = new(1, 0), EndPoint = new(0, 1),
        GradientStops = { new() { Color = Color(start), Offset = 0 }, new() { Color = Color(middle), Offset = .55 }, new() { Color = Color(end), Offset = 1 } }
    };
    static FrameworkElement Icon(string name, string color = "5386D7", double size = 22)
    {
        string data = name switch {
            "tv" => "M5.5,4 L18.5,4 Q21.5,4 21.5,7 L21.5,14.5 Q21.5,17.5 18.5,17.5 L5.5,17.5 Q2.5,17.5 2.5,14.5 L2.5,7 Q2.5,4 5.5,4 M8,21 L16,21 M12,17.5 L12,21",
            "download" => "M12,3 L12,15 M7,10 L12,15 L17,10 M4,16 L4,20 L20,20 L20,16",
            "shield" => "M12,2.5 L3.5,6 L3.5,12 C3.5,17 12,21.5 12,21.5 C12,21.5 20.5,17 20.5,12 L20.5,6 Z M8,12 L11,15 L16,9",
            "reset" => "M4,9 A8.5,8.5 0 1 1 4,16 M4,3 L4,9 L10,9",
            "arrow" => "M4,12 L19,12 M13,6 L19,12 L13,18",
            _ => "M5,12 L9.5,16.5 L19,7"
        };
        // These are local vector paths rendered by WinUI, not browser content.
        var path = (Microsoft.UI.Xaml.Shapes.Path)XamlReader.Load($"<Path xmlns='http://schemas.microsoft.com/winfx/2006/xaml/presentation' Data='{data}' Stroke='#{color}' StrokeThickness='1.65' StrokeStartLineCap='Round' StrokeEndLineCap='Round' StrokeLineJoin='Round' />");
        var canvas = new Canvas { Width = 24, Height = 24 }; canvas.Children.Add(path);
        return new Viewbox { Width = size, Height = size, Child = canvas };
    }
    static Border IconTile(string name, string color = "5386D7", string background = "EEF4FF", double size = 38) => new() {
        Width = size, Height = size, CornerRadius = new(size / 3.5), Background = Brush(background), Child = Icon(name, color, size * .56), VerticalAlignment = VerticalAlignment.Center
    };
    static Border Divider() => new() { Height = 1, Background = Brush("14232D46"), Margin = new(0, 5, 0, 5) };
    static StackPanel SectionHeading(string title, string description, string icon = "tv")
    {
        var caption = new StackPanel { Spacing = 6, VerticalAlignment = VerticalAlignment.Center };
        caption.Children.Add(Text(title, 16, true)); caption.Children.Add(Text(description, 11, color: "737782"));
        var grid = new Grid { ColumnSpacing = 13, Margin = new(0, 0, 0, 8) };
        grid.ColumnDefinitions.Add(new() { Width = GridLength.Auto }); grid.ColumnDefinitions.Add(new() { Width = new(1, GridUnitType.Star) });
        grid.Children.Add(IconTile(icon)); Grid.SetColumn(caption, 1); grid.Children.Add(caption);
        var panel = new StackPanel { Spacing = 12 }; panel.Children.Add(grid); return panel;
    }
    static Grid Feature(string icon, string color, string background, string title, string subtitle)
    {
        var caption = new StackPanel { Spacing = 5, VerticalAlignment = VerticalAlignment.Center };
        caption.Children.Add(Text(title, 12, true)); caption.Children.Add(Text(subtitle, 11, color: "89909C"));
        var grid = new Grid { ColumnSpacing = 12, Padding = new(0, 10, 0, 10) };
        grid.ColumnDefinitions.Add(new() { Width = GridLength.Auto }); grid.ColumnDefinitions.Add(new() { Width = new(1, GridUnitType.Star) }); grid.ColumnDefinitions.Add(new() { Width = GridLength.Auto });
        grid.Children.Add(IconTile(icon, color, background, 32)); Grid.SetColumn(caption, 1); grid.Children.Add(caption);
        var tick = Icon("check", "A2AFC3", 17); Grid.SetColumn(tick, 2); grid.Children.Add(tick); return grid;
    }
    void BuildFrame()
    {
        root.RequestedTheme = ElementTheme.Light;
        root.Background = Gradient("E2EBFD", "F4F5F9", "EAE3F4");
        root.ColumnDefinitions.Add(new() { Width = new(224) }); root.ColumnDefinitions.Add(new() { Width = new(1, GridUnitType.Star) });
        pageScroll.Content = pageContent; Grid.SetColumn(pageScroll, 1); root.Children.Add(pageScroll);
        // Handle wheel input before the outer ScrollViewer. TextBox/ComboBox can consume
        // routed wheel events even when they have no scrollable content of their own.
        pageContent.AddHandler(UIElement.PointerWheelChangedEvent, new PointerEventHandler(PageWheel), true);
        pageScroll.ViewChanged += (_, _) => UpdateNavigation();
        var heading = new StackPanel { Spacing = 9, Margin = new(0, 0, 0, 12) };
        heading.Children.Add(Text("WELCOME TO PTV", 10, true, "9299AB"));
        heading.Children.Add(Text("让电视，回归简单。", 30, true));
        heading.Children.Add(Text("连接电视，其余交给安装助手。", 13, color: "737782"));
        var pillContent = Wrap(connectionDot, connectionLabel); pillContent.VerticalAlignment = VerticalAlignment.Center;
        var pill = new Border { Child = pillContent, Padding = new(11, 8, 11, 8), CornerRadius = new(20), Background = Brush("70FFFFFF"), BorderBrush = Brush("99FFFFFF"), BorderThickness = new(1), VerticalAlignment = VerticalAlignment.Center };
        pageContent.Children.Add(Row(heading, pill));
    }
    void BuildSidebar(params FrameworkElement[] sections)
    {
        var side = new Grid { Padding = new(20, 40, 20, 24), Background = Brush("45FFFFFF"), BorderBrush = Brush("90FFFFFF"), BorderThickness = new(0, 0, 1, 0) };
        side.RowDefinitions.Add(new() { Height = GridLength.Auto }); side.RowDefinitions.Add(new() { Height = new(1, GridUnitType.Star) }); side.RowDefinitions.Add(new() { Height = GridLength.Auto });
        var brand = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 13, Margin = new(10, 0, 0, 46) };
        brand.Children.Add(new Border { Width = 48, Height = 48, CornerRadius = new(13), Background = Gradient("5EA5FF", "437FEF", "3465DF"), Child = Icon("tv", "FFFFFF", 29) });
        var brandText = new StackPanel { Spacing = 4, VerticalAlignment = VerticalAlignment.Center }; brandText.Children.Add(Text("PTV", 23, true)); brandText.Children.Add(Text("安装助手", 11, color: "737782")); brand.Children.Add(brandText); side.Children.Add(brand);
        var nav = new StackPanel { Spacing = 5 }; nav.Children.Add(new Border { Padding = new(14, 0, 0, 5), Child = Text("开始使用", 11, color: "9295A0") });
        string[] labels = ["连接电视", "安装与配置", "检查与完成"];
        for (int i = 0; i < sections.Length; i++) {
            var target = sections[i];
            var number = new Border { Width = 19, Height = 19, CornerRadius = new(10), BorderThickness = new(1), Child = new TextBlock { Text = (i + 1).ToString(), FontSize = 10, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center } };
            var label = Text(labels[i], 13); var navButton = new Button { Content = Wrap(number, label), HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Left, Padding = new(14, 12, 14, 12), Style = (Style)Application.Current.Resources["QuietButtonStyle"] };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(navButton, labels[i]);
            navButton.Click += (_, _) => { var y = target.TransformToVisual(pageContent).TransformPoint(new Point(0, 0)).Y; pageScroll.ChangeView(null, Math.Max(0, y - 24), null, true); };
            navigation.Add((navButton, number, label, target)); nav.Children.Add(navButton);
        }
        Grid.SetRow(nav, 1); side.Children.Add(nav);
        var bottom = new StackPanel { Spacing = 14, Padding = new(13, 30, 13, 0) };
        var shield = Icon("shield", "858A98", 23); shield.HorizontalAlignment = HorizontalAlignment.Left; bottom.Children.Add(shield);
        bottom.Children.Add(Text("只在你的电脑与电视之间操作。\n配置备份保存在本机。", 11, color: "858A98"));
        bottom.Children.Add(Text(NativeApp.Edition + " · Windows x64", 10, color: "A1A5B0")); Grid.SetRow(bottom, 2); side.Children.Add(bottom);
        side.AddHandler(UIElement.PointerWheelChangedEvent, new PointerEventHandler(PageWheel), true);
        root.Children.Add(side); root.Loaded += (_, _) => UpdateNavigation();
    }
    void UpdateNavigation()
    {
        if (navigation.Count == 0 || !pageContent.IsLoaded) return;
        int selected = 0;
        for (int i = 1; i < navigation.Count; i++) if (pageScroll.VerticalOffset + 100 >= navigation[i].Target.TransformToVisual(pageContent).TransformPoint(new(0, 0)).Y) selected = i;
        if (pageScroll.ScrollableHeight > 0 && pageScroll.VerticalOffset >= pageScroll.ScrollableHeight - 1) selected = navigation.Count - 1;
        for (int i = 0; i < navigation.Count; i++) {
            var item = navigation[i]; bool active = i == selected;
            item.Button.Background = Brush(active ? "0C007AFF" : "00000000"); item.Label.Foreground = Brush(active ? "0069DF" : "757B89");
            item.Number.Background = Brush(active ? "007AFF" : "00000000"); item.Number.BorderBrush = Brush(active ? "007AFF" : "B6BCC8");
            ((TextBlock)item.Number.Child).Foreground = Brush(active ? "FFFFFF" : "757B89");
        }
    }
    void PageWheel(object sender, PointerRoutedEventArgs e)
    {
        var properties = e.GetCurrentPoint(root).Properties;
        if (properties.IsHorizontalMouseWheel || properties.MouseWheelDelta == 0) return;
        // A real nested scroller (history/logs) keeps its own wheel handling.
        // Non-scrolling inner text editors must not swallow page scrolling.
        for (DependencyObject? item = e.OriginalSource as DependencyObject; item != null && item != pageContent; item = VisualTreeHelper.GetParent(item)) {
            if (item is ScrollViewer nested && nested != pageScroll && nested.ScrollableHeight > 0) return;
            if (item is ComboBox combo && combo.IsDropDownOpen) return;
        }
        double destination = Math.Clamp(pageScroll.VerticalOffset - properties.MouseWheelDelta * .75, 0, pageScroll.ScrollableHeight);
        pageScroll.ChangeView(null, destination, null, true);
        e.Handled = true;
    }
}
