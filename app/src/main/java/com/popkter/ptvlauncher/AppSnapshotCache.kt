package com.popkter.ptvlauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.*

/** A disposable, atomic snapshot; PackageManager remains the authoritative source. */
internal class AppSnapshotCache(directory: File) {
    private val file=AtomicFile(File(directory,"apps-v1.bin"))
    fun read():List<AppEntry> = runCatching {
        DataInputStream(BufferedInputStream(file.openRead())).use { input ->
            require(input.readInt()==0x50545631)
            val count=input.readInt().also {require(it in 0..2000)}
            List(count) {
                val id=input.readUTF();val label=input.readUTF()
                val launchable=input.readBoolean();val color=input.readInt()
                AppEntry(id,label,input.bitmap(),launchable,input.bitmap(),color)
            }
        }
    }.getOrDefault(emptyList())

    fun write(entries:List<AppEntry>) {
        val stream=file.startWrite()
        try {
            val output=DataOutputStream(BufferedOutputStream(stream))
            output.writeInt(0x50545631);output.writeInt(entries.size)
            entries.forEach {
                output.writeUTF(it.id);output.writeUTF(it.label)
                output.writeBoolean(it.launchable);output.writeInt(it.brandColor)
                output.bitmap(it.icon);output.bitmap(it.banner)
            }
            output.flush();file.finishWrite(stream)
        } catch(error:Exception) {file.failWrite(stream);throw error}
    }
    private fun DataInputStream.bitmap():Bitmap? {
        val size=readInt().also {require(it in 0..2_000_000)}
        if(size==0)return null
        val bytes=ByteArray(size);readFully(bytes)
        return BitmapFactory.decodeByteArray(bytes,0,size)
    }
    private fun DataOutputStream.bitmap(bitmap:Bitmap?) {
        if(bitmap==null) {writeInt(0);return}
        val bytes=ByteArrayOutputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
            it.toByteArray()
        }
        writeInt(bytes.size);write(bytes)
    }
}
