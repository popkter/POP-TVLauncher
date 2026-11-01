package com.popkter.ptvlauncher

import android.app.Activity
import android.graphics.Color
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout

/** A real TIF session for the selected HDMI/AV port, rather than a generic VIEW intent. */
class SourcePlaybackActivity:Activity() {
    private lateinit var tv:TvView
    @Volatile internal var connectionFailed=false;private set
    @Volatile internal var videoState:Int?=null;private set
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(android.os.Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){finish()}
        val id=intent.getStringExtra("input_id")?:run{finish();return}
        val manager=getSystemService(TvInputManager::class.java)
        val input=manager?.getTvInputInfo(id)
        if(input==null||!input.isPassthroughInput){finish();return}
        val root=FrameLayout(this).apply{setBackgroundColor(Color.BLACK)}
        tv=TvView(this)
        root.addView(tv,FrameLayout.LayoutParams(-1,-1))
        val status=label(this,"正在切换到 ${input.loadLabel(this)}…",18f).apply{gravity=Gravity.CENTER;tag="source:status"}
        root.addView(status,FrameLayout.LayoutParams(-1,-1))
        tv.setCallback(object:TvView.TvInputCallback(){
            override fun onConnectionFailed(inputId:String){connectionFailed=true;status.text="无法连接此信号源，按返回回到控制中心"}
            override fun onDisconnected(inputId:String){status.visibility=android.view.View.VISIBLE;status.text="信号连接已中断，按返回回到控制中心"}
            override fun onVideoAvailable(inputId:String){videoState=-1;status.visibility=android.view.View.GONE}
            override fun onVideoUnavailable(inputId:String,reason:Int){videoState=reason;status.visibility=android.view.View.VISIBLE;status.text="${input.loadLabel(this@SourcePlaybackActivity)} · 等待输入信号"}
        })
        setContentView(root)
        tv.post{tv.tune(id,TvContract.buildChannelUriForPassthroughInput(id))}
    }
    override fun onDestroy(){if(::tv.isInitialized)tv.reset();super.onDestroy()}
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(event.keyCode==KeyEvent.KEYCODE_BACK){if(event.action==KeyEvent.ACTION_UP)finish();return true}
        return if(::tv.isInitialized&&tv.dispatchKeyEvent(event))true else super.dispatchKeyEvent(event)
    }
}
