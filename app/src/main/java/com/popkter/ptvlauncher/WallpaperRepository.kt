// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class WallpaperEntry(val file: File, val caption: String)
data class WallpaperResult(val entries: List<WallpaperEntry>, val offline: Boolean)

/** Only Bing supplies remote paths. Cache metadata is committed after complete image downloads. */
class WallpaperRepository(context: Context) {
    private val folder = File(context.cacheDir, "wallpapers").apply { mkdirs() }
    private val metadata = File(folder, "index.json")
    private val mutex = Mutex()
    private fun cached(): List<WallpaperEntry> = runCatching {
        val array = JSONObject(metadata.readText()).getJSONArray("images")
        (0 until array.length()).mapNotNull { i ->
            val entry = array.getJSONObject(i)
            val file = File(folder, entry.getString("file"))
            if (file.parentFile == folder && file.isFile && file.length() > 0) WallpaperEntry(file, entry.getString("caption")) else null
        }
    }.getOrDefault(emptyList())
    suspend fun loadCached(): WallpaperResult = withContext(Dispatchers.IO) {WallpaperResult(cached(),true)}
    suspend fun load(): WallpaperResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = cached()
            if (old.isNotEmpty() && System.currentTimeMillis() - metadata.lastModified() < 24 * 60 * 60_000L)
                return@withLock WallpaperResult(old, false)
            try {
                val json = JSONObject(fetch(URL("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=zh-CN"), 256 * 1024).decodeToString())
                val source = json.getJSONArray("images")
                val result = JSONArray()
                for (i in 0 until source.length()) {
                    val item = source.getJSONObject(i)
                    val day = item.getString("startdate").filter { it.isDigit() }.take(8)
                    if (day.length != 8) continue
                    val file = File(folder, "$day.jpg")
                    if (!file.exists()) {
                        val base = item.getString("urlbase")
                        require(base.startsWith("/th?") || base.startsWith("/th/"))
                        val bytes = fetch(URL("https://www.bing.com${base}_1920x1080.jpg"), 10 * 1024 * 1024)
                        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        require(options.outWidth > 0 && options.outHeight > 0)
                        val temporary = File(folder, "$day.part")
                        temporary.writeBytes(bytes)
                        check(temporary.renameTo(file))
                    }
                    result.put(JSONObject().put("file", file.name).put("caption", item.optString("copyright", "Bing 每日图片")))
                }
                require(result.length() > 0)
                val tmp = File(folder, "index.part")
                tmp.writeText(JSONObject().put("images", result).toString())
                check(tmp.renameTo(metadata))
                val current = cached()
                val keep = current.map { it.file.name }.toSet() + "index.json"
                folder.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
                WallpaperResult(current, false)
            } catch (_: Exception) { WallpaperResult(old, true) }
        }
    }
    private fun fetch(url: URL, limit: Int): ByteArray {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000; connection.readTimeout = 12_000
        try {
            require(connection.responseCode == 200)
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= limit)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally { connection.disconnect() }
    }
}
