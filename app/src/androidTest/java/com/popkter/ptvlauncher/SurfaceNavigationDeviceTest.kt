package com.popkter.ptvlauncher

import android.graphics.Bitmap
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*

class SurfaceNavigationDeviceTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    @Test fun homeMenuDownOpensNativePanelWithoutWaitingForRelease() {
        val automation=instrument.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=launchTestHome()
        instrument.runOnMainSync {
            activity.surface.show(null)
            activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_MENU))
            assertNull(activity.surface.mode)
            assertNotNull(activity.window.decorView.findViewWithTag<View>("桌面设置按钮"))
            assertNull(activity.window.decorView.findViewWithTag<View>("控制中心按钮"))
        }
        try {
            var native=false
            repeat(30) {
                if(automation.rootInActiveWindow?.packageName?.toString()=="com.xiaomi.mitv.smartpanel")native=true
                if(!native)Thread.sleep(100)
            }
            assertTrue("MENU must open Xiaomi's actual panel from the launcher UID",native)
        } finally {
            instrument.runOnMainSync {activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_MENU))}
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            Thread.sleep(300)
        }
    }
    @Test fun rapidPanelChangesFinishWithRestoredRailFocus() {
        val activity=launchTestHome()
        Thread.sleep(1000)
        instrument.runOnMainSync {
            activity.surface.show("apps")
            activity.surface.show("settings")
            activity.surface.show(null)
            activity.surface.show("apps")
            activity.surface.show(null)
        }
        Thread.sleep(400)
        instrument.runOnMainSync {
            assertNull(activity.surface.mode)
            assertEquals("Outgoing panels must be removed",2,activity.surface.childCount)
            val rail=activity.surface.findViewWithTag<View>("rail:scroll")
            assertEquals(View.VISIBLE,rail.visibility)
            assertEquals(1f,rail.alpha,0.001f)
            assertEquals(0f,rail.translationX,0.001f)
            assertTrue("Focus must return to the rail",rail.hasFocus())
        }
    }
    @Test fun blurSmoothsEdgesWithoutChangingSolidColors() {
        val input=Bitmap.createBitmap(960,540,Bitmap.Config.ARGB_8888)
        input.eraseColor(0xff456789.toInt())
        val solid=BackdropBlur.create(input)
        assertEquals(480,solid.width)
        assertEquals(0xff456789.toInt(),solid.getPixel(240,135))
        for(x in 480 until 960)for(y in 0 until 540)input.setPixel(x,y,0xffffffff.toInt())
        val blurred=BackdropBlur.create(input)
        assertNotEquals(0xff456789.toInt(),blurred.getPixel(235,135))
        assertNotEquals(0xffffffff.toInt(),blurred.getPixel(245,135))
        input.recycle();solid.recycle();blurred.recycle()
    }
}
