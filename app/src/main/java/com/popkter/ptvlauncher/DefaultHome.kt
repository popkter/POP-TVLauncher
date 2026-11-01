// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/** Selection and routing are separate: Xiaomi can hold PTV's HOME role but force its own activity. */
data class DefaultHomeStatus(
    val selected: Boolean,
    val systemRoutesToPtv: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean = false,
) {
    val needsCompatibility: Boolean get() = selected && !systemRoutesToPtv
    val ready: Boolean get() = selected && (systemRoutesToPtv || (accessibilityEnabled && accessibilityConnected))
}

object DefaultHomeController {
    /** Only trust our signed bridge, never an unrelated app using the allowlisted ID. */
    fun systemRoutesToPtv(context: Context): Boolean {
        val home = systemHome(context) ?: return false
        if (home.packageName == context.packageName) return true
        return home == ComponentName("com.ts.launcher", "com.popkter.ptvlauncher.homebridge.HomeActivity") &&
            context.packageManager.checkSignatures(context.packageName, home.packageName) == PackageManager.SIGNATURE_MATCH
    }

    fun systemHome(context: Context): ComponentName? = context.packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
    )?.activityInfo?.let { ComponentName(it.packageName, it.name) }

    private fun holdsHomeRole(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        }.getOrDefault(false)
    }

    fun isSelected(context: Context): Boolean =
        holdsHomeRole(context) || systemRoutesToPtv(context)

    fun inspect(context: Context): DefaultHomeStatus {
        val direct = systemRoutesToPtv(context)
        return DefaultHomeStatus(holdsHomeRole(context) || direct, direct,
            SystemLinks.accessibilityEnabled(context), SwitcherService.connected.value)
    }

    /** Always use the system's selection UI; the launcher never grants itself the HOME role. */
    fun openChooser(context: Context): Boolean {
        val chooser = if (Build.VERSION.SDK_INT >= 29) {
            val role = context.getSystemService(RoleManager::class.java)
            if (role != null && role.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !role.isRoleHeld(RoleManager.ROLE_HOME)) {
                role.createRequestRoleIntent(RoleManager.ROLE_HOME)
            } else Intent(Settings.ACTION_HOME_SETTINGS)
        } else Intent(Settings.ACTION_HOME_SETTINGS)
        return try {
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (_: RuntimeException) {
            com.popkter.glass_comonpent.GlassMessages.show(context,"系统未提供可用的桌面选择器，请参考安装说明通过 ADB 设置")
            false
        }
    }

    fun testHome(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: RuntimeException) {
            com.popkter.glass_comonpent.GlassMessages.show(context,"系统未提供可用的主页入口")
        }
    }
}
