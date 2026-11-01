package com.popkter.ptvlauncher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.widget.ImageView

/** Shared icon-only dismissal control, matching the App List header. */
internal fun closeControl(context:Context,description:String,action:()->Unit)=ImageView(context).apply {
    id=android.view.View.generateViewId()
    setImageResource(R.drawable.f7_xmark)
    scaleType=ImageView.ScaleType.FIT_CENTER
    setPadding(context.dp(10),context.dp(10),context.dp(10),context.dp(10))
    contentDescription=description;isFocusable=true;isClickable=true
    if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
    fun state(focused:Boolean) {
        background=RippleDrawable(ColorStateList.valueOf(0x40ffffff),rounded(if(focused)WHITE else 0x1fffffff,context.dp(22).toFloat()),null)
        imageTintList=ColorStateList.valueOf(if(focused)0xff172231.toInt() else 0xd9ffffff.toInt())
    }
    state(false);setOnFocusChangeListener{_,focus->state(focus)}
    setOnClickListener{action()}
}
