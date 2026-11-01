// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.app.UiAutomation
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingRailNavigationTest {
    @Test fun desktopTopStopsBeforeControlCenterAndLibraryHasOnlyFullscreenBackdrop() = runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=instrument.startActivitySync(Intent(instrument.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val graph=LauncherGraph.get(activity)
        withTimeout(10000){graph.apps.entries.first{it.any{app->app.launchable}}}
        withTimeout(5000){graph.store.state.first{it.initialized}}
        instrument.waitForIdleSync()
        val surface=activity.surface
        lateinit var first:View
        instrument.runOnMainSync {surface.show(null);surface.firstRail();first=surface.findFocus();assertTrue(first is AppCardView)}
        repeat(3){instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)}
        instrument.waitForIdleSync()
        instrument.runOnMainSync {
            assertSame(first,surface.findFocus())
            assertNull(surface.mode)
        }
        instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
        instrument.waitForIdleSync()
        try {
            instrument.runOnMainSync {
                assertEquals("apps",surface.mode)
                val content=surface.findViewWithTag<View>("library:content")
                assertNull(content.background)
                assertEquals(0f,content.elevation)
                val backdrop=surface.findViewWithTag<View>("library:backdrop")
                assertEquals(surface.width,backdrop.width)
                assertEquals(surface.height,backdrop.height)
            }
        } finally {instrument.runOnMainSync {surface.show(null)}}
    }
    @Test fun upAtFirstFloatingAppKeepsFocusAndNeverNavigatesHome() = runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation()
        instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=instrument.startActivitySync(Intent(instrument.targetContext,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val graph=LauncherGraph.get(activity)
        val apps=withTimeout(10_000) {graph.apps.entries.first {it.any{app->app.launchable}}}
        val prefs=withTimeout(5000) {graph.store.state.first {it.initialized}}
        // Use an isolated pin list; this regression must never change the user's saved desktop.
        val testPins=apps.filter{it.launchable}.take(2).map{it.id}
        var dismissCalls=0
        lateinit var rail:NativeSurface
        instrument.runOnMainSync {
            rail=NativeSurface(activity,graph,true,{dismissCalls++})
            rail.update(apps,prefs.copy(pins=testPins))
            activity.addContentView(rail,ViewGroup.LayoutParams(-1,-1))
        }
        instrument.waitForIdleSync()
        try {
            instrument.runOnMainSync {
                rail.firstRail()
                val first=rail.findFocus()
                assertTrue(first is AppCardView)
                // Include hardware-style repeats and release, not just a click handler call.
                rail.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP))
                rail.dispatchKeyEvent(KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP,1))
                rail.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_UP))
                assertSame(first,rail.findFocus())
                assertNull(rail.mode)
                assertEquals(0,dismissCalls)
                assertTrue(MainActivity.isForeground)
            }
        } finally {
            instrument.runOnMainSync {(rail.parent as? ViewGroup)?.removeView(rail)}
        }
    }
}
