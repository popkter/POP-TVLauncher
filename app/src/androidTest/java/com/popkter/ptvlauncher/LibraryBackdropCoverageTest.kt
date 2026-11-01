package com.popkter.ptvlauncher

import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.PixelCopy
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class LibraryBackdropCoverageTest {
    @Test fun openingLibraryCoversAllEdgesWithoutWallpaperRefresh() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        assertTrue("Turn on the TV before running window pixel coverage tests",
            instrument.targetContext.getSystemService(android.os.PowerManager::class.java).isInteractive)
        instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        Thread.sleep(1200)
        val activity=launchTestHome()
        lateinit var surface:NativeSurface
        val backdrop=Bitmap.createBitmap(480,270,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(20,180,90))}
        val bounds=Rect()
        instrument.runOnMainSync {
            // Isolate from the Activity's asynchronous wallpaper loader/rotation.
            surface=NativeSurface(activity,LauncherGraph.get(activity),false,{})
            activity.setContentView(surface,ViewGroup.LayoutParams(-1,-1))
            surface.wallpaper=backdrop
            surface.show("apps")
        }
        try {
            // No bitmap reassignment/invalidate after entry: exercise the hardware display list.
            Thread.sleep(450)
            instrument.runOnMainSync {
                val view=surface.findViewWithTag<View>("library:backdrop")
                assertNotNull(view)
                assertTrue("Backdrop dimensions=${view.width}x${view.height}, surface=${surface.width}x${surface.height}, attached=${view.isAttachedToWindow}, activityFinishing=${activity.isFinishing}",view.getGlobalVisibleRect(bounds))
                assertEquals(0f,(view.parent as View).translationY,0.001f)
            }
            val screenshot=Bitmap.createBitmap(activity.window.decorView.width,activity.window.decorView.height,Bitmap.Config.ARGB_8888)
            val copied=CountDownLatch(1)
            var result=-1
            PixelCopy.request(activity.window,screenshot,{result=it;copied.countDown()},Handler(Looper.getMainLooper()))
            assertTrue("Window copy timed out",copied.await(5,TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS,result)
            try {
                for(x in listOf(bounds.left+3,bounds.right-4))for(y in listOf(bounds.top+3,bounds.bottom-4)) {
                    val pixel=screenshot.getPixel(x,y)
                    assertEquals("Uncovered edge at $x,$y",20f,Color.red(pixel).toFloat(),3f)
                    assertEquals(180f,Color.green(pixel).toFloat(),3f)
                    assertEquals(90f,Color.blue(pixel).toFloat(),3f)
                }
            } finally {screenshot.recycle()}
        } finally {
            instrument.runOnMainSync {(surface.parent as? ViewGroup)?.removeView(surface)}
        }
    }
}
