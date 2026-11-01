package com.popkter.glass_comonpent

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Optional native catalogue. Has no system permissions and never changes real device settings. */
class GlassGalleryView @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): ScrollView(context,attrs) {
    private val stack=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    private val styled=mutableListOf<(GlassStyle)->Unit>()
    private var menu: GlassContextMenu?=null
    var glassStyle=GlassStyle()
        set(value){field=value;styled.forEach { it(value) };menu?.glassStyle=value}
    init {
        setBackgroundColor(0xff253f4a.toInt());addView(stack,LayoutParams(-1,-2))
        val p=context.dp(24f);stack.setPaddingRelative(p,p,p,p)
        heading("基础按钮 · 宽 × 高")
        val grid=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        GlassSize.entries.forEach { size ->
            val row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
            row.addView(TextView(context).apply { text="${size.columns} × ${size.rows}";applyTextStyle(glassStyle) },LinearLayout.LayoutParams(context.dp(72f),-2))
            val button=GlassButton(context).apply { gridSize=size;icon=icon(R.drawable.glass_icon_photo);title="壁纸";description="必应每日画报";iconOnly=size.columns==1 || size==GlassSize.X4Y1 }
            row.addView(button);style(button);grid.addView(row,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=context.dp(10f) })
        };stack.addView(grid)
        heading("列表 / 分类")
        val list=GlassSectionListView(context).apply {
            submitSections(listOf(GlassListSection("connected","已连接",listOf(GlassListItem("home","家庭 Wi-Fi","已连接",icon(R.drawable.glass_icon_wifi),"✓",selected=true))),GlassListSection("other","其他网络",listOf(GlassListItem("guest","访客 Wi-Fi","安全网络",icon(R.drawable.glass_icon_wifi))))))
        };stack.addView(list,LinearLayout.LayoutParams(-1,context.dp(230f)));styled.add { list.glassStyle=it }
        heading("开关 / 选择器 / Tab")
        val toggle=GlassSwitch(context).apply { bind(GlassListItem("wifi","Wi-Fi","已开启",icon(R.drawable.glass_icon_wifi)));isChecked=true };add(toggle)
        val selector=GlassSelector(context).apply { submitOptions(listOf(GlassOption("one","1 分钟"),GlassOption("three","3 分钟"),GlassOption("off","关闭画报")),setOf("three")) };stack.addView(selector);styled.add { selector.glassStyle=it }
        val tabs=GlassTabs(context).apply { submitTabs(listOf(GlassOption("bing","必应画报"),GlassOption("album","自定义相册"))) };stack.addView(tabs);styled.add { tabs.glassStyle=it }
        val segments=GlassSegmentedControl(context).apply { submitTabs(listOf(GlassOption("ordered","顺序"),GlassOption("random","随机"),GlassOption("fixed","固定"))) };stack.addView(segments);styled.add { segments.glassStyle=it }
        heading("连续值 / 步进 / 表单")
        add(GlassSlider(context).apply { icon=icon(R.drawable.glass_icon_sun);contentDescription="亮度" })
        add(GlassStepper(context))
        add(GlassTextField(context).apply { editText.hint="设备名称";editText.setText("客厅电视") })
        heading("反馈 / 确认 / 内容状态")
        val notice=GlassNotice(context).apply { message="设置已保存" };add(notice)
        add(GlassConfirmation(context).apply { titleView.text="忘记此网络？";messageView.text="再次连接时需要重新输入密码";onCancel={notice.show("已取消")};onConfirm={notice.show("示例网络已忘记")} })
        add(GlassStateView(context).apply { state=GlassContentState.ERROR;onRetry={state=GlassContentState.LOADING;postDelayed({state=GlassContentState.EMPTY},800)} })
        heading("长按操作 · 或长按逻辑起始方向键")
        val anchor=GlassButton(context).apply { icon=icon(R.drawable.glass_icon_photo);title="壁纸";description="长按展开菜单";gridSize=GlassSize.X4Y2 }
        add(anchor)
        fun attachMenu(){menu?.close();menu=GlassContextMenu.attach(anchor,listOf(GlassMenuItem("pin","置顶"),GlassMenuItem("favorite","收藏"),GlassMenuItem("remove","移除",destructive=true)),glassStyle){notice.show("示例操作：${it.title}")}}
        anchor.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View){attachMenu()}
            override fun onViewDetachedFromWindow(v: View){menu?.close();menu=null}
        })
        glassStyle=glassStyle
    }
    private fun icon(id: Int): Drawable?=context.getDrawable(id)
    private fun heading(text: String){stack.addView(TextView(context).apply { this.text=text;applyTextStyle(glassStyle);textSize=18f;setPaddingRelative(0,context.dp(24f),0,context.dp(14f)) })}
    private fun style(view: GlassSurface){styled.add { view.glassStyle=it };view.glassStyle=glassStyle}
    private fun add(view: GlassSurface){style(view);stack.addView(view,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=context.dp(12f) })}
}
