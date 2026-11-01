package com.popkter.ptvlauncher.networkbridge

import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.wifi.WifiManager
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Narrow, authenticated shell-side adapter. No arbitrary command, path, or Binder is accepted. */
@android.annotation.SuppressLint("MissingPermission") // This entry point enforces uid 2000 before any operation.
object LocalControlDaemon {
    const val SOCKET="ptv.local.control.v1"
    private const val APP="com.popkter.ptvlauncher"
    private const val BRIDGE="$APP.networkbridge"
    private lateinit var context:Context
    private val profiles=ConcurrentHashMap<Int,BluetoothProfile>()
    private val ids=listOf(1,2,4,11,16,21,22)
    private val workers=Executors.newFixedThreadPool(2)
    @Volatile private var registered=false
    private val adapter get()=context.getSystemService(BluetoothManager::class.java)?.adapter
    private val wifi:WifiManager by lazy {createWifiManager()}
    @android.annotation.SuppressLint("SoonBlockedPrivateApi") // Shell-only app_process entry point; verified on Android 14.
    private fun createWifiManager():WifiManager =
        context.getSystemService(WifiManager::class.java).also {
            // WifiManager normally substitutes the application context, which belongs to
            // ActivityThread.systemMain (android), rather than this shell package context.
            WifiManager::class.java.getDeclaredField("mContext").apply{isAccessible=true}.set(it,context)
        }
    @JvmStatic fun main(args:Array<String>) {
        check(Process.myUid()==Process.SHELL_UID){"Start from the PTV installer"}
        if(args.firstOrNull()=="--check") {println(if(LocalControlClient.request(JSONObject().put("action","ping")).optBoolean("ready"))"ready" else "inactive");return}
        Looper.prepareMainLooper()
        val thread=Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null)
        val system=thread.javaClass.getMethod("getSystemContext").invoke(thread) as Context
        context=system.createPackageContext("com.android.shell",Context.CONTEXT_IGNORE_SECURITY)
        // ContextImpl inherits the system process's op package when created by systemMain.
        // Attribute Wi-Fi operations to the actual shell UID/package instead.
        context.javaClass.getDeclaredField("mOpPackageName").apply{isAccessible=true}.set(context,"com.android.shell")
        context.javaClass.getDeclaredField("mBasePackageName").apply{isAccessible=true}.set(context,"com.android.shell")
        if(Build.VERSION.SDK_INT>=31)context.javaClass.getDeclaredField("mAttributionSource").apply{isAccessible=true}.set(context,
            android.content.AttributionSource.Builder(Process.myUid()).setPackageName("com.android.shell").build())
        val backend=Messenger(object:Handler(Looper.getMainLooper()) {
            override fun handleMessage(message:Message) {
                val uid=message.sendingUid
                val valid=runCatching{uid==context.packageManager.getApplicationInfo(BRIDGE,0).uid && context.packageManager.checkSignatures(APP,BRIDGE)==PackageManager.SIGNATURE_MATCH}.getOrDefault(false)
                if(!valid)return
                if(message.what==101){registered=true;return}
                if(message.what!=200)return
                val reply=message.replyTo?:return
                val text=message.data.getString("request").orEmpty()
                if(text.length>2048)return
                workers.execute {
                    val response=try{handle(JSONObject(text))}catch(error:Exception){android.util.Log.e("PtvLocalControl","Operation failed",error);failure("系统未接受操作，请重试或查看系统设置")}
                    runCatching{reply.send(Message.obtain(null,1).apply{data=Bundle().apply{putString("result",response.toString())}})}
                }
            }
        })
        bindRegistry(backend)
        ids.forEach { id->runCatching {adapter?.getProfileProxy(context,object:BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile:Int,proxy:BluetoothProfile){profiles[profile]=proxy}
            override fun onServiceDisconnected(profile:Int){profiles.remove(profile)}
        },id)} }
        val server=LocalServerSocket(SOCKET)
        Thread({while(true){val socket=server.accept();workers.execute {serve(socket)}}},"ptv-control-listener").start()
        Looper.loop()
    }
    /** Same external-provider registration route as the Android content shell command. */
    private fun bindRegistry(backend:Messenger) {
        val authority="$BRIDGE.localcontrol"
        val manager=Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val method=manager.javaClass.methods.first{it.name=="getContentProviderExternal"}
        var strings=0
        val token=Binder()
        val args=method.parameterTypes.map{type->when {
            type==String::class.java->if(strings++==0)authority else "ptv-control"
            type==java.lang.Integer.TYPE->0
            type==IBinder::class.java->token
            else->null
        }}.toTypedArray()
        val holder=requireNotNull(method.invoke(manager,*args))
        val provider=holder.javaClass.getField("provider").get(holder)
        val providerBinder=provider.javaClass.getMethod("asBinder").invoke(provider) as IBinder
        providerBinder.linkToDeath({
            registered=false
            val main=Handler(Looper.getMainLooper())
            val retry=object:Runnable {override fun run(){if(runCatching{bindRegistry(backend)}.isFailure)main.postDelayed(this,3000)}}
            main.postDelayed(retry,1000)
        },0)
        // Prefer the authority-aware overload; deprecated overloads substitute "unknown".
        val call=provider.javaClass.methods.filter{it.name=="call"}.maxBy{it.parameterCount}
        val types=call.parameterTypes
        val stringSlots=types.indices.filter{types[it]==String::class.java}
        val modern=types.any{it.name=="android.content.AttributionSource"}
        val values=types.mapIndexed{index,type->when {
            type==Bundle::class.java->Bundle().apply{putBinder("backend",backend.binder)}
            type.name=="android.content.AttributionSource"&&Build.VERSION.SDK_INT>=31->context.attributionSource
            type==String::class.java->when(index){stringSlots[stringSlots.size-2]->"register";stringSlots.last()->null;else->if(modern || stringSlots.size>=4&&index==stringSlots[1])authority else "com.android.shell"}
            else->null
        }}.toTypedArray()
        check((call.invoke(provider,*values) as Bundle).getBoolean("ok"))
    }
    private fun serve(socket:LocalSocket) {
        socket.use {
            val uid=socket.peerCredentials.uid
            val pm=context.packageManager
            val allowed=runCatching {
                val appUid=pm.getApplicationInfo(APP,0).uid
                val bridgeUid=pm.getApplicationInfo(BRIDGE,0).uid
                uid==Process.SHELL_UID || ((uid==appUid || uid==bridgeUid) && pm.checkSignatures(APP,BRIDGE)==PackageManager.SIGNATURE_MATCH)
            }.getOrDefault(false)
            if(!allowed)return
            socket.soTimeout=10000
            val reader=socket.inputStream.bufferedReader()
            val line=StringBuilder()
            while(line.length<=2048){val c=reader.read();if(c<0 || c==10)break;line.append(c.toChar())}
            val result=if(line.length>2048)failure("请求过长")else try {handle(JSONObject(line.toString()))}catch(_:Exception){failure("系统未接受操作，请重试或查看系统设置")}
            socket.outputStream.bufferedWriter().apply {write(result.toString());newLine();flush()}
        }
    }
    @Suppress("DEPRECATION")
    private fun handle(request:JSONObject):JSONObject = when(request.optString("action")) {
        "ping"->JSONObject().put("ok",true).put("ready",registered).put("version",1)
        "probe_wifi"->{
            val ssid="PTV-system-probe-${java.util.UUID.randomUUID().toString().take(8)}"
            val config=android.net.wifi.WifiConfiguration().apply {SSID=JSONObject.quote(ssid);allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)}
            val id=wifi.addNetwork(config)
            if(id<0)failure("无法创建诊断配置")else {
                Handler(Looper.getMainLooper()).postDelayed({runCatching{if(wifi.configuredNetworks.orEmpty().any{it.networkId==id&&it.SSID==config.SSID})wifi.removeNetwork(id)}},60000)
                JSONObject().put("ok",true).put("id",id).put("ssid",ssid).put("creatorUid",Process.myUid())
            }
        }
        "forget_wifi"->{
            val id=request.getInt("id");require(id>=0)
            val config=wifi.configuredNetworks.orEmpty().firstOrNull{it.networkId==id}
            if(config==null)success("已忘记网络")
            else if(config.SSID.orEmpty().removeSurrounding("\"")!=request.getString("ssid"))failure("网络配置已变化，请刷新后重试")
            else {
                var removed=wifi.removeNetwork(id)
                if(!removed) {
                    val process=ProcessBuilder("cmd","wifi","forget-network",id.toString()).redirectErrorStream(true).start()
                    val completed=if(Build.VERSION.SDK_INT>=26)process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS) else {process.waitFor();true}
                    if(!completed)process.destroy()
                    removed=completed && process.exitValue()==0
                }
                if(removed && wifi.configuredNetworks.orEmpty().none{it.networkId==id})success("已忘记网络")else failure("网络仍被系统保留，请检查设备管理策略")
            }
        }
        "bluetooth_state"->bluetoothState()
        "bluetooth_connect","bluetooth_disconnect","bluetooth_forget","bluetooth_cancel","bluetooth_pair"->{
            val address=request.getString("address");require(BluetoothAdapter.checkBluetoothAddress(address))
            val device=requireNotNull(adapter?.getRemoteDevice(address))
            val action=request.getString("action")
            if(adapter?.isDiscovering==true)adapter?.cancelDiscovery()
            val method=when(action){"bluetooth_connect"->"connect";"bluetooth_disconnect"->"disconnect";"bluetooth_forget"->"removeBond";"bluetooth_cancel"->"cancelBondProcess";else->"createBond"}
            val alreadyDone=when(action){
                "bluetooth_connect"->runCatching{device.javaClass.getMethod("isConnected").invoke(device)==true}.getOrDefault(false)
                "bluetooth_disconnect"->runCatching{device.javaClass.getMethod("isConnected").invoke(device)==false}.getOrDefault(false)
                "bluetooth_forget"->device.bondState==BluetoothDevice.BOND_NONE
                else->false
            }
            val value=if(alreadyDone)Result.success(true)else runCatching{device.javaClass.getMethod(method).invoke(device)}
            var accepted=value.getOrNull().let{it==true || it is Int && it==0}
            if(!accepted && action in listOf("bluetooth_connect","bluetooth_disconnect")) {
                val perProfile=if(action=="bluetooth_connect")"connect" else "disconnect"
                profiles.values.forEach { profile->if(runCatching{profile.javaClass.getMethod(perProfile,BluetoothDevice::class.java).invoke(profile,device)==true}.getOrDefault(false))accepted=true }
            }
            if(accepted)success(when(action){"bluetooth_connect"->"正在连接…";"bluetooth_disconnect"->"正在断开…";"bluetooth_forget"->"正在忽略设备…";"bluetooth_cancel"->"正在取消配对…";else->"正在配对，请确认设备上的提示…"})
            else failure("系统未接受此操作，请确认设备已开启并在附近后重试")
        }
        "bluetooth_enable","bluetooth_disable"->{
            val accepted=if(request.getString("action")=="bluetooth_enable")adapter?.enable()==true else adapter?.disable()==true
            if(accepted)success("正在切换蓝牙状态…")else failure("系统未接受蓝牙开关操作")
        }
        else->failure("不支持的操作")
    }
    private fun bluetoothState():JSONObject {
        val adapter=adapter?:return JSONObject().put("ok",true).put("available",false).put("devices",JSONArray())
        val devices=JSONArray()
        adapter.bondedDevices.orEmpty().sortedBy{it.name.orEmpty()}.forEach { device->
            val states=profiles.values.mapNotNull{runCatching{it.getConnectionState(device)}.getOrNull()}
            val acl=runCatching{device.javaClass.getMethod("isConnected").invoke(device)==true}.getOrDefault(false)
            val state=when {BluetoothProfile.STATE_CONNECTED in states || acl->BluetoothProfile.STATE_CONNECTED;BluetoothProfile.STATE_CONNECTING in states->BluetoothProfile.STATE_CONNECTING;BluetoothProfile.STATE_DISCONNECTING in states->BluetoothProfile.STATE_DISCONNECTING;else->BluetoothProfile.STATE_DISCONNECTED}
            val battery=runCatching{device.javaClass.getMethod("getBatteryLevel").invoke(device) as Int}.getOrDefault(-1)
            devices.put(JSONObject().put("address",device.address).put("name",device.name.orEmpty()).put("bond",device.bondState).put("state",state).put("battery",battery).put("deviceClass",device.bluetoothClass?.deviceClass?:0))
        }
        return JSONObject().put("ok",true).put("available",true).put("enabled",adapter.isEnabled).put("state",adapter.state).put("devices",devices)
    }
    private fun success(message:String)=JSONObject().put("ok",true).put("message",message)
    private fun failure(message:String)=JSONObject().put("ok",false).put("message",message)
}

internal object LocalControlClient {
    fun request(request:JSONObject):JSONObject {
      if(Process.myUid()!=Process.SHELL_UID)return LocalControlRegistry.request(request)
      return try {
        LocalSocket().use {socket->
            socket.connect(LocalSocketAddress(LocalControlDaemon.SOCKET,LocalSocketAddress.Namespace.ABSTRACT))
            check(socket.peerCredentials.uid==Process.SHELL_UID)
            socket.soTimeout=7000
            socket.outputStream.bufferedWriter().apply{write(request.toString());newLine();flush()}
            JSONObject(socket.inputStream.bufferedReader().readLine())
        }
      }catch(_:Exception){JSONObject().put("ok",false).put("message","本地管理服务未激活，请通过 PTV 安装工具重新激活（电视重启后需要）")}
    }
}
