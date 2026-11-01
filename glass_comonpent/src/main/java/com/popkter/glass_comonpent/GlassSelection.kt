package com.popkter.glass_comonpent

import android.content.Context
import android.util.AttributeSet
import android.view.*
import android.widget.LinearLayout

data class GlassOption(val key: String,val title: String,val description: String="",val enabled: Boolean=true)

class GlassSelector @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): LinearLayout(context,attrs) {
    var multiple=false
    var glassStyle=GlassStyle()
        set(value) { field=value;rows.values.forEach { it.glassStyle=value } }
    var onSelectionChanged: ((Set<String>)->Unit)?=null
    private var options=listOf<GlassOption>()
    private val rows=linkedMapOf<String,GlassListRow>()
    private val selection=linkedSetOf<String>()
    val selectedKeys: Set<String> get()=selection.toSet()
    init { orientation=VERTICAL }
    fun submitOptions(values: List<GlassOption>,selected: Set<String> = selectedKeys) {
        require(values.map { it.key }.distinct().size==values.size)
        val previous=rows.entries.firstOrNull { it.value.hasFocus() }?.key
        options=values;selection.clear();selection.addAll(values.filter { it.key in selected }.map { it.key }.let { if(multiple)it else it.take(1) })
        removeAllViews();rows.clear()
        values.forEach { option ->
            val row=GlassListRow(context).apply { glassStyle=this@GlassSelector.glassStyle }
            rows[option.key]=row;addView(row,LayoutParams(-1,-2));row.setOnClickListener { choose(option.key) }
            row.accessibilityDelegate=object: View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View,info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host,info);info.className=if(multiple)android.widget.CheckBox::class.java.name else android.widget.RadioButton::class.java.name
                    info.isCheckable=true;info.isChecked=option.key in selection
                }
            }
        };refresh()
        if(previous!=null)post { rows[previous]?.takeIf { it.isEnabled }?.requestFocus() }
    }
    fun select(key: String) { require(options.any { it.key==key && it.enabled });choose(key) }
    private fun choose(key: String) {
        if(multiple){if(!selection.add(key))selection.remove(key)}else{selection.clear();selection.add(key)}
        refresh();onSelectionChanged?.invoke(selectedKeys)
    }
    private fun refresh() { options.forEach { option ->rows[option.key]?.bind(GlassListItem(option.key,option.title,option.description,trailing=if(option.key in selection)"✓" else "",enabled=option.enabled,selected=option.key in selection)) } }
}

open class GlassTabs @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): HorizontalScrollViewCompat(context,attrs) {
    private val strip=LinearLayout(context)
    private val buttons=linkedMapOf<String,GlassButton>()
    var glassStyle=GlassStyle()
        set(value){field=value;buttons.values.forEach { it.glassStyle=value };update()}
    var onTabSelected: ((String)->Unit)?=null
    var onTabFocused: ((String)->Unit)?=null
    var onTabConfirmed: ((String)->Unit)?=null
    var selectedKey: String?=null
        private set
    init { isHorizontalScrollBarEnabled=false;addView(strip,LayoutParams(-2,-2)) }
    fun submitTabs(options: List<GlassOption>,selected: String?=selectedKey) {
        require(options.map { it.key }.distinct().size==options.size)
        val focused=buttons.entries.firstOrNull { it.value.hasFocus() }?.key
        strip.removeAllViews();buttons.clear()
        options.forEach { option ->
            val button=createTabButton().apply {
                glassStyle=this@GlassTabs.glassStyle;title=option.title;contentLayout=GlassContentLayout.ICON_TEXT
                isEnabled=option.enabled;setOnClickListener { select(option.key);onTabConfirmed?.invoke(option.key) }
                setOnFocusChangeListener{_,focused->if(focused)onTabFocused?.invoke(option.key)}
            }
            buttons[option.key]=button
            strip.addView(button,LinearLayout.LayoutParams(-2,context.dp(48f)).apply { marginEnd=context.dp(4f) })
        }
        selectedKey=selected?.takeIf { buttons[it]?.isEnabled==true } ?: options.firstOrNull { it.enabled }?.key
        update();if(focused!=null)post { buttons[focused]?.requestFocus() }
    }
    fun tabView(key:String):GlassButton?=buttons[key]
    protected open fun createTabButton():GlassButton=GlassTabButton(context)
    protected open fun updateTabAppearance(button:GlassButton,selected:Boolean){button.isSelected=selected}
    fun select(key: String) { require(buttons[key]?.isEnabled==true);if(selectedKey==key)return;selectedKey=key;update();onTabSelected?.invoke(key) }
    private fun update(){buttons.forEach { (key,button)->updateTabAppearance(button,key==selectedKey) }}
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(event.action==KeyEvent.ACTION_DOWN && event.keyCode in intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val enabled=buttons.filterValues { it.isEnabled }.toList()
            val current=enabled.indexOfFirst { it.second.hasFocus() }
            if(current>=0) {
                val delta=if((event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)!=rtl)1 else -1
                val next=enabled.getOrNull(current+delta) ?: return super.dispatchKeyEvent(event)
                select(next.first);next.second.requestFocus();return true
            }
        };return super.dispatchKeyEvent(event)
    }
}
/** A named base keeps the XML constructors of GlassTabs and GlassSegmentedControl identical. */
open class HorizontalScrollViewCompat @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): android.widget.HorizontalScrollView(context,attrs)
class GlassSegmentedControl @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): GlassTabs(context,attrs){
    init {
        background=android.graphics.drawable.GradientDrawable().apply{setColor(0x99162f3c.toInt());cornerRadius=context.px(18f)}
        val inset=context.dp(4f);setPaddingRelative(inset,inset,inset,inset)
        clipToPadding=false
    }
    override fun createTabButton():GlassButton=GlassSegmentButton(context)
    override fun updateTabAppearance(button:GlassButton,selected:Boolean){
        super.updateTabAppearance(button,selected)
        button.titleView.setTextColor(if(selected)0xff304c5b.toInt() else glassStyle.foreground)
    }
}
