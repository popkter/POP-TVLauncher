# 安装助手验收记录 · 2026-11-01

## 已通过

- Android `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`：通过。APK 版本 1.3.14-native / versionCode 20，包含通过 secure setting 消费一次性安装配置请求的逻辑。
- Go 单元与流程模拟测试、`go test -race ./...`、`go vet ./...`：通过。覆盖设备地址校验、shell 引用、保留其他无障碍服务、跨设备恢复拒绝、备份不被重试覆盖、安装失败后停止授权、未知固件不禁用原桌面、原桌面验证失败时不移除兼容入口、HTTP 令牌/Origin/Host 验证、并发操作拒绝、单行与多行无障碍绑定输出。
- `node --check web/app.js`：通过。
- Windows AMD64 交叉编译：通过。PE32+ GUI 子系统、7 个尺寸的应用图标、asInvoker manifest 已直接检查资源目录与 RVA，有效。无需申请 Windows 管理员权限。
- Safari 中已实际打开界面，检查文字、按钮、输入框、侧栏和布局。后续电脑锁屏，未能另存截图；不将其描述为 Windows 界面验收。

## 真实 ADB 模拟器集成

使用 `emulator-5554`，通过与 EXE 相同的 Go 配置逻辑，执行真实 ADB 安装与恢复（不使用 instrumentation）。

初始状态：默认桌面 `com.google.android.tvlauncher/.MainActivity`；已启用 `com.google.android.marvin.talkback/.TalkBackService`；PTV 无 WRITE_SECURE_SETTINGS。

最终一轮结果：

- APK 安装成功。
- WRITE_SECURE_SETTINGS 授予成功。
- PTV 无障碍实际绑定成功，TalkBack 保留。
- 防冻结前台服务 `isForeground=true`。
- 一次性安装设置请求已消费，`ptv_installer_shortcuts=null`。
- Android HOME 角色已给 PTV，但该模拟器实际 HOME 仍被系统解析为 Google TV Launcher：正确显示黄色提示，没有误报默认桌面成功。
- 恢复流程通过，原桌面和 TalkBack 恢复/保留，移除本次 PTV 授权，PTV APK 及数据保留。
- `TestEmulatorInstallAndRestore` PASS，耗时约 4.2 秒（不包括编译）。

实测过程中发现并修复：多个无障碍服务的 Bound services 输出跨行，导致原解析漏掉第二项；默认桌面命令返回 Success 不代表实际 HOME 已切换。

## 小米兼容范围核对

本轮仅只读查询真实电视，无安装/授权/重启操作：model=MiTV-MFTR0，manufacturer=xiaomi，incremental=OS3.0.102.0.UFTMATV。对应兼容入口沿用已有真机验证实现。安装助手的“停用原桌面+恢复”分支本轮只做流程测试，未再次对真机执行。

## 未验证及发布前事项

- 当前环境无 Windows，未验证 EXE 在 Windows 的启动、Edge 应用窗口、USB 驱动、SmartScreen 或杀毒软件行为。
- 交付 EXE 未做 Authenticode 签名；内置 APK 为项目既有开发签名。正式发布必须确定长期签名，并保证 PTV 与 HomeBridge 同签名。
- 未在未知电视固件上验证，默认桌面和原生控制中心的可用性取决于固件。实体按键、开机画面、网络壁纸功能仍需目标设备验收。

## 2026-11-01 Windows 更新修复：保留已有 HomeBridge

- 从 Windows 安装备份确认：安装前 HOME 为 `com.ts.launcher/com.popkter.ptvlauncher.homebridge.HomeActivity`。
- 实机 MiTV-MFTR0 / OS3.0.102.0.UFTMATV / API 34：修复前实际 HOME 为 HomeBridge，但 ROLE_HOME 为 `com.popkter.ptvlauncher`，原桌面已处于停用状态。这说明旧版普通安装覆盖了原有 HOME 角色，原检查仅看解析结果遗漏了冲突。
- 已将此电视的 HOME 角色恢复为 `com.ts.launcher`，命令返回 Success，回读角色与实际入口一致。
- 安装助手更新：已有有效 HomeBridge 入口时保留并同步角色，不安装/覆盖该入口，不改变原桌面启用状态，不取得其恢复卸载所有权。Android 10+ 同时验证 HOME 角色与入口。
- Windows 本地 `go test ./...`、`go vet ./...` 通过。新增覆盖兼容选项勾选/未勾选时已有入口保留，以及角色一致、冲突、空输出三种检查结果。
- 使用官方 Go 1.27.3 Windows AMD64 工具链构建新版 EXE；下载工具链校验 SHA256 通过。沿用已有 APK 与 ADB payload，重新生成 EXE 和 payload 校验和。未重新编译 APK。
- 已经用户同意执行电视重启；记录时 ADB 尚未恢复连接，开机画面仍待确认。未将本次重启标记为通过。

重启补充：等待连接的事件日志查询曾成功返回开机记录，显示 HomeBridge 启动 PTV MainActivity，PTV 获得焦点。随后 ADB 连接再次中断，因此尚未完成重启后 HOME 角色回读；截取的日志不足以证明整个开机期间没有短暂出现选择器。

最终实机验收：用户确认本次重启直接进入 PTV，没有出现启动器选择界面。本次默认桌面配置修复的重启验收通过。新版安装助手已通过自动化回归测试，但尚未在真机重新执行新版 EXE 的完整安装流程。

## 2026-11-01 新增一键还原原生系统

- 独立于旧版“恢复安装前配置”的入口，针对已适配小米固件还原原生桌面并移除主用户 PTV/HomeBridge 及 PTV 数据，要求界面确认删除范围。
- 覆盖成功清理、保留其他无障碍服务、空服务列表关闭全局无障碍、无备份执行及重复执行、未知固件/原桌面缺失/陌生兼容包/默认角色或路由失败时停止、卸载命令返回 Failure 和卸载后仍存在包时拒绝报成功。
- 真机仅只读核对：HomeBridge 的 HOME 查询返回预期完整组件；小米原生包及 MainActivityUserMode 存在。未在用户电视上执行卸载或原生还原。
- Go 单元测试、go vet 与前端 JavaScript 语法检查通过。完整还原、实体遥控器与还原后重启仍待实际使用验收。

## 原生桌面启用与启动入口补充

用户反馈“恢复原配置”未回到小米桌面后，真机确认 com.mitv.tvhome 仍被停用，ROLE_HOME 和实际 HOME 均为 HomeBridge。已重新启用原生桌面并设置 HOME 为 com.mitv.tvhome/.MainActivityUserMode，角色和解析回读均正确。通过主页键启动后，topResumedActivity 确认为小米原生桌面；保留 PTV 与用户数据，未执行卸载或重启。

实测直接 am start HOME 因缺少 com.mitv.tvhome.permission.HOME_STATE 被拒绝，而 input keyevent KEYCODE_HOME 成功。两个恢复流程均改为通过系统主页键进入已验证的默认桌面。安装前备份恢复对话框补充明确提示：备份本身可能是 PTV，恢复原生请使用独立的原生还原入口。

## 2026-11-01 PTV 与原生模式切换修正

- 本轮开始时小米原生桌面处于启用状态，HOME 角色为 PTV 主包。已停用 com.mitv.tvhome，将默认角色与实际入口设为 HomeBridge，并确认前台为 PTV。
- 安装流程对已适配型号/固件自动使用小米兼容模式，不再依赖可漏选的复选框。识别并复用已有 HomeBridge，即使当前默认桌面是小米；复用分支也会重新停用原生桌面。不会将原有 HomeBridge 记为新安装的包。
- 检查结果新增原生桌面停用状态检查；配置不完整显示警告。陌生同名兼容包仍会在安装/停用前拒绝。
- Go 测试、go vet、JavaScript 语法检查通过；覆盖已有/新装入口、当前原生/兼容入口、未勾选情况下自动配置、同名冲突拒绝、原生桌面仍启用时警告。
- 实机短暂休眠后，以电源键唤醒；mWakefulness=Awake，topResumedActivity 为 PTV MainActivity。休眠期间回读原生桌面仍停用、HOME 角色仍为 com.ts.launcher。本轮未再次执行完整重启。

## 2026-11-01 安装、配置与保留数据还原

- 界面分开“安装／更新 PTV”与“一键配置”。前者仅安装内置 APK；后者只在包未安装时自动安装，已安装时保留版本与数据、重新启用包并配置权限、服务及 HOME。
- 原生还原对话框默认不勾选卸载：停用 PTV/HomeBridge，保留应用和数据；勾选后才卸载删除数据，确认按钮同步说明破坏性操作。
- 兼容入口识别包含已停用组件（MATCH_DISABLED_COMPONENTS），重新配置会启用 PTV 与 HomeBridge。
- Go 测试通过，新增状态模拟的“保留数据还原→重新配置”完整流程，断言没有安装、卸载或清数据；另覆盖未安装时自动安装、已安装时跳过、独立安装不修改配置。原卸载清理与安全停止测试仍通过。
- 本轮未对电视执行还原、卸载或切换操作。真实电视的保留数据往返流程尚待用户验收。
- 本轮前端交互冒烟测试通过：保留默认值、主动勾选卸载、重新打开对话框重置为保留、配置与独立安装分别提交对应操作。go vet 与 Windows EXE 构建通过；尚未完成新版界面视觉实测。

## 2026-11-01 窗口与进程退出修复

- 取消 Edge 窗口关闭后固定保留后台三分钟的行为。助手使用独立浏览器配置，跟踪本次创建的实际助手窗口；Edge 启动器转交窗口后，不误把启动器进程退出当成窗口关闭。
- 关闭窗口或确认“退出助手”后取消后台 ADB 命令、关闭 HTTP 服务、关闭助手专用窗口并释放单实例锁。其他浏览器窗口和共享 ADB 服务不被终止。
- 默认浏览器回退使用带令牌和 Origin 校验的页面关闭通知，保留一秒刷新宽限；刷新后的心跳取消退出。正常关闭无需等待三分钟，异常断联且没有通知时保留超时兜底。
- Windows 实机自动测试通过：连续打开/关闭专用窗口；完整助手进程关闭窗口后退出；紧接着再次启动成功（证明运行锁已释放）；退出接口结束进程。测试不连接电视。旧版残留的已识别助手进程已清理。
- 单元测试覆盖关闭通知鉴权、忙碌时窗口关闭、刷新不误退出、取消后台命令和重复停止。
