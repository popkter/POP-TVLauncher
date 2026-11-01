package com.popkter.ptvlauncher

import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SurfaceDismissTest {
    @Test fun railEntersFromLeftAndDismissesToLeftOnlyOnce() {
        val instrument=InstrumentationRegistry.getInstrumentation()
        val activity=launchTestHome()
        lateinit var surface:NativeSurface
        lateinit var rail:View
        var dismissCalls=0
        var completions=0
        instrument.runOnMainSync {
            surface=NativeSurface(activity,LauncherGraph.get(activity),true,{dismissCalls++})
            activity.addContentView(surface,ViewGroup.LayoutParams(-1,-1))
            rail=surface.findViewWithTag("rail:scroll")
            assertTrue("Rail must enter from beyond the left edge",rail.translationX<=-activity.dp(AppLayout.RAIL_WIDTH))
        }
        Thread.sleep(50)
        var startAlpha=0f
        var startX=0f
        var previousAlpha=1f
        var previousX=0f
        try {
            instrument.runOnMainSync {
                surface.animateDismiss {completions++}
                startAlpha=rail.alpha
                startX=rail.translationX
                previousX=startX
                previousAlpha=startAlpha
                surface.back()
                surface.animateDismiss {completions++}
            }
            repeat(6) {
                Thread.sleep(40)
                instrument.runOnMainSync {
                    assertTrue(rail.alpha<=previousAlpha+0.001f)
                    assertEquals(1f,surface.alpha,0.001f)
                    assertEquals(0f,surface.translationX,0.001f)
                    assertEquals(0f,surface.translationY,0.001f)
                    assertTrue("Exit must move left",rail.translationX<=previousX+0.001f)
                    previousX=rail.translationX
                    previousAlpha=rail.alpha
                }
            }
            instrument.runOnMainSync {
                assertEquals(0,dismissCalls)
                assertEquals(1,completions)
                assertEquals(0f,rail.alpha,0.001f)
                assertEquals(View.INVISIBLE,surface.visibility)
                assertTrue("Rail must finish offscreen",rail.translationX+rail.right<0)
            }
        } finally {
            instrument.runOnMainSync {(surface.parent as? ViewGroup)?.removeView(surface)}
        }
    }
}
