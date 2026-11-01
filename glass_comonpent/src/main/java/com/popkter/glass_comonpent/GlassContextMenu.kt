package com.popkter.glass_comonpent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.*
import android.widget.LinearLayout
import android.widget.PopupWindow
import kotlin.math.roundToInt

data class GlassMenuItem(val key: String,val title: String,val icon: android.graphics.drawable.Drawable?=null,val enabled: Boolean=true,val destructive: Boolean=false)
enum class GlassMenuLayout { LIST, ICON_ROW }

/** Installs long-click and long-DPAD-start handling. Normal short clicks remain with the anchor. */
class GlassContextMenu private constructor(
    private val anchor: View,
    private val items: ()->List<GlassMenuItem>,
    var glassStyle: GlassStyle,
    private val onAction: (GlassMenuItem)->Unit,
    private val installTriggers: Boolean=true
) : AutoCloseable {
    private var popup: PopupWindow?=null
    private var animation: android.view.ViewPropertyAnimator?=null
    private var pendingHold=false
    private var closed=false
    var returnFocusTo: View=anchor
    var layoutMode=GlassMenuLayout.LIST
    /** Optional visible artwork bounds, in anchor-local coordinates rather than its label area. */
    var anchorBounds: RectF?=null
    var anchorGap=8f
    /** Menus float over unblurred content and supply their own translucent material. */
    var backgroundStyle: GlassStyle?=null
    var restoreFocusOnDismiss=true
    var onDismiss: (()->Unit)?=null
    private var dismissing=false
    private var allowWindowDismiss=false
    private var slideOffset=0f
    private val hold=Runnable { pendingHold=false;show() }
    private val attachListener=object: View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) { cancelHold();popup?.dismiss() }
    }
    val isShowing get()=popup?.isShowing==true
    init {
        if(installTriggers)anchor.setOnLongClickListener { show();true }
        if(installTriggers)anchor.setOnKeyListener { _,code,event ->
            val start=if(anchor.rtl)KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            if(code==start && anchor.isEnabled) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0){pendingHold=true;anchor.postDelayed(hold,ViewConfiguration.getLongPressTimeout().toLong())}
                if(event.action==KeyEvent.ACTION_UP){val wasPending=pendingHold;cancelHold();if(wasPending && !isShowing)anchor.focusSearch(if(anchor.rtl)View.FOCUS_RIGHT else View.FOCUS_LEFT)?.requestFocus()}
                true
            } else false
        }
        anchor.addOnAttachStateChangeListener(attachListener)
    }
    private fun cancelHold(){pendingHold=false;anchor.removeCallbacks(hold)}
    fun show() {
        if(closed || isShowing || !anchor.isAttachedToWindow || !anchor.isEnabled)return
        cancelHold()
        val values=items();if(values.isEmpty())return
        require(values.map { it.key }.distinct().size==values.size)
        val context=anchor.context
        val panel=GlassSurface(context).apply {
            glassStyle=backgroundStyle ?: this@GlassContextMenu.glassStyle.copy(surfaceColor=0xffdce5eb.toInt(),fillOpacity=.50f)
            layoutDirection=anchor.layoutDirection
        }
        val iconRow=layoutMode==GlassMenuLayout.ICON_ROW
        val color=panel.glassStyle.surfaceColor
        val light=Color.red(color)+Color.green(color)+Color.blue(color)>450
        val actionStyle=if(light)glassStyle.copy(
            foreground=0xff304553.toInt(),secondary=0xff536875.toInt(),surfaceColor=0xff506777.toInt(),
            fillOpacity=.18f,focusOverlayColor=0x80ffffff.toInt(),focusColor=Color.WHITE
        )else glassStyle
        val column=LinearLayout(context).apply { orientation=if(iconRow)LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val rows:List<View> =values.mapIndexed { index,item ->
          if(iconRow)GlassButton(context).apply {
            glassStyle=actionStyle;gridSize=GlassSize.X1Y1;iconOnly=true
            title=item.title;icon=item.icon;isEnabled=item.enabled;alpha=if(item.enabled)1f else .35f
            tag="glass:menu:${item.key}";setOnClickListener { dismiss { onAction(item) } }
          }.also { column.addView(it,LinearLayout.LayoutParams(context.dp(glassStyle.unit),context.dp(glassStyle.unit)).apply { if(index>0)marginStart=context.dp(glassStyle.gap) }) }
          else GlassListRow(context).apply {
            glassStyle=actionStyle
            bind(GlassListItem(item.key,item.title,icon=item.icon,enabled=item.enabled))
            if(item.destructive)titleView.setTextColor(if(light)0xffb52720.toInt() else 0xffffccc8.toInt())
            setOnClickListener { dismiss { onAction(item) } }
          }.also { column.addView(it,LinearLayout.LayoutParams(-1,-2)) }
        }
        panel.addView(column,android.widget.FrameLayout.LayoutParams(-1,-2))
        val visible=Rect();anchor.getWindowVisibleDisplayFrame(visible)
        val desiredWidth=context.dp(if(iconRow)values.size*glassStyle.unit+(values.size-1)*glassStyle.gap+panel.glassStyle.contentPadding*2 else 192f).coerceAtMost(visible.width())
        panel.measure(View.MeasureSpec.makeMeasureSpec(desiredWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(visible.height(),View.MeasureSpec.AT_MOST))
        val scroll=android.widget.ScrollView(context).apply { isVerticalScrollBarEnabled=false;layoutDirection=anchor.layoutDirection;addView(panel) }
        val height=panel.measuredHeight.coerceAtMost(visible.height())
        val location=IntArray(2);anchor.getLocationOnScreen(location)
        val artwork=RectF(anchorBounds ?: RectF(0f,0f,anchor.width.toFloat(),anchor.height.toFloat()))
        val origin=floatArrayOf(0f,0f)
        anchor.matrix.mapRect(artwork);anchor.matrix.mapPoints(origin)
        artwork.offset(location[0]-origin[0],location[1]-origin[1])
        val anchorLeft=artwork.left.roundToInt();val anchorRight=artwork.right.roundToInt()
        val gap=context.dp(anchorGap.coerceAtLeast(0f))
        val startX=if(anchor.rtl)anchorRight+gap else anchorLeft-desiredWidth-gap
        val oppositeX=if(anchor.rtl)anchorLeft-desiredWidth-gap else anchorRight+gap
        val x=(if(startX>=visible.left && startX+desiredWidth<=visible.right)startX else oppositeX).coerceIn(visible.left,(visible.right-desiredWidth).coerceAtLeast(visible.left))
        val y=(artwork.centerY()-height/2).roundToInt().coerceIn(visible.top,(visible.bottom-height).coerceAtLeast(visible.top))
        val window=object:PopupWindow(scroll,desiredWidth,height,true) {
            override fun dismiss() {
                if(allowWindowDismiss || closed || !anchor.isAttachedToWindow)super.dismiss()
                else this@GlassContextMenu.dismiss()
            }
        }.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));isOutsideTouchable=true
            animationStyle=0;elevation=context.px(12f)
            setOnDismissListener {
                animation?.cancel();animation=null;popup=null;dismissing=false
                if(!installTriggers){closed=true;anchor.removeOnAttachStateChangeListener(attachListener)}
                if(restoreFocusOnDismiss && returnFocusTo.isAttachedToWindow && returnFocusTo.isEnabled)returnFocusTo.requestFocus()
                onDismiss?.invoke()
            }
        }
        window.setTouchInterceptor { _,event->if(event.action==MotionEvent.ACTION_OUTSIDE){dismiss();true}else false }
        scroll.isFocusableInTouchMode=true
        scroll.setOnKeyListener { _,code,event->
            val toward=if(x<anchorLeft)KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            if(code==KeyEvent.KEYCODE_BACK || code==KeyEvent.KEYCODE_ESCAPE || code==toward){if(event.action==KeyEvent.ACTION_UP)dismiss();true}else false
        }
        // Capture return before focused descendants, so popup dismissal always uses the reverse animation.
        rows.forEachIndexed { index,row->row.setOnKeyListener { _,code,event->
                if(iconRow && code in intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    if(event.action==KeyEvent.ACTION_DOWN){
                        val delta=if((code==KeyEvent.KEYCODE_DPAD_RIGHT)!=anchor.rtl)1 else -1
                        var next=index+delta
                        while(next in rows.indices && !rows[next].isEnabled)next+=delta
                        rows.getOrNull(next)?.requestFocus()
                    };true
                }else if(iconRow && code in intArrayOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN))true
                else if(code==KeyEvent.KEYCODE_BACK || code==KeyEvent.KEYCODE_ESCAPE || (!iconRow && code==towardKey(x,anchorLeft))){if(event.action==KeyEvent.ACTION_UP)dismiss();true}else false
        } }
        popup=window;window.showAtLocation(anchor.rootView,Gravity.TOP or Gravity.LEFT,x,y)
        val offset=context.px(if(x<anchorLeft)10f else -10f)
        slideOffset=offset
        scroll.alpha=0f;scroll.translationX=offset
        animate(scroll,1f,0f,180)
        rows.firstOrNull { it.isEnabled }?.requestFocus()
    }
    private fun towardKey(menuX: Int,anchorX: Int)=if(menuX<anchorX)KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
    private fun animate(view: View,alpha: Float,x: Float,duration: Long,end: (()->Unit)?=null) {
        val actual=if(android.os.Build.VERSION.SDK_INT>=26 && !ValueAnimator.areAnimatorsEnabled())0L else duration
        animation=view.animate().alpha(alpha).translationX(x).setDuration(actual).setInterpolator(android.view.animation.PathInterpolator(.2f,.8f,.2f,1f))
        animation?.setListener(object: AnimatorListenerAdapter(){override fun onAnimationEnd(animation: Animator){end?.invoke()}})?.start()
    }
    fun dismiss(after: (()->Unit)?=null) {
        val window=popup ?: return
        if(dismissing)return
        dismissing=true;animation?.setListener(null);animation?.cancel()
        val content=window.contentView
        animate(content,0f,slideOffset,140){allowWindowDismiss=true;window.dismiss();allowWindowDismiss=false;after?.invoke()}
    }
    override fun close() {
        closed=true;cancelHold();animation?.setListener(null);animation?.cancel();popup?.dismiss()
        anchor.removeOnAttachStateChangeListener(attachListener)
        if(installTriggers){anchor.setOnLongClickListener(null);anchor.setOnKeyListener(null)}
    }
    companion object {
        /** For recycled grids or hosts that already own the long-click/key listeners. */
        fun create(anchor: View,items: List<GlassMenuItem>,style: GlassStyle=GlassStyle(),onAction: (GlassMenuItem)->Unit)=GlassContextMenu(anchor,{items},style,onAction,false)
        fun attach(anchor: View,items: List<GlassMenuItem>,style: GlassStyle=GlassStyle(),onAction: (GlassMenuItem)->Unit)=GlassContextMenu(anchor,{items},style,onAction)
        fun attach(anchor: View,style: GlassStyle=GlassStyle(),items: ()->List<GlassMenuItem>,onAction: (GlassMenuItem)->Unit)=GlassContextMenu(anchor,items,style,onAction)
    }
}
