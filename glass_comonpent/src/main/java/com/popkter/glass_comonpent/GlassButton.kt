package com.popkter.glass_comonpent

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/** Nine grid sizes, one icon geometry. Two-by-one deliberately never shows a description. */
open class GlassButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GlassSurface(context, attrs),android.widget.Checkable {
    /** Commands/navigation stay momentary; stateful buttons can opt into confirm-to-toggle. */
    var toggleOnClick=false
    val iconView = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    val titleView = TextView(context).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    val descriptionView = TextView(context).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    var gridSize = GlassSize.X2Y1
        set(value) { field = value; updateContent(); requestLayout() }
    var contentLayout = GlassContentLayout.AUTO
        set(value) { field = value; updateContent(); requestLayout() }
    var iconOnly = false
        set(value) { field = value; updateContent(); requestLayout() }
    var title: CharSequence = ""
        set(value) { field = value; titleView.text = value; contentDescription = value; updateContent() }
    var description: CharSequence = ""
        set(value) { field = value; descriptionView.text = value; updateContent() }
    var icon: Drawable? = null
        set(value) { field = value; iconView.setImageDrawable(value); iconView.visibility=if(value==null)GONE else VISIBLE; requestLayout() }
    private val stacked get() = contentLayout != GlassContentLayout.ICON_TEXT && gridSize.rows > 1
    init {
        // This view computes its own safe inset, including compact 36dp icon buttons.
        // FrameLayout's default padding clip would otherwise crop a 22dp icon to 4dp.
        clipToPadding=false
        prepareAction(); addView(iconView); copy.addView(titleView); copy.addView(descriptionView); addView(copy)
        applyStyle(); updateContent()
    }
    override fun applyStyle() {
        super.applyStyle(); titleView.applyTextStyle(glassStyle); descriptionView.applyTextStyle(glassStyle, true)
        iconView.imageTintList = android.content.res.ColorStateList.valueOf(glassStyle.foreground)
    }
    private fun updateContent() {
        copy.visibility = if (iconOnly || gridSize == GlassSize.X1Y1 || gridSize.columns == 1) GONE else VISIBLE
        descriptionView.visibility = if (description.isNotEmpty() && gridSize != GlassSize.X2Y1 && contentLayout != GlassContentLayout.ICON_TEXT) VISIBLE else GONE
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(context.dp(gridSize.width(glassStyle)), widthMeasureSpec)
        val h = resolveSize(context.dp(gridSize.height(glassStyle)), heightMeasureSpec)
        val size = if(icon==null)0 else min(context.dp(glassStyle.iconSize), min(w, h).coerceAtLeast(0))
        val pad = effectivePadding(w, h, size)
        iconView.measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
        val available = (w - pad * 2 - if (stacked || size==0) 0 else size + context.dp(glassStyle.gap)).coerceAtLeast(0)
        copy.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec((h - pad * 2 - if (stacked) size else 0).coerceAtLeast(0), MeasureSpec.AT_MOST))
        setMeasuredDimension(w, h)
    }
    private fun effectivePadding(w: Int, h: Int, size: Int) = min(context.dp(glassStyle.contentPadding), ((min(w,h)-size)/2-1).coerceAtLeast(0))
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val size = iconView.measuredWidth; val pad = effectivePadding(width, height, size)
        val only = copy.visibility == GONE
        val start = if (only) (width-size)/2 else pad
        val x = if (rtl) width-start-size else start
        val y = if (stacked && !only) pad else (height-size)/2
        iconView.layout(x, y, x+size, y+size)
        if (!only) {
            val copyStart = if (stacked || size==0) pad else pad+size+context.dp(glassStyle.gap)
            val cx = if(size==0)(width-copy.measuredWidth)/2 else if (rtl) width-copyStart-copy.measuredWidth else copyStart
            val cy = if (stacked) height-pad-copy.measuredHeight else (height-copy.measuredHeight)/2
            copy.layout(cx, cy, cx+copy.measuredWidth, cy+copy.measuredHeight)
        }
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info); info.className = android.widget.Button::class.java.name
        info.isCheckable=toggleOnClick;info.isChecked=isChecked
    }
    override fun isChecked()=isSelected
    override fun setChecked(checked:Boolean){isSelected=checked}
    override fun toggle(){isSelected=!isSelected}
    override fun performClick():Boolean {if(isEnabled && toggleOnClick)toggle();return super.performClick()}
}
