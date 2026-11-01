package com.popkter.ptvlauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.*
import kotlinx.coroutines.ensureActive

/** A disposable, atomic snapshot; PackageManager remains the authoritative source. */
internal class AppSnapshotCache(directory: File) {
    private val file=AtomicFile(File(directory,"apps-v1.bin"))
    private data class Encoded(val entry: AppEntry, val icon: ByteArray, val banner: ByteArray) {
        fun decode() = entry.copy(icon=decode(icon), banner=decode(banner))
        private fun decode(bytes: ByteArray): Bitmap? =
            if(bytes.isEmpty()) null else BitmapFactory.decodeByteArray(bytes,0,bytes.size)
    }
    private fun encoded(): List<Encoded> = runCatching {
        DataInputStream(BufferedInputStream(file.openRead())).use { input ->
            val version=input.readInt()
            require(version==0x50545631 || version==0x50545632)
            val count=input.readInt().also {require(it in 0..2000)}
            var total=0L
            fun artwork(): ByteArray {
                val size=input.readInt().also {require(it in 0..2_000_000)}
                total+=size;require(total<=64L*1024*1024)
                return ByteArray(size).also {input.readFully(it)}
            }
            List(count) {
                val id=input.readUTF();val label=input.readUTF()
                val launchable=input.readBoolean();val color=input.readInt()
                val key=if(version==0x50545632)input.readUTF() else ""
                Encoded(AppEntry(id,label,null,launchable,null,color,key),artwork(),artwork())
            }
        }
    }.getOrDefault(emptyList())

    fun read(): List<AppEntry> = encoded().map {it.decode()}

    /** Publish the complete list with home artwork before decoding the rest. Caller runs on IO. */
    suspend fun readStaged(priority: List<String>, publish: suspend (List<AppEntry>) -> Unit): List<AppEntry> {
        val records=encoded()
        if(records.isEmpty())return emptyList()
        val home=(if(priority.isEmpty()) records.filter {it.entry.launchable}.take(PinOrder.MAX_PINS)
            .map {it.entry.id} else priority).toSet()
        val result=records.map {if(it.entry.id in home)it.decode() else it.entry}.toMutableList()
        publish(result.toList())
        records.forEachIndexed { index, record ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if(record.entry.id !in home)result[index]=record.decode()
        }
        return result
    }

    fun write(entries:List<AppEntry>) {
        val stream=file.startWrite()
        try {
            val output=DataOutputStream(BufferedOutputStream(stream))
            output.writeInt(0x50545632);output.writeInt(entries.size)
            entries.forEach {
                output.writeUTF(it.id);output.writeUTF(it.label)
                output.writeBoolean(it.launchable);output.writeInt(it.brandColor);output.writeUTF(it.artworkKey)
                output.bitmap(it.icon);output.bitmap(it.banner)
            }
            output.flush();file.finishWrite(stream)
        } catch(error:Exception) {file.failWrite(stream);throw error}
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
