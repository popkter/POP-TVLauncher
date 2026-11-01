package com.popkter.glass_comonpent

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.ViewTreeObserver
import android.widget.FrameLayout

/** Shared material, padding, disabled appearance and focus ring for every compound control. */
open class GlassSurface @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    open var glassStyle: GlassStyle = GlassStyle.from(context, attrs)
        set(value) { field = value; material.style = value; applyStyle(); requestLayout(); invalidate() }
    private val material = GlassBackground(context, glassStyle)
    var focusHighlighted = false
        set(value) { field=value;invalidate() }
    var highlightDescendantFocus = false
        set(value) { field = value; invalidate() }
    private val focusObserver = ViewTreeObserver.OnGlobalFocusChangeListener { _, _ -> invalidate() }
    init {
        setWillNotDraw(false)
        val pad = context.dp(glassStyle.contentPadding); setPaddingRelative(pad,pad,pad,pad)
        if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
    }
    protected open fun applyStyle() {
        val pad = context.dp(glassStyle.contentPadding)
        setPaddingRelative(pad, pad, pad, pad)
    }
    override fun onDraw(canvas: Canvas) {
        material.setBounds(0, 0, width, height)
        material.focused = focusHighlighted || isFocused || (highlightDescendantFocus && hasFocus())
        material.selected = isSelected
        material.alpha = if (isEnabled) 255 else 90
        material.draw(canvas); super.onDraw(canvas)
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previous: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previous); invalidate()
    }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnGlobalFocusChangeListener(focusObserver) }
    override fun onDetachedFromWindow() { viewTreeObserver.removeOnGlobalFocusChangeListener(focusObserver); super.onDetachedFromWindow() }
    override fun onRtlPropertiesChanged(layoutDirection: Int) { super.onRtlPropertiesChanged(layoutDirection); requestLayout() }
}
