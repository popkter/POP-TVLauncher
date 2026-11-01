package com.popkter.glass_comonpent

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.util.AttributeSet
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import kotlin.math.roundToInt

/** Drawn switch supports remote, touch, accessibility and inherited RTL. */
class GlassSwitch @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GlassListRow(context, attrs), Checkable {
    private var checked = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var onCheckedChange: ((Boolean) -> Unit)? = null
    init {
        glassStyle=glassStyle.copy(activeColor=0xff30d158.toInt())
        trailingView.visibility = INVISIBLE
        trailingView.minimumWidth = context.dp(47f)
        setOnClickListener { toggle() }
    }
    override fun isChecked() = checked
    override fun setChecked(value: Boolean) { if (checked != value) { checked = value; invalidate(); onCheckedChange?.invoke(value) } }
    override fun toggle() { if (isEnabled) setChecked(!checked) }
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val w = context.px(43f); val h = context.px(26f)
        val x = if (rtl) paddingLeft.toFloat() else width-paddingRight-w
        val y = (height-h)/2
        paint.color = if (checked) glassStyle.activeColor else 0x70c6d3de
        canvas.drawRoundRect(RectF(x,y,x+w,y+h), h/2,h/2,paint)
        val end = checked != rtl
        paint.color = glassStyle.foreground
        canvas.drawCircle(x+context.px(if (end) 30f else 13f),y+h/2,context.px(10f),paint)
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info); info.className = Switch::class.java.name; info.isCheckable = true; info.isChecked = checked
    }
}

/** Native keyboard editing inside a single shared focus outline, including its clear action. */
class GlassTextField @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GlassSurface(context, attrs) {
    val editText = EditText(ContextThemeWrapper(context,R.style.GlassInputTheme)).apply {
        background = null; isSingleLine = true; textSize = 16f
        minimumHeight=0;minHeight=0
        setPaddingRelative(0,0,0,0); layoutDirection = LAYOUT_DIRECTION_INHERIT
        if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
    }
    val clearButton = TextView(context).apply { text = "×"; textSize = 20f; gravity = Gravity.CENTER; contentDescription = "清除"; prepareAction() }
    private val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    init {
        minimumHeight = context.dp(56f); highlightDescendantFocus = true
        row.addView(editText,LinearLayout.LayoutParams(0,-2,1f)); row.addView(clearButton,LinearLayout.LayoutParams(context.dp(44f),context.dp(44f)))
        addView(row,LayoutParams(-1,-2,Gravity.CENTER_VERTICAL)); applyStyle()
        clearButton.setOnClickListener { editText.text.clear(); editText.requestFocus(); showKeyboard() }
        editText.setOnClickListener { showKeyboard() }
    }
    override fun applyStyle() { super.applyStyle(); editText.setTextColor(glassStyle.foreground); editText.setHintTextColor(glassStyle.secondary); clearButton.setTextColor(glassStyle.foreground) }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        // One base unit high: horizontal padding must not add 32dp to the 44dp clear target.
        val height=resolveSize(context.dp(glassStyle.unit),heightMeasureSpec)
        val vertical=((height-context.dp(44f))/2).coerceAtLeast(0)
        val horizontal=context.dp(glassStyle.contentPadding)
        if(paddingTop!=vertical || paddingStart!=horizontal)setPaddingRelative(horizontal,vertical,horizontal,vertical)
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY))
    }
    fun focusInput(showKeyboard: Boolean = true) { editText.requestFocus(); if (showKeyboard) post { showKeyboard() } }
    private fun showKeyboard() { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(editText,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
}

/** Filled 4x1 base surface. Geometry and icon inset are exactly those of GlassButton. */
class GlassSlider @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : GlassSurface(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private val clip = Path()
    var icon: android.graphics.drawable.Drawable? = null
        set(value) { field = value; invalidate() }
    var minimum = 0
        set(value) { require(value < maximum); field = value; valueChanged(progress, false) }
    var maximum = 100
        set(value) { require(value > minimum); field = value; valueChanged(progress, false) }
    var step = 5
        set(value) { require(value > 0); field = value }
    var progress = 45
        set(value) { field = value.coerceIn(minimum,maximum); invalidate() }
    var onValueChanged: ((Int, Boolean) -> Unit)? = null
    init { prepareAction(); contentDescription = "" }
    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(resolveSize(context.dp(GlassSize.X4Y1.width(glassStyle)),w),resolveSize(context.dp(glassStyle.unit),h)) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = track.apply { set(1f,1f,width-1f,height-1f) }
        val radius = context.px(glassStyle.cornerRadius)
        val path = clip.apply { rewind();addRoundRect(rect,radius,radius,Path.Direction.CW) }
        val ratio = (progress-minimum).toFloat()/(maximum-minimum)
        val boundary = if (rtl) width*(1-ratio) else width*ratio
        canvas.save(); canvas.clipPath(path)
        paint.color = 0xffedf4f8.toInt()
        if (rtl) canvas.drawRect(boundary,0f,width.toFloat(),height.toFloat(),paint) else canvas.drawRect(0f,0f,boundary,height.toFloat(),paint)
        val size = context.px(glassStyle.iconSize).coerceAtMost(minOf(width,height).toFloat())
        val inset = minOf(context.px(glassStyle.contentPadding), ((minOf(width,height)-size)/2-context.px(1f)).coerceAtLeast(0f))
        val x = if (rtl) width-inset-size else inset
        icon?.mutate()?.let { image ->
            image.setBounds(x.roundToInt(),((height-size)/2).roundToInt(),(x+size).roundToInt(),((height+size)/2).roundToInt())
            image.setTint(glassStyle.foreground); image.draw(canvas)
            canvas.save(); if (rtl) canvas.clipRect(boundary,0f,width.toFloat(),height.toFloat()) else canvas.clipRect(0f,0f,boundary,height.toFloat())
            image.setTint(0xff344f5e.toInt()); image.draw(canvas); canvas.restore()
        }
        canvas.restore()
        // Redraw the focus outline above the filled track.
        if (isFocused) { paint.style=Paint.Style.STROKE;paint.strokeWidth=context.px(2f);paint.color=glassStyle.focusColor;canvas.drawRoundRect(rect,radius,radius,paint);paint.style=Paint.Style.FILL }
    }
    private fun valueChanged(value: Int, user: Boolean) { val next=value.coerceIn(minimum,maximum); if (progress != next) { progress=next; onValueChanged?.invoke(next,user) } }
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean {
        if (isEnabled && code in intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val increase = (code == KeyEvent.KEYCODE_DPAD_RIGHT) != rtl
            valueChanged(progress+if (increase) step else -step,true); return true
        }; return super.onKeyDown(code,event)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE -> { requestFocus(); parent?.requestDisallowInterceptTouchEvent(true); val ratio=(event.x/width.coerceAtLeast(1)).coerceIn(0f,1f);valueChanged((minimum+(if(rtl)1-ratio else ratio)*(maximum-minimum)).roundToInt(),true) }
            MotionEvent.ACTION_UP -> { parent?.requestDisallowInterceptTouchEvent(false);performClick() }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }; return true
    }
    override fun performClick(): Boolean { super.performClick();return true }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info);info.className=SeekBar::class.java.name
        info.rangeInfo=AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,minimum.toFloat(),maximum.toFloat(),progress.toFloat())
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun performAccessibilityAction(action: Int,args: Bundle?): Boolean {
        if (!isEnabled) return false
        when(action) {
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> { valueChanged(args?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.roundToInt() ?: progress,true);return true }
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { valueChanged(progress+step,true);return true }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { valueChanged(progress-step,true);return true }
        };return super.performAccessibilityAction(action,args)
    }
}

class GlassStepper @JvmOverloads constructor(context: Context,attrs: AttributeSet? = null) : GlassSurface(context,attrs) {
    val decreaseButton=GlassButton(context).apply { title="−";iconOnly=false;contentLayout=GlassContentLayout.ICON_TEXT;gridSize=GlassSize.X2Y1;contentDescription="减少" }
    val increaseButton=GlassButton(context).apply { title="+";contentLayout=GlassContentLayout.ICON_TEXT;gridSize=GlassSize.X2Y1;contentDescription="增加" }
    val valueView=TextView(context).apply { gravity=Gravity.CENTER }
    var minimum=0
    var maximum=100
    var step=5
    var value=30
        set(newValue) { require(minimum<=maximum);field=newValue.coerceIn(minimum,maximum);valueView.text=java.text.NumberFormat.getIntegerInstance(resources.configuration.locales[0]).format(field);decreaseButton.isEnabled=field>minimum;increaseButton.isEnabled=field<maximum }
    var onValueChanged: ((Int)->Unit)?=null
    init {
        val row=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        row.addView(decreaseButton,LinearLayout.LayoutParams(context.dp(56f),context.dp(56f)))
        row.addView(valueView,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(increaseButton,LinearLayout.LayoutParams(context.dp(56f),context.dp(56f)))
        addView(row,LayoutParams(-1,-2));applyStyle();value=30
        decreaseButton.setOnClickListener { change(-step) };increaseButton.setOnClickListener { change(step) }
    }
    private fun change(delta: Int) { require(step>0);value+=delta;onValueChanged?.invoke(value) }
    override fun applyStyle() { super.applyStyle();decreaseButton.glassStyle=glassStyle;increaseButton.glassStyle=glassStyle;valueView.applyTextStyle(glassStyle) }
}
