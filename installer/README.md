# PTV Windows 安装助手

提供两套 Windows 10/11 x64 发布包，界面与电视功能相同，均内置 PTV 1.3.14、HomeBridge 和 Google 官方 Windows Platform Tools 37.0.1。

| 发布文件 | 是否内置运行库 | 使用方式 |
| --- | --- | --- |
| `dist/PTV-Installer-Windows-x64-WithRuntime.exe` | 内置 .NET 和 Windows App Runtime | 直接运行，可离线使用，无需另装运行库 |
| `dist/PTV-Installer-Windows-x64-NoRuntime.zip` | 不内置运行库 | 完整解压后运行其中的 `PTV-Installer-Windows-x64.exe` |

轻量版启动时先由 .NET 原生 apphost 检查 .NET 10 x64，再由微软 Windows App SDK bootstrapper 检查兼容的 Windows App Runtime 1.8 x64（本次 SDK 对应 1.8.12）。缺少时显示微软原生安装/下载提示；用户可取消退出，依赖齐全则直接启动。程序不静默下载或安装运行库，只有用户主动选择提示中的下载选项才会进入相应安装流程。两项都缺失时可能依次提示，安装后重新启动即可。

轻量包必须保留同目录 DLL、JSON、资源及工作进程，不支持单独分发其中的 EXE；这是 WinUI 框架依赖发布方式的要求。包内 `README.txt` 提供微软官方下载地址。两套均无需 Python、Java、Android Studio；用户仍须在电视开启 ADB，并允许电脑调试。

## 使用

1. 电视与电脑连接同一局域网，在电视设置开启 ADB/网络调试。
2. 双击 EXE，输入电视 IP（默认端口 5555）。支持选择 USB 已连接设备，也支持使用系统给出的无线配对地址与六位配对码。
3. 连接成功后点击“一键配置”。已安装 PTV 时只启用和配置，未安装则自动安装；需要更新内置版本时，单独点击“安装／更新 PTV”。
4. 查看检查结果。黄色项目不代表成功，可打开“电视桌面设置”处理固件限制。
5. 用实体遥控器验证方向键、长按返回、主页和菜单；点击“重启验证”，开机后重新连接并检查。
6. 如需回到小米桌面，点击“一键还原”：默认保留 PTV 与数据；需要彻底移除时，勾选卸载和删除数据。旧的“恢复安装前配置”仍按本机备份恢复。

首次运行会将依赖释放至 `%APPDATA%\PTVInstaller\payload`，原配置保存至 `%APPDATA%\PTVInstaller\backups`。“恢复安装前配置”依赖该备份，请勿提前删除；已适配小米的原生还原不依赖它。成功恢复后备份归档，下一次安装会重新保存当前配置。支持重新执行失败的安装，但中途出错可能留下部分配置，界面会明确标记；请使用恢复按钮。

## 界面与实现

界面使用 C# / WinUI 3 原生 Windows 控件，不启动 Edge、默认浏览器、WebView 或本地 HTTP 页面。完整版发布为自包含单文件 EXE，内置 .NET / Windows App SDK 运行依赖；轻量版使用系统已安装的运行库。完整版首次启动由 .NET 将依赖释放到本机缓存，随后显示原生窗口。

ADB 配置逻辑由随包隐藏启动的 Go 工作进程处理，界面与它通过专用标准输入输出管道通信。关闭窗口会关闭管道、取消后台操作、结束工作进程并释放运行锁；配置进行中退出会提示确认。不会结束用户其他浏览器窗口或共享 ADB 服务。

ADB 参数不经 Windows shell，Android shell 参数逐项引用。只操作所选设备，每次变更前重新验证设备身份，且当前用户必须是主用户 0。其他已启用的无障碍服务会保留。

## 自动配置内容

- 安装或更新同签名网络兼容组件，并激活受限的本地 Wi-Fi／蓝牙管理服务。仅开放固定操作，不开放通用命令；电视重启后需重新运行安装工具激活。恢复流程会停止本工具启动的服务。

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

当前 WinUI 3 构建已在 Windows 本机启动，验证真实 ADB 连接、原生还原确认对话框及进程退出；检查记录见 `VALIDATION.md`。本次界面迁移未改变电视当前配置。

## 重建

在 Windows 上准备 Android SDK、JDK、Python 3、Go 1.25+ 和 .NET 10 SDK，`local.properties` 或 Android SDK 环境变量需指向有效 SDK。默认自动执行 `:app:assembleDebug :homebridge:assembleDebug`，沿用当前项目的开发签名；不会安装 APK 到电视。正式签名发布可用下面的显式 APK 参数传入两个已签名且签名一致的 APK。

在项目根目录运行，一次构建 Android 和两套 Windows 发布包：

```powershell
.\installer\native\build.ps1
```

流水线依次完成：Gradle 编译两个 APK → 更新 payload 内的 APK、许可证与 SHA256 → Go 测试和静态检查 → 编译内嵌最新 APK 的后端 → 发布完整 EXE 与轻量 ZIP。任一步失败都会停止，不会在 Android 编译失败后继续打包旧 APK。Gradle 会按源码变化增量编译。

默认复用 `installer/payload` 中现有 ADB。首次缺少 ADB 时传入官方 Platform Tools ZIP；工具不在 PATH 中时可以指定路径：

```powershell
.\installer\native\build.ps1 -AdbZip C:/packages/platform-tools-windows.zip -Go C:/tools/go/bin/go.exe
```

只修改 Windows 助手时可复用已经放入 payload 的 APK：

```powershell
.\installer\native\build.ps1 -SkipAndroidBuild
```

也可以传入预编译 APK（必须同时提供两个，不再触发 Android 编译）：

```powershell
.\installer\native\build.ps1 -AppApk C:/packages/PTV.apk -BridgeApk C:/packages/HomeBridge.apk
```

默认 `-Flavor Both` 同时生成 `dist/PTV-Installer-Windows-x64-WithRuntime.exe` 和 `dist/PTV-Installer-Windows-x64-NoRuntime.zip`，并更新 `dist/SHA256SUMS.txt`。可用 `-Flavor WithRuntime` 或 `-Flavor NoRuntime` 单独构建。支持 `-Go`、`-DotNet`、`-Python` 指定工具，`-NugetSource` 指定离线 NuGet 源。两套的中间文件和发布目录隔离，轻量包发布前检查未混入运行库。

Python 入口 `python installer/build.py` 调用同一套流水线，也默认编译两个 APK；对应参数为 `--skip-android-build`、`--app-apk` 和 `--bridge-apk`、`--adb-zip`、`--flavor`、`--go`、`--dotnet`、`--nuget-source`。

测试：在 `installer` 中运行 `go test -race ./...` 和 `go vet ./...`。真实模拟器验收是显式 opt-in 的 `PTV_TEST_EMULATOR=1 PTV_TEST_ADB=/path/to/adb go test -run TestEmulatorInstallAndRestore -v`；只允许 emulator-5554，测试后恢复原配置，不调用 Android instrumentation。

第三方许可随 EXE 解压到 payload：ADB 的 NOTICE.txt、PTV-LICENSE.txt 与 PTV-THIRD-PARTY-NOTICES.txt。Go 标准库与编译工具遵循 Go 的 BSD 许可。

## 一键还原原生系统

连接已适配的小米电视后点击“一键还原”。确认窗口默认保留 PTV：恢复并验证原生桌面，撤销 PTV 自动恢复权限，停止快捷服务，清理一次性配置，停用 PTV 和 HomeBridge。应用、布局、自定义壁纸和偏好均保留。随后点击“一键配置”会重新启用这两个包，恢复权限和快捷功能、停用原生桌面并设置 HomeBridge；不会重新安装已存在的 PTV。

如勾选“同时卸载 PTV 和兼容入口，并删除布局、自定义壁纸及偏好”，确认按钮会改为“还原、卸载并删除数据”。该分支卸载主用户的两个包，数据无法从安装配置备份找回。移除后再次点击“一键配置”会自动安装并配置。

这两种方式均保留其他应用、账号、其他无障碍服务及电视设置，不进行恢复出厂设置，不关闭 ADB 调试，也不删除电脑上的安装助手、ADB 和备份。还原成功后归档旧配置备份，下次配置重新保存基线；任何步骤失败均停止并显示错误。

原生还原目前仅支持 Xiaomi / MiTV-MFTR0 / OS3.0.102.0.UFTMATV 主用户 0。未知固件不猜测原生组件，也不自动停用原生桌面。

## 连接历史

成功连接并通过设备授权检查的远程地址会保存在本机 `%APPDATA%\PTVInstaller\connection-history.json`，最多保留最近 50 条，最近使用的排在前面。省略端口时按 5555 保存，同一地址不会重复出现；无线配对码和 USB 序列号不写入地址历史。

点击电视地址输入框可查看历史，输入内容会筛选匹配地址。点击地址直接连接；点击该条右侧“×”仅删除本机历史，不断开当前连接、不删除备份。删除结果跨 EXE 重启保留；之后再次主动连接该地址时会重新记录。

### 原生界面样式与滚动

WinUI 3 版本沿用旧版的浅蓝紫背景、圆角卡片、蓝色主按钮、编号侧栏和功能图标。左侧步骤可直接跳转；鼠标悬停于卡片、按钮、输入框和侧栏时可滚动主页面。地址历史和操作记录较长时可在各自区域内滚动。原生窗口启用按显示器 DPI 缩放。

### 对话框

确认弹窗与主界面使用同一套圆角、图标、文字与按钮样式。灰色取消在左，蓝色操作在右；还原时勾选卸载会显示不可恢复警告并将操作按钮改为红色。初始焦点在取消按钮上，Escape 可关闭弹窗，不提交操作。

## GitHub 在线安装与更新（2.0.4）

在“安装与配置 → 安装来源与在线更新”中填写 GitHub 公开仓库地址、Release 中 PTV APK 的完整文件名，勾选在线来源并保存。设置保存于 `%APPDATA%/PTVInstaller/release-source.json`，重启助手后继续生效。本仓库未设置默认远程地址，因此首次使用需填写真实仓库。

- “检查最新版本”读取已保存仓库的最新正式 Release，显示版本与附件大小，不安装到电视。
- “安装／更新 PTV”会重新查询最新正式版，下载指定附件、核对 GitHub 提供的 SHA256 和文件大小、检查 AndroidManifest 包名后，使用 ADB 保留数据安装。不会强制降级或通过卸载解决签名冲突。
- “一键配置”仍保留已安装版本；只有 PTV 未安装时才按已保存的来源下载安装。HomeBridge 继续使用内置兼容入口。
- Release 需包含文件名完全匹配的独立 APK，且 GitHub API 返回有效 SHA256 digest；旧附件没有 digest 时请重新上传。草稿、预发布、私有仓库、自动选择多架构 APK、安装助手自身更新暂不支持。
- 下载失败、附件缺失、包名不符或校验失败会停止并显示原因，不会静默降级到内置 APK。关闭在线来源并保存后，可明确选择使用内置版本。关闭助手会取消网络请求，下载临时文件在任务结束后清理。
- 发布 APK 必须沿用既有 PTV 签名，并与需要安装的内置 HomeBridge 签名保持一致。在线功能不会改变电视的签名校验或清除用户数据。

接口依据：[GitHub 官方 Releases API](https://docs.github.com/en/rest/releases/releases)。
