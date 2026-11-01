// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.text.Collator
import java.util.Locale

private val Context.launcherDataStore by preferencesDataStore("launcher")

data class AppEntry(val id: String, val label: String, val icon: Bitmap?, val launchable: Boolean, val banner: Bitmap? = null, val brandColor: Int = 0xFF454A55.toInt(), val artworkKey: String = "")
data class HomePreferences(val pins: List<String> = emptyList(), val initialized: Boolean = false,
    val idleMinutes: Int = 3, val rotate: Boolean = true,
    val customWallpaper: Boolean = false, val customRotate: Boolean = true, val fixedWallpaper: String = "")

object PinOrder {
    const val MAX_PINS = 10
    fun toggle(ids: List<String>, id: String): List<String> = when {
        id in ids -> ids - id
        ids.size < MAX_PINS -> ids + id
        else -> ids
    }
    fun decode(value: String): List<String> = value.split('|').filter { it.isNotBlank() }.distinct()
    fun encode(ids: List<String>) = ids.distinct().joinToString("|")
    fun move(ids: List<String>, id: String, delta: Int): List<String> {
        val result = ids.toMutableList()
        val from = result.indexOf(id)
        if (from < 0) return ids
        val to = (from + delta).coerceIn(0, result.lastIndex)
        result.removeAt(from); result.add(to, id)
        return result
    }
    fun visible(ids: List<String>, available: Set<String>) = ids.distinct().filter { it in available }
}

/** Pure timing policy; uses monotonic timestamps supplied by the caller. */
class BackPressGate(private val threshold: Long = 600) {
    private var downAt: Long? = null
    private var longFired = false
    fun down(now: Long) { if (downAt == null) { downAt = now; longFired = false } }
    fun longPress(now: Long): Boolean {
        val start = downAt ?: return false
        if (!longFired && now - start >= threshold) { longFired = true; return true }
        return false
    }
    fun up(cancelled: Boolean): Boolean {
        val short = downAt != null && !longFired && !cancelled
        reset(); return short
    }
    fun reset() { downAt = null; longFired = false }
}

/** Window events can repeat while an activity is starting; never launch HOME twice in that interval. */
class HomeReturnGate {
    private var pendingUntil:Long?=null
    fun claim(now:Long):Boolean {
        if(pendingUntil?.let{now<it}==true)return false
        pendingUntil=now+1500
        return true
    }
    fun reset() {pendingUntil=null}
}

object IdlePolicy {
    fun shouldShow(resumed: Boolean, panelOpen: Boolean, now: Long, lastInput: Long, minutes: Int) =
        resumed && !panelOpen && minutes > 0 && now - lastInput >= minutes * 60_000L
}

class LauncherStore(context: Context, scope: CoroutineScope,
    private val dataStore: androidx.datastore.core.DataStore<Preferences> = context.launcherDataStore) {
    private val pins = stringPreferencesKey("pins")
    private val initialized = booleanPreferencesKey("pins_initialized")
    private val schema = intPreferencesKey("pins_schema")
    private val idle = intPreferencesKey("idle_minutes")
    private val customSource = booleanPreferencesKey("custom_wallpaper")
    private val customRotation = booleanPreferencesKey("custom_rotation")
    private val fixedPicture = stringPreferencesKey("fixed_wallpaper")
    private val rotation = booleanPreferencesKey("rotation")
    val state = dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { HomePreferences(PinOrder.decode(it[pins].orEmpty()), it[initialized] ?: false,
            it[idle] ?: 3, it[rotation] ?: true, it[customSource] ?: false, it[customRotation] ?: true, it[fixedPicture].orEmpty()) }
        .stateIn(scope, SharingStarted.Eagerly, HomePreferences())
    suspend fun cachedPins(): List<String> = PinOrder.decode(dataStore.data.first()[pins].orEmpty())
    suspend fun initialize(defaults: List<String>) {
        dataStore.edit {
            if (it[initialized] != true) { it[pins] = PinOrder.encode(defaults.take(PinOrder.MAX_PINS)); it[initialized] = true }
            else if ((it[schema] ?: 1) < 2 && defaults.size >= 10 && PinOrder.decode(it[pins].orEmpty()) == defaults.take(6)) {
                // Upgrade only the untouched six-app default from the first development build.
                it[pins] = PinOrder.encode(defaults.take(PinOrder.MAX_PINS))
            }
            it[pins] = PinOrder.encode(PinOrder.decode(it[pins].orEmpty()).take(PinOrder.MAX_PINS))
            it[schema] = 2
        }
    }
    suspend fun toggle(id: String): Boolean {
        var changed = false
        dataStore.edit {
            val current = PinOrder.decode(it[pins].orEmpty())
            val next = PinOrder.toggle(current, id)
            changed = next != current
            it[pins] = PinOrder.encode(next)
        }
        return changed
    }
    suspend fun prune(available: Set<String>) = dataStore.edit {
        if (it[initialized] == true) it[pins] = PinOrder.encode(PinOrder.visible(PinOrder.decode(it[pins].orEmpty()), available))
    }
    suspend fun move(id: String, delta: Int) = dataStore.edit {
        it[pins] = PinOrder.encode(PinOrder.move(PinOrder.decode(it[pins].orEmpty()), id, delta))
    }
    suspend fun idle(minutes: Int) = dataStore.edit { it[idle] = minutes }
    suspend fun wallpaperSource(custom: Boolean) = dataStore.edit { it[customSource] = custom; if(!custom)it[rotation]=true }
    suspend fun customRotation(enabled: Boolean) = dataStore.edit { it[customRotation] = enabled }
    suspend fun fixedWallpaper(name: String) = dataStore.edit { it[fixedPicture] = name; it[customSource] = true; it[customRotation] = false }
    suspend fun rotation(enabled: Boolean) = dataStore.edit { it[rotation] = enabled }
}

class AppRepository(private val context: Context, private val scope: CoroutineScope,
    private val priority: suspend () -> List<String> = { emptyList() }) {
    val entries = MutableStateFlow<List<AppEntry>>(emptyList())
    // Never prune saved pins using a potentially stale disk snapshot.
    val scannedEntries = MutableStateFlow<List<AppEntry>?>(null)
    val loading = MutableStateFlow(true)
    private val snapshot=AppSnapshotCache(context.filesDir)
    private var refreshJob: Job? = null
    private val cacheMutex = kotlinx.coroutines.sync.Mutex()
    private val invalidated = mutableSetOf<String>()
    private var persistedEntries: List<AppEntry>? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.data?.schemeSpecificPart?.let {invalidated.add(it)}
            refresh(debounce = true)
        }
    }
    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }
    fun refresh(debounce: Boolean = false) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            loading.value = true
            try {
                if(debounce)delay(250)
                val started=android.os.SystemClock.elapsedRealtime()
                if(entries.value.isEmpty()) {
                    val preferred=try { priority() }
                        catch(error: CancellationException) {throw error}
                        catch(error: Exception) {emptyList()}
                    val cached=withContext(Dispatchers.IO) {
                        cacheMutex.withLock {
                            snapshot.readStaged(preferred) { home ->
                                withContext(Dispatchers.Main.immediate) {
                                    entries.value=home
                                    android.util.Log.i("PTVStartup","Home apps ready: ${home.size}, ${android.os.SystemClock.elapsedRealtime()-started}ms")
                                }
                            }
                        }
                    }
                    if(cached.isNotEmpty()) {
                        entries.value=cached
                        persistedEntries=cached
                        android.util.Log.i("PTVStartup","Cached apps ready: ${cached.size}, ${android.os.SystemClock.elapsedRealtime()-started}ms")
                    }
                }
                val dirty=invalidated.toSet()
                val previous=entries.value.associateBy {it.id}
                val list = withContext(Dispatchers.IO) {
                    val pm = context.packageManager
                    val resolved = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
                        .flatMap { category -> pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0) }
                    val launchers = resolved.map { it.activityInfo.packageName }.toSet()
                    val activityInfo = resolved.groupBy { it.activityInfo.packageName }
                    val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
                    val configuration=context.resources.configuration.toString()
                    var reused=0
                    pm.getInstalledApplications(0).filter { it.packageName != context.packageName }.map { app ->
                        ensureActive()
                        val launchable = app.enabled && (app.packageName in launchers ||
                            VendorAppEntrypoints.resolve(context,app.packageName)!=null)
                        val stamp=runCatching {pm.getPackageInfo(app.packageName,0).lastUpdateTime}.getOrNull()
                        val key=if(stamp==null) "" else "$stamp|${app.sourceDir}|$configuration|$launchable"
                        val old=previous[app.packageName]
                        if(app.packageName !in dirty && key.isNotEmpty() && old?.artworkKey==key && (!launchable || old.icon!=null)) {
                            reused++
                            return@map old
                        }
                        val icon = if (launchable) runCatching { app.loadIcon(pm).toBitmap(128, 128) }.getOrNull() else null
                        val banner = if (launchable) runCatching {
                            (activityInfo[app.packageName]?.firstOrNull()?.activityInfo?.loadBanner(pm) ?: app.loadBanner(pm))?.toBitmap(320, 180)
                        }.getOrNull() else null
                        AppEntry(app.packageName, app.loadLabel(pm).toString(), icon, launchable, banner, dominantColor(icon),key)
                    }.distinctBy { it.id }.sortedWith { a, b -> collator.compare(a.label, b.label) }
                        .also {android.util.Log.i("PTVStartup","Artwork reused: $reused/${it.size}")}
                }
                entries.value = list
                scannedEntries.value = list
                invalidated.removeAll(dirty)
                android.util.Log.i("PTVStartup","App scan ready: ${list.size}, ${android.os.SystemClock.elapsedRealtime()-started}ms")
                if(list!=persistedEntries) {
                    val saved=withContext(Dispatchers.IO) {
                        cacheMutex.withLock {
                            runCatching {snapshot.write(list)}
                                .onFailure {android.util.Log.w("PTVStartup","App snapshot write failed",it)}.isSuccess
                        }
                    }
                    if(saved)persistedEntries=list
                }
            } catch(error: CancellationException) { throw error
            } catch(error: Exception) {
                android.util.Log.w("PTVStartup","App refresh failed; keeping cached apps",error)
            } finally { if(currentCoroutineContext().isActive)loading.value = false }
        }
    }
    internal fun launchIntent(id:String):Intent? {
        val pm = context.packageManager
        return pm.getLeanbackLaunchIntentForPackage(id) ?: pm.getLaunchIntentForPackage(id)
            ?: VendorAppEntrypoints.resolve(context,id)
    }
    fun launch(id: String): Boolean {
        val intent=launchIntent(id)
        if (intent == null) { toast("暂未识别到这个应用的启动入口"); refresh(); return false }
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)); true
        } catch (_: RuntimeException) { toast("无法打开应用，请检查应用是否仍已安装"); false }
    }
    private fun toast(text: String) = com.popkter.glass_comonpent.GlassMessages.show(context,text)
}

class LauncherGraph private constructor(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val store = LauncherStore(context, scope)
    val apps = AppRepository(context, scope) { store.cachedPins() }
    val wallpaper = WallpaperRepository(context)
    val customWallpapers = CustomWallpapers(context)
    var wallpaperBackdrop: Bitmap? = null
    init {
        scope.launch { apps.scannedEntries.filterNotNull().filter { it.isNotEmpty() }.collect { list ->
            // Favor actual media apps when available, then retain the user's order forever.
            val preferred = listOf("com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.mediaexplorer", "com.xiaomi.mitv.smartshare")
            val launchable = list.filter { it.launchable }.map { it.id }
            store.initialize((preferred.filter { it in launchable } + launchable).distinct().take(PinOrder.MAX_PINS))
            store.prune(launchable.toSet())
        } }
    }
    companion object {
        @Volatile private var instance: LauncherGraph? = null
        fun get(context: Context): LauncherGraph = instance ?: synchronized(this) {
            instance ?: LauncherGraph(context.applicationContext).also { instance = it }
        }
    }
}

object SystemLinks {
    /** Public exported entry from the installed Xiaomi SmartPanel manifest. */
    fun showNativeControlCenter(context:Context):Boolean {
        val intent=Intent("com.xiaomi.mitv.smartpanel.action.SHOW_PANEL")
            .setComponent(ComponentName("com.xiaomi.mitv.smartpanel","com.xiaomi.mitv.smartpanel.SmartPanelService"))
        return try {
            val requested=context.startService(intent)!=null
            android.util.Log.i("PTVLauncher","Native panel request accepted=$requested from="+context.javaClass.simpleName)
            requested
        } catch(error:RuntimeException) {
            android.util.Log.w("PTVLauncher","Native control center unavailable",error)
            false
        }
    }

    fun open(context: Context, action: String, fallback: String = Settings.ACTION_SETTINGS) {
        try { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: RuntimeException) {
            try { context.startActivity(Intent(fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: RuntimeException) { com.popkter.glass_comonpent.GlassMessages.show(context,"系统未提供此设置入口") }
        }
    }
    fun appInfo(context: Context, id: String) {
        try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$id"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: RuntimeException) { open(context, Settings.ACTION_APPLICATION_SETTINGS) }
    }
    fun accessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, SwitcherService::class.java)
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == expected }
    }
}


/** Preserve an app's own brand when it has no Android TV banner. No substitute artwork is downloaded. */
private fun dominantColor(bitmap: Bitmap?): Int {
    if (bitmap == null) return 0xFF454A55.toInt()
    val bins = mutableMapOf<Int, Int>()
    for (x in 0 until bitmap.width step 4) for (y in 0 until bitmap.height step 4) {
        val c = bitmap.getPixel(x, y)
        if (android.graphics.Color.alpha(c) < 200) continue
        val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val b = android.graphics.Color.blue(c)
        val rgb = (r / 24 * 24 shl 16) or (g / 24 * 24 shl 8) or (b / 24 * 24)
        bins[rgb] = (bins[rgb] ?: 0) + 1
    }
    val rgb = bins.maxByOrNull { it.value }?.key ?: 0x454A55
    return (0xFF000000L or rgb.toLong()).toInt()
}
