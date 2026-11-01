// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.app.UiAutomation
import android.content.Intent
import android.view.*
import android.widget.GridView
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppRailLibraryTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private lateinit var activity:MainActivity
    private lateinit var rail:NativeSurface
    private lateinit var apps:List<AppEntry>
    private lateinit var prefs:HomePreferences
    private var dismissCalls=0
    @Before fun open() = runBlocking {
        instrument.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        activity=launchTestHome()
        val graph=LauncherGraph.get(activity)
        apps=withTimeout(10000){graph.apps.entries.first{it.count{app->app.launchable}>=10}}
        prefs=withTimeout(5000){graph.store.state.first{it.initialized}}
        instrument.runOnMainSync {
            activity.surface.visibility=View.GONE
            rail=NativeSurface(activity,graph,true,{dismissCalls++})
            rail.update(apps,prefs.copy(pins=apps.filter{it.launchable}.take(2).map{it.id}))
            activity.addContentView(rail,ViewGroup.LayoutParams(-1,-1))
        }
        instrument.waitForIdleSync()
    }
    @After fun close() {
        instrument.runOnMainSync {
            (rail.parent as? ViewGroup)?.removeView(rail)
            activity.surface.visibility=View.VISIBLE
        }
    }
    private fun key(code:Int) {
        if(code==KeyEvent.KEYCODE_BACK) {
            // This fixture hosts the floating surface inside an Activity; its desktop BACK
            // callback belongs to a different surface. Route this one key to the tested window.
            instrument.runOnMainSync {
                rail.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code))
                rail.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code))
            }
        } else {
            // Use the actual ViewRoot input stage for directional focus navigation.
            instrument.sendKeyDownUpSync(code)
        }
        instrument.waitForIdleSync()
    }
    @Test fun cardSizeIsIdenticalForTwoAndTenPinnedApps() {
        var twoHeight=0
        instrument.runOnMainSync {
            rail.firstRail()
            twoHeight=rail.findFocus().height
            assertEquals(activity.dp(AppLayout.RAIL_ROW_HEIGHT),twoHeight)
            rail.update(apps,prefs.copy(pins=apps.filter{it.launchable}.take(10).map{it.id}))
        }
        instrument.waitForIdleSync()
        instrument.runOnMainSync {
            rail.firstRail()
            assertEquals(twoHeight,rail.findFocus().height)
            assertEquals(activity.dp(AppLayout.RAIL_ROW_HEIGHT),rail.findFocus().layoutParams.height)
            assertTrue(rail.findViewWithTag<ScrollView>("rail:scroll").canScrollVertically(1))
        }
    }
    @Test fun scrollToAppListOpenSheetAndReturnToTheSameButton() {
        instrument.runOnMainSync {
            rail.update(apps,prefs.copy(pins=apps.filter{it.launchable}.take(10).map{it.id}))
        }
        instrument.waitForIdleSync()
        instrument.runOnMainSync {rail.firstRail()}
        repeat(10){key(KeyEvent.KEYCODE_DPAD_DOWN)}
        android.os.SystemClock.sleep(350) // Native ScrollView settles its focus-scroll animation.
        instrument.runOnMainSync {
            assertEquals("rail:app-list",rail.findFocus().tag)
            assertTrue(rail.findViewWithTag<ScrollView>("rail:scroll").scrollY>0)
            val visible=android.graphics.Rect()
            assertTrue(rail.findFocus().getGlobalVisibleRect(visible))
            val button=rail.findFocus()
            // The focus animation scales the View: compare the painted card/halo, not its
            // unscaled touch row (which includes extra empty space below the artwork).
            val artwork=android.graphics.RectF(activity.dp(4).toFloat(),activity.dp(4).toFloat(),
                activity.dp(8+AppLayout.RAIL_CARD_WIDTH+4).toFloat(),
                activity.dp(8+AppLayout.RAIL_CARD_HEIGHT+4).toFloat())
            button.matrix.mapRect(artwork)
            val parent=button.parent as View
            val origin=IntArray(2)
            parent.getLocationOnScreen(origin)
            artwork.offset((origin[0]+button.left-parent.scrollX).toFloat(),
                (origin[1]+button.top-parent.scrollY).toFloat())
            assertTrue(visible.left<=artwork.left+1 && visible.top<=artwork.top+1)
            assertTrue(visible.right>=artwork.right-1 && visible.bottom>=artwork.bottom-1)
        }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        instrument.runOnMainSync {
            assertEquals("apps",rail.mode)
            val content=rail.findViewWithTag<View>("library:content")
            assertNull(content.background)
            assertEquals(0f,content.elevation)
            assertNull(rail.findViewWithTag<View>("library:backdrop"))
            val grid=rail.findViewWithTag<GridView>("library")
            assertEquals(AppLayout.LIBRARY_COLUMNS,grid.numColumns)
            assertTrue(grid.hasFocus())
        }
        key(KeyEvent.KEYCODE_BACK)
        instrument.runOnMainSync {
            assertNull(rail.mode)
            assertEquals("rail:app-list",rail.findFocus().tag)
            assertEquals(0,dismissCalls)
        }
    }
    @Test fun everyFocusedRowFitsAtBothEdgesDuringRoundTrip() {
        val entries=apps.filter{it.launchable}.take(10)
        instrument.runOnMainSync {rail.update(apps,prefs.copy(pins=entries.map{it.id}))}
        instrument.waitForIdleSync()
        instrument.runOnMainSync {rail.firstRail()}
        fun assertVisible(index:Int) {
            instrument.runOnMainSync {
                val card=rail.findFocus()
                assertEquals(if(index==10)"rail:app-list" else "app:"+entries[index].id,card.tag)
                val scroll=rail.findViewWithTag<ScrollView>("rail:scroll")
                val cardPosition=IntArray(2)
                (card.parent as View).getLocationOnScreen(cardPosition)
                val viewport=IntArray(2);scroll.getLocationOnScreen(viewport)
                // Assert the final expanded halo even before its 140ms animation ends.
                val pivot=activity.dp(8+AppLayout.RAIL_CARD_HEIGHT/2f).toFloat()
                val top=cardPosition[1]+card.top+pivot+(activity.dp(3.5f)-pivot)*1.065f
                val bottom=cardPosition[1]+card.top+pivot+(activity.dp(8+AppLayout.RAIL_CARD_HEIGHT+4.5f)-pivot)*1.065f
                assertTrue("row $index top $top clipped at ${viewport[1]+scroll.paddingTop}",top>=viewport[1]+scroll.paddingTop)
                assertTrue("row $index bottom $bottom clipped",bottom<=viewport[1]+scroll.height-scroll.paddingBottom)
            }
        }
        assertVisible(0)
        for(index in 1..10) {key(KeyEvent.KEYCODE_DPAD_DOWN);assertVisible(index)}
        for(index in 9 downTo 0) {key(KeyEvent.KEYCODE_DPAD_UP);assertVisible(index)}
    }
    @Test fun emptyRailStillHasAnAppListAction() {
        instrument.runOnMainSync {rail.update(apps,prefs.copy(pins=emptyList()))}
        instrument.waitForIdleSync()
        instrument.runOnMainSync {rail.firstRail();assertEquals("rail:app-list",rail.findFocus().tag)}
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        instrument.runOnMainSync {assertEquals("apps",rail.mode)}
    }
    @Test fun managingPinsPreservesOffscreenSelectionAndVisibleChildren() {
        val entries=(0 until 35).map { AppEntry("preview.$it","App $it",null,true) }
        instrument.runOnMainSync {
            rail.update(entries,prefs.copy(pins=emptyList()))
            rail.show("apps")
            rail.findViewWithTag<View>("library:manage").performClick()
        }
        instrument.waitForIdleSync()
        instrument.runOnMainSync {rail.findViewWithTag<GridView>("library").setSelection(25)}
        instrument.waitForIdleSync()
        var original:GridView?=null
        var first:View?=null
        var top=0
        instrument.runOnMainSync {
            val grid=rail.findViewWithTag<GridView>("library")
            assertTrue(grid.firstVisiblePosition>0)
            original=grid;first=grid.getChildAt(0);top=first!!.top
            rail.update(entries,prefs.copy(pins=listOf(entries[25].id)))
        }
        instrument.waitForIdleSync()
        instrument.runOnMainSync {
            val grid=rail.findViewWithTag<GridView>("library")
            assertSame(original,grid)
            assertSame(first,grid.getChildAt(0))
            assertEquals(top,grid.getChildAt(0).top)
            assertEquals(25,grid.selectedItemPosition)
            assertTrue(grid.hasFocus())
            assertTrue((grid.selectedView as AppCardView).pinned)
            rail.update(entries,prefs.copy(pins=emptyList()))
        }
        instrument.waitForIdleSync()
        instrument.runOnMainSync {
            val grid=rail.findViewWithTag<GridView>("library")
            assertSame(original,grid)
            assertSame(first,grid.getChildAt(0))
            assertEquals(top,grid.getChildAt(0).top)
            assertEquals(25,grid.selectedItemPosition)
            assertFalse((grid.selectedView as AppCardView).pinned)
        }
    }
    @Test fun libraryShowsPartialNextRowAndCanReachTheEnd() {
        val entries=(0 until 35).map { AppEntry("preview.$it","App $it",null,true) }
        instrument.runOnMainSync {
            rail.update(entries,prefs.copy(pins=emptyList()))
            rail.show("apps")
        }
        instrument.waitForIdleSync()
        instrument.runOnMainSync {
            val grid=rail.findViewWithTag<GridView>("library")
            assertTrue(grid.isVerticalFadingEdgeEnabled)
            assertTrue(grid.canScrollVertically(1))
            val last=grid.getChildAt(grid.childCount-1)
            val edge=grid.height-grid.paddingBottom
            assertTrue("Next row should peek into the viewport",last.top<edge)
            assertTrue("Next row should be partially visible",last.bottom>edge)
        }
        repeat(6){key(KeyEvent.KEYCODE_DPAD_DOWN)}
        instrument.runOnMainSync {
            val grid=rail.findViewWithTag<GridView>("library")
            assertEquals(30,grid.selectedItemPosition)
            assertFalse(grid.canScrollVertically(1))
            assertTrue(grid.selectedView.bottom<=grid.height-grid.paddingBottom)
        }
    }
    @Test fun libraryFiltersCanBeReachedWithDirectionsAndReturnToRail() {
        instrument.runOnMainSync {rail.firstRail()}
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        instrument.runOnMainSync {
            assertEquals(apps.count{it.launchable},rail.findViewWithTag<GridView>("library").count)
        }
        key(KeyEvent.KEYCODE_DPAD_UP)
        instrument.runOnMainSync {assertEquals("library:all",rail.findFocus().tag)}
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        instrument.runOnMainSync {assertEquals("library:pinned",rail.findFocus().tag)}
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        instrument.runOnMainSync {
            assertEquals(2,rail.findViewWithTag<GridView>("library").count)
            assertTrue(rail.findViewWithTag<GridView>("library").hasFocus())
        }
        key(KeyEvent.KEYCODE_BACK)
        instrument.runOnMainSync {assertNull(rail.mode);assertTrue(rail.findFocus() is AppCardView)}
    }
}
