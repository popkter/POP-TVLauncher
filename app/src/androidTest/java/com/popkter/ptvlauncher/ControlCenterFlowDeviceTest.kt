package com.popkter.ptvlauncher

import android.view.View
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test

class ControlCenterFlowDeviceTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private fun settle(){instrument.waitForIdleSync();Thread.sleep(1000)}
    @Test fun wallpaperTransitionAnimatesOnlyContentOverMatchingFullSizeBackground() {
        val activity=launchTestHome();settle()
        instrument.runOnMainSync{activity.surface.show("settings")};settle()
        val entered=java.util.concurrent.CountDownLatch(1)
        val exited=java.util.concurrent.CountDownLatch(1)
        val valid=java.util.concurrent.atomic.AtomicBoolean(true)
        var wallpaper:WallpaperSettingsActivity?=null
        val callbacks=object:android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a:android.app.Activity,state:android.os.Bundle?) {}
            override fun onActivityStarted(a:android.app.Activity) {
                if(a !is WallpaperSettingsActivity)return
                wallpaper=a
                val content=a.window.decorView.findViewWithTag<View>("wallpaper:content")
                val background=a.window.decorView.findViewWithTag<View>("wallpaper:fixed-background")
                content.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw():Boolean {
                        if(content.alpha>.02f&&content.alpha<.98f&&content.translationX>0f) {
                            valid.set(valid.get()&&background.scaleX==1f&&background.scaleY==1f&&background.translationX==0f&&background.translationY==0f&&a.window.decorView.scaleX==1f)
                            entered.countDown()
                        };return true
                    }
                })
            }
            override fun onActivityResumed(a:android.app.Activity){}
            override fun onActivityPaused(a:android.app.Activity){}
            override fun onActivityStopped(a:android.app.Activity){}
            override fun onActivitySaveInstanceState(a:android.app.Activity,b:android.os.Bundle){}
            override fun onActivityDestroyed(a:android.app.Activity){}
        }
        instrument.runOnMainSync{activity.application.registerActivityLifecycleCallbacks(callbacks);activity.surface.findViewWithTag<View>("control:wallpaper").performClick()}
        try {
            assertTrue("Entry must draw intermediate content frames",entered.await(5,java.util.concurrent.TimeUnit.SECONDS));settle()
            instrument.runOnMainSync {
                val a=wallpaper!!
                val background=a.window.decorView.findViewWithTag<View>("wallpaper:fixed-background")
                val old=activity.surface.findViewWithTag<View>("control:backdrop")
                fun pixels(view:View)=android.graphics.Bitmap.createBitmap(96,54,android.graphics.Bitmap.Config.ARGB_8888).also{b->val c=android.graphics.Canvas(b);c.scale(96f/view.width,54f/view.height);view.draw(c)}
                val first=pixels(old);val second=pixels(background)
                assertTrue("Both screens must use the exact same background",first.sameAs(second));first.recycle();second.recycle()
                val content=a.window.decorView.findViewWithTag<View>("wallpaper:content")
                content.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw():Boolean {
                        if(content.alpha>.02f&&content.alpha<.98f){valid.set(valid.get()&&background.scaleX==1f&&background.scaleY==1f&&a.window.decorView.scaleX==1f);exited.countDown()};return true
                    }
                })
                a.window.decorView.findViewWithTag<View>("back").performClick()
            }
            assertTrue("Exit must draw intermediate content frames",exited.await(5,java.util.concurrent.TimeUnit.SECONDS));settle()
            assertTrue("Background/decor must never shrink",valid.get())
        }finally{instrument.runOnMainSync{activity.application.unregisterActivityLifecycleCallbacks(callbacks);wallpaper?.takeUnless{it.isFinishing}?.finish();activity.surface.show(null)};settle()}
    }
    @Test fun forgettingTemporarySavedNetworkRemovesItWithoutDisconnectingCurrentWifi() {
        val activity=launchTestHome();settle()
        val bridge=NetworkBridgeClient(instrument.targetContext)
        val ready=java.util.concurrent.CountDownLatch(1)
        var response=""
        instrument.runOnMainSync{bridge.request("","","",operation=2,retainProbe=true){response=it;ready.countDown()}}
        assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS))
        val probe=org.json.JSONObject(response);val id=probe.getInt("id")
        try {
            instrument.runOnMainSync{activity.surface.show("settings")};settle()
            instrument.runOnMainSync{activity.surface.findViewWithTag<View>("control:network").performClick()};settle()
            instrument.runOnMainSync{activity.surface.findViewWithTag<View>("control:row:saved:$id").performClick()};settle()
            instrument.runOnMainSync{activity.surface.findViewWithTag<View>("control:row:忘记网络").performClick()};settle()
            instrument.runOnMainSync{activity.surface.findViewWithTag<View>("control:row:确认忘记").performClick()};settle()
            instrument.runOnMainSync {
                assertNull("Forgotten network must disappear",activity.surface.findViewWithTag<View>("control:row:saved:$id"))
                assertNotNull("Focus must stay in the remaining network list",activity.surface.findFocus())
                activity.surface.show(null)
            };settle()
        } finally {
            instrument.runOnMainSync{bridge.request("","","",operation=5,networkId=id){}}
        }
    }
    @Test fun savedNetworkReturnsAndRefreshRetainFocusWithoutPasswordPrompt() {
        instrument.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val context=instrument.targetContext
        val activity=launchTestHome();settle()
        val ready=java.util.concurrent.CountDownLatch(1)
        var saved=emptyList<NetworkBridgeClient.SavedNetwork>()
        instrument.runOnMainSync{NetworkBridgeClient(context).savedNetworks{saved=it;ready.countDown()}}
        assertTrue(ready.await(10,java.util.concurrent.TimeUnit.SECONDS));assertTrue("A saved network is required",saved.isNotEmpty())
        instrument.runOnMainSync{activity.surface.show("settings")};settle()
        instrument.runOnMainSync{activity.surface.findViewWithTag<View>("control:network").performClick()};settle()
        val tag="control:row:saved:${saved.first().id}"
        instrument.runOnMainSync {activity.surface.findViewWithTag<View>(tag).apply{requestFocus();performClick()}}
        settle()
        instrument.runOnMainSync {
            assertNull("Saved networks must not force password entry",activity.surface.findViewWithTag<View>("control:wifi-password"))
            assertNotNull(activity.surface.findViewWithTag<View>("control:row:连接"))
            activity.surface.findViewWithTag<View>("control:row:忘记网络").performClick()
        };settle()
        instrument.runOnMainSync {activity.surface.findViewWithTag<View>("control:row:取消").performClick()};settle()
        instrument.runOnMainSync {assertEquals("control:row:忘记网络",activity.surface.findFocus()?.tag);activity.surface.back()};settle()
        instrument.runOnMainSync {
            assertEquals(tag,activity.surface.findFocus()?.tag)
            activity.surface.findViewWithTag<View>("control:row:刷新附近网络").apply{requestFocus();performClick()}
        };settle()
        instrument.runOnMainSync {assertEquals("control:row:刷新附近网络",activity.surface.findFocus()?.tag);activity.surface.refreshStatus()};settle()
        instrument.runOnMainSync {assertEquals("control:row:刷新附近网络",activity.surface.findFocus()?.tag);activity.surface.show(null)}
        settle()
    }
    @Test fun wallpaperCloseAnimatesAndRestoresWallpaperTileFocus() {
        instrument.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=launchTestHome();settle()
        instrument.runOnMainSync{activity.surface.show(null)};settle()
        instrument.runOnMainSync{activity.surface.show("settings")};settle()
        fun bounds(view:View)=android.graphics.Rect().also{view.getGlobalVisibleRect(it)}
        var centerBounds=android.graphics.Rect();var bodyTop=0;var headerHeight=0
        instrument.runOnMainSync {
            val center=activity.surface.findViewWithTag<android.view.ViewGroup>("control:center")
            centerBounds=bounds(center);headerHeight=center.getChildAt(0).height
            bodyTop=bounds(activity.surface.findViewWithTag("control:wallpaper")).top
        }
        instrument.runOnMainSync{activity.surface.findViewWithTag<View>("control:wallpaper").performClick()};settle()
        var wallpaper:WallpaperSettingsActivity?=null
        instrument.runOnMainSync {
            wallpaper=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<WallpaperSettingsActivity>().firstOrNull()
            assertNotNull("Wallpaper screen must open",wallpaper)
            assertTrue(wallpaper!!.intent.hasExtra("control_wallpaper_origin"))
            val header=wallpaper!!.window.decorView.findViewWithTag<View>("wallpaper:header")
            val body=wallpaper!!.window.decorView.findViewWithTag<View>("wallpaper:body")
            val layout=wallpaper!!.window.decorView.findViewWithTag<View>("wallpaper:layout")
            assertEquals("Header top must align with control center",centerBounds.top,bounds(header).top)
            assertEquals("Header rows must have identical height",headerHeight,header.height)
            assertEquals("Content must start at the same height",bodyTop,bounds(body).top)
            assertEquals("Content region must end at the same height",centerBounds.bottom,bounds(layout).bottom)
            wallpaper!!.window.decorView.findViewWithTag<View>("back").performClick()
        }
        settle()
        instrument.runOnMainSync {
            assertTrue(wallpaper!!.isFinishing||wallpaper!!.isDestroyed)
            assertEquals("settings",activity.surface.mode)
            assertEquals("control:wallpaper",activity.surface.findFocus()?.tag)
            activity.surface.show(null)
        };settle()
    }
    @Test fun passwordEntryRequestsKeyboardAndReturnsToNetworkPanel() {
        val automation=instrument.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        val activity=launchTestHome();settle()
        instrument.runOnMainSync{activity.surface.show("settings")};settle()
        instrument.runOnMainSync{activity.surface.findViewWithTag<View>("control:network").performClick()};settle()
        instrument.runOnMainSync {
            val cc=activity.surface.findViewWithTag<ControlCenterView>("panel:settings")
            @Suppress("DEPRECATION") val network=android.net.wifi.ScanResult().apply{SSID="PTV keyboard test";capabilities="[WPA2-PSK-CCMP][ESS]"}
            cc.openWifiNetwork(network)
        };settle()
        instrument.runOnMainSync {
            val input=activity.surface.findViewWithTag<EditText>("control:wifi-password")
            assertTrue("Password must receive focus",input.hasFocus())
            input.performClick()
        };settle()
        assertTrue("Clicking password must open the IME",automation.windows.any{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD})
        instrument.runOnMainSync{activity.surface.back()};settle()
        instrument.runOnMainSync {
            assertNull(activity.surface.findViewWithTag<View>("control:wifi-password"))
            assertNotNull(activity.surface.findFocus())
            activity.surface.show(null)
        };settle()
    }
}
