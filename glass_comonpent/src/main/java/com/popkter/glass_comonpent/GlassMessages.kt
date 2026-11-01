package com.popkter.glass_comonpent

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/** Notices target the current app/overlay host; no extra windows or overlay permission are needed. */
object GlassMessages {
    private val hosts=mutableListOf<WeakReference<ViewGroup>>()
    private val main=Handler(Looper.getMainLooper())
    fun register(host: ViewGroup) { hosts.removeAll { it.get()==null || it.get()===host };hosts.add(WeakReference(host)) }
    fun unregister(host: ViewGroup) { hosts.removeAll { it.get()==null || it.get()===host } }
    fun show(context: Context,message: CharSequence,durationMillis: Long=2500) {
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post { show(context,message,durationMillis) };return}
        var owner:Context?=context
        while(owner is ContextWrapper && owner !is Activity){val next=owner.baseContext;if(next===owner)break;owner=next}
        val host=(owner as? Activity)?.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
            ?: hosts.mapNotNull { it.get() }.lastOrNull { it.isAttachedToWindow }
        if(host==null){android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_SHORT).show();return}
        val notice=host.findViewWithTag<GlassNotice>("glass:notice") ?: GlassNotice(context).apply {
            tag="glass:notice";elevation=context.px(20f)
            host.addView(this,FrameLayout.LayoutParams(context.dp(360f).coerceAtMost(context.resources.displayMetrics.widthPixels-context.dp(32f)),-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=context.dp(24f) })
        }
        notice.bringToFront();notice.show(message,durationMillis)
    }
}
