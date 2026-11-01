package com.popkter.ptvlauncher

import android.graphics.Bitmap

/** One-shot, in-memory handoff. Never writes desktop captures to disk. */
internal object WallpaperTransitionBackdrop {
    private var pending:Bitmap?=null
    fun prepare(source:Bitmap?) {pending?.recycle();pending=source?.copy(Bitmap.Config.ARGB_8888,false)}
    fun take():Bitmap?=pending.also{pending=null}
}
