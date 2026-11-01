package com.popkter.ptvlauncher

import android.content.*
import android.content.pm.PackageManager
import android.os.*

internal class NetworkBridgeClient(private val context:Context) {
    data class SavedNetwork(val id:Int,val ssid:String)
    companion object {const val PACKAGE="com.popkter.ptvlauncher.networkbridge"}
    fun installed()=context.packageManager.checkSignatures(context.packageName,PACKAGE)==PackageManager.SIGNATURE_MATCH
    fun authorized()=installed()&&listOf(android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION).all{context.packageManager.checkPermission(it,PACKAGE)==PackageManager.PERMISSION_GRANTED}
    fun authorize()=runCatching{context.startActivity(Intent().setComponent(ComponentName(PACKAGE,"$PACKAGE.PermissionActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}.isSuccess
    fun savedNetworks(result:(List<SavedNetwork>)->Unit) {
        request("","","",operation=3){response->
            result(runCatching{val values=org.json.JSONArray(response);(0 until values.length()).map{val n=values.getJSONObject(it);SavedNetwork(n.getInt("id"),n.getString("ssid"))}}.getOrDefault(emptyList()))
        }
    }
    fun request(ssid:String,password:String,security:String,operation:Int=1,networkId:Int=-1,retainProbe:Boolean=false,address:String="",result:(String)->Unit) {
        if(!installed()){result("请安装与 PTV 同签名的网络兼容组件");return}
        if(operation in 1..5 && !authorized()){result("请先授权网络兼容组件");return}
        val handler=Handler(Looper.getMainLooper())
        var bound=false;var finished=false
        lateinit var connection:ServiceConnection
        fun complete(message:String){if(finished)return;finished=true;if(bound)runCatching{context.unbindService(connection)};result(message)}
        val reply=Messenger(object:Handler(Looper.getMainLooper()){
            override fun handleMessage(msg:Message){complete(msg.data.getString("result")?:"连接请求未返回结果")}
        })
        connection=object:ServiceConnection {
            override fun onServiceConnected(name:ComponentName,binder:IBinder){
                if(finished){runCatching{context.unbindService(this)};return}
                runCatching {Messenger(binder).send(Message.obtain(null,operation).apply{replyTo=reply;data=Bundle().apply{putString("ssid",ssid);putString("password",password);putString("security",security);putInt("networkId",networkId);putBoolean("retainProbe",retainProbe);putString("address",address)}})}
                    .onFailure{complete("无法提交连接请求")}
            }
            override fun onServiceDisconnected(name:ComponentName){complete("网络组件已断开")}
        }
        val flags=Context.BIND_AUTO_CREATE or if(Build.VERSION.SDK_INT>=30)Context.BIND_INCLUDE_CAPABILITIES else 0
        bound=runCatching{context.bindService(Intent().setComponent(ComponentName(PACKAGE,"$PACKAGE.NetworkBridgeService")),connection,flags)}.getOrDefault(false)
        if(!bound)complete("无法启动网络组件") else handler.postDelayed({complete("网络组件响应超时")},8000)
    }
}
