// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.app.UiAutomation
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import android.widget.GridView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherUiTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private lateinit var activity:MainActivity
    @Before fun open() {
        instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        activity=instrument.startActivitySync(Intent(instrument.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrument.waitForIdleSync()
        Thread.sleep(1200)
    }
    @After fun close() {instrument.runOnMainSync {activity.surface.show(null)}}
    private fun key(code:Int) {
        instrument.runOnMainSync {
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code))
            activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code))
        }
        instrument.waitForIdleSync()
    }
    @Test fun settingsButtonAndBackNavigateDesktopSettings() {
        instrument.runOnMainSync {activity.window.decorView.findViewWithTag<View>("桌面设置按钮").performClick()}
        instrument.waitForIdleSync()
        instrument.runOnMainSync {assertEquals("settings",activity.surface.mode);assertNotNull(activity.surface.findFocus())}
        key(KeyEvent.KEYCODE_BACK)
        instrument.runOnMainSync {assertNull(activity.surface.mode)}
    }
    @Test fun leftOpensRecycledGridAndBackRestoresCardFocus() {
        instrument.runOnMainSync {activity.surface.firstRail()}
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        instrument.runOnMainSync {
            assertEquals("apps",activity.surface.mode)
            assertTrue(activity.surface.findFocus() is GridView)
        }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        key(KeyEvent.KEYCODE_BACK)
        instrument.runOnMainSync {assertNull(activity.surface.mode);assertTrue(activity.surface.findFocus() is AppCardView)}
    }
    @Test fun nativeLongClickOpensInlineActionsWithoutLaunchingApplication() {
        instrument.runOnMainSync {
            activity.surface.firstRail()
            assertTrue(activity.surface.findFocus().performLongClick())
            assertNull(activity.surface.mode)
            assertNotNull(activity.surface.findViewWithTag<View>("app:actions"))
        }
        key(KeyEvent.KEYCODE_BACK)
        instrument.runOnMainSync {assertNull(activity.surface.mode)}
    }
    @Test fun defaultHomePageShowsActualServiceState() {
        instrument.runOnMainSync {
            activity.surface.show("default_home")
            assertEquals("default_home",activity.surface.mode)
            val status=DefaultHomeController.inspect(activity)
            assertEquals(SystemLinks.accessibilityEnabled(activity),status.accessibilityEnabled)
            assertEquals(SwitcherService.connected.value,status.accessibilityConnected)
        }
        key(KeyEvent.KEYCODE_BACK)
        instrument.runOnMainSync {assertEquals("settings",activity.surface.mode)}
    }
    @Test fun pictureModeConsumesTheWholeFirstWakeKey() = runBlocking {
        val graph=LauncherGraph.get(instrument.targetContext)
        val original=withTimeout(5000) {graph.store.state.first{it.initialized}}.idleMinutes
        try {
            graph.store.idle(1)
            instrument.runOnMainSync {activity.surface.show(null);activity.surface.firstRail()}
            Thread.sleep(61_500)
            instrument.runOnMainSync {assertEquals(View.VISIBLE,activity.window.decorView.findViewWithTag<View>("画报视图").visibility)}
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            instrument.runOnMainSync {
                assertEquals(View.GONE,activity.window.decorView.findViewWithTag<View>("画报视图").visibility)
                assertTrue(MainActivity.isForeground)
                assertNull(activity.surface.mode)
            }
        } finally {graph.store.idle(original)}
    }
}
