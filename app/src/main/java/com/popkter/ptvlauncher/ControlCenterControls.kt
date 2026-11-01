package com.popkter.ptvlauncher
import android.content.Context
import android.graphics.Rect
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import com.popkter.glass_comonpent.*
internal object ControlCenterLayout {
    const val TOP=48
    const val HEIGHT=450
    const val HEADER_HEIGHT=44
    const val CONTENT_TOP=64
}


internal typealias ControlGlass = GlassBackground
/** App business adapter; all material, geometry, interaction and rendering live in the library. */
internal class ControlTile(context:Context,val key:String,val icon:Int,val title:String,
    subtitle:String="",val large:Boolean=false,val iconOnly:Boolean=false,val slider:Boolean=false):FrameLayout(context) {
    private val button=if(!slider)GlassButton(context).apply {
        gridSize=when{iconOnly->GlassSize.X1Y1;large->GlassSize.X2Y2;else->GlassSize.X2Y1}
        this.iconOnly=this@ControlTile.iconOnly;this.title=this@ControlTile.title
        this.icon=context.getDrawable(this@ControlTile.icon)
        glassStyle=glassStyle.copy(activeColor=if(key=="shortcut")0xff30d158.toInt() else 0xff0a84ff.toInt())
    }else null
    private val range=if(slider)GlassSlider(context).apply {icon=context.getDrawable(this@ControlTile.icon);contentDescription=this@ControlTile.title} else null
    private val control:GlassSurface=button ?: range!!
    var subtitle=subtitle
        set(value){field=value;button?.description=value}
    var checked=false
        set(value){field=value;control.isSelected=value}
    var progress=0
        set(value){field=value.coerceIn(0,100);range?.progress=field;contentDescription=if(slider)"$title $field%" else title}
    var adjust:((Int)->Int)?=null
    init {
        tag="control:$key";id=generateViewId();isFocusable=true;isClickable=true;contentDescription=title
        if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
        control.isFocusable=false;control.isClickable=false;control.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(control,LayoutParams(-1,-1));button?.description=subtitle
        range?.onValueChanged={value,user->if(user)progress=adjust?.invoke(value) ?: value}
        range?.setOnClickListener{performClick()}
    }
    override fun onFocusChanged(focused:Boolean,direction:Int,previous:Rect?){super.onFocusChanged(focused,direction,previous);control.focusHighlighted=focused}
    override fun onKeyDown(code:Int,event:KeyEvent):Boolean=if(slider && code in intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT))range!!.onKeyDown(code,event) else super.onKeyDown(code,event)
    override fun onInterceptTouchEvent(event:MotionEvent):Boolean=slider || super.onInterceptTouchEvent(event)
    override fun onTouchEvent(event:MotionEvent):Boolean=if(slider){if(event.actionMasked==MotionEvent.ACTION_DOWN)requestFocus();range!!.onTouchEvent(event)} else super.onTouchEvent(event)
    override fun onInitializeAccessibilityNodeInfo(info:AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);if(slider)range!!.onInitializeAccessibilityNodeInfo(info) else {info.className=android.widget.Button::class.java.name;info.isCheckable=key=="shortcut";info.isChecked=checked}}
    override fun performAccessibilityAction(action:Int,args:android.os.Bundle?):Boolean=if(slider)range!!.performAccessibilityAction(action,args) else super.performAccessibilityAction(action,args)
}
