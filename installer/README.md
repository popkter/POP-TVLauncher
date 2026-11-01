# PTV Windows 安装助手

交付：`dist/PTV-Installer-Windows-x64.exe`。Windows 10/11 x64 单文件便携工具，内置 PTV 1.3.14、桌面兼容入口及 Google 官方 Windows Platform Tools 37.0.1。无需 Python、Java、Android Studio 或管理员权限。用户仍须在电视开启 ADB，并在首次连接时允许电脑调试。

## 使用

1. 电视与电脑连接同一局域网，在电视设置开启 ADB/网络调试。
2. 双击 EXE，输入电视 IP（默认端口 5555）。支持选择 USB 已连接设备，也支持使用系统给出的无线配对地址与六位配对码。
3. 连接成功后点击“一键配置”。已安装 PTV 时只启用和配置，未安装则自动安装；需要更新内置版本时，单独点击“安装／更新 PTV”。
4. 查看检查结果。黄色项目不代表成功，可打开“电视桌面设置”处理固件限制。
5. 用实体遥控器验证方向键、长按返回、主页和菜单；点击“重启验证”，开机后重新连接并检查。
6. 如需回到小米桌面，点击“一键还原”：默认保留 PTV 与数据；需要彻底移除时，勾选卸载和删除数据。旧的“恢复安装前配置”仍按本机备份恢复。

首次运行会将依赖释放至 `%APPDATA%\PTVInstaller\payload`，原配置保存至 `%APPDATA%\PTVInstaller\backups`。“恢复安装前配置”依赖该备份，请勿提前删除；已适配小米的原生还原不依赖它。成功恢复后备份归档，下一次安装会重新保存当前配置。支持重新执行失败的安装，但中途出错可能留下部分配置，界面会明确标记；请使用恢复按钮。

## 界面与实现

Go 后端绑定随机 `127.0.0.1` 端口，以 Edge 应用窗口显示本地 HTML/CSS 界面；无 Edge 时使用默认浏览器。不是远程网站，也不依赖 WebView2 或 .NET。采用浅色、半透明面板、统一描边图标、居中按钮、清晰的主次操作和展开过渡。设计参考 [Apple HIG Buttons](https://developer.apple.com/design/human-interface-guidelines/buttons) 的一致性与层级；具体 CSS 尺寸是本项目实现，不声称是 Apple 规定数值。

Windows Edge 窗口关闭后，助手随即取消后台命令、关闭本地服务并释放运行锁；点击“退出助手”也会关闭专用窗口。助手使用独立浏览器配置，不关闭其他 Edge 窗口。配置进行中关闭窗口会提示确认；强行退出可能留下部分配置，重开后请检查或重新配置。没有 Edge 时使用默认浏览器，正常关闭页面约一秒后退出，页面刷新不会误退出；浏览器异常消失且未发送关闭通知时仍保留超时兜底。

API 使用每次启动随机令牌、Host 和 Origin 验证，不接收任意 shell 命令；ADB 参数不经 Windows shell，Android shell 参数逐项引用。只操作所选设备，每次变更前重新验证设备身份，且当前用户必须是主用户 0。其他已启用的无障碍服务会保留。

## 自动配置内容

- 重新安装时，如果电视当前已通过 PTV 系统桌面入口（HomeBridge）启动，保留该入口并将 HOME 角色同步到它；不重复安装或把原有入口记为本工具新装。Android 10+ 的检查同时核对实际 HOME 入口与 HOME 角色，避免固件路由掩盖角色冲突。

- 一键配置仅在 PTV 未安装时自动安装；已安装则保留版本和数据，重新启用已停用的包。独立安装／更新入口只安装内置 APK，不自动设置桌面或授权，也不会通过卸载/清数据解决签名冲突。
- 保存原桌面、PTV 授权状态和无障碍列表。
- 授予 WRITE_SECURE_SETTINGS，合并启用 PTV 快速切换。
- 通过安全设置中的一次性请求启用应用内长按返回开关；普通应用不能通过 Intent 伪造该请求。
- 设置 HOME，并以实际 HOME 解析结果验证，不只看命令退出状态。
- 验证 APK、授权、无障碍实际绑定及前台防冻结服务。
- 提供原配置恢复、日志导出和重启操作。

## 兼容边界

- Android 7.0+。固件限制第三方 HOME 时，即使系统接受 HOME 角色，实际主页仍可能回到原桌面，此时标记黄色，需要电视端处理或专门适配。
- 小米兼容选项仅对 `ro.product.model=MiTV-MFTR0`、manufacturer=xiaomi、incremental=`OS3.0.102.0.UFTMATV` 开放，并在一键配置 PTV 时自动启用。安装或复用已识别的 PTV 兼容入口并停用原桌面 `com.mitv.tvhome`。已有 `com.ts.launcher` 仅在 HOME 组件确认为 PTV HomeBridge 时复用，不覆盖；其他包名冲突会停止。恢复时先恢复并验证原桌面，再移除本工具安装的入口。未知机型绝不自动停用原桌面。
- 原生控制中心取决于电视系统，不保证全品牌通用。实体遥控器与开机画面无法由电脑命令替代验收。
- 壁纸上传需要手机可访问电视所在局域网；必应壁纸需要互联网。

## 当前发布状态

当前为可运行的预览构建：EXE 尚无 Authenticode 签名，内置 APK 仍用项目现有开发签名。正式面向公众发布前需要确定长期 APK 签名与 Windows 签名；切换 APK 签名不能直接覆盖开发版。兼容入口与 PTV 必须使用同一证书。

最初在 macOS 交叉编译；后续修改已在 Windows 本地构建并通过 Go 测试与静态检查。最新界面尚未完成 Windows 双击启动/Edge 集成/SmartScreen 验收。Android 构建、单元测试、Lint、Go 测试及模拟器真实 ADB 流程见 `VALIDATION.md`。

## 重建

需要 Python 3、Go 1.25+、已构建 APK，以及 [Google 官方 Windows Platform Tools ZIP](https://developer.android.com/tools/releases/platform-tools)。图标与清单通过 `make_resources.py` 直接生成 COFF，无额外依赖。

```sh
python3 installer/build.py \
  --adb-zip /path/to/platform-tools-windows.zip \
  --app-apk app/build/outputs/apk/debug/app-debug.apk \
  --bridge-apk homebridge/build/outputs/apk/debug/homebridge-debug.apk
```

测试：在 `installer` 中运行 `go test -race ./...` 和 `go vet ./...`。真实模拟器验收是显式 opt-in 的 `PTV_TEST_EMULATOR=1 PTV_TEST_ADB=/path/to/adb go test -run TestEmulatorInstallAndRestore -v`；只允许 emulator-5554，测试后恢复原配置，不调用 Android instrumentation。

第三方许可随 EXE 解压到 payload：ADB 的 NOTICE.txt、PTV-LICENSE.txt 与 PTV-THIRD-PARTY-NOTICES.txt。Go 标准库与编译工具遵循 Go 的 BSD 许可。

## 一键还原原生系统

连接已适配的小米电视后点击“一键还原”。确认窗口默认保留 PTV：恢复并验证原生桌面，撤销 PTV 自动恢复权限，停止快捷服务，清理一次性配置，停用 PTV 和 HomeBridge。应用、布局、自定义壁纸和偏好均保留。随后点击“一键配置”会重新启用这两个包，恢复权限和快捷功能、停用原生桌面并设置 HomeBridge；不会重新安装已存在的 PTV。

如勾选“同时卸载 PTV 和兼容入口，并删除布局、自定义壁纸及偏好”，确认按钮会改为“还原、卸载并删除数据”。该分支卸载主用户的两个包，数据无法从安装配置备份找回。移除后再次点击“一键配置”会自动安装并配置。

这两种方式均保留其他应用、账号、其他无障碍服务及电视设置，不进行恢复出厂设置，不关闭 ADB 调试，也不删除电脑上的安装助手、ADB 和备份。还原成功后归档旧配置备份，下次配置重新保存基线；任何步骤失败均停止并显示错误。

原生还原目前仅支持 Xiaomi / MiTV-MFTR0 / OS3.0.102.0.UFTMATV 主用户 0。未知固件不猜测原生组件，也不自动停用原生桌面。
