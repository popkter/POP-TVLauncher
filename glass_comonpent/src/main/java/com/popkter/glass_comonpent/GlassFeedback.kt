package com.popkter.glass_comonpent

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.*

enum class GlassContentState { LOADING, EMPTY, ERROR, CONTENT }

class GlassStateView @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): GlassSurface(context,attrs) {
    private val stack=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER }
    private val spinner=ProgressBar(context)
    val titleView=TextView(context).apply { gravity=Gravity.CENTER }
    val descriptionView=TextView(context).apply { gravity=Gravity.CENTER }
    val retryButton=GlassButton(context).apply { title="重试";contentLayout=GlassContentLayout.ICON_TEXT }
    val content=FrameLayout(context)
    var onRetry: (()->Unit)?=null
    var state=GlassContentState.EMPTY
        set(value) { field=value;updateState() }
    init {
        minimumHeight=context.dp(180f)
        stack.addView(spinner,LinearLayout.LayoutParams(context.dp(28f),context.dp(28f)))
        stack.addView(titleView);stack.addView(descriptionView);stack.addView(retryButton)
        addView(stack,LayoutParams(-1,-2,Gravity.CENTER));addView(content,LayoutParams(-1,-1))
        retryButton.setOnClickListener { onRetry?.invoke() };applyStyle();updateState()
        accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    override fun applyStyle(){super.applyStyle();titleView.applyTextStyle(glassStyle);descriptionView.applyTextStyle(glassStyle,true);retryButton.glassStyle=glassStyle}
    private fun updateState(){
        stack.visibility=if(state==GlassContentState.CONTENT)GONE else VISIBLE
        content.visibility=if(state==GlassContentState.CONTENT)VISIBLE else GONE
        spinner.visibility=if(state==GlassContentState.LOADING)VISIBLE else GONE
        retryButton.visibility=if(state==GlassContentState.ERROR)VISIBLE else GONE
        titleView.text=when(state){GlassContentState.LOADING->"正在加载";GlassContentState.EMPTY->"暂无内容";GlassContentState.ERROR->"暂时无法加载";else->""}
    }
}

class GlassNotice @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): GlassSurface(context,attrs) {
    val messageView=TextView(context)
    var message: CharSequence
        get()=messageView.text
        set(value){messageView.text=value;visibility=VISIBLE}
    private val hide=Runnable { visibility=GONE }
    init { addView(messageView,LayoutParams(-1,-2));applyStyle();accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
    override fun applyStyle(){super.applyStyle();messageView.applyTextStyle(glassStyle)}
    fun show(message: CharSequence,durationMillis: Long=2500){this.message=message;removeCallbacks(hide);if(durationMillis>0)postDelayed(hide,durationMillis)}
    override fun onDetachedFromWindow(){removeCallbacks(hide);super.onDetachedFromWindow()}
}

/** Inline confirmation; dialog/window ownership remains with the host to avoid nested popups. */
class GlassConfirmation @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null): GlassSurface(context,attrs) {
    val titleView=TextView(context)
    val messageView=TextView(context)
    val cancelButton=GlassButton(context).apply { title="取消";contentLayout=GlassContentLayout.ICON_TEXT }
    val confirmButton=GlassButton(context).apply { title="确认";contentLayout=GlassContentLayout.ICON_TEXT }
    var onCancel: (()->Unit)?=null
    var onConfirm: (()->Unit)?=null
    init {
        val stack=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
        stack.addView(titleView);stack.addView(messageView)
        val actions=LinearLayout(context)
        actions.addView(cancelButton,LinearLayout.LayoutParams(0,context.dp(56f),1f).apply { marginEnd=context.dp(8f) })
        actions.addView(confirmButton,LinearLayout.LayoutParams(0,context.dp(56f),1f))
        stack.addView(actions);addView(stack,LayoutParams(-1,-2));applyStyle()
        cancelButton.setOnClickListener { onCancel?.invoke() };confirmButton.setOnClickListener { onConfirm?.invoke() }
    }
    override fun applyStyle(){super.applyStyle();titleView.applyTextStyle(glassStyle);messageView.applyTextStyle(glassStyle,true);cancelButton.glassStyle=glassStyle;confirmButton.glassStyle=glassStyle}
}
