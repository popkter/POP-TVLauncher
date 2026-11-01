package com.popkter.ptvlauncher

import android.content.Context
import android.graphics.*
import android.view.View

/** A single cropped photograph, with separate selection and remote-focus states. */
internal class WallpaperThumbnail(context:Context,private val bitmap:Bitmap?,private val number:Int,
    private val checked:Boolean,private val moving:Boolean):View(context) {
    init {if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun setSelected(selected:Boolean) {super.setSelected(selected);invalidate()}
    override fun onDraw(canvas:Canvas) {
        val inset=context.dp(4).toFloat()
        val rect=RectF(inset,inset,width-inset,height-context.dp(20).toFloat())
        val radius=context.dp(12).toFloat()
        val clip=Path().apply{addRoundRect(rect,radius,radius,Path.Direction.CW)}
        canvas.save();canvas.clipPath(clip)
        paint.color=0x30ffffff;canvas.drawRect(rect,paint)
        paint.alpha=255
        bitmap?.let {
            val scale=maxOf(rect.width()/it.width,rect.height()/it.height)
            val w=it.width*scale;val h=it.height*scale
            canvas.drawBitmap(it,null,RectF(rect.centerX()-w/2,rect.centerY()-h/2,rect.centerX()+w/2,rect.centerY()+h/2),paint)
        }
        canvas.restore()
        paint.style=Paint.Style.STROKE;paint.strokeWidth=context.dp(if(isSelected || moving)2 else 1).toFloat()
        paint.color=if(moving)0xff88c7ff.toInt() else if(isSelected)WHITE else 0x50ffffff
        canvas.drawRoundRect(rect,radius,radius,paint);paint.style=Paint.Style.FILL
        if(checked) {
            val r=context.dp(9).toFloat();val x=rect.right-r-context.dp(6);val y=rect.top+r+context.dp(6)
            paint.color=0xff0a84ff.toInt();canvas.drawCircle(x,y,r,paint)
            IosIcons.draw(context,canvas,R.drawable.f7_checkmark,RectF(x-r*.65f,y-r*.65f,x+r*.65f,y+r*.65f))
        }
        paint.color=if(isSelected)WHITE else SECONDARY;paint.textSize=context.dp(10).toFloat();paint.textAlign=Paint.Align.CENTER
        canvas.drawText(if(moving)"移动中 · $number" else "$number",width/2f,height-context.dp(3).toFloat(),paint)
    }
}
