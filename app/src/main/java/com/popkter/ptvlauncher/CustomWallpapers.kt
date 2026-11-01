package com.popkter.ptvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.UUID

/** User images belong in filesDir, never the evictable Bing cache. */
class CustomWallpapers(context:Context) {
    private val folder=File(context.filesDir,"custom-wallpapers").apply{mkdirs()}
    val revision=MutableStateFlow(0)
    private val orderFile=android.util.AtomicFile(File(folder,"order.txt"))
    @Synchronized fun entries():List<WallpaperEntry> {
        val files=folder.listFiles().orEmpty().filter{it.extension=="jpg"}.sortedBy{it.name}.associateBy{it.name}
        val saved=runCatching{orderFile.openRead().bufferedReader().use{it.readLines()}}.getOrDefault(emptyList())
        return (saved+files.keys).distinct().mapNotNull{files[it]}.map{WallpaperEntry(it,"自定义壁纸")}
    }
    private fun saveOrder(names:List<String>) {
        val stream=orderFile.startWrite()
        try {stream.write(names.joinToString("\n").toByteArray());orderFile.finishWrite(stream)}
        catch(e:Exception){orderFile.failWrite(stream);throw e}
    }
    @Synchronized fun move(name:String,delta:Int) {
        val names=entries().map{it.file.name}
        saveOrder(WallpaperOrder.move(names,name,delta));revision.value++
    }
    @Synchronized fun deleteMany(names:Set<String>) {
        entries().filter{it.file.name in names}.forEach{check(it.file.delete()){ "图片删除失败" }}
        saveOrder(entries().map{it.file.name});revision.value++
    }
    @Synchronized fun add(bytes:ByteArray):File {
        require(bytes.size in 1..12*1024*1024){"每张图片不能超过 12 MB"}
        require(entries().size<30){"最多保存 30 张壁纸，请先删除一些图片"}
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outMimeType in setOf("image/jpeg","image/png","image/webp")){"请上传 JPG、PNG 或 WebP 图片"}
        require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=80_000_000){"无法读取图片或图片尺寸过大，请上传 JPG、PNG 或 WebP"}
        val options=BitmapFactory.Options()
        while(maxOf(bounds.outWidth,bounds.outHeight)/options.inSampleSize.coerceAtLeast(1)>3840)
            options.inSampleSize=options.inSampleSize.coerceAtLeast(1)*2
        var bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,options) ?: error("无法解码图片")
        val orientation=runCatching {android.media.ExifInterface(bytes.inputStream()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1)}.getOrDefault(1)
        val matrix=android.graphics.Matrix().apply {
            when(orientation) {
                2->setScale(-1f,1f)
                3->setRotate(180f)
                4->setScale(1f,-1f)
                5->{setRotate(90f);postScale(-1f,1f)}
                6->setRotate(90f)
                7->{setRotate(-90f);postScale(-1f,1f)}
                8->setRotate(-90f)
            }
        }
        if(!matrix.isIdentity) {
            val oriented=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
            if(oriented!==bitmap)bitmap.recycle()
            bitmap=oriented
        }
        val file=File(folder,"${System.currentTimeMillis()}-${UUID.randomUUID()}.jpg")
        val temp=File(folder,file.name+".part")
        try {
            temp.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.JPEG,92,it))}
            check(temp.renameTo(file))
        } finally {bitmap.recycle();temp.delete()}
        revision.value++
        return file
    }
    @Synchronized fun delete(name:String) {
        entries().find{it.file.name==name}?.file?.delete()
        revision.value++
    }
}

internal object WallpaperOrder {
    fun move(names:List<String>,name:String,delta:Int):List<String> {
        val from=names.indexOf(name)
        if(from<0)return names
        val to=(from+delta).coerceIn(0,names.lastIndex)
        return names.toMutableList().apply{removeAt(from);add(to,name)}
    }
}
