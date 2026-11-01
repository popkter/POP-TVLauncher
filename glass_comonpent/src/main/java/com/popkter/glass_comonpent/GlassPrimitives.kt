package com.popkter.glass_comonpent

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*

/** Library-owned typography and platform collection primitives, also usable as subclass bases. */
open class GlassTextView @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):TextView(context,attrs) {
    init { applyTextStyle(GlassStyle());includeFontPadding=false }
}
open class GlassIconView @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):ImageView(context,attrs) {
    init { imageTintList=android.content.res.ColorStateList.valueOf(GlassStyle().foreground);scaleType=ScaleType.FIT_CENTER }
}
open class GlassGridView @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):GridView(context,attrs) {
    init { selector=ColorDrawable(Color.TRANSPARENT);isVerticalScrollBarEnabled=false;overScrollMode=OVER_SCROLL_NEVER }
}
open class GlassScrollView @JvmOverloads constructor(context:Context,attrs:AttributeSet?=null):ScrollView(context,attrs)

/** Component-owned confirmation window; system permission UIs remain owned by Android. */
class GlassDialog(context:Context):Dialog(context) {
    val confirmation=GlassConfirmation(context)
    init {
        setContentView(confirmation)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        confirmation.onCancel={dismiss()};confirmation.onConfirm={dismiss()}
        setOnShowListener {
            window?.setLayout(context.dp(400f).coerceAtMost(context.resources.displayMetrics.widthPixels-context.dp(32f)),-2)
            window?.setGravity(Gravity.CENTER);confirmation.cancelButton.requestFocus()
        }
    }
    companion object {
        fun show(context:Context,title:String,message:String,confirm:String="确认",cancel:String="取消",onConfirm:()->Unit={}):GlassDialog = GlassDialog(context).apply {
            confirmation.titleView.text=title;confirmation.messageView.text=message
            confirmation.confirmButton.title=confirm;confirmation.cancelButton.title=cancel
            confirmation.onConfirm={dismiss();onConfirm()};show()
        }
    }
}
