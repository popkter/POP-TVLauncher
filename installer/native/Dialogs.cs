using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Automation;

namespace PTVInstaller;

sealed partial class MainWindow
{
    // Keep WinUI's modal focus scope, Escape handling and scrolling, but use the
    // same card/button design as the main window instead of the system footer.
    sealed class AppDialog : ContentDialog
    {
        public Button ActionButton { get; }
        readonly Button dismiss;
        bool accepted;

        public AppDialog(XamlRoot root, string title, UIElement body, string? action, string icon = "shield")
        {
            XamlRoot = root;
            RequestedTheme = ElementTheme.Light;
            DefaultButton = ContentDialogButton.None;
            CornerRadius = new(22);
            Background = Brush("FDFDFF");
            BorderBrush = Brush("FFFFFF");
            BorderThickness = new(1);
            AutomationProperties.SetName(this, title);
            Resources["ContentDialogMaxWidth"] = 460d;
            Resources["ContentDialogMinWidth"] = 300d;
            Resources["ContentDialogPadding"] = new Thickness(30);
            Resources["ContentDialogTitleMargin"] = new Thickness(0);
            Resources["ContentDialogSeparatorThickness"] = new Thickness(0);
            Resources["ContentDialogTopOverlay"] = Brush("FDFDFF");
            Resources["OverlayCornerRadius"] = new CornerRadius(22);

            var panel = new StackPanel { Spacing = 18, MinWidth = 0 };
            var tile = IconTile(icon, "4F86D8", "EFF4FC", 50);
            tile.HorizontalAlignment = HorizontalAlignment.Center;
            panel.Children.Add(tile);
            var heading = Text(title, 20, true);
            heading.TextAlignment = TextAlignment.Center;
            panel.Children.Add(heading);
            panel.Children.Add(body);

            var actions = new Grid { ColumnSpacing = 10, Margin = new(0, 6, 0, 0) };
            dismiss = DialogButton(action == null ? "知道了" : "取消", action == null ? "PrimaryButtonStyle" : "SoftButtonStyle");
            dismiss.Click += (_, _) => Hide();
            actions.ColumnDefinitions.Add(new() { Width = new(1, GridUnitType.Star) });
            actions.Children.Add(dismiss);
            ActionButton = DialogButton(action ?? "知道了", "PrimaryButtonStyle");
            if (action != null) {
                actions.ColumnDefinitions.Add(new() { Width = new(1, GridUnitType.Star) });
                Grid.SetColumn(ActionButton, 1);
                actions.Children.Add(ActionButton);
                ActionButton.Click += (_, _) => { accepted = true; Hide(); };
            }
            panel.Children.Add(actions);
            Content = panel;
            // A blue action button must not imply that Enter confirms a destructive
            // operation on opening. Initial focus remains on the grey Cancel button.
            Opened += (_, _) => dismiss.Focus(FocusState.Programmatic);
        }

        static Button DialogButton(string text, string style) => new() {
            Content = Text(text, 13, true, style == "SoftButtonStyle" ? "333844" : "FFFFFF"),
            Style = (Style)Application.Current.Resources[style],
            HorizontalAlignment = HorizontalAlignment.Stretch, MinHeight = 44,
            Padding = new(10, 12, 10, 12)
        };

        public async Task<bool> ShowChoiceAsync() { await ShowAsync(); return accepted; }
    }

    static TextBlock DialogDescription(string text)
    {
        var block = Text(text, 13, color: "7E8797");
        block.TextAlignment = TextAlignment.Center;
        block.LineHeight = 23;
        return block;
    }

    async Task<bool> Confirm(string title, string message, string action = "继续") =>
        await new AppDialog(root.XamlRoot, title, DialogDescription(message), action).ShowChoiceAsync();

    async Task Message(string title, string text) =>
        await new AppDialog(root.XamlRoot, title, DialogDescription(text), null).ShowChoiceAsync();

    async void RestoreNative()
    {
        var remove = new CheckBox {
            Content = Text("同时卸载 PTV 和兼容入口，并删除全部 PTV 数据", 13, color: "7C4230"),
            IsChecked = false, HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Stretch, VerticalContentAlignment = VerticalAlignment.Top
        };
        AutomationProperties.SetName(remove, "同时卸载 PTV 和兼容入口，并删除全部 PTV 数据");
        var warning = Text("将永久删除布局、自定义壁纸和偏好，无法从配置备份找回。", 12, color: "B44737");
        warning.Visibility = Visibility.Collapsed;
        var choice = new StackPanel { Spacing = 10 };
        choice.Children.Add(remove); choice.Children.Add(warning);
        var panel = new StackPanel { Spacing = 16 };
        panel.Children.Add(DialogDescription("恢复小米原生桌面并关闭 PTV 接管。\n默认保留应用和数据，之后一键配置即可重新启用。"));
        panel.Children.Add(new Border { Child = choice, Padding = new(14), CornerRadius = new(10), Background = Brush("FFF4ED") });
        panel.Children.Add(DialogDescription("其他应用、账号和电视设置保持不变。"));
        var dialog = new AppDialog(root.XamlRoot, "还原小米原生桌面", panel, "还原并保留数据", "reset");
        void UpdateChoice() {
            bool deleting = remove.IsChecked == true;
            string label = deleting ? "还原、卸载并删除数据" : "还原并保留数据";
            dialog.ActionButton.Content = Text(label, 13, true, "FFFFFF");
            AutomationProperties.SetName(dialog.ActionButton, label);
            dialog.ActionButton.Background = Brush(deleting ? "C64B3F" : "007AFF");
            warning.Visibility = deleting ? Visibility.Visible : Visibility.Collapsed;
        }
        remove.Checked += (_, _) => UpdateChoice();
        remove.Unchecked += (_, _) => UpdateChoice();
        UpdateChoice();
        if (await dialog.ShowChoiceAsync()) Send("restoreNative", ("confirmNative", true), ("removePTV", remove.IsChecked == true));
    }
}
