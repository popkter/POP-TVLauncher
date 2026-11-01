package com.popkter.ptvlauncher

import android.Manifest
import android.app.Application
import com.popkter.glass_comonpent.GlassDialog
import android.content.*
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/** Preserve every other service when repairing our own firmware-cleared registration. */
internal object ShortcutServiceList {
    fun without(raw:String?,own:String):List<String> = raw.orEmpty().split(':')
        .filter {it.isNotBlank() && it!="null" && it!=own && it!=own.replace("/.","/${own.substringBefore('/') }.")}.distinct()
    fun enabled(raw:String?,own:String):String=(without(raw,own)+own).joinToString(":")
}

internal object ShortcutRecovery {
    private val handler=Handler(Looper.getMainLooper())
    private var rebindPending=false
    fun initialize(context:Context) {
        val prefs=context.getSharedPreferences("shortcuts",Context.MODE_PRIVATE)
        if(!prefs.contains("long_back_enabled"))prefs.edit().putBoolean("long_back_enabled",SystemLinks.accessibilityEnabled(context)).apply()
    }
    // Only a caller able to write secure settings (the computer installer) can request setup.
    // No exported component accepts a boolean extra that another app could forge.
    fun consumeInstallerRequest(context:Context) {
        if(context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)!=PackageManager.PERMISSION_GRANTED)return
        val resolver=context.contentResolver
        val request=Settings.Secure.getString(resolver,"ptv_installer_shortcuts") ?: return
        if(request!="enable" && request!="disable")return
        Settings.Secure.putString(resolver,"ptv_installer_shortcuts",null)
        setEnabled(context,request=="enable")
    }
    fun enabled(context:Context)=context.getSharedPreferences("shortcuts",Context.MODE_PRIVATE).getBoolean("long_back_enabled",false)
    fun setEnabled(context:Context,value:Boolean) {
        context.getSharedPreferences("shortcuts",Context.MODE_PRIVATE).edit().putBoolean("long_back_enabled",value).apply()
        if(value)schedule(context) else handler.removeCallbacks(check)
    }
    fun status(context:Context)=when {
        !enabled(context)->"已关闭"
        !SystemLinks.accessibilityEnabled(context)->"等待授权 · 尚未生效"
        !SwitcherService.connected.value->"已授权 · 等待服务连接"
        else->"已开启 · 在任意应用内长按返回键"
    }
    fun requestPermission(context:Context) {
        if(SystemLinks.accessibilityEnabled(context)) {schedule(context);return}
        GlassDialog.show(context,"开启长按返回应用列表",
            "需要开启「PTV 快速切换」无障碍服务，以识别其他应用中的长按返回键。请在系统授权页面手动允许；未授权前此功能不会生效。",
            confirm="去授权",cancel="暂不授权") {
                try {context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}
                catch(_:RuntimeException) {GlassDialog.show(context,"系统未提供授权页面",
                    "此电视需要通过电脑辅助授权，请参阅 PTV 安装说明。应用不能自行取得无障碍权限。",confirm="知道了")}
            }
    }
    private fun allowed(context:Context)=enabled(context) &&
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)==PackageManager.PERMISSION_GRANTED
    fun schedule(context:Context) {
        val app=context.applicationContext
        if(!allowed(app))return
        handler.removeCallbacks(check)
        activeContext=app
        ensure(app,false)
        handler.postDelayed(check,3000)
    }
    private var activeContext:Context?=null
    private val check=Runnable {activeContext?.let {ensure(it,true)}}
    private fun ensure(context:Context,rebind:Boolean) {
        if(!allowed(context) || SwitcherService.connected.value || rebindPending ||
            !context.getSystemService(PowerManager::class.java).isInteractive)return
        val resolver=context.contentResolver
        val own=ComponentName(context,SwitcherService::class.java).flattenToShortString()
        try {
            if(SystemLinks.accessibilityEnabled(context) && rebind) {
                rebindPending=true
                Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    ShortcutServiceList.without(Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),own).joinToString(":"))
                handler.postDelayed({
                    rebindPending=false
                    if(allowed(context))writeEnabled(context,own)
                },350)
            } else if(!SystemLinks.accessibilityEnabled(context) ||
                Settings.Secure.getInt(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,0)==0)writeEnabled(context,own)
        } catch(error:SecurityException) {rebindPending=false;Log.w("PTVRecovery","Recovery permission unavailable",error)}
    }
    private fun writeEnabled(context:Context,own:String) {
        try {
            val resolver=context.contentResolver
            val current=Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,ShortcutServiceList.enabled(current,own))
            Settings.Secure.putInt(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,1)
            Log.i("PTVRecovery","Restored shortcut service registration")
        } catch(error:SecurityException) {Log.w("PTVRecovery","Recovery permission unavailable",error)}
    }
}

class LauncherApplication:Application() {
    override fun onCreate() {
        super.onCreate()
        ShortcutRecovery.initialize(this)
        androidx.core.content.ContextCompat.registerReceiver(this,object:BroadcastReceiver() {
            override fun onReceive(context:Context,intent:Intent) {ShortcutRecovery.schedule(context)}
        },IntentFilter().apply {addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_USER_PRESENT)},
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        ShortcutRecovery.schedule(this)
    }
}

class ShortcutBootReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {ShortcutRecovery.schedule(context)}
}
