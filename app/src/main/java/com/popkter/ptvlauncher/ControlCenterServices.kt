// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.tv.*
import android.net.*
import android.net.wifi.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.database.ContentObserver
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/** Public SDK operations only; no fabricated network/device/connection state. */
@SuppressLint("MissingPermission")
internal class ControlCenterServices(private val context:Context,private val changed:()->Unit) {
    val wifi=context.applicationContext.getSystemService(WifiManager::class.java)
    val bluetooth=context.getSystemService(BluetoothManager::class.java)?.adapter
    val inputs=context.getSystemService(TvInputManager::class.java)
    val networkBridge=NetworkBridgeClient(context)
    val bluetoothControl=ControlCenterBluetooth(context,networkBridge,changed)
    var wifiConnectionStatus="";private set
    private var requestedSsid:String?=null
    var savedNetworks=emptyList<NetworkBridgeClient.SavedNetwork>();private set
    fun refreshSavedNetworks(){networkBridge.savedNetworks{savedNetworks=it;changed()}}
    fun savedNetworkAction(network:NetworkBridgeClient.SavedNetwork,forget:Boolean,result:(String)->Unit) {
        if(!forget)requestedSsid=network.ssid
        networkBridge.request("","","",operation=if(forget)5 else 4,networkId=network.id){status->
            wifiConnectionStatus=status;refreshSavedNetworks();result(status)
        }
    }
    private val audio=context.getSystemService(AudioManager::class.java)
    private val discovered=linkedMapOf<String,BluetoothDevice>()
    private var ownsDiscovery=false
    private var registered=false
    private val settingsObserver=object:ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange:Boolean){changed()}
    }
    private val receiver=object:BroadcastReceiver() {
        override fun onReceive(c:Context?,intent:Intent?) {
            if(intent?.action in listOf(BluetoothDevice.ACTION_FOUND,BluetoothDevice.ACTION_BOND_STATE_CHANGED,BluetoothDevice.ACTION_ACL_CONNECTED,BluetoothDevice.ACTION_ACL_DISCONNECTED)) {
                @Suppress("DEPRECATION") val device=intent?.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if(device!=null){runCatching {discovered[device.address]=device};bluetoothControl.found(device)}
            }
            if(intent?.action?.startsWith("android.bluetooth")==true)bluetoothControl.refresh()
            if(intent?.action==BluetoothAdapter.ACTION_DISCOVERY_FINISHED)ownsDiscovery=false
            if(requestedSsid!=null&&connectedSsid()==requestedSsid)wifiConnectionStatus="已连接 $requestedSsid"
            changed()
        }
    }
    fun start() {
        if(registered)return
        val filter=IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_FOUND);addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED);addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED);addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction("android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED")
        }
        ContextCompat.registerReceiver(context,receiver,filter,ContextCompat.RECEIVER_EXPORTED);registered=true
        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI,true,settingsObserver)
        refreshSavedNetworks()
        bluetoothControl.start()
    }
    fun stop() {
        bluetoothControl.stop()
        if(registered) {context.unregisterReceiver(receiver);context.contentResolver.unregisterContentObserver(settingsObserver);registered=false}
        if(ownsDiscovery)runCatching{bluetooth?.cancelDiscovery()};ownsDiscovery=false
    }
    private fun granted(permission:String)=ContextCompat.checkSelfPermission(context,permission)==PackageManager.PERMISSION_GRANTED
    fun wifiAllowed()=granted(Manifest.permission.ACCESS_FINE_LOCATION)
    fun bluetoothAllowed()=if(Build.VERSION.SDK_INT>=31)granted(Manifest.permission.BLUETOOTH_SCAN)&&granted(Manifest.permission.BLUETOOTH_CONNECT) else wifiAllowed()
    fun requestPermissions(bluetooth:Boolean) {
        val activity=context as? Activity ?: return
        val permissions=if(bluetooth && Build.VERSION.SDK_INT>=31)arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        activity.requestPermissions(permissions,735)
    }
    fun networkSummary():String {
        val connectivity=context.getSystemService(ConnectivityManager::class.java)
        val caps=connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return "未连接"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)->"有线网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)->"Wi-Fi 已连接"
            else->"网络已连接"
        }
    }
    fun connectedSsid():String? = runCatching {
        val connectivity=context.getSystemService(ConnectivityManager::class.java)
        if(connectivity?.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)!=true)return null
        @Suppress("DEPRECATION") val info=wifi?.connectionInfo
        info?.ssid?.trim('"')?.takeUnless{it==WifiManager.UNKNOWN_SSID||it.isBlank()}
    }.getOrNull()
    fun wifiNetworks():List<ScanResult> = if(!wifiAllowed())emptyList() else runCatching {
        wifi?.scanResults.orEmpty().filter {it.SSID.isNotBlank()}.sortedByDescending {it.level}.distinctBy {it.SSID to it.capabilities}
    }.getOrDefault(emptyList())
    fun scanWifi():String {
        if(wifi==null)return "此设备未提供 Wi-Fi"
        if(!wifiAllowed())return "授权后可查看附近网络"
        if(!wifi.isWifiEnabled)return "Wi-Fi 已关闭，可在更多网络设置中开启"
        @Suppress("DEPRECATION") val accepted=runCatching{wifi.startScan()}.getOrDefault(false)
        return if(accepted)"正在刷新附近网络…" else "系统暂未允许刷新，请检查定位开关或稍后重试"
    }
    fun saveWifi(network:ScanResult,password:String):String {
        val caps=network.capabilities
        if(caps.contains("EAP")||caps.contains("WEP"))return "此网络需要在系统 Wi-Fi 设置中连接"
        if(!networkBridge.installed())return "请先安装 PTV 网络兼容组件"
        if(!networkBridge.authorized())return "请先授权网络兼容组件的位置权限"
        if(caps.contains("SAE")&&!caps.contains("PSK"))return "暂不支持此 WPA3 网络，请使用更多网络设置"
        if(caps.contains("OWE"))return "暂不支持此增强开放网络，请使用更多网络设置"
        requestedSsid=network.SSID
        networkBridge.request(network.SSID,password,if(caps.contains("PSK"))"wpa2" else "open") {
            wifiConnectionStatus=it;changed()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if(requestedSsid==network.SSID&&connectedSsid()!=requestedSsid){wifiConnectionStatus="未能连接 ${network.SSID}，请检查密码或信号后重试";changed()}
        },45000)
        return "正在提交连接请求…"

    }
    fun bluetoothDevices():List<BluetoothDevice> = if(!bluetoothAllowed())emptyList() else runCatching {
        (bluetooth?.bondedDevices.orEmpty()+discovered.values).distinctBy{it.address}.sortedBy{it.name.orEmpty()}
    }.getOrDefault(emptyList())
    fun scanBluetooth():String {
        val adapter=bluetooth?:return "此设备未提供蓝牙"
        if(!bluetoothAllowed())return "授权后可查看附近设备"
        return runCatching {
            if(!adapter.isEnabled) return "蓝牙已关闭"
            if(adapter.isDiscovering)return "正在搜索附近设备…"
            ownsDiscovery=adapter.startDiscovery()
            if(ownsDiscovery)"正在搜索附近设备…" else "暂时无法搜索，请稍后重试"
        }.getOrDefault("无法读取蓝牙状态")
    }
    fun pair(device:BluetoothDevice):String = runCatching {
        if(device.bondState==BluetoothDevice.BOND_BONDED)return "设备已配对。音频连接可在更多蓝牙设置中管理"
        if(ownsDiscovery)bluetooth?.cancelDiscovery()
        if(device.createBond())"配对已请求，请按系统提示确认" else "无法发起配对，请重试"
    }.getOrDefault("无法发起配对，请检查蓝牙权限")
    fun enableBluetooth()=runCatching {
        context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    fun sourceList():List<TvInputInfo> = runCatching{inputs?.tvInputList.orEmpty().filter{!it.isHidden(context)}}.getOrDefault(emptyList())
    fun openSource(input:TvInputInfo):Boolean = runCatching {
        if(input.isPassthroughInput) {
            context.startActivity(Intent(context,SourcePlaybackActivity::class.java).putExtra("input_id",input.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        // Xiaomi's tuners expose explicit playback actions instead of ACTION_VIEW.
        val name=input.loadLabel(context).toString()
        val tunerAction=when {
            name.contains("ATV",ignoreCase=true)->"com.xiaomi.mitv.tvplayer.ATV_PLAY"
            name.contains("DTV",ignoreCase=true)->"com.xiaomi.mitv.tvplayer.DTMB_PLAY"
            else->null
        }
        if(tunerAction!=null) {
            val native=Intent(tunerAction).setPackage("com.xiaomi.mitv.tvplayer").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if(native.resolveActivity(context.packageManager)!=null){context.startActivity(native);return true}
        }
        // Other televisions can register the standard channel VIEW contract.
        val uri=if(input.isPassthroughInput)TvContract.buildChannelUriForPassthroughInput(input.id) else TvContract.buildChannelsUriForInput(input.id)
        context.startActivity(Intent(Intent.ACTION_VIEW,uri).putExtra("android.media.tv.extra.INPUT_ID",input.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));true
    }.getOrDefault(false)
    fun volume():Int {
        val max=audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: return 0
        return if(max>0)((audio.getStreamVolume(AudioManager.STREAM_MUSIC)*100f)/max).roundToInt() else 0
    }
    fun volume(value:Int):Int {
        runCatching {val max=audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: return@runCatching
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,(value*max/100f).roundToInt(),0)}
        return volume()
    }
    fun brightness():Int=(Settings.System.getInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS,128)*100f/255).roundToInt().coerceIn(0,100)
    fun canSetBrightness()=Settings.System.canWrite(context)
    fun brightness(value:Int):Boolean = runCatching {
        if(!canSetBrightness())return false
        Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS_MODE,Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver,Settings.System.SCREEN_BRIGHTNESS,(value*255f/100).roundToInt().coerceIn(1,255))
    }.getOrDefault(false)
    fun brightnessPermission():Boolean = runCatching {
        val intent=Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if(intent.resolveActivity(context.packageManager)==null)return false
        context.startActivity(intent);true
    }.getOrDefault(false)
}
