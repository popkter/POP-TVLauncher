PTV 安装助手 · 轻量版（不含运行库）

1. 先将整个 ZIP 解压到一个文件夹。
2. 双击 PTV-Installer-Windows-x64.exe。不要只拷贝这个 EXE，也不要直接在 ZIP 内运行。
3. 程序启动时由微软启动组件检查所需运行库：
   - 缺少 .NET 10 x64：显示 .NET 原生安装/下载提示。
   - 缺少兼容的 Windows App Runtime 1.8 x64：显示 Windows App SDK 原生安装提示。
   - 两项都具备：直接打开安装助手。
4. 只有你主动选择提示中的下载/安装选项时，才会进入微软提供的安装流程。程序不静默安装运行库。
5. 安装完成后重新打开助手。两项都缺失时，可能需要依次完成两次提示。

运行库下载（微软官方）：
.NET 10 x64： https://dotnet.microsoft.com/en-us/download/dotnet/10.0
Windows App Runtime 1.8.12 x64： https://learn.microsoft.com/en-us/windows/apps/windows-app-sdk/downloads-archive
请使用 1.8 系列对应运行库；仅安装 2.x 不能替代 1.8。无需安装 SDK 或开发工具。

功能与完整版相同，仍内置 ADB、PTV.apk 和 HomeBridge.apk。
连接历史、备份及 PTV 数据的处理方式与完整版相同。
本包包含必需的应用 DLL、WinRT 接口投影和运行库引导组件，但不包含 .NET / Windows App Runtime 本体。

如果希望无需安装任何运行库并离线使用，请下载 WithRuntime 完整版 EXE。
