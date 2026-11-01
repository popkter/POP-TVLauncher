// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Separate opaque action buttons, using MIT-licensed Framework7 iOS-style symbols. */
internal class AppActionView(context:Context,private val kind:String,private val title:String):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        isFocusable=true;isClickable=true;contentDescription=title;tag="action:$kind"
        setOnFocusChangeListener {_,focused->
            animate().scaleX(if(focused)1.07f else 1f).scaleY(if(focused)1.07f else 1f).setDuration(120).start()
            invalidate()
        }
    }
    override fun onDraw(canvas:Canvas) {
        canvas.save();canvas.scale(width/56f,height/56f)
        paint.style=Paint.Style.FILL
        paint.color=if(isFocused)WHITE else when(kind) {
            "remove"->0xffffe9e8.toInt()
            "add"->0xffe4f1ff.toInt()
            "edit"->0xffeeeef0.toInt()
            else->0xffe7efff.toInt()
        }
        canvas.drawCircle(28f,28f,24f,paint)
        if(isFocused) {
            paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;paint.color=WHITE
            canvas.drawCircle(28f,28f,26f,paint)
            paint.style=Paint.Style.FILL
        }
        val icon=when(kind) {
            "remove"->R.drawable.f7_minus_circle
            "add"->R.drawable.f7_plus_circle
            "edit"->R.drawable.f7_line_horizontal_3
            else->R.drawable.f7_info_circle
        }
        val color=when {
            kind=="remove"->0xffff3b30.toInt()
            kind=="edit"->0xff3a3a3c.toInt()
            else->0xff007aff.toInt()
        }
        IosIcons.draw(context,canvas,icon,RectF(12f,12f,44f,44f),color)
        canvas.restore()
    }
}

