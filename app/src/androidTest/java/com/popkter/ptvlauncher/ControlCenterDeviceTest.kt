package com.popkter.ptvlauncher

import android.view.View
import android.view.KeyEvent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ControlCenterDeviceTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private fun settle(){instrument.waitForIdleSync();Thread.sleep(750);instrument.waitForIdleSync()}
    private fun rect(v:View)=Rect().also{v.getGlobalVisibleRect(it)}
    @Test fun backdropStartsTransparentAndCloseHasVisibleIntermediateFrames() {
        instrument.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=launchTestHome();settle()
        instrument.runOnMainSync{activity.surface.show(null)};settle()
        instrument.runOnMainSync {
            activity.window.decorView.findViewWithTag<View>("桌面设置按钮").performClick()
            assertEquals("Preparing blur must not flash an opaque background",0f,activity.surface.findViewWithTag<View>("control:backdrop").alpha,.001f)
        };settle()
        repeat(40) {
            var running=false
            instrument.runOnMainSync{running=activity.surface.findViewWithTag<ControlCenterView>("panel:settings")?.animationRunning==true}
            if(running)Thread.sleep(100)
        }
        if(!android.animation.ValueAnimator.areAnimatorsEnabled())return
        val sampled=java.util.concurrent.CountDownLatch(1)
        var sampledAlpha=0f
        instrument.runOnMainSync {
            val panel=activity.surface.findViewWithTag<View>("control:center")
            panel.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw():Boolean {
                    if(panel.scaleX in .4f.. .95f){sampledAlpha=panel.alpha;sampled.countDown();panel.viewTreeObserver.removeOnPreDrawListener(this)}
                    return true
                }
            })
            activity.surface.back()
        }
        assertTrue("Return must render intermediate geometry",sampled.await(4,java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("Content must remain visible while shrinking",sampledAlpha>.8f)
        settle()
        instrument.runOnMainSync{assertNull(activity.surface.mode)}
    }
    @Test fun vectorAssetsInflateOnTheTelevision() {
        instrument.runOnMainSync {
            R.drawable::class.java.fields.filter{it.name.startsWith("f7_")||it.name.startsWith("cc_")}.forEach {
                assertNotNull(it.name,instrument.targetContext.getDrawable(it.getInt(null)))
            }
        }
    }
    @Test fun integratedPanelsPreserveDesktopAndRestoreEntryFocus() {
        instrument.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=launchTestHome();settle()
        lateinit var clock:View;lateinit var rail:View;lateinit var clockBounds:Rect;lateinit var entry:View
        instrument.runOnMainSync {
            clock=activity.window.decorView.findViewWithTag("home:clock")
            rail=activity.surface.findViewWithTag("rail:scroll")
            entry=activity.window.decorView.findViewWithTag("桌面设置按钮")
            clockBounds=rect(clock);entry.performClick()
        };settle()
        instrument.runOnMainSync {
            assertEquals("settings",activity.surface.mode)
            val panel=activity.surface.findViewWithTag<View>("control:center")
            assertEquals("Align with the original desktop settings button",rect(entry).right,rect(panel).right)
            assertSame(clock,activity.window.decorView.findViewWithTag("home:clock"))
            assertSame(rail,activity.surface.findViewWithTag("rail:scroll"))
            assertEquals(clockBounds,rect(clock))
            assertNull(activity.surface.findViewWithTag<View>("自定义桌面应用"))
            val source=activity.surface.findViewWithTag<View>("control:source")
            assertEquals(activity.dp(109),source.height)
            for(key in listOf("brightness","volume")) {
                val slider=activity.surface.findViewWithTag<ControlTile>("control:$key")
                assertEquals(activity.dp(225),slider.width);assertEquals(activity.dp(51),slider.height)
                val info=AccessibilityNodeInfo.obtain();slider.onInitializeAccessibilityNodeInfo(info)
                assertNotNull(info.rangeInfo);info.recycle()
            }
        }
        for(key in listOf("network","bluetooth","source","idle","about")) {
            instrument.runOnMainSync{activity.surface.findViewWithTag<View>("control:$key").performClick()};settle()
            instrument.runOnMainSync {
                val detail=activity.surface.findViewWithTag<View>("control:detail")
                assertEquals(View.VISIBLE,detail.visibility);assertEquals(rect(entry).right,rect(detail).right)
                val back=detail.findViewWithTag<View>("control:返回控制中心")
                assertEquals("Back focus surface must be a circle",back.width,back.height)
                val column=detail as android.widget.LinearLayout
                val header=column.getChildAt(0);val content=column.getChildAt(1)
                assertEquals("Keep header spacing after every list refresh",activity.dp(20),content.top-header.bottom)
                assertEquals(View.INVISIBLE,activity.surface.findViewWithTag<View>("control:center").visibility)
                assertEquals("settings",activity.surface.mode)
                activity.surface.back()
            };settle()
            instrument.runOnMainSync{assertEquals("control:$key",activity.surface.findFocus()?.tag)}
        }
        instrument.runOnMainSync{activity.surface.findViewWithTag<View>("control:关闭桌面设置").performClick()};settle()
        instrument.runOnMainSync {
            assertNull(activity.surface.mode);assertSame(clock,activity.window.decorView.findViewWithTag("home:clock"))
            assertEquals(clockBounds,rect(clock));assertTrue(entry.hasFocus());assertEquals(View.VISIBLE,rail.visibility)
            assertNull(activity.surface.findViewWithTag<View>("panel:settings"))
        }
    }
    @Test fun sliderDpadIsAnAdjustmentNotNavigation() {
        // Exercise the control independently, without changing the television volume/brightness.
        val activity=launchTestHome();settle()
        instrument.runOnMainSync {
            val slider=ControlTile(activity,"test",R.drawable.f7_sun_max,"亮度",slider=true)
            slider.adjust={it};slider.progress=50
            slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT))
            assertEquals(55,slider.progress)
            slider.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_LEFT))
            assertEquals(50,slider.progress)
        }
    }
}
