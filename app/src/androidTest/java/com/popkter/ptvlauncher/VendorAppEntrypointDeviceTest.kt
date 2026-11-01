// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.app.UiAutomation
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.View
import android.widget.GridView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VendorAppEntrypointDeviceTest {
    @Test fun mijiaWithoutLauncherCategoryOpensFromTheRealAppList() = verifyAppListEntry(VendorAppEntrypoints.MIJIA)
    @Test fun appStoreWithoutLauncherCategoryOpensFromTheRealAppList() = verifyAppListEntry(VendorAppEntrypoints.APPSTORE)

    private fun verifyAppListEntry(id:String) = runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val automation=instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val intent=Intent(instrument.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity=launchTestHome()
        val graph=LauncherGraph.get(activity)
        val apps=withTimeout(15000){graph.apps.scannedEntries.filterNotNull().first{it.isNotEmpty()}}
        assertEquals(1,apps.count{it.id==id})
        val entry=apps.single{it.id==id}
        assertTrue("$id public UI must not be classified as a headless component",entry.launchable)
        assertNotNull(entry.icon)
        assertNull(activity.packageManager.getLeanbackLaunchIntentForPackage(id))
        assertNull(activity.packageManager.getLaunchIntentForPackage(id))
        val vendor=VendorAppEntrypoints.resolve(activity,id)
        assertNotNull(vendor)
        assertEquals(id,vendor!!.component!!.packageName)
        val target=activity.packageManager.getActivityInfo(vendor.component!!,0)
        assertTrue(target.exported)
        assertTrue(target.permission==null || activity.checkSelfPermission(target.permission)==PackageManager.PERMISSION_GRANTED)
        assertNull(VendorAppEntrypoints.resolve(activity,"unrecognized.system.component"))
        instrument.waitForIdleSync()
        instrument.runOnMainSync {
            activity.surface.show("apps")
            activity.surface.findViewWithTag<View>("library:all").performClick()
        }
        instrument.waitForIdleSync()
        instrument.runOnMainSync {
            val grid=activity.surface.findViewWithTag<GridView>("library")
            val positions=(0 until grid.count).filter{(grid.getItemAtPosition(it) as AppEntry).id==id}
            assertEquals(1,positions.size)
            grid.setSelection(positions.single());grid.requestFocus()
        }
        instrument.waitForIdleSync()
        try {
            instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            var opened=false
            repeat(50) {
                if(automation.rootInActiveWindow?.packageName?.toString()==id)opened=true
                if(!opened)Thread.sleep(100)
            }
            assertTrue("The launcher UID must open $id via an actual AppList selection",opened)
        } finally {
            // HOME is singleTask and already exists: no new Activity will be created
            // for startActivitySync's monitor to observe during cleanup.
            instrument.runOnMainSync {activity.startActivity(Intent(intent))}
        }
    }
}
