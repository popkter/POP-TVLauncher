package com.popkter.ptvlauncher

import android.view.View
import android.view.ViewGroup
import com.popkter.glass_comonpent.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Integration coverage: app factories must instantiate library controls, not duplicate renderers. */
class GlassMigrationDeviceTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private fun settle(){instrument.waitForIdleSync();Thread.sleep(850);instrument.waitForIdleSync()}
    private fun descendants(view: View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{descendants(view.getChildAt(it))}else emptyList()
    @Test fun desktopLibraryAndControlCenterUseLibraryControlsInBothDirections(){
        instrument.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=launchTestHome();settle()
        for(direction in listOf(View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL)) {
            instrument.runOnMainSync {
                activity.surface.show(null)
                activity.window.decorView.layoutDirection=direction
                assertTrue(activity.window.decorView.findViewWithTag<View>("桌面设置按钮") is GlassButton)
                assertTrue(activity.window.decorView.findViewWithTag<View>("home:clock") is GlassTextView)
                assertTrue(activity.surface.findViewWithTag<View>("rail:app-list") is GlassAppListButton)
                activity.surface.show("apps")
            };settle()
            instrument.runOnMainSync {
                assertTrue(activity.surface.findViewWithTag<View>("library") is GlassGridView)
                assertTrue(activity.surface.findViewWithTag<View>("library:manage") is GlassTextButton)
                assertTrue(activity.surface.findViewWithTag<View>("library:tabs") is GlassSegmentedControl)
                assertTrue(activity.surface.findViewWithTag<View>("library:all") is GlassButton)
                assertTrue(descendants(activity.surface).any{it is GlassArtworkCard})
                if(direction==View.LAYOUT_DIRECTION_LTR){
                    val view=activity.window.decorView
                    val image=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(image));java.io.File(activity.getExternalFilesDir(null),"glass-tabs-preview.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
                }
                activity.surface.show("settings")
            };settle()
            instrument.runOnMainSync {
                val wallpaper=activity.surface.findViewWithTag<ViewGroup>("control:wallpaper")
                val volume=activity.surface.findViewWithTag<ViewGroup>("control:volume")
                assertTrue(wallpaper.getChildAt(0) is GlassButton)
                assertTrue(volume.getChildAt(0) is GlassSlider)
                assertEquals(direction,wallpaper.getChildAt(0).layoutDirection)
                activity.surface.findViewWithTag<View>("control:idle").performClick()
            };settle()
            instrument.runOnMainSync {
                assertTrue(activity.surface.findViewWithTag<View>("control:row:idle:3") is GlassListRow)
                activity.surface.back()
            };settle()
            instrument.runOnMainSync {activity.surface.show(null)};settle()
        }
        instrument.runOnMainSync {activity.window.decorView.layoutDirection=View.LAYOUT_DIRECTION_LTR}
    }
    @Test fun nativePopupBackUsesReverseAnimationAndRestoresAnchor(){
        val automation=instrument.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=launchTestHome();settle()
        lateinit var anchor:View
        lateinit var menu:GlassContextMenu
        instrument.runOnMainSync {
            activity.surface.show(null)
            anchor=activity.window.decorView.findViewWithTag("桌面设置按钮")
            anchor.requestFocus()
            menu=GlassContextMenu.create(anchor,listOf(GlassMenuItem("one","菜单操作"))){}
            menu.show();assertTrue(menu.isShowing)
        };settle()
        val reverseFrame=java.util.concurrent.CountDownLatch(1)
        val frameTrace=java.util.Collections.synchronizedList(mutableListOf<Float>())
        instrument.runOnMainSync {
            val field=GlassContextMenu::class.java.getDeclaredField("popup").apply{isAccessible=true}
            val content=(field.get(menu) as android.widget.PopupWindow).contentView
            content.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw():Boolean {
                    frameTrace.add(content.alpha)
                    if(content.alpha in .05f.. .95f && menu.isShowing){reverseFrame.countDown();content.viewTreeObserver.removeOnPreDrawListener(this)}
                    return true
                }
            })
        }
        val time=android.os.SystemClock.uptimeMillis()
        automation.injectInputEvent(android.view.KeyEvent(time,time,android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_BACK,0),true)
        automation.injectInputEvent(android.view.KeyEvent(time,time+5,android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_BACK,0),true)
        if(android.animation.ValueAnimator.areAnimatorsEnabled()){
            val rendered=reverseFrame.await(3,java.util.concurrent.TimeUnit.SECONDS)
            if(!rendered){var showing=false;instrument.runOnMainSync{showing=menu.isShowing;menu.close()};fail("Return must draw intermediate frames; showing=$showing, frames=$frameTrace")}
        }
        settle()
        instrument.runOnMainSync {assertFalse(menu.isShowing);assertTrue(anchor.hasFocus());menu.close()}
    }
    @Test fun wifiPasswordFieldUsesBaseHeightWithoutLosingKeyboardFocus(){
        val automation=instrument.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=launchTestHome();settle()
        instrument.runOnMainSync {activity.surface.show(null)};settle()
        instrument.runOnMainSync {activity.surface.show("settings")};settle()
        instrument.runOnMainSync {activity.surface.findViewWithTag<View>("control:network").performClick()};settle()
        instrument.runOnMainSync {
            @Suppress("DEPRECATION") val network=android.net.wifi.ScanResult().apply{SSID="PTV input preview";capabilities="[WPA2-PSK-CCMP][ESS]"}
            activity.surface.findViewWithTag<ControlCenterView>("panel:settings").openWifiNetwork(network)
        };settle()
        instrument.runOnMainSync {
            val input=activity.surface.findViewWithTag<android.widget.EditText>("control:wifi-password")
            val field=input.parent.parent as GlassTextField
            assertEquals(activity.dp(56),field.height);assertTrue(input.hasFocus())
            assertTrue(field.clearButton.height<=field.height-field.paddingTop-field.paddingBottom)
            val confirm=activity.surface.findViewWithTag<GlassListRow>("control:row:连接")
            assertEquals(confirm.glassStyle.cornerRadius,field.glassStyle.cornerRadius,.01f)
            val fieldBounds=android.graphics.Rect().also{field.getGlobalVisibleRect(it)}
            val confirmBounds=android.graphics.Rect().also{confirm.getGlobalVisibleRect(it)}
            assertEquals(activity.dp(10),confirmBounds.top-fieldBounds.bottom)
        }
        instrument.runOnMainSync {
            val view=activity.window.decorView
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File(activity.getExternalFilesDir(null),"glass-input-preview.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
        instrument.runOnMainSync{activity.surface.back()};settle()
    }
    @Test fun appLongPressUsesIndependentWhiteButtons(){
        instrument.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val activity=launchTestHome();settle()
        instrument.runOnMainSync {activity.surface.show(null)};settle()
        lateinit var menu:AppIconMenu
        instrument.runOnMainSync {
            val card=descendants(activity.surface).filterIsInstance<AppCardView>().first()
            card.performLongClick()
            menu=NativeSurface::class.java.getDeclaredField("actionMenu").apply{isAccessible=true}.get(activity.surface) as AppIconMenu
        };settle()
        instrument.runOnMainSync {
            val popup=AppIconMenu::class.java.getDeclaredField("popup").apply{isAccessible=true}.get(menu) as android.widget.PopupWindow
            val view=popup.contentView as android.widget.LinearLayout
            val row=view
            assertEquals(3,row.childCount)
            assertNotNull("Desktop menu shares a prepared backdrop",AppIconMenu::class.java.getDeclaredField("blurredBackdrop").apply{isAccessible=true}.get(menu))
            assertNull("No overall menu material",view.background)
            assertEquals(0f,popup.elevation,.01f)
            val card=descendants(activity.surface).filterIsInstance<AppCardView>().first()
            val artwork=card.artworkBounds()
            val origin=floatArrayOf(0f,0f);val point=IntArray(2);card.getLocationOnScreen(point)
            card.matrix.mapRect(artwork);card.matrix.mapPoints(origin);artwork.offset(point[0]-origin[0],point[1]-origin[1])
            val menuPoint=IntArray(2);view.getLocationOnScreen(menuPoint)
            val distance=if(menuPoint[0]>=artwork.right)menuPoint[0]-artwork.right else artwork.left-(menuPoint[0]+view.width)
            assertEquals("Menu gap must follow the actual artwork edge",activity.dp(20).toFloat(),distance,2f)
            (0 until 3).forEach{index->val button=row.getChildAt(index) as GlassButton;assertTrue(button.iconOnly);assertEquals(.5f,button.glassStyle.fillOpacity,.01f);assertEquals(android.graphics.Color.WHITE,button.glassStyle.surfaceColor);assertEquals(GlassStyle().focusOverlayColor,button.glassStyle.focusOverlayColor);assertEquals(GlassStyle().focusColor,button.glassStyle.focusColor);assertEquals(button.width,button.height);assertEquals(row.getChildAt(0).top,button.top)}
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            assertEquals("Gaps between buttons stay transparent",0,android.graphics.Color.alpha(bitmap.getPixel(row.getChildAt(0).right+activity.dp(5),view.height/2)))
            java.io.File(activity.getExternalFilesDir(null),"glass-menu-preview.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            menu.dismiss()
        };settle()
    }
}
