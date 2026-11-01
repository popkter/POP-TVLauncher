// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Verified OEM entry points for apps whose public UI omits the launcher categories. */
internal object VendorAppEntrypoints {
    const val MIJIA="com.xiaomi.smarthome.tv"
    const val APPSTORE="com.xiaomi.mitv.appstore"
    private const val MIJIA_MAIN="com.xiaomi.smarthome.tv.action.MAIN"

    fun resolve(context:Context,id:String):Intent? {
        val action=when(id) {
            MIJIA->MIJIA_MAIN
            APPSTORE->"mitv.appstore.action.LAUNCHER"
            else->return null
        }
        val intent=Intent(action).setPackage(id).addCategory(Intent.CATEGORY_DEFAULT)
        val activity=context.packageManager.resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?:return null
        // Resolve the installed firmware's exported entry; never guess or bypass a private UI.
        if(activity.packageName!=id || !activity.enabled || !activity.applicationInfo.enabled || !activity.exported)return null
        if(activity.permission?.let {context.checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}==true)return null
        return intent.setComponent(ComponentName(activity.packageName,activity.name))
    }
}
