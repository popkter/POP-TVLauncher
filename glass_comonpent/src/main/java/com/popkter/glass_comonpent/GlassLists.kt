package com.popkter.glass_comonpent

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.*

data class GlassListItem(val key: String, val title: String, val description: String = "", val icon: Drawable? = null,
                         val trailing: String = "", val enabled: Boolean = true, val selected: Boolean = false)
data class GlassListSection(val key: String, val title: String, val items: List<GlassListItem>)

/** Start icon, flexible title/description, end status; all slots inherit layout direction. */
open class GlassListRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GlassSurface(context, attrs) {
    val content = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    val iconView = ImageView(context)
    val titleView = TextView(context)
    val descriptionView = TextView(context)
    val trailingView = TextView(context)
    private val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    var key: String = ""
        private set
    init {
        prepareAction(); minimumHeight = context.dp(60f)
        text.addView(titleView); text.addView(descriptionView)
        content.addView(iconView, LinearLayout.LayoutParams(context.dp(24f), context.dp(24f)).apply { marginEnd = context.dp(12f) })
        content.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(trailingView, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(10f) })
        addView(content, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL)); applyStyle()
        titleView.maxLines = 2; descriptionView.maxLines = 2
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        content.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    override fun applyStyle() {
        super.applyStyle(); titleView.applyTextStyle(glassStyle); descriptionView.applyTextStyle(glassStyle, true); trailingView.applyTextStyle(glassStyle, true)
        iconView.layoutParams = (iconView.layoutParams as LinearLayout.LayoutParams).apply { width = context.dp(glassStyle.iconSize); height = width }
        iconView.imageTintList = android.content.res.ColorStateList.valueOf(glassStyle.foreground)
    }
    open fun bind(item: GlassListItem) {
        key = item.key; tag = "glass:item:${item.key}"
        titleView.text = item.title; descriptionView.text = item.description
        descriptionView.visibility = if (item.description.isEmpty()) GONE else VISIBLE
        iconView.setImageDrawable(item.icon); iconView.visibility = if (item.icon == null) GONE else VISIBLE
        trailingView.text = item.trailing; isEnabled = item.enabled; alpha = if (item.enabled) 1f else .4f
        isSelected = item.selected; contentDescription = listOf(item.title,item.description,item.trailing).filter { it.isNotEmpty() }.joinToString(", ")
    }
}

/** Stable item keys preserve focus and scroll during refresh or navigation back. */
open class GlassListView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    val itemsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    var glassStyle = GlassStyle()
        set(value) { field = value; rows.values.forEach { it.glassStyle = value } }
    var onItemClick: ((GlassListItem) -> Unit)? = null
    var onItemLongClick: ((GlassListRow, GlassListItem) -> Boolean)? = null
    private val rows = linkedMapOf<String, GlassListRow>()
    private var focusKey: String? = null
    init { addView(itemsContainer, LayoutParams(-1,-2)); isVerticalScrollBarEnabled = false; isFillViewport = false }
    fun submitItems(items: List<GlassListItem>) = submitSections(listOf(GlassListSection("", "", items)))
    fun submitSections(sections: List<GlassListSection>) {
        val keys = sections.flatMap { it.items }.map { it.key }
        require(keys.size == keys.toSet().size) { "List item keys must be unique across sections" }
        val focused = rows.entries.firstOrNull { it.value.hasFocus() }?.key
        val previousScroll = scrollY
        focusKey = focused ?: focusKey
        val oldRows = rows.toMap(); rows.clear(); itemsContainer.removeAllViews()
        sections.forEach { section ->
            if (section.title.isNotEmpty()) itemsContainer.addView(TextView(context).apply {
                text = section.title; applyTextStyle(glassStyle,true); setPaddingRelative(context.dp(12f),context.dp(16f),context.dp(12f),context.dp(8f)); isFocusable = false
            })
            section.items.forEach { item ->
                val row = oldRows[item.key] ?: GlassListRow(context)
                row.glassStyle = glassStyle; row.bind(item)
                row.setOnClickListener { focusKey = item.key; onItemClick?.invoke(item) }
                row.setOnLongClickListener { onItemLongClick?.invoke(row, item) ?: false }
                rows[item.key] = row
                itemsContainer.addView(row, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = context.dp(4f) })
            }
        }
        post {
            scrollTo(0, previousScroll)
            if (focused != null) (rows[focusKey]?.takeIf { it.isEnabled } ?: rows.values.firstOrNull { it.isEnabled })?.requestFocus()
        }
    }
    fun restoreItemFocus(key: String? = focusKey): Boolean = rows[key]?.takeIf { it.isEnabled }?.requestFocus() ?: false
    fun findItemView(key: String): GlassListRow? = rows[key]
}

/** Grouping uses the same stable-key list implementation. */
class GlassSectionListView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GlassListView(context, attrs)
