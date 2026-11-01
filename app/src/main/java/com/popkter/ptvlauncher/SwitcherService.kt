// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityOptions
import android.content.*
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.os.*
import android.util.Log
import android.view.*
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** Key filtering is constant-time. Native UI creation is queued after returning the filter result. */
class SwitcherService:AccessibilityService() {
    companion object { val connected=MutableStateFlow(false) }
    private val handler=Handler(Looper.getMainLooper())
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val graph by lazy {LauncherGraph.get(this)}
    private var overlay:NativeSurface?=null
    private var closingSurface:NativeSurface?=null
    private var foregroundPackage=""
    private var homeRedirect:Runnable?=null
    private val homeReturnGate=HomeReturnGate()
    private var forcedHomePackage:String?=null
    private var captureGeneration=0
    private var homeKeyCaptured=false
    private val menuKey=MenuKeyGate()
    private val manager get()=getSystemService(WINDOW_SERVICE) as WindowManager
    private inner class Capture {
        val gate=BackPressGate()
        var held=false
        var swallowUp=false
        val timeout=Runnable {if(gate.longPress(SystemClock.uptimeMillis()))requestSurface("rail")}
        fun reset() {handler.removeCallbacks(timeout);gate.reset();held=false;swallowUp=false}
    }
    private val captures=mapOf(KeyEvent.KEYCODE_BACK to Capture())
    private val preferenceListener=SharedPreferences.OnSharedPreferenceChangeListener {_,key->
        if(key=="long_back_enabled") {
            if(!ShortcutRecovery.enabled(this))reset()
            if(connected.value)updateForegroundShortcut()
        }
    }
    private val screenOff=object:BroadcastReceiver(){override fun onReceive(context:Context?,intent:Intent?){reset()}}
    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("shortcuts",MODE_PRIVATE).registerOnSharedPreferenceChangeListener(preferenceListener)
        androidx.core.content.ContextCompat.registerReceiver(this,screenOff,IntentFilter(Intent.ACTION_SCREEN_OFF),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {combine(graph.apps.entries,graph.store.state){apps,prefs->apps to prefs}.collect { (apps,prefs)->overlay?.update(apps,prefs) }}
    }
    override fun onServiceConnected() {
        serviceInfo=serviceInfo.apply {flags=flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS}
        forcedHomePackage=DefaultHomeController.systemHome(this)?.packageName?.takeIf{it!=packageName}
        connected.value=true
        updateForegroundShortcut()
        Log.i("PTVSwitcher","Native service connected; key filter ready; HOME selected="+DefaultHomeController.isSelected(this)+" forced="+DefaultHomeController.systemHome(this))
        if(SystemClock.uptimeMillis()<120_000 && DefaultHomeController.isSelected(this) && !DefaultHomeController.systemRoutesToPtv(this))scheduleHomeRedirect(null,5000)
    }
    /** Xiaomi GzBoost freezes bound accessibility processes for 500 ms, but exempts FGS.
     * Keep the user-enabled remote shortcut responsive without changing global freeze policy. */
    private fun updateForegroundShortcut() {
        if(!ShortcutRecovery.enabled(this)) {stopForeground(STOP_FOREGROUND_REMOVE);return}
        try {
            val channel="remote_shortcuts"
            if(Build.VERSION.SDK_INT>=26) {
                getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
                    android.app.NotificationChannel(channel,"遥控器快捷切换",android.app.NotificationManager.IMPORTANCE_LOW)
                        .apply{description="保持长按返回快捷操作及时响应";setShowBadge(false)})
            }
            val intent=Intent(this,MainActivity::class.java).putExtra("open_settings",true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending=android.app.PendingIntent.getActivity(this,41,intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val notification=androidx.core.app.NotificationCompat.Builder(this,channel)
                .setSmallIcon(R.drawable.f7_square_grid_2x2)
                .setContentTitle("遥控器快捷切换运行中")
                .setContentText("长按返回键打开应用列表 · 点击管理")
                .setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_SERVICE).build()
            if(Build.VERSION.SDK_INT>=34)startForeground(41,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(41,notification)
            Log.i("PTVSwitcher","Remote shortcut foreground service active")
        } catch(error:RuntimeException) {
            Log.e("PTVSwitcher","Unable to protect remote shortcut from background freezing",error)
        }
    }
    private fun scheduleHomeRedirect(expected:String?,delay:Long=0) {
        homeRedirect?.let{handler.removeCallbacks(it)}
        homeRedirect=Runnable {
            val forced=forcedHomePackage
            if(DefaultHomeController.isSelected(this) && !DefaultHomeController.systemRoutesToPtv(this) && getSystemService(PowerManager::class.java).isInteractive && !MainActivity.isForeground &&
                (if(expected==null)foregroundPackage.isEmpty() || foregroundPackage==forced else foregroundPackage==expected)) {
                if(homeReturnGate.claim(SystemClock.uptimeMillis())) {Log.i("PTVSwitcher","Redirect forced HOME to PTV without delay");home()}
            }
        }.also{handler.postDelayed(it,delay)}
    }
    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(event?.eventType!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)return
        val pkg=event.packageName?.toString().orEmpty()
        val cls=event.className?.toString().orEmpty()
        Log.d("PTVSwitcher","Window "+pkg+" / "+cls+"; HOME foreground="+MainActivity.isForeground)
        if(pkg.isNotEmpty() && (pkg!=packageName || cls==MainActivity::class.java.name))foregroundPackage=pkg
        if(pkg==packageName && cls==MainActivity::class.java.name) {
            homeReturnGate.reset();hideSurface()
            forcedHomePackage=DefaultHomeController.systemHome(this)?.packageName?.takeIf{it!=packageName}
        } else if(pkg==forcedHomePackage) {
            // Resolve/role checks happen at launch, without querying PackageManager for every window.
            scheduleHomeRedirect(pkg)
        }
    }
    override fun onKeyEvent(event:KeyEvent):Boolean {
        if(event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) {
            // Debug-only metadata: never consume, copy or re-inject navigation events.
            if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                Log.d("PTVKeyTrace","pass key=${event.keyCode} action=${event.action} repeat=${event.repeatCount} down=${event.downTime} event=${event.eventTime} age=${SystemClock.uptimeMillis()-event.eventTime} flags=${event.flags} source=${event.source} device=${event.deviceId}")
            return false
        }
        if(event.keyCode==KeyEvent.KEYCODE_MENU) {
            val onHome=MainActivity.isForeground && MainActivity.hasInputFocus && !MainActivity.isShowingSaver
            val result=if(event.action==KeyEvent.ACTION_DOWN)menuKey.down(event.downTime,event.repeatCount,onHome)
                else menuKey.up(event.downTime)
            if(result==MenuKeyGate.Result.OPEN)handler.post {
                Log.i("PTVSwitcher","Desktop MENU down: request native panel")
                SystemLinks.showNativeControlCenter(this)
            }
            return result!=MenuKeyGate.Result.PASS
        }
        if(event.keyCode==KeyEvent.KEYCODE_BACK && !ShortcutRecovery.enabled(this))return false
        // Claim HOME before any stock launcher window when the firmware delivers it.
        // Some vendor policies consume HOME earlier; window redirection remains a fallback.
        if(event.keyCode==KeyEvent.KEYCODE_HOME) {
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)
                homeKeyCaptured=DefaultHomeController.isSelected(this) && !DefaultHomeController.systemRoutesToPtv(this)
            if(!homeKeyCaptured)return false
            if(event.action==KeyEvent.ACTION_UP) {
                homeKeyCaptured=false
                if(!event.isCanceled)handler.post {Log.i("PTVSwitcher","Captured HOME directly");home()}
            }
            return true
        }
        val c=captures[event.keyCode]?:return false
        Log.d("PTVSwitcher","Key "+event.keyCode+" action="+event.action+" heldMs="+(SystemClock.uptimeMillis()-event.downTime))
        if(c.swallowUp) {if(event.action==KeyEvent.ACTION_UP)c.swallowUp=false;return true}
        if(overlay!=null && !c.held) {
            if(event.action==KeyEvent.ACTION_DOWN) {
                c.swallowUp=true
                handler.post {overlay?.back()}
            }
            return true
        }
        if(MainActivity.isForeground && !c.held)return false
        when(event.action) {
            KeyEvent.ACTION_DOWN->if(!c.held) {
                c.held=true;c.gate.down(SystemClock.uptimeMillis())
                handler.postDelayed(c.timeout,600)
            }
            KeyEvent.ACTION_UP->{
                handler.removeCallbacks(c.timeout)
                if(!event.isCanceled && c.gate.longPress(SystemClock.uptimeMillis()))requestSurface("rail")
                val short=c.gate.up(event.isCanceled);c.held=false
                if(short && event.keyCode==KeyEvent.KEYCODE_BACK)handler.post{performGlobalAction(GLOBAL_ACTION_BACK)}
            }
            else->return false
        }
        return true
    }
    private fun requestSurface(kind:String) {
        // Never attach windows inside onKeyEvent, including its release/threshold fallback.
        handler.post {
            if(!connected.value || !getSystemService(PowerManager::class.java).isInteractive)return@post
            val start=SystemClock.uptimeMillis()
            closingSurface?.let { it.animate().cancel();runCatching {manager.removeView(it)} };closingSurface=null
            val existing=overlay
            if(existing!=null) {existing.show(if(kind=="rail")null else kind);return@post}
            val generation=++captureGeneration
            fun attach(backdrop:Bitmap?) {
                if(generation!=captureGeneration || !connected.value)return
                val view=NativeSurface(this,graph,true,{hideSurface(true)},{updateLibraryBlur()})
            view.wallpaper=backdrop
            view.update(graph.apps.entries.value,graph.store.state.value)
            view.show(if(kind=="rail")null else kind)
            val params=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.TRANSLUCENT).apply {
                gravity=Gravity.TOP or Gravity.START;dimAmount=0f;windowAnimations=0;title="PTV Native "+kind
            }
            try {manager.addView(view,params);overlay=view;updateLibraryBlur();view.post{if(kind=="rail")view.firstRail()};Log.i("PTVSwitcher","Native "+kind+" attached in "+(SystemClock.uptimeMillis()-start)+"ms")}
            catch(error:RuntimeException) {Log.e("PTVSwitcher","Overlay failed",error)}
            }
            if(Build.VERSION.SDK_INT>=30 && !(Build.VERSION.SDK_INT>=31 && manager.isCrossWindowBlurEnabled)) {
                try { takeScreenshot(Display.DEFAULT_DISPLAY,mainExecutor,object:TakeScreenshotCallback {
                    override fun onSuccess(result:ScreenshotResult) {
                        val buffer=result.hardwareBuffer
                        val hardware=Bitmap.wrapHardwareBuffer(buffer,result.colorSpace)
                        val bitmap=hardware?.copy(Bitmap.Config.ARGB_8888,false)
                        hardware?.recycle();buffer.close()
                        if(bitmap==null) {attach(null);return}
                        scope.launch {
                            val blurred=withContext(Dispatchers.Default) {try {BackdropBlur.create(bitmap)} finally {bitmap.recycle()} }
                            attach(blurred)
                        }
                    }
                    override fun onFailure(errorCode:Int) {Log.w("PTVSwitcher","Backdrop capture unavailable: $errorCode");attach(null)}
                }) } catch(error:RuntimeException) {
                    Log.w("PTVSwitcher","Backdrop capture rejected",error);attach(null)
                }
            } else attach(null)
        }
    }
    private fun updateLibraryBlur() {
        if(Build.VERSION.SDK_INT<31)return
        val view=overlay?:return
        val params=view.layoutParams as? WindowManager.LayoutParams?:return
        val library=view.mode in listOf("apps","customize","components")
        // Unsupported TV compositors retain the live, transparent app view. Never add
        // a colored fallback sheet or dim the video to imitate a blur.
        val blur=library && manager.isCrossWindowBlurEnabled
        params.flags=if(blur)params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            else params.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
        params.setBlurBehindRadius(if(blur)dp(24) else 0)
        manager.updateViewLayout(view,params)
    }
    private fun hideSurface(animated:Boolean=false) {
        captureGeneration++
        closingSurface?.let {it.animate().cancel();runCatching {manager.removeView(it)}};closingSurface=null
        val view=overlay
        overlay=null
        if(view!=null) {
            if(animated && view.isAttachedToWindow) {
                closingSurface=view
                view.animateDismiss {
                    runCatching {manager.removeViewImmediate(view)}
                    if(closingSurface===view)closingSurface=null
                }
            } else runCatching {manager.removeViewImmediate(view)}
        }
    }
    private fun home(settings:Boolean=false) {
        hideSurface()
        val intent=Intent(this,MainActivity::class.java).apply {if(!settings)addCategory(Intent.CATEGORY_HOME)}
            .putExtra("open_settings",settings).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try {
            startActivity(intent,ActivityOptions.makeCustomAnimation(this,0,0).toBundle())
        } catch(error:RuntimeException) {
            homeReturnGate.reset()
            Log.e("PTVSwitcher","Unable to return HOME",error)
        }
    }
    private fun reset() {homeKeyCaptured=false;menuKey.reset();handler.removeCallbacksAndMessages(null);homeRedirect=null;homeReturnGate.reset();captures.values.forEach{it.reset()};hideSurface()}
    override fun onInterrupt(){reset()}
    override fun onUnbind(intent:Intent?):Boolean {connected.value=false;stopForeground(STOP_FOREGROUND_REMOVE);reset();return super.onUnbind(intent)}
    override fun onDestroy(){connected.value=false;reset();scope.cancel();getSharedPreferences("shortcuts",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(preferenceListener);unregisterReceiver(screenOff);super.onDestroy()}
}
