# PTV 系统桌面入口（本机兼容构建）

独立编写，仅使用 Android Activity API，无网络权限、无服务。使用固件 HOME 白名单中的 `com.ts.launcher` 应用 ID，不代表或包含该包名原所有者的软件，不应发布到应用商店。若设备已有这个包，不得覆盖安装。

## 入口 1.1 的启动优化

用户反馈启动黑屏后出现 Android 设置。设备日志确认旧入口在 22:40:05 收到 ActivityNotFoundException 后主动打开了设置，22:40:12 再次启动 PTV 才成功。1.1 不再自动打开设置：入口支持 direct boot，暂时启动不了 PTV 时显示 PTV 启动画面，每 200ms 重试（持续失败时降低到每秒）；30 秒后提供用户主动选择的设置按钮。停止显示时取消重试，避免从后台抢回画面。

PTV 1.3.4 使用磁盘应用/图标快照，先显示缓存再后台扫描。真机进程冷启动的缓存读取为 83–265ms，同次全扫描为 1551–1838ms；这不是开机总耗时。壁纸也先读已有缓存，再联网更新。缓存仅供显示，固定应用的清理以扫描结果为准。缓存读写和损坏回退的 2 项真机测试通过。

1.3.4 和入口 1.1 已安装并完成开机日志验证：22:54:33 系统启动入口，入口重试 6 次、用时 1442ms 后成功启动 PTV，22:54:37.770 PTV 首次显示；没有自动打开 Android 设置。开机高负载下缓存读取 1159ms，全扫描 8757ms，缓存比全扫描早 7598ms 就绪。22:54:48 才由系统 SmartPanel 打开小米设置，与旧入口的 Android 设置兜底不同。快捷服务重启后正常绑定且无崩溃，HOME 路由保持入口。原始日志见 `docs/native-1.3.4-reboot.txt`。上述缓存时间从仓库开始加载计时，不是按电源到显示桌面的总耗时。

系统 HOME → HomeActivity → 现有 PTV MainActivity。PTV 数据不变，不依赖无障碍，也不赋予 PTV root 或系统应用身份。

## 2026-10-30 本机验证

设备 MiTV-MFTR0 / OS3.0.102：

- 本机日志有 `com.ts.launcher=511`，包含 HOME 位 4；固件 ResolveIntentHelper 会过滤白名单外的第三方 HOME。
- assembleDebug、lintDebug 通过。
- 安装入口后 HOME 从原桌面变成 ResolverActivity，说明入口通过过滤。
- 关闭快捷服务并强停 PTV 后，显式启动入口成功冷启动 PTV，topResumedActivity 为 PTV。
- 初次测试后曾卸载入口并恢复快捷服务；以下接管测试在用户明确批准后执行。

## 用户批准后的系统接管实测

- 再次确认包名未占用，安装入口，执行 `pm disable-user --user 0 com.mitv.tvhome` 成功。
- HOME 解析为 `com.ts.launcher/com.popkter.ptvlauncher.homebridge.HomeActivity`，没有进入兜底桌面。
- 清空已启用的快捷服务（当时只有 PTV 一项），强停 PTV，打开系统设置，再执行 `input keyevent KEYCODE_HOME`。
- 结果：`topResumedActivity=com.popkter.ptvlauncher/.MainActivity`，同时 `Bound services:{}`、`Crashed services:{}`。证明这次 HOME 由系统路由启动，不是无障碍拦截或窗口守护。
- 随后执行重启。ADB 先超时，随后端口拒绝连接；等待用户重新开启 ADB 和反馈开机画面，重启验收尚未完成。
- 当前预期持久状态：入口已安装、原桌面 disabled-user、快捷服务关闭。重启后状态尚待重新连接确认，不应先卸载入口。
- PTV 1.3.3 已构建并通过单元测试和 Lint，尚未安装。它识别同签名的入口，并在系统已接管时放行 HOME；菜单功能仍由快捷服务提供。

## 后续验收状态

用户确认重启自动进入 PTV。恢复 ADB 后 HOME 路由仍指向入口。1.3.3 已安装；已恢复此前为独立 HOME 测试而关闭的快捷服务，确认服务绑定正常、无崩溃。22:31 的实体按键日志确认在日历中长按返回打开 rail、长按菜单打开 control，关闭后仍回到日历。以下是接管操作步骤记录。

## 接管与验收步骤

1. 确认包名无冲突，重新安装本模块 APK。
2. 保持 ADB 在线，可恢复停用 `com.mitv.tvhome`，不卸载或清数据。用户已经明确批准，并已完成本次停用。
3. 立即确认 HOME 解析为本模块，否则立即恢复原桌面。
4. 暂停快捷服务，从其他应用按 HOME，确认系统启动 PTV；恢复服务供菜单功能使用。
5. 通过实体按键验证后验证重启。云控或 OTA 可能移除白名单资格，导致 FallbackHome 黑屏，届时需 ADB 恢复。

## 恢复命令

先恢复原桌面并确认成功，再卸载入口；不得反序：

```sh
adb -s 192.168.31.229:5555 shell pm enable --user 0 com.mitv.tvhome
adb -s 192.168.31.229:5555 uninstall com.ts.launcher
adb -s 192.168.31.229:5555 shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

[同型号实测参考](https://github.com/pengchujin/aerial-tv/blob/main/docs/devices/xiaomi-mitv-mftr0.md)。未复制该项目代码，本机日志与固件分析独立确认白名单规则。
