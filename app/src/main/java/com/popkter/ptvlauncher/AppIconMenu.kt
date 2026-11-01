package com.popkter.ptvlauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.*
import android.widget.LinearLayout
import android.widget.PopupWindow
import com.popkter.glass_comonpent.*
import kotlin.math.roundToInt
import kotlinx.coroutines.*

/** Desktop-only presentation: three independent glass buttons, no enclosing material. */
internal class AppIconMenu(private val anchor:GlassArtworkCard,items:List<GlassMenuItem>,private val action:(GlassMenuItem)->Unit):AutoCloseable {
    private var popup:PopupWindow?=null
    private var closed=false
    private var closing=false
    private var commitDismiss=false
    private var offset=0f
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var blurredBackdrop:Bitmap?=null
    private val backdropOrigin=IntArray(2)
    private var backdropWidth=0
    private var backdropHeight=0
    var returnFocusTo:View=anchor
    var restoreFocusOnDismiss=true
    var onDismiss:(()->Unit)?=null
    val isShowing get()=popup?.isShowing==true
    private val buttonStyle=GlassStyle(surfaceColor=Color.WHITE,fillOpacity=.5f,
        foreground=0xff304553.toInt())
    private val row=LinearLayout(anchor.context).apply {
        layoutDirection=anchor.layoutDirection;clipChildren=false;clipToPadding=false
    }
    private val buttons=items.mapIndexed { index,item->
        object:GlassButton(anchor.context){
            private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            private val clip=Path()
            private val bounds=RectF()
            private val destination=RectF()
            private val location=IntArray(2)
            override fun onDraw(canvas:Canvas){
                blurredBackdrop?.takeUnless{it.isRecycled}?.let{image->
                    getLocationOnScreen(location)
                    bounds.set(1f,1f,width-1f,height-1f)
                    clip.rewind();clip.addRoundRect(bounds,context.dp(glassStyle.cornerRadius).toFloat(),context.dp(glassStyle.cornerRadius).toFloat(),Path.Direction.CW)
                    destination.set((backdropOrigin[0]-location[0]).toFloat(),(backdropOrigin[1]-location[1]).toFloat(),
                        (backdropOrigin[0]+backdropWidth-location[0]).toFloat(),(backdropOrigin[1]+backdropHeight-location[1]).toFloat())
                    canvas.save();canvas.clipPath(clip);canvas.drawBitmap(image,null,destination,paint);canvas.restore()
                }
                super.onDraw(canvas)
            }
        }.apply {
            gridSize=GlassSize.X1Y1;iconOnly=true;title=item.title;icon=item.icon;glassStyle=buttonStyle
            tag="glass:menu:${item.key}";isEnabled=item.enabled;alpha=if(item.enabled)1f else .35f
            setOnClickListener{dismiss{action(item)}}
        }.also { row.addView(it,LinearLayout.LayoutParams(anchor.context.dp(56),anchor.context.dp(56)).apply{if(index>0)marginStart=anchor.context.dp(10)}) }
    }
    private val attachment=object:View.OnAttachStateChangeListener{
        override fun onViewAttachedToWindow(view:View){}
        override fun onViewDetachedFromWindow(view:View){close()}
    }
    init {anchor.addOnAttachStateChangeListener(attachment)}
    fun show(){
        if(closed || isShowing || !anchor.isAttachedToWindow)return
        val c=anchor.context
        prepareBackdrop()
        val width=c.dp(buttons.size*56+(buttons.size-1)*10);val height=c.dp(56)
        val visible=Rect();anchor.getWindowVisibleDisplayFrame(visible)
        val bounds=anchor.artworkBounds();val origin=floatArrayOf(0f,0f);val location=IntArray(2)
        anchor.getLocationOnScreen(location);anchor.matrix.mapRect(bounds);anchor.matrix.mapPoints(origin)
        bounds.offset(location[0]-origin[0],location[1]-origin[1])
        val rtl=anchor.layoutDirection==View.LAYOUT_DIRECTION_RTL;val gap=c.dp(20)
        val preferred=if(rtl)bounds.right.roundToInt()+gap else bounds.left.roundToInt()-width-gap
        val alternate=if(rtl)bounds.left.roundToInt()-width-gap else bounds.right.roundToInt()+gap
        val x=(if(preferred>=visible.left && preferred+width<=visible.right)preferred else alternate).coerceIn(visible.left,(visible.right-width).coerceAtLeast(visible.left))
        val y=(bounds.centerY()-height/2).roundToInt().coerceIn(visible.top,(visible.bottom-height).coerceAtLeast(visible.top))
        val window=object:PopupWindow(row,width,height,true){
            override fun dismiss(){if(commitDismiss || closed)super.dismiss()else this@AppIconMenu.dismiss()}
        }.apply{
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));elevation=0f;animationStyle=0;isOutsideTouchable=true
            setOnDismissListener{
                popup=null;closing=false;closed=true;anchor.removeOnAttachStateChangeListener(attachment)
                releaseBackdrop()
                if(restoreFocusOnDismiss && returnFocusTo.isAttachedToWindow)returnFocusTo.requestFocus()
                onDismiss?.invoke()
            }
        }
        window.setTouchInterceptor{_,event->if(event.action==MotionEvent.ACTION_OUTSIDE){dismiss();true}else false}
        buttons.forEachIndexed{index,button->button.setOnKeyListener{_,key,event->
            when(key){
                KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_ESCAPE->{if(event.action==KeyEvent.ACTION_UP)dismiss();true}
                KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->{
                    if(event.action==KeyEvent.ACTION_DOWN){
                        val step=if((key==KeyEvent.KEYCODE_DPAD_RIGHT)!=rtl)1 else -1
                        var next=index+step;while(next in buttons.indices && !buttons[next].isEnabled)next+=step
                        buttons.getOrNull(next)?.requestFocus()
                    };true
                }
                KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN->true
                else->false
            }
        }}
        popup=window;window.showAtLocation(anchor.rootView,Gravity.TOP or Gravity.LEFT,x,y)
        offset=c.dp(if(x<bounds.left)10 else -10).toFloat();row.alpha=0f;row.translationX=offset
        row.animate().alpha(1f).translationX(0f).setDuration(duration(180)).start()
        buttons.firstOrNull{it.isEnabled}?.requestFocus()
    }
    private fun duration(value:Long)=if(android.os.Build.VERSION.SDK_INT>=26 && !ValueAnimator.areAnimatorsEnabled())0L else value
    fun dismiss(after:(()->Unit)?=null){
        val window=popup?:return;if(closing)return;closing=true
        row.animate().setListener(null).cancel()
        row.animate().alpha(0f).translationX(offset).setDuration(duration(140)).setListener(object:AnimatorListenerAdapter(){
            override fun onAnimationEnd(animation:Animator){commitDismiss=true;window.dismiss();commitDismiss=false;after?.invoke()}
        }).start()
    }
    private fun prepareBackdrop(){
        val root=anchor.rootView
        if(root.width<=0 || root.height<=0)return
        backdropWidth=root.width;backdropHeight=root.height;root.getLocationOnScreen(backdropOrigin)
        scope.launch {
            val w=480.coerceAtMost(root.width);val h=(root.height*w.toFloat()/root.width).roundToInt().coerceAtLeast(1)
            val source=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            var created:Bitmap?=null
            try {
                val canvas=Canvas(source);canvas.scale(w.toFloat()/root.width,h.toFloat()/root.height);root.draw(canvas)
                // Transparent accessibility overlays cannot capture the application behind them.
                if(Color.alpha(source.getPixel(w/2,h/2))==0)return@launch
                withContext(Dispatchers.Default){created=BackdropBlur.create(source)}
                if(!closed){blurredBackdrop=created;created=null;buttons.forEach{it.invalidate()}}
            } finally {source.recycle();created?.recycle()}
        }
    }
    private fun releaseBackdrop(){scope.cancel();blurredBackdrop?.recycle();blurredBackdrop=null}
    override fun close(){closed=true;row.animate().setListener(null).cancel();popup?.dismiss();releaseBackdrop();anchor.removeOnAttachStateChangeListener(attachment)}
}
