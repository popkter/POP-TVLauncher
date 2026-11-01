package com.popkter.ptvlauncher

import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI

class WallpaperUploadServerTest {
    @Test fun tokenAndSizeChecksProtectUploadAndCloseStopsListener() {
        var received:ByteArray?=null
        val server=WallpaperUploadServer{received=it}
        val uri=URI(server.localUrl)
        fun request(path:String,body:String?=null,length:Int=body?.length ?: 0):String = Socket("127.0.0.1",uri.port).use {socket->
            socket.soTimeout=2000
            val method=if(body==null)"GET" else "POST"
            socket.getOutputStream().write(("$method $path HTTP/1.1\r\nHost: localhost\r\nContent-Length: $length\r\n\r\n"+(body?:"")).toByteArray())
            socket.getInputStream().readBytes().decodeToString()
        }
        try {
            assertTrue(request("/wrong").startsWith("HTTP/1.1 404"))
            assertTrue(request(uri.path).contains("上传到电视"))
            assertTrue(request(uri.path,"abc").startsWith("HTTP/1.1 200"))
            assertArrayEquals("abc".toByteArray(),received)
            assertTrue(request(uri.path,"",13*1024*1024).startsWith("HTTP/1.1 400"))
        } finally {server.close()}
        assertTrue(runCatching {Socket("127.0.0.1",uri.port).close()}.isFailure)
    }
}
