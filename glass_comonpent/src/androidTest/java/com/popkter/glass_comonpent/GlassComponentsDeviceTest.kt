package com.popkter.glass_comonpent

import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

class GlassTestActivity: Activity() {
    lateinit var root: FrameLayout
    override fun onCreate(saved: Bundle?) { super.onCreate(saved);root=FrameLayout(this);setContentView(root) }
}

@RunWith(AndroidJUnit4::class)
class GlassComponentsDeviceTest {
    private val instrument=InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: GlassTestActivity
    private fun ui(block: ()->Unit)=instrument.runOnMainSync(block)
    @Before fun launch(){activity=instrument.startActivitySync(Intent(instrument.context,GlassTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as GlassTestActivity;instrument.waitForIdleSync()}
    @After fun finish(){ui { activity.finish() }}
    private fun mount(view: View,direction: Int=View.LAYOUT_DIRECTION_LTR){activity.root.removeAllViews();activity.root.layoutDirection=direction;activity.root.addView(view,FrameLayout.LayoutParams(-1,-2))}
    private fun layout(view: View,width: Int,height: Int){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)}
    @Test fun everySizeMirrorsIconAndKeepsDescriptionPolicy(){ui {
        for(size in GlassSize.entries){
            val button=GlassButton(activity).apply { gridSize=size;title="Source";description="HDMI 1";icon=ColorDrawable(Color.WHITE) }
            val w=activity.dp(size.width(button.glassStyle));val h=activity.dp(size.height(button.glassStyle))
            mount(button);layout(button,w,h);val ltr=button.iconView.left
            button.layoutDirection=View.LAYOUT_DIRECTION_RTL;layout(button,w,h)
            assertEquals("Mirrored icon: $size",w-ltr-button.iconView.width,button.iconView.left)
            if(size==GlassSize.X2Y1)assertEquals(View.GONE,button.descriptionView.visibility)
            button.glassStyle=button.glassStyle.copy(iconSize=20f,contentPadding=8f,cornerRadius=12f);layout(button,w,h)
            assertEquals(activity.dp(20f),button.iconView.width)
        }
    }}
    @Test fun sliderMirrorsFillAndRemoteIncrement(){ui {
        for(direction in listOf(View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL)){
            val slider=GlassSlider(activity).apply { progress=25;glassStyle=glassStyle.copy(fillOpacity=0f) }
            mount(slider,direction);layout(slider,400,100)
            val bitmap=Bitmap.createBitmap(400,100,Bitmap.Config.ARGB_8888);slider.draw(Canvas(bitmap))
            val start=bitmap.getPixel(if(direction==View.LAYOUT_DIRECTION_RTL)350 else 50,50)
            val end=bitmap.getPixel(if(direction==View.LAYOUT_DIRECTION_RTL)50 else 350,50)
            assertTrue(Color.alpha(start)>Color.alpha(end));bitmap.recycle()
            val key=if(direction==View.LAYOUT_DIRECTION_RTL)KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            slider.onKeyDown(key,KeyEvent(KeyEvent.ACTION_DOWN,key));assertEquals(30,slider.progress)
        }
    }}
    @Test fun compactButtonDoesNotClipItsIconToSurfacePadding(){ui {
        for(direction in listOf(View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL)){
            val button=GlassButton(activity).apply {gridSize=GlassSize.X1Y1;icon=ColorDrawable(Color.RED);glassStyle=glassStyle.copy(iconSize=22f);iconView.imageTintList=android.content.res.ColorStateList.valueOf(Color.RED)}
            mount(button,direction)
            val size=activity.dp(36f);layout(button,size,size)
            val bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);button.draw(Canvas(bitmap))
            var red=0;for(x in 0 until size)for(y in 0 until size)if(bitmap.getPixel(x,y)==Color.RED)red++
            val icon=activity.dp(22f);assertTrue("Compact button must draw the entire icon",red>=icon*icon*.95);bitmap.recycle()
        }
    }}
    @Test fun inputHeightIsOneUnitAndFocusFillsWholeSurface(){ui {
        for(direction in listOf(View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL)) {
            val field=GlassTextField(activity);mount(field,direction)
            field.measure(View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.AT_MOST))
            field.layout(0,0,field.measuredWidth,field.measuredHeight)
            assertEquals(activity.dp(56f),field.height)
            assertTrue(field.clearButton.bottom+field.paddingBottom<=field.height)
            field.focusInput(false);assertTrue(field.hasFocus())
            val focused=Bitmap.createBitmap(field.width,field.height,Bitmap.Config.ARGB_8888);field.draw(Canvas(focused))
            val pixel=focused.getPixel(field.width/2,activity.dp(3f))
            assertTrue(kotlin.math.abs(Color.red(pixel)-Color.blue(pixel))<=12);assertTrue(Color.alpha(pixel)>=70)
            focused.recycle()
        }
    }}
    @Test fun focusOverlayPreservesIndependentActiveColor(){ui {
        val surface=GlassSurface(activity);mount(surface);layout(surface,300,100)
        fun pixel():Int {val bitmap=Bitmap.createBitmap(300,100,Bitmap.Config.ARGB_8888);surface.draw(Canvas(bitmap));val value=bitmap.getPixel(150,50);bitmap.recycle();return value}
        val normal=pixel()
        surface.focusHighlighted=true;val focused=pixel()
        assertTrue("Focus adds a translucent mask",Color.alpha(focused)>Color.alpha(normal))
        surface.focusHighlighted=false;surface.isSelected=true;val active=pixel()
        assertTrue("Active defaults to iOS blue",Color.blue(active)>Color.red(active)+100)
        surface.focusHighlighted=true;val activeFocused=pixel()
        assertTrue("Focus overlays the active color",Color.red(activeFocused)>Color.red(active))
        assertTrue("Focused active button remains blue",Color.blue(activeFocused)>Color.red(activeFocused)+50)
        surface.glassStyle=surface.glassStyle.copy(activeColor=0xff30d158.toInt());surface.focusHighlighted=false
        val green=pixel();assertTrue("Each control owns its active color",Color.green(green)>Color.red(green)+80)
        val button=GlassButton(activity).apply{toggleOnClick=true;title="Toggle"}
        button.performClick();assertTrue(button.isChecked);button.performClick();assertFalse(button.isChecked)
        val appList=GlassAppListButton(activity);mount(appList);layout(appList,activity.dp(246f),activity.dp(83f));appList.requestFocus()
        val picture=Bitmap.createBitmap(appList.width,appList.height,Bitmap.Config.ARGB_8888);appList.draw(Canvas(picture))
        val fill=picture.getPixel(activity.dp(28f),activity.dp(39f));assertEquals(Color.red(fill),Color.green(fill));assertEquals(Color.green(fill),Color.blue(fill));picture.recycle()
    }}
    @Test fun iconMenuHasThreeSquareButtonsOnOneRowWithOwnBackground(){
        for(direction in listOf(View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL)) {
            lateinit var menu:GlassContextMenu
            ui {
                val anchor=GlassButton(activity).apply{title="App";layoutDirection=direction};mount(anchor,direction)
                menu=GlassContextMenu.create(anchor,listOf("pin","sort","info").map{GlassMenuItem(it,it,ColorDrawable(Color.WHITE))}){}
                menu.layoutMode=GlassMenuLayout.ICON_ROW;menu.show()
            }
            instrument.waitForIdleSync();SystemClock.sleep(220)
            ui {
                val popup=GlassContextMenu::class.java.getDeclaredField("popup").apply{isAccessible=true}.get(menu) as android.widget.PopupWindow
                val scroll=popup.contentView as android.widget.ScrollView
                val panel=scroll.getChildAt(0) as GlassSurface
                assertTrue(panel.glassStyle.fillOpacity in .4f.. .65f)
                val row=panel.getChildAt(0) as android.widget.LinearLayout
                assertEquals(3,row.childCount);assertEquals(android.widget.LinearLayout.HORIZONTAL,row.orientation)
                for(i in 0 until 3) {val button=row.getChildAt(i) as GlassButton;assertEquals(GlassSize.X1Y1,button.gridSize);assertTrue(button.iconOnly);assertEquals(activity.dp(56f),button.measuredWidth);assertEquals(button.measuredWidth,button.measuredHeight);assertTrue(Color.alpha(button.glassStyle.focusOverlayColor)>=100)}
                assertEquals(row.getChildAt(0).top,row.getChildAt(2).top)
                val first=row.getChildAt(0);first.requestFocus()
                val key=if(direction==View.LAYOUT_DIRECTION_RTL)KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                first.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,key));assertTrue("Horizontal navigation must select second button in direction $direction",row.getChildAt(1).hasFocus())
                menu.close()
            }
        }
    }
    @Test fun listRefreshRetainsFocusedKeyAndSelectionIsIndependentOfPosition(){
        lateinit var list: GlassListView
        ui { list=GlassListView(activity);mount(list);list.submitItems(listOf(GlassListItem("a","A"),GlassListItem("b","B")));list.findItemView("b")!!.requestFocus() }
        instrument.waitForIdleSync()
        ui { list.submitItems(listOf(GlassListItem("b","B updated"),GlassListItem("c","C"))) }
        instrument.waitForIdleSync()
        ui { assertTrue(list.findItemView("b")!!.hasFocus());assertEquals("B updated",list.findItemView("b")!!.titleView.text) }
    }
    @Test fun allCompoundControlsConstructAndInheritBothDirections(){ui {
        for(direction in listOf(View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL)){
            val views=listOf(GlassSwitch(activity),GlassTextField(activity),GlassStepper(activity),GlassSelector(activity),GlassTabs(activity),GlassSegmentedControl(activity),GlassNotice(activity),GlassConfirmation(activity),GlassStateView(activity),GlassGalleryView(activity))
            views.forEach { view->mount(view,direction);layout(view,900,600);assertEquals(direction,view.layoutDirection);if(view is GlassSurface)view.glassStyle=view.glassStyle.copy(contentPadding=12f);view.draw(Canvas(Bitmap.createBitmap(900,600,Bitmap.Config.ARGB_8888))) }
            val selector=GlassSelector(activity).apply { submitOptions(listOf(GlassOption("a","A"),GlassOption("b","B")),setOf("a"));select("b") };assertEquals(setOf("b"),selector.selectedKeys)
            val toggle=GlassSwitch(activity);toggle.performClick();assertTrue(toggle.isChecked)
            val field=GlassTextField(activity);mount(field,direction);field.editText.requestFocus();assertTrue(field.hasFocus());assertTrue(field.highlightDescendantFocus)
        }
    }}
    @Test fun tabsUseLogicalDirectionAndSkipDisabledOptions(){ui {
        for(direction in listOf(View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL)){
            val tabs=GlassTabs(activity);mount(tabs,direction)
            tabs.submitTabs(listOf(GlassOption("a","A"),GlassOption("disabled","Disabled",enabled=false),GlassOption("b","B")),"a")
            layout(tabs,800,200);tabs.findFocus()
            val strip=tabs.getChildAt(0) as android.view.ViewGroup;strip.getChildAt(0).requestFocus()
            val key=if(direction==View.LAYOUT_DIRECTION_RTL)KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            tabs.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,key));assertEquals("b",tabs.selectedKey)
        }
    }}
    @Test fun menuAnimatesAndReturnsFocusInBothDirections(){
        for(direction in listOf(View.LAYOUT_DIRECTION_LTR,View.LAYOUT_DIRECTION_RTL)){
            lateinit var anchor: GlassButton;lateinit var menu: GlassContextMenu
            ui { anchor=GlassButton(activity).apply { title="Anchor" };mount(anchor,direction);anchor.requestFocus();menu=GlassContextMenu.attach(anchor,listOf(GlassMenuItem("one","One"))){ };menu.show();assertTrue(menu.isShowing) }
            SystemClock.sleep(220)
            ui { menu.dismiss();assertTrue(menu.isShowing) }
            SystemClock.sleep(200);instrument.waitForIdleSync()
            ui { assertFalse(menu.isShowing);assertTrue(anchor.hasFocus());menu.close() }
        }
    }
}
