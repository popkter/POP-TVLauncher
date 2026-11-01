package com.popkter.ptvlauncher.networkbridge

import android.os.*
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exported only for shell registration; authenticated requests use the signature-protected service. */
class LocalControlRegistry:android.content.ContentProvider() {
    override fun onCreate()=true
    override fun call(method:String,arg:String?,extras:Bundle?):Bundle {
        if(Binder.getCallingUid()!=Process.SHELL_UID || method!="register")throw SecurityException("Shell registration only")
        val registered=Messenger(requireNotNull(extras?.getBinder("backend")))
        backend=registered
        runCatching{registered.binder.linkToDeath({if(backend?.binder==registered.binder)backend=null},0)}
        registered.send(Message.obtain(null,101))
        return Bundle().apply{putBoolean("ok",true)}
    }
    override fun query(uri:android.net.Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):android.database.Cursor?=null
    override fun getType(uri:android.net.Uri):String?=null
    override fun insert(uri:android.net.Uri,values:android.content.ContentValues?):android.net.Uri?=throw UnsupportedOperationException()
    override fun delete(uri:android.net.Uri,selection:String?,args:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun update(uri:android.net.Uri,values:android.content.ContentValues?,selection:String?,args:Array<out String>?):Int=throw UnsupportedOperationException()
    companion object {
        @Volatile private var backend:Messenger?=null
        fun request(request:JSONObject):JSONObject {
            val target=backend?:return inactive()
            val done=CountDownLatch(1);var response:JSONObject?=null
            val reply=Messenger(object:Handler(Looper.getMainLooper()) {
                override fun handleMessage(message:Message){response=runCatching{JSONObject(message.data.getString("result").orEmpty())}.getOrNull();done.countDown()}
            })
            return try {
                target.send(Message.obtain(null,200).apply{replyTo=reply;data=Bundle().apply{putString("request",request.toString())}})
                if(done.await(7,TimeUnit.SECONDS))response?:inactive()else inactive()
            }catch(_:Exception){inactive()}
        }
        private fun inactive()=JSONObject().put("ok",false).put("message","本地管理服务未激活，请通过 PTV 安装工具重新激活（电视重启后需要）")
    }
}
