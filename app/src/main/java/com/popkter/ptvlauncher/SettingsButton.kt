package com.popkter.ptvlauncher

import android.content.Context
import android.graphics.*
import android.view.View

/** A centered, resolution-independent gear; never relies on a font's symbol metrics. */
internal class SettingsButton(context:Context):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        isFocusable=true;isClickable=true
        contentDescription="桌面设置";tag="桌面设置按钮"
    }
    override fun onFocusChanged(gainFocus:Boolean,direction:Int,previouslyFocusedRect:Rect?) {
        super.onFocusChanged(gainFocus,direction,previouslyFocusedRect)
        invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val cx=width/2f;val cy=height/2f
        paint.style=Paint.Style.FILL;paint.pathEffect=null
        paint.color=if(hasFocus())Color.WHITE else 0x30ffffff
        canvas.drawCircle(cx,cy,minOf(width,height)/2f,paint)
        val size=context.dp(24).toFloat()
        IosIcons.draw(context,canvas,R.drawable.f7_gear_alt,RectF(cx-size/2,cy-size/2,cx+size/2,cy+size/2),
            if(hasFocus())0xff17212b.toInt() else Color.WHITE)
    }
}
