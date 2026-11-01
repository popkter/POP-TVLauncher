# glass_comonpent

PTV 玻璃风格自定义 View 组件库。模块名保留为用户指定的 `glass_comonpent`，包名为 `com.popkter.glass_comonpent`。最低 Android 7.0 / API 24；不依赖 Compose、AppCompat 或系统设置权限。

## 组件

| 类 | 用途 |
| --- | --- |
| `GlassTextView` / `GlassIconView` | 共享文本、矢量/图像显示 |
| `GlassGridView` / `GlassScrollView` | 网格与滚动基础控件 |
| `GlassArtworkCard` / `GlassAppListButton` | 应用横幅卡片与 AppList 入口 |
| `GlassImageTile` | 壁纸照片、选中标记与焦点 |
| `GlassDialog` / `GlassMessages` | 确认窗口与应用内轻提示 |
| `GlassSurface` | 共享玻璃材质、圆角、留白、焦点框 |
| `GlassButton` | 1×1、1×2、2×1、2×2、1×4、2×4、4×4、4×1、4×2；宽×高 |
| `GlassListRow` / `GlassListView` | 图标、标题、描述、尾部状态；稳定 key、刷新焦点恢复 |
| `GlassSectionListView` | 带分组标题的列表 |
| `GlassSwitch` | 遥控器、触摸和无障碍开关 |
| `GlassSelector` | 单选、多选、已选标记 |
| `GlassTabs` / `GlassSegmentedControl` | 下划线标签 / 整体底槽及浅色当前项的分段选择；跳过不可用项 |
| `GlassTextButton` | 普通态仅文字，落焦使用统一遮罩和描边 |
| `GlassSlider` | 4×1 填充式滑块；图标几何与基础按钮一致 |
| `GlassStepper` | 减少、数值、增加；边界禁用 |
| `GlassTextField` | 原生输入法、清除；整个输入背景响应焦点 |
| `GlassConfirmation` | 内嵌确认内容，宿主管理展示和关闭 |
| `GlassNotice` | 自动消失的轻提示、无障碍播报 |
| `GlassStateView` | 加载、空、错误/重试、内容状态 |
| `GlassContextMenu` | 长按菜单；展开/收起动画、返回和焦点恢复 |
| `GlassGalleryView` | 可放进任意 Activity 的原生演示目录；仅示例交互 |

`GlassStyle` 中的尺寸单位均为 dp；默认图标 24、留白 16、圆角 28、玻璃填充 11%。功能开启/选定状态 (`isSelected` / `isChecked`) 使用控件自己的 `activeColor`，默认 iOS 蓝色；开关和长按返回功能使用绿色。遥控器落焦状态独立叠加 `focusOverlayColor` 浅白遮罩与 `focusColor` 边框，不覆盖蓝/绿底色。状态按钮可设 `toggleOnClick = true`，确认后切换开启状态；导航和命令按钮默认不持久切换颜色。`GlassTextField` 默认高 56dp，清除按钮在这一个单元内布局，不额外叠加上下留白。`AUTO` 对多行尺寸使用上下布局，2×1 不显示描述，1 单元宽的按钮只显示图标。4×2 与 4×4 共用上下布局。

## 使用

```kotlin
implementation(project(":glass_comonpent"))
```

```kotlin
val style = GlassStyle(iconSize = 24f, contentPadding = 16f,
    cornerRadius = 28f, fillOpacity = .11f)

val wallpaper = GlassButton(context).apply {
    glassStyle = style
    gridSize = GlassSize.X4Y2
    title = "壁纸"
    description = "必应每日画报"
    icon = context.getDrawable(R.drawable.my_wallpaper_icon)
    setOnClickListener { openWallpaper() }
}
```

XML 支持 `glassIconSize`、`glassContentPadding`、`glassCornerRadius`、`glassFillOpacity`。尺寸、标题、业务回调由代码设置。

```xml
<com.popkter.glass_comonpent.GlassTextField
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    app:glassContentPadding="16dp"
    app:glassCornerRadius="28dp" />
```

## 列表与焦点

```kotlin
list.glassStyle = style
list.submitSections(listOf(
    GlassListSection("connected", "已连接", connectedItems),
    GlassListSection("available", "其他网络", availableItems)
))
list.onItemClick = { item -> openDetails(item.key) }
// 返回详情页时使用稳定 key；刷新时组件自动保留当前焦点与滚动位置。
list.restoreItemFocus(previousKey)
```

每一项必须有跨分组唯一且稳定的 key。名称、状态和列表位置不能替代业务 key。

## 长按菜单及动画

```kotlin
val menu = GlassContextMenu.attach(wallpaper, style = style,
    items = {
        listOf(
            GlassMenuItem("pin", "置顶"),
            GlassMenuItem("remove", "移除", destructive = true)
        )
    }
) { action -> handleAction(action.key) }

// 页面销毁/不再使用时解除绑定。
menu.close()
```

长按触摸、长按确认键由 View 原生长按机制触发；长按逻辑起始方向键也可触发：LTR 为左、RTL 为右。短按该方向键仍会移动焦点。也可通过 `menu.show()` 绑定“更多操作”入口。

桌面应用菜单设置 `layoutMode = GlassMenuLayout.ICON_ROW`，显示一行三个 1×1 图标按钮；其他调用仍可使用 `LIST`。弹出菜单默认带 50% 浅色材质作为独立背景，自动使用深色图标/文字以保证对比度，不依赖桌面模糊。按钮常态增加灰蓝色填充，落焦使用更明显的浅白遮罩与白色边框；功能开启色仍独立保留。可通过 `backgroundStyle` 覆盖背景颜色和透明度。

菜单优先出现在逻辑起始侧，空间不足时翻到另一侧并约束在可见窗口中。淡入+位移展开 180ms，返回/动作选择时反向收起 140ms，随后恢复锚点焦点；关闭系统动画时取消时长。菜单处理展示、动画、键盘导航和生命周期，业务只提供菜单项与回调。移除等破坏性动作应由业务回调展示 `GlassConfirmation`，组件不会自行删除数据。

已由列表/网格管理长按事件时，使用 `GlassContextMenu.create()`，随后调用 `show()`；它不会覆盖已有的按键/长按 listener。应用卡片可提供 `anchorBounds = card.artworkBounds()`，按真实图片边缘而非整行标签区域定位；默认 `anchorGap = 8f`，并计入锚点缩放。可指定 `returnFocusTo` 为网格本身，避免回收到不可聚焦的 item View。

`attach` 接管锚点的 `OnLongClickListener` 和 `OnKeyListener`。每个锚点保留一个实例；重新绑定前先 `close()`。不能再用第二套 listener 覆盖它们。

## LTR / RTL

宿主 application 声明 `android:supportsRtl="true"`。所有控件继承父级布局方向，不固定语言：

```kotlin
container.layoutDirection = View.LAYOUT_DIRECTION_RTL // 或 LTR / LOCALE
```

图标/文本按 start/end 布局；分类、表单清除按钮、开关、Tab、步进器顺序随父级镜像。滑块从逻辑起始侧填充，方向键朝逻辑结束侧增加数值。菜单展开侧镜像，返回方向根据实际菜单位置计算。图标图形本身不自动镜像：方向性图标请由宿主提供 `android:autoMirrored="true"` 的矢量资源；Wi-Fi、太阳等非方向图标保持原样。

## 背景和业务边界

组件只绘制透明玻璃材质。整页背景模糊由宿主提供，沿用 PTV 当前的共享背景快照；避免每个控件重复截屏/模糊。网络连接、信号源、蓝牙等真实操作由调用方接入，组件库没有系统权限或真实设备操作。

PTV 已使用本模块替换应用内自绘/可操作控件：控制中心按钮与滑块、详情列表、Wi-Fi 输入、桌面设置入口、AppList 筛选与管理按钮、应用卡片、壁纸缩略图/工具栏、确认窗和轻提示。`AppCardView` 等 app 类仅作为业务数据适配器；绘制与交互由模块提供。布局容器和背景画布保留宿主职责；Android 的输入法、授权页面和 TV 视频输入 Surface 仍由系统提供。

## 验证与产物

```text
gradlew :glass_comonpent:assembleDebug :glass_comonpent:lintDebug
gradlew :glass_comonpent:assembleDebugAndroidTest
```

AAR：`build/outputs/aar/glass_comonpent-debug.aar`。

设备测试覆盖九种尺寸图标镜像、描述显示策略、样式更新、RTL 滑块填充/方向键、刷新焦点、复合控件构造、Tab 跳过禁用项、菜单动画与焦点恢复。测试 APK 为独立包，不覆盖 PTV 或修改 Wi-Fi 等系统配置。

示例目录所用图标沿用项目已使用的 Framework7 Icons 5.0.5 矢量资源，授权见模块 assets/licenses 与项目 `THIRD_PARTY_NOTICES.md`。

`GlassTabs` 提供 `tabView(key)`、`onTabFocused`、`onTabSelected`、`onTabConfirmed`，支持接入遥控器筛选和确认进入内容的不同操作。App List 使用 `GlassSegmentedControl`，管理入口使用 `GlassTextButton`。
