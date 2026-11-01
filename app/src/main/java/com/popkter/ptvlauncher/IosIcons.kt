package com.popkter.ptvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** Framework7 Icons 5.0.5 (MIT), bundled vectors; all bounds share a 56-unit viewport. */
internal object IosIcons {
    private val icons=mutableMapOf<Int,Drawable>()
    fun draw(context:Context,canvas:Canvas,id:Int,bounds:RectF,color:Int=WHITE) {
        val icon=icons.getOrPut(id){context.getDrawable(id)!!.mutate()}
        icon.setTint(color)
        icon.setBounds(bounds.left.toInt(),bounds.top.toInt(),bounds.right.toInt(),bounds.bottom.toInt())
        icon.draw(canvas)
    }
}
