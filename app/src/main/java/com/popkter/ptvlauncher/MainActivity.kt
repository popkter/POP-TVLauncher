// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.os.*
import android.view.*
import android.widget.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    companion object {
        @Volatile var isForeground=false;private set
        @Volatile var hasInputFocus=false;private set
        @Volatile var isShowingSaver=false;private set
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val handler=Handler(Looper.getMainLooper())
    private val graph by lazy { LauncherGraph.get(this) }
    private lateinit var root:FrameLayout
    private lateinit var landscape:WallpaperView
    internal lateinit var surface:NativeSurface;private set
    private lateinit var clock:TextView
    private lateinit var caption:TextView
    private lateinit var chrome:FrameLayout
    private lateinit var saverLabel:TextView
    private var preferences=HomePreferences()
    private var collector:Job?=null
    private var wallpaperJob:Job?=null
    private var wallpapers=WallpaperResult(emptyList(),false)
    private var wallpaperIndex=0
    private var lastInput=0L
    private var saver=false
    private var wakeKey:Int?=null
    private var loadedPath:String?=null
    private val menuKey=MenuKeyGate()
    private val tick=object:Runnable {
        override fun run() { updateClock();if(isForeground)handler.postDelayed(this,30_000) }
    }
    private val idle=Runnable {
        if(IdlePolicy.shouldShow(isForeground,surface.mode!=null,SystemClock.uptimeMillis(),lastInput,preferences.idleMinutes))setSaver(true)
    }
    private val rotate=object:Runnable {
        override fun run() {
            if(!isForeground)return
            if((if(preferences.customWallpaper)preferences.customRotate else preferences.rotate) && wallpapers.entries.size>1) {wallpaperIndex=(wallpaperIndex+1)%wallpapers.entries.size;loadPicture()}
            handler.postDelayed(this,30_000)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        ShortcutRecovery.consumeInstallerRequest(this)
        if(Build.VERSION.SDK_INT<30) window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        window.decorView // Inflate the native decor before querying its insets controller.
        if(Build.VERSION.SDK_INT>=30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        if(Build.VERSION.SDK_INT>=33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if(saver) {lastInput=SystemClock.uptimeMillis();setSaver(false)} else surface.back()
            }
        root=FrameLayout(this);landscape=WallpaperView(this);root.addView(landscape,FrameLayout.LayoutParams(-1,-1))
        chrome=FrameLayout(this);root.addView(chrome,FrameLayout.LayoutParams(-1,-1))
        chrome.addView(label(this,"PTV",18f),FrameLayout.LayoutParams(dp(90),dp(34),Gravity.TOP or Gravity.START).apply {setMargins(dp(30),dp(25),0,0)})
        // Balanced clock typography in the wallpaper's upper-right area.
        // A native TextView keeps minute changes local and needs no bitmap/GPU effects.
        clock=AlignedClockView(this).apply {
            tag="home:clock";textSize=76f;setTextColor(WHITE)
            fontFeatureSettings="tnum";includeFontPadding=false
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            letterSpacing=-.015f
            setShadowLayer(dp(2).toFloat(),0f,dp(1).toFloat(),0x40000000)
        }
        chrome.addView(clock,FrameLayout.LayoutParams(dp(300),dp(100),Gravity.TOP or Gravity.END).apply {
            setMargins(0,dp(AppLayout.RAIL_TOP+AppLayout.RAIL_SCROLL_INSET+AppLayout.CARD_INSET),dp(AppLayout.HOME_RIGHT_MARGIN),0)
        })
        val settings=SettingsButton(this).apply {
            setOnClickListener { surface.show("settings") }
        }
        chrome.addView(settings,FrameLayout.LayoutParams(dp(36),dp(36),Gravity.TOP or Gravity.END).apply {setMargins(0,dp(20),dp(AppLayout.HOME_RIGHT_MARGIN),0)})
        caption=label(this,"",10f,true).apply {gravity=Gravity.END;setLineSpacing(dp(5).toFloat(),1f)}
        chrome.addView(caption,FrameLayout.LayoutParams(dp(570),dp(80),Gravity.BOTTOM or Gravity.END).apply {setMargins(0,0,dp(30),dp(22))})
        surface=NativeSurface(this,graph,false,{surface.show(null)}) {
            chrome.visibility=if(surface.mode==null && !saver)View.VISIBLE else View.GONE
            armIdle()
        }
        root.addView(surface,FrameLayout.LayoutParams(-1,-1))
        // Header remains reachable by remote; surface only occupies its actual children.
        chrome.bringToFront()
        saverLabel=label(this,"",26f).apply {tag="画报视图";setPadding(dp(35),0,dp(35),dp(35));gravity=Gravity.BOTTOM;visibility=View.GONE}
        root.addView(saverLabel,FrameLayout.LayoutParams(-1,-1))
        setContentView(root)
        if(intent.getBooleanExtra("open_settings",false))surface.show("settings")
    }
    override fun onStart() {
        super.onStart()
        collector=scope.launch {
            launch { combine(graph.apps.entries,graph.store.state){apps,prefs->apps to prefs}.collect { (apps,prefs)->
                val changed=preferences.customWallpaper!=prefs.customWallpaper || preferences.customRotate!=prefs.customRotate || preferences.fixedWallpaper!=prefs.fixedWallpaper
                preferences=prefs;surface.update(apps,prefs);armIdle()
                if(changed && isForeground)refreshWallpaperSource()
            } }
            launch { SwitcherService.connected.collect { if(isForeground)surface.refreshStatus() } }
        }
    }
    override fun onResume() {
        super.onResume();isForeground=true
        ShortcutRecovery.schedule(this)
        lastInput=SystemClock.uptimeMillis();setSaver(false);surface.refreshStatus()
        handler.post(tick);handler.postDelayed(rotate,30_000);armIdle()
        refreshWallpaperSource()

    }
    private var sourceJob:Job?=null
    private fun refreshWallpaperSource() {
        sourceJob?.cancel()
        sourceJob=scope.launch {
            if(preferences.customWallpaper) {
                val custom=withContext(Dispatchers.IO){graph.customWallpapers.entries()}
                wallpapers=WallpaperResult(custom,false)
                wallpaperIndex=if(preferences.customRotate)0 else custom.indexOfFirst{it.file.name==preferences.fixedWallpaper}.coerceAtLeast(0)
                if(isForeground)loadPicture()
            } else {
                val cached=graph.wallpaper.loadCached()
                wallpapers=cached;wallpaperIndex=0;if(isForeground)loadPicture()
                wallpapers=graph.wallpaper.load();wallpaperIndex=0;if(isForeground)loadPicture()
            }
        }
    }
    override fun onPause() {
        isForeground=false;hasInputFocus=false;handler.removeCallbacks(tick);handler.removeCallbacks(rotate);handler.removeCallbacks(idle)
        sourceJob?.cancel();wallpaperJob?.cancel();wakeKey=null;menuKey.reset();super.onPause()
    }
    override fun onStop() {collector?.cancel();super.onStop()}
    override fun onDestroy() {handler.removeCallbacksAndMessages(null);scope.cancel();super.onDestroy()}
    override fun onWindowFocusChanged(hasFocus:Boolean) {
        super.onWindowFocusChanged(hasFocus)
        hasInputFocus=hasFocus
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent)
        ShortcutRecovery.consumeInstallerRequest(this)
        surface.show(if(intent.getBooleanExtra("open_settings",false))"settings" else null)
    }
    // TV physical BACK must consume the matching release too; gesture back is registered above.
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(event.action==KeyEvent.ACTION_DOWN) {
            lastInput=SystemClock.uptimeMillis();armIdle()
            if(saver) {setSaver(false);wakeKey=event.keyCode}
        }
        if(wakeKey==event.keyCode) {if(event.action==KeyEvent.ACTION_UP)wakeKey=null;return true}
        if(event.keyCode==KeyEvent.KEYCODE_MENU) {
            val result=if(event.action==KeyEvent.ACTION_DOWN)menuKey.down(event.downTime,event.repeatCount,true)
                else menuKey.up(event.downTime)
            if(result==MenuKeyGate.Result.OPEN && !SystemLinks.showNativeControlCenter(this))
                Toast.makeText(this,"系统未提供可用的原生控制中心入口",Toast.LENGTH_SHORT).show()
            return true
        }
        if(event.keyCode==KeyEvent.KEYCODE_BACK) {if(event.action==KeyEvent.ACTION_UP && !event.isCanceled)surface.back();return true}
        return super.dispatchKeyEvent(event)
    }
    private fun armIdle() {
        handler.removeCallbacks(idle)
        if(isForeground && !saver && surface.mode==null && preferences.idleMinutes>0)
            handler.postDelayed(idle,(preferences.idleMinutes*60_000L-(SystemClock.uptimeMillis()-lastInput)).coerceAtLeast(0))
    }
    private fun setSaver(value:Boolean) {
        saver=value;isShowingSaver=value;saverLabel.visibility=if(value)View.VISIBLE else View.GONE
        surface.visibility=if(value)View.GONE else View.VISIBLE
        chrome.visibility=if(!value && surface.mode==null)View.VISIBLE else View.GONE
        updateClock();if(!value)armIdle()
    }
    private fun updateClock() {
        val time=SimpleDateFormat("HH:mm",Locale.CHINA).format(Date())
        clock.text=time
        val text=wallpapers.entries.getOrNull(wallpaperIndex)?.caption?:"山川之间 · PTV 原创风景"
        caption.text=SimpleDateFormat("M月d日 EEEE",Locale.CHINA).format(Date())+"\n"+text+"\n右上角打开桌面设置 · 长按确定管理应用"
        saverLabel.text=time+"\n"+text+"\n按任意键返回桌面"
    }
    private fun loadPicture() {
        val entry=wallpapers.entries.getOrNull(wallpaperIndex)
        if(entry==null) {wallpaperJob?.cancel();landscape.bitmap=null;loadedPath=null;surface.wallpaper=null;graph.wallpaperBackdrop=null;updateClock();return}
        if(loadedPath==entry.file.path) {updateClock();return}
        wallpaperJob?.cancel()
        wallpaperJob=scope.launch {
            val picture=withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(entry.file.path,BitmapFactory.Options().apply {inPreferredConfig=Bitmap.Config.RGB_565})
            }
            if(isForeground) {landscape.bitmap=picture;loadedPath=entry.file.path;updateClock()}
            val blurred=withContext(Dispatchers.Default) {picture?.let {BackdropBlur.create(it)}}
            if(isForeground) {surface.wallpaper=blurred;graph.wallpaperBackdrop=blurred}
        }
    }
}

/** Align visible digits, rather than the font's ascender and trailing side bearing. */
private class AlignedClockView(context:Context):TextView(context) {
    private val ink=Rect()
    override fun onDraw(canvas:Canvas) {
        val value=text.toString()
        if(value.isEmpty())return
        paint.color=currentTextColor
        paint.getTextBounds(value,0,value.length,ink)
        canvas.drawText(value,(width-paddingRight-ink.right).toFloat(),(paddingTop-ink.top).toFloat(),paint)
    }
}
