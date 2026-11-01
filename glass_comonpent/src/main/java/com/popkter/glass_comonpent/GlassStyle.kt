package com.popkter.glass_comonpent

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import kotlin.math.roundToInt

/** All lengths are dp. Foreground blur is intentionally separate from the shared glass material. */
data class GlassStyle(
    val unit: Float = 56f, val gap: Float = 10f,
    val iconSize: Float = 24f, val contentPadding: Float = 16f,
    val cornerRadius: Float = 28f, val fillOpacity: Float = .11f,
    val foreground: Int = 0xfff5f7fb.toInt(), val secondary: Int = 0xffd4dfe7.toInt(),
    val activeColor: Int = 0xff0a84ff.toInt(), val focusColor: Int = 0xffedf5fa.toInt(),
    val surfaceColor: Int = 0xffe4eff9.toInt(), val focusOverlayColor: Int = 0x38ffffff
) {
    init {
        require(unit > 0 && gap >= 0 && iconSize > 0 && contentPadding >= 0 && cornerRadius >= 0)
        require(fillOpacity in 0f..1f)
    }
    companion object {
        fun from(context: Context, attrs: AttributeSet?): GlassStyle {
            val defaults = GlassStyle()
            val a = context.obtainStyledAttributes(attrs, R.styleable.GlassSurface)
            return try {
                defaults.copy(
                    iconSize = a.getDimension(R.styleable.GlassSurface_glassIconSize, context.px(defaults.iconSize)) / context.resources.displayMetrics.density,
                    contentPadding = a.getDimension(R.styleable.GlassSurface_glassContentPadding, context.px(defaults.contentPadding)) / context.resources.displayMetrics.density,
                    cornerRadius = a.getDimension(R.styleable.GlassSurface_glassCornerRadius, context.px(defaults.cornerRadius)) / context.resources.displayMetrics.density,
                    fillOpacity = a.getFloat(R.styleable.GlassSurface_glassFillOpacity, defaults.fillOpacity).coerceIn(0f, 1f)
                )
            } finally { a.recycle() }
        }
    }
}

enum class GlassSize(val columns: Int, val rows: Int) {
    X1Y1(1,1), X1Y2(1,2), X2Y1(2,1), X2Y2(2,2), X1Y4(1,4),
    X2Y4(2,4), X4Y4(4,4), X4Y1(4,1), X4Y2(4,2);
    fun width(style: GlassStyle) = columns * style.unit + (columns - 1) * style.gap
    fun height(style: GlassStyle) = rows * style.unit + (rows - 1) * style.gap
}
enum class GlassContentLayout { AUTO, ICON_TEXT, STACKED }

internal fun Context.px(dp: Float) = dp * resources.displayMetrics.density
internal fun Context.dp(dp: Float) = px(dp).roundToInt()
internal fun Context.dp(dp: Int) = dp(dp.toFloat())
internal fun drawGlassIcon(context: Context,canvas: Canvas,id: Int,bounds: RectF,color: Int=Color.WHITE) {
    context.getDrawable(id)?.mutate()?.let { icon->icon.setTint(color);icon.setBounds(bounds.left.toInt(),bounds.top.toInt(),bounds.right.toInt(),bounds.bottom.toInt());icon.draw(canvas) }
}
internal val View.rtl get() = layoutDirection == View.LAYOUT_DIRECTION_RTL
internal fun View.prepareAction() {
    id = View.generateViewId(); isFocusable = true; isClickable = true
    if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
}
internal fun TextView.applyTextStyle(style: GlassStyle, secondary: Boolean = false) {
    setTextColor(if (secondary) style.secondary else style.foreground)
    textSize = if (secondary) 11f else 14f
    textDirection = View.TEXT_DIRECTION_LOCALE
    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
}

class GlassBackground(private val context: Context, var style: GlassStyle = GlassStyle()): Drawable() {
    constructor(context: Context, radius: Float): this(context, GlassStyle(cornerRadius=radius))
    var radius: Float
        get()=style.cornerRadius
        set(value){style=style.copy(cornerRadius=value);invalidateSelf()}
    var radiusX: Float?=null
    var radiusY: Float?=null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var highlight: Shader? = null
    var focused = false
        set(value){field=value;invalidateSelf()}
    var selected = false
    private var opacity = 255
    override fun draw(canvas: Canvas) {
        val rx = context.px(radiusX ?: style.cornerRadius)
        val ry = context.px(radiusY ?: style.cornerRadius)
        paint.style = Paint.Style.FILL
        val alpha = (style.fillOpacity * 255).roundToInt()
        paint.color = if (selected) style.activeColor
            else Color.argb(alpha,Color.red(style.surfaceColor),Color.green(style.surfaceColor),Color.blue(style.surfaceColor))
        paint.alpha = paint.alpha * opacity / 255
        canvas.drawRoundRect(rect, rx, ry, paint)
        paint.color=Color.WHITE;paint.alpha=opacity;paint.shader = highlight
        canvas.drawRoundRect(rect, rx, ry, paint); paint.shader = null
        if(focused) {
            paint.color=style.focusOverlayColor;paint.alpha=paint.alpha*opacity/255
            canvas.drawRoundRect(rect,rx,ry,paint)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = context.px(if (focused) 2f else .7f)
        paint.color = if (focused) style.focusColor else 0x50ffffff
        paint.alpha = paint.alpha * opacity / 255
        canvas.drawRoundRect(rect, rx, ry, paint)
    }
    override fun onBoundsChange(bounds: Rect) {
        rect.set(bounds);rect.inset(context.px(1f),context.px(1f))
        highlight=LinearGradient(rect.left,rect.top,rect.right,rect.bottom,intArrayOf(0x19ffffff,0x00ffffff),null,Shader.TileMode.CLAMP)
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable contract") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
