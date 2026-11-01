package com.popkter.glass_comonpent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet

/** Text command without a resting pill; focus uses the same theme mask and outline. */
open class GlassTextButton @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):GlassButton(context,attrs) {
    private val focusMaterial=GlassBackground(context)
    init { contentLayout=GlassContentLayout.ICON_TEXT }
    override fun onDraw(canvas:Canvas){
        if(isFocused || focusHighlighted || isPressed){
            focusMaterial.style=glassStyle.copy(fillOpacity=0f)
            focusMaterial.focused=true;focusMaterial.selected=false
            focusMaterial.setBounds(0,0,width,height);focusMaterial.draw(canvas)
        }
    }
}

internal class GlassTabButton(context:Context):GlassTextButton(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        if(isSelected){
            paint.color=glassStyle.activeColor
            val inset=context.px(12f).coerceAtMost(width/4f)
            canvas.drawRoundRect(inset,height-context.px(4f),width-inset,height-context.px(2f),context.px(1f),context.px(1f),paint)
        }
    }
}

internal class GlassSegmentButton(context:Context):GlassTextButton(context){
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas){
        if(isSelected){
            paint.color=0xffdce9f0.toInt()
            val r=context.px(14f)
            canvas.drawRoundRect(1f,1f,width-1f,height-1f,r,r,paint)
        }
        super.onDraw(canvas)
    }
}
