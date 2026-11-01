package com.popkter.ptvlauncher

import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/** A temporary, token-protected LAN receiver, alive only while its QR screen is visible. */
class WallpaperUploadServer(private val receive:(ByteArray)->Unit) : AutoCloseable {
    private val token=UUID.randomUUID().toString().replace("-","")
    private val server=ServerSocket(0)
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var active:Socket?=null
    internal val localUrl:String get()="http://127.0.0.1:${server.localPort}/$token"
    val url:String?=NetworkInterface.getNetworkInterfaces().toList()
        .filter{it.isUp && !it.isLoopback}.flatMap{it.inetAddresses.toList()}
        .filterIsInstance<Inet4Address>().firstOrNull{it.isSiteLocalAddress}
        ?.hostAddress?.let{"http://$it:${server.localPort}/$token"}
    init {worker.execute {while(!server.isClosed) {
        try {server.accept().use{socket->active=socket;socket.soTimeout=15000;handle(socket)}}
        catch(_:Exception) {} finally {active=null}
    }}}
    private fun handle(socket:Socket) {
        val input=BufferedInputStream(socket.getInputStream())
        var headerBytes=0
        fun line():String {
            val out=StringBuilder()
            while(true) {
                val b=input.read();require(b>=0 && ++headerBytes<=16384)
                if(b==10)return out.toString().trimEnd('\r')
                out.append(b.toChar())
            }
        }
        val request=line().split(' ')
        val headers=mutableMapOf<String,String>()
        while(true) {val l=line();if(l.isEmpty())break;val i=l.indexOf(':');if(i>0)headers[l.substring(0,i).lowercase()]=l.substring(i+1).trim()}
        fun reply(status:Int,type:String,text:String) {
            val bytes=text.toByteArray(Charsets.UTF_8)
            val head="HTTP/1.1 $status ${if(status==200)"OK" else "Error"}\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply{write(head.toByteArray());write(bytes);flush()}
        }
        if(request.size<2 || request[1]!="/$token") {reply(404,"text/plain","链接已失效，请重新扫码");return}
        when(request[0]) {
            "GET"->reply(200,"text/html",PAGE)
            "POST"->try {
                val length=headers["content-length"]?.toIntOrNull() ?: 0
                require(length in 1..12*1024*1024){"图片不能为空，且不能超过 12 MB"}
                require(headers["transfer-encoding"]==null){"不支持该上传方式"}
                val bytes=ByteArray(length);var read=0
                while(read<length) {val n=input.read(bytes,read,length-read);require(n>0);read+=n}
                receive(bytes);reply(200,"text/plain","上传成功，请在电视上选择使用方式")
            } catch(e:Exception) {reply(400,"text/plain",e.message ?: "上传失败，请重试")}
            else->reply(405,"text/plain","不支持的请求")
        }
    }
    override fun close() {server.close();active?.close();worker.shutdownNow()}
    companion object {
        private val PAGE="""<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PTV 上传壁纸</title><style>body{font:17px system-ui;background:#f2f3f7;color:#202734;padding:32px 20px;max-width:520px;margin:auto}section{background:white;border-radius:24px;padding:26px}h1{font-size:28px}button{background:#1677ff;color:white;border:0;border-radius:14px;padding:15px 24px;font-size:17px;width:100%;margin-top:24px}p{line-height:1.6;color:#627084}input{max-width:100%}</style><section><h1>上传到电视</h1><p>选择喜欢的照片作为 PTV 壁纸。手机和电视需连接同一局域网，并保持电视上传页面打开。</p><input id="files" type="file" accept="image/jpeg,image/png,image/webp" multiple><button id="send">上传壁纸</button><p id="status">支持 JPG、PNG、WebP，每张不超过 12 MB，最多 30 张。</p></section><script>const b=document.querySelector('#send'),s=document.querySelector('#status');b.onclick=async()=>{const fs=document.querySelector('#files').files;if(!fs.length){s.textContent='请先选择图片';return}b.disabled=true;let ok=0;try{for(const f of fs){if(f.size>12582912)throw Error('图片超过 12 MB：'+f.name);s.textContent='正在上传 '+f.name;const r=await fetch(location.pathname,{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:f});const t=await r.text();if(!r.ok)throw Error(t);ok++}s.textContent='已上传 '+ok+' 张。请返回电视选择轮播或固定一张。'}catch(e){s.textContent='已上传 '+ok+' 张；'+e.message}finally{b.disabled=false}};</script></html>"""
    }
}
