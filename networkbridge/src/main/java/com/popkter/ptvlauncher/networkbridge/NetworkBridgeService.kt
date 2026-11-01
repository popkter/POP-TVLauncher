package com.popkter.ptvlauncher.networkbridge

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.*
import android.os.*

/** Signature-gated local IPC. Passwords are never logged or stored by this process. */
@Suppress("DEPRECATION")
class NetworkBridgeService:Service() {
    private val worker=java.util.concurrent.Executors.newFixedThreadPool(2)
    private fun sendReply(target:Messenger,result:String){try{target.send(Message.obtain(null,1).apply{data=Bundle().apply{putString("result",result)}})}catch(_:RemoteException){}}
    private val messenger by lazy {Messenger(object:Handler(Looper.getMainLooper()) {
        override fun handleMessage(message:Message) {
            if(packageManager.checkSignatures(applicationInfo.uid,message.sendingUid)!=PackageManager.SIGNATURE_MATCH)return
            val reply=message.replyTo?:return
            if(message.what in 8..18) {
                val operation=message.what
                val action=when(operation){8->"ping";9->"probe_wifi";10->"bluetooth_state";11->"bluetooth_connect";12->"bluetooth_disconnect";13->"bluetooth_forget";14->"bluetooth_cancel";15->"bluetooth_pair";16->"bluetooth_enable";17->"bluetooth_disable";else->"unsupported"}
                val request=org.json.JSONObject().put("action",action).put("address",message.data.getString("address", ""))
                worker.execute{val response=LocalControlClient.request(request);sendReply(reply,if(operation in listOf(8,9,10,18))response.toString() else response.optString("message","操作未返回结果"))}
                return
            }
            var result="无法发起连接"
            try {
                if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)result="请先授权 PTV 网络连接的位置权限"
                else {
                    val wifi=applicationContext.getSystemService(WifiManager::class.java)
                    if(message.what==3) {
                        val list=org.json.JSONArray()
                        wifi.configuredNetworks.orEmpty().forEach{network->list.put(org.json.JSONObject().put("id",network.networkId).put("ssid",network.SSID.orEmpty().removeSurrounding("\"")))}
                        result=list.toString()
                    } else if(message.what==4||message.what==5) {
                        val id=message.data.getInt("networkId",-1)
                        require(id>=0)
                        val config=wifi.configuredNetworks.orEmpty().firstOrNull{it.networkId==id}
                        if(config==null){sendReply(reply,if(message.what==5)"已忘记网络" else "网络配置已变化，请刷新后重试");return}
                        result=if(message.what==4){if(wifi.enableNetwork(id,true))"正在重新连接…" else "系统拒绝重连"}
                            else if(wifi.removeNetwork(id)&&wifi.configuredNetworks.orEmpty().none{it.networkId==id})"已忘记网络" else {
                                val request=org.json.JSONObject().put("action","forget_wifi").put("id",id).put("ssid",config.SSID.orEmpty().removeSurrounding("\""))
                                worker.execute{sendReply(reply,LocalControlClient.request(request).optString("message","未能删除此网络"))}
                                return
                            }
                    } else if(message.what==2) {
                        val probe=WifiConfiguration().apply {SSID=org.json.JSONObject.quote("PTV-probe-${java.util.UUID.randomUUID().toString().take(8)}");allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)}
                        val id=wifi.addNetwork(probe)
                        if(id>=0&&message.data.getBoolean("retainProbe",false)) {
                            result=org.json.JSONObject().put("id",id).put("ssid",probe.SSID.removeSurrounding("\"")).toString()
                            postDelayed({runCatching{if(wifi.configuredNetworks.orEmpty().any{it.networkId==id&&it.SSID==probe.SSID})wifi.removeNetwork(id)}},120000)
                        } else result=if(id>=0&&wifi.removeNetwork(id))"直连配置接口可用" else "系统拒绝直连配置"
                    }
                    else {
                        val ssid=message.data.getString("ssid").orEmpty()
                        val password=message.data.getString("password").orEmpty()
                        val security=message.data.getString("security").orEmpty()
                        require(ssid.isNotBlank())
                        require(security in listOf("open","wpa2"))
                        val configuration=WifiConfiguration().apply {
                            SSID=org.json.JSONObject.quote(ssid)
                            if(security=="wpa2") {require(password.length in 8..63);allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);preSharedKey=org.json.JSONObject.quote(password)}
                            else allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                        }
                        val id=wifi.addNetwork(configuration)
                        result=if(id>=0&&wifi.enableNetwork(id,true))"正在连接 $ssid…" else "系统拒绝了连接请求"
                    }
                }
            }catch(_:Exception){result="无法连接，请检查网络类型、密码和权限"}
            finally{message.data.remove("password")}
            try{reply.send(Message.obtain(null,1).apply{data=Bundle().apply{putString("result",result)}})}catch(_:RemoteException){}
        }
    })}
    override fun onBind(intent:Intent):IBinder=messenger.binder
    override fun onDestroy(){worker.shutdownNow();super.onDestroy()}
}

class PermissionActivity:Activity() {
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(listOf(android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION).all{checkSelfPermission(it)==PackageManager.PERMISSION_GRANTED})finish()
        else requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION),1)
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,grants:IntArray){super.onRequestPermissionsResult(code,permissions,grants);finish()}
}
