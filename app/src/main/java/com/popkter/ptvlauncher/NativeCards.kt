// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/** Rail artwork stays 112 × 63 dp regardless of app count. Extra rows scroll, never shrink. */
internal object AppLayout {
    const val HOME_RIGHT_MARGIN=30
    const val RAIL_TOP=70
    const val RAIL_SCROLL_INSET=8
    const val CARD_INSET=8
    const val RAIL_CARD_WIDTH=112
    const val RAIL_CARD_HEIGHT=63
    const val RAIL_ROW_HEIGHT=83
    const val RAIL_WIDTH=246
    const val LIBRARY_COLUMNS=5
    const val LIBRARY_ROW_HEIGHT=98
}

private fun View.lift(focused:Boolean) {
    animate().cancel()
    animate().scaleX(if(focused)1.065f else 1f).scaleY(if(focused)1.065f else 1f)
        .setDuration(140).setInterpolator(DecelerateInterpolator(1.5f)).start()
}

private fun drawFocus(canvas:Canvas,rect:RectF,radius:Float,padding:Float,paint:Paint) {
    val shell=RectF(rect.left-padding,rect.top-padding,rect.right+padding,rect.bottom+padding)
    paint.shader=LinearGradient(shell.left,shell.top,shell.right,shell.bottom,
        intArrayOf(0xb0ffffff.toInt(),0x36ffffff,0x7affffff),null,Shader.TileMode.CLAMP)
    canvas.drawRoundRect(shell,radius+padding,radius+padding,paint)
    paint.shader=null;paint.style=Paint.Style.STROKE;paint.strokeWidth=padding*.18f;paint.color=0xaaffffff.toInt()
    canvas.drawRoundRect(shell,radius+padding,radius+padding,paint)
    paint.style=Paint.Style.FILL
}

/** The same original card renderer is shared by HOME, floating rail, and the recycled app grid. */
internal class AppCardView(context:Context):View(context) {
    var entry:AppEntry?=null
    var library=false
    var pinned=false
    var showPinBadge=false
    var actionsExpanded=false
        set(value) {field=value;invalidate()}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint=TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val clip=Path()
    init {
        isFocusable=true;isClickable=true;isLongClickable=true
        setOnFocusChangeListener{_,focused->lift(focused);invalidate()}
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        // Scale around the artwork, not the wide label area, so its left halo stays visible.
        pivotX=if(library)w/2f else context.dp(AppLayout.CARD_INSET+AppLayout.RAIL_CARD_WIDTH/2).toFloat()
        pivotY=if(library)h/2f else context.dp(AppLayout.CARD_INSET+AppLayout.RAIL_CARD_HEIGHT/2f).toFloat()
    }
    override fun setSelected(selected:Boolean) {
        val changed=isSelected!=selected
        super.setSelected(selected)
        if(changed && library) {lift(selected);invalidate()}
    }
    fun bind(app:AppEntry,inLibrary:Boolean,isPinned:Boolean) {
        entry=app;library=inLibrary;pinned=isPinned;tag="app:"+app.id
        contentDescription=app.label+if(isPinned)"，已在主屏幕" else ""
        invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        val app=entry?:return
        val focused=isFocused || isSelected || actionsExpanded
        val inset=context.dp(AppLayout.CARD_INSET).toFloat()
        val ch=if(library)min((width-inset*2)*9f/16f,height-context.dp(35).toFloat()) else context.dp(AppLayout.RAIL_CARD_HEIGHT).toFloat()
        val cw=if(library)ch*16f/9f else context.dp(AppLayout.RAIL_CARD_WIDTH).toFloat()
        val left=if(library)(width-cw)/2 else inset
        val rect=RectF(left,inset,left+cw,inset+ch)
        val radius=ch*.19f
        if(focused)drawFocus(canvas,rect,radius,context.dp(4).toFloat(),paint)
        AppIconArtwork.draw(canvas,app,rect,radius)
        // Subtle edge separates bright banners from the glass focus shell.
        paint.style=Paint.Style.STROKE;paint.strokeWidth=context.dp(.5f).toFloat();paint.color=0x24ffffff
        canvas.drawRoundRect(rect,radius,radius,paint);paint.style=Paint.Style.FILL
        if(library && showPinBadge) {
            paint.color=if(pinned)0xff3478f6.toInt() else 0xccffffff.toInt();canvas.drawCircle(rect.right-context.dp(11),rect.top+context.dp(11),context.dp(8).toFloat(),paint)
            val cx=rect.right-context.dp(11);val cy=rect.top+context.dp(11);val half=context.dp(5).toFloat()
            IosIcons.draw(context,canvas,if(pinned)R.drawable.f7_checkmark else R.drawable.f7_plus,
                RectF(cx-half,cy-half,cx+half,cy+half),if(pinned)WHITE else 0xff516175.toInt())
        }
        if((library || focused) && !actionsExpanded) {
            textPaint.color=if(focused)WHITE else 0xcde8edf5.toInt()
            textPaint.textSize=context.dp(if(library)11 else 12).toFloat()
            textPaint.typeface=Typeface.create(if(focused)"sans-serif-medium" else "sans-serif",Typeface.NORMAL)
            textPaint.textAlign=if(library)Paint.Align.CENTER else Paint.Align.LEFT
            textPaint.setShadowLayer(context.dp(3).toFloat(),0f,context.dp(1).toFloat(),0x90000000.toInt())
            val maxWidth=if(library)width-inset*2 else width-rect.right-context.dp(13)
            if(maxWidth>0) {
                val text=TextUtils.ellipsize(app.label,textPaint,maxWidth,TextUtils.TruncateAt.END).toString()
                canvas.drawText(text,if(library)width/2f else rect.right+context.dp(13),if(library)rect.bottom+context.dp(20) else rect.centerY()+context.dp(4),textPaint)
            }
            textPaint.clearShadowLayer()
        }
    }
}

/** A real focusable AppList action, always placed after the pinned applications. */
internal class AppListButton(context:Context):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint=TextPaint(Paint.ANTI_ALIAS_FLAG)
    init {
        isFocusable=true;isClickable=true;tag="rail:app-list";contentDescription="AppList，所有应用"
        setOnFocusChangeListener{_,focused->lift(focused);invalidate()}
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        pivotX=context.dp(AppLayout.CARD_INSET+AppLayout.RAIL_CARD_WIDTH/2).toFloat()
        pivotY=context.dp(AppLayout.CARD_INSET+AppLayout.RAIL_CARD_HEIGHT/2f).toFloat()
    }
    override fun onDraw(canvas:Canvas) {
        val inset=context.dp(AppLayout.CARD_INSET).toFloat()
        val rect=RectF(inset,inset,inset+context.dp(AppLayout.RAIL_CARD_WIDTH),inset+context.dp(AppLayout.RAIL_CARD_HEIGHT))
        val radius=context.dp(13).toFloat()
        if(isFocused)drawFocus(canvas,rect,radius,context.dp(4).toFloat(),paint)
        paint.shader=LinearGradient(rect.left,rect.top,rect.right,rect.bottom,intArrayOf(0xc9515b6c.toInt(),0xb02b3444.toInt()),null,Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect,radius,radius,paint);paint.shader=null
        paint.style=Paint.Style.STROKE;paint.strokeWidth=context.dp(.6f).toFloat();paint.color=0x55ffffff
        canvas.drawRoundRect(rect,radius,radius,paint);paint.style=Paint.Style.FILL
        val half=context.dp(15).toFloat()
        IosIcons.draw(context,canvas,R.drawable.f7_square_grid_2x2,
            RectF(rect.centerX()-half,rect.centerY()-half,rect.centerX()+half,rect.centerY()+half))
        if(isFocused) {
            textPaint.color=WHITE;textPaint.textSize=context.dp(12).toFloat();textPaint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            textPaint.setShadowLayer(context.dp(3).toFloat(),0f,context.dp(1).toFloat(),0xa0000000.toInt())
            canvas.drawText("AppList",rect.right+context.dp(13),rect.centerY()+context.dp(4),textPaint)
        }
    }
}
