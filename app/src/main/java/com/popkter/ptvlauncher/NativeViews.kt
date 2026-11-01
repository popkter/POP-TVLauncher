// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher
import com.popkter.glass_comonpent.*

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.view.*
import android.widget.*
import kotlin.math.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal fun Context.dp(value: Float) = (value * resources.displayMetrics.density).roundToInt()
internal fun Context.dp(value: Int) = dp(value.toFloat())
internal const val WHITE = -0x1
internal val SECONDARY = Color.rgb(204, 209, 216)
internal fun label(context: Context, value: String, size: Float = 12f, secondary: Boolean = false) =
    GlassTextView(context).apply {
        text = value; textSize = size; setTextColor(if (secondary) SECONDARY else WHITE)
        fontFeatureSettings = "tnum"; includeFontPadding = false
    }

/** Original, inexpensive TV artwork; bitmap decoding and downsampling happen off the UI thread. */
internal class WallpaperView(context: Context) : View(context) {
    var bitmap: Bitmap? = null
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap
        if (b != null) {
            val scale = max(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = b.width * scale; val h = b.height * scale
            canvas.drawBitmap(b, null, RectF((width-w)/2, (height-h)/2, (width+w)/2, (height+h)/2), paint)
        } else {
            paint.shader = LinearGradient(0f,0f,0f,height.toFloat(),intArrayOf(0xff355d72.toInt(),0xffdbbfa4.toInt(),0xff15383f.toInt()),null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint); paint.shader = null
            val colors = intArrayOf(0xff75918e.toInt(),0xff486d70.toInt(),0xff294c54.toInt(),0xff15383f.toInt())
            for (layer in 0..3) {
                val path = Path().apply {
                    moveTo(0f,height.toFloat())
                    for (i in 0..70) {
                        val x=i/70f
                        val y=.48f+layer*.16f-(sin((x+layer*.18f)*5.4)*.5+.5).toFloat()*.19f-sin(x*21+layer*2.5).toFloat()*.024f
                        lineTo(x*width,y*height)
                    }
                    lineTo(width.toFloat(),height.toFloat()); close()
                }
                paint.color=colors[layer]; canvas.drawPath(path,paint)
            }
        }
        paint.shader = LinearGradient(0f,0f,0f,height.toFloat(),intArrayOf(0x25000000,0x00000000,0x45000000),null,Shader.TileMode.CLAMP)
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint); paint.shader=null
    }
}

/** A single shared native hierarchy is used on HOME and in accessibility overlay windows. */
internal class NativeSurface(
    context: Context,
    private val graph: LauncherGraph,
    private val floating: Boolean,
    private val dismiss: () -> Unit,
    private val changed: () -> Unit = {},
) : FrameLayout(context) {
    var mode: String? = null; private set
    var wallpaper: Bitmap? = null
        set(value) {
            field=value
            panels.findViewWithTag<PanelBackdrop>("library:backdrop")?.bitmap=value ?: fallbackWallpaper
        }
    private val fallbackWallpaper by lazy {
        Bitmap.createBitmap(480,270,Bitmap.Config.ARGB_8888).also { bitmap ->
            WallpaperView(context).apply {layout(0,0,480,270);draw(Canvas(bitmap))}
        }.let { BackdropBlur.create(it) }
    }
    private var apps=emptyList<AppEntry>()
    private var prefs=HomePreferences()
    private val rail=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; clipChildren=false; clipToPadding=false }
    private val railScroll=RailScrollView(context).apply {
        tag="rail:scroll";isVerticalScrollBarEnabled=false;overScrollMode=OVER_SCROLL_NEVER
        isSmoothScrollingEnabled=false;clipToPadding=true;clipChildren=true
        setPadding(context.dp(8),context.dp(AppLayout.RAIL_SCROLL_INSET),context.dp(8),context.dp(12))
        addView(rail,LayoutParams(-1,-2))
    }
    private var panels=FrameLayout(context).apply { clipChildren=false; clipToPadding=false }
    private var outgoing:View?=null
    private var dismissing=false
    // Include the rail's left inset and focus halo so the whole rail clears the screen.
    private fun railOffscreenX()=(if(layoutDirection==View.LAYOUT_DIRECTION_RTL)1 else -1)*context.dp(20+AppLayout.RAIL_WIDTH+16).toFloat()
    private fun enter(view:View, horizontal:Boolean=false) {
        if(view===panels && panels.findViewWithTag<View>("library:backdrop")!=null) {
            // The blur must cover the window throughout entry, independently of content motion.
            panels.animate().cancel()
            panels.alpha=1f;panels.translationX=0f;panels.translationY=0f
            panels.findViewWithTag<View>("library:content")?.let {enter(it)}
            return
        }
        view.animate().cancel()
        view.alpha=0f
        view.translationX=if(horizontal)railOffscreenX() else 0f
        view.translationY=if(horizontal)0f else context.dp(12).toFloat()
        view.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(240)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }
    fun animateDismiss(done:()->Unit) {
        if(dismissing)return
        dismissing=true
        // Freeze the entry/focus animations at their current presentation. Otherwise
        // an untouched rail can still brighten or move while its window fades out.
        fun freeze(view:View) {
            view.animate().cancel()
            view.clearAnimation()
            if(view is ViewGroup)for(index in 0 until view.childCount)freeze(view.getChildAt(index))
        }
        freeze(this)
        // Animate content, not the WindowManager root. Keep the window geometry stable
        // until its last frame is hidden, so detach cannot replay a root transform.
        val content=if(mode==null)railScroll else panels
        content.animate().alpha(0f).translationX(if(mode==null)railOffscreenX() else content.translationX).setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction {
                visibility=INVISIBLE
                done()
            }.start()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow();GlassMessages.register(this)
        if(floating)enter(if(mode==null)railScroll else panels,mode==null)
    }
    override fun onDetachedFromWindow(){closeActions(immediate=true,restore=false);GlassMessages.unregister(this);super.onDetachedFromWindow()}
    private var restoreTag: String?=null
    private var editing: String?=null
    private var actionMenu:AppIconMenu?=null
    private var actionAnchor:AppCardView?=null
    private var librarySelection=0
    private var libraryMode:String?=null
    private var libraryPinnedOnly=false
    private var libraryManaging=false
    private var refreshLibraryPins:(()->Unit)?=null
    private var controlCenter:ControlCenterView?=null
    init {
        clipChildren=false;clipToPadding=false
        val heightDp=context.resources.displayMetrics.heightPixels/context.resources.displayMetrics.density
        val visibleRows=((heightDp-AppLayout.RAIL_TOP-24-20)/AppLayout.RAIL_ROW_HEIGHT).toInt().coerceAtLeast(1)
        val viewportHeight=visibleRows*AppLayout.RAIL_ROW_HEIGHT+20
        addView(railScroll,LayoutParams(context.dp(AppLayout.RAIL_WIDTH),context.dp(viewportHeight),Gravity.START or Gravity.TOP).apply {
            marginStart=context.dp(20);topMargin=context.dp(AppLayout.RAIL_TOP)
        })
        addView(panels,LayoutParams(-1,-1))
    }
    fun update(entries: List<AppEntry>, settings: HomePreferences) {
        val entriesChanged=apps!=entries
        val pinsChanged=prefs.pins!=settings.pins
        val railChanged=rail.childCount==0 || pinsChanged ||
            homeApps(apps,prefs.pins)!=homeApps(entries,settings.pins)
        val initializing=(!prefs.initialized && settings.initialized) ||
            (apps.none{it.launchable} && entries.any{it.launchable})
        apps=entries;prefs=settings
        controlCenter?.refresh()
        if(railChanged) {
            closeActions(immediate=true)
            val focus=if(initializing)null else findFocus()?.tag?.toString()
            buildRail()
            if(mode==null)post { focus?.let { findViewWithTag<View>(it)?.requestFocus() } ?: firstRail() }
        }
        if(entriesChanged || pinsChanged) {
            if(mode in listOf("apps","customize","components")) {
                val refresh=refreshLibraryPins
                if(!entriesChanged && refresh!=null)refresh() else show(mode)
            }
        }
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        // Also handle direct events in the window (ADB injection can bypass the global filter).
        if(dismissing)return true
        if(floating && event.keyCode==KeyEvent.KEYCODE_BACK) {
            if(event.action==KeyEvent.ACTION_UP && !event.isCanceled) {
                back()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    fun firstRail() { railScroll.scrollTo(0,0);rail.getChildAt(0)?.requestFocus() }
    private fun homeApps(entries: List<AppEntry>, pins: List<String>) =
        pins.take(PinOrder.MAX_PINS).mapNotNull {id -> entries.find {it.id==id && it.launchable}}
    private fun buildRail() {
        rail.removeAllViews()
        val pinned=homeApps(apps,prefs.pins)
        pinned.forEachIndexed { index,app ->
            val card=AppCardView(context).apply {
                bind(app,false,true)
                setOnClickListener { if(editing!=null) { editing=null;changed() } else launch(app) }
                setOnLongClickListener { manage(app,this);true }
                setOnKeyListener { _,code,event ->
                    if(event.action!=KeyEvent.ACTION_DOWN) false
                    else if(code==KeyEvent.KEYCODE_DPAD_LEFT) { show("apps");true }
                    else if(editing==app.id && code in listOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)) {
                        graph.scope.launchSafely { graph.store.move(app.id,if(code==KeyEvent.KEYCODE_DPAD_UP)-1 else 1) };true
                    } else if(index==0 && code==KeyEvent.KEYCODE_DPAD_UP) {
                        // Both HOME and the floating rail stop here, including key repeats.
                        true
                    }
                    else false
                }
            }
            rail.addView(card,LinearLayout.LayoutParams(-1,context.dp(AppLayout.RAIL_ROW_HEIGHT)))
        }
        rail.addView(AppListButton(context).apply {
            setOnClickListener{show("apps")}
            setOnKeyListener{_,code,event->
                when {
                    event.action!=KeyEvent.ACTION_DOWN->false
                    code==KeyEvent.KEYCODE_DPAD_LEFT->{show("apps");true}
                    code==KeyEvent.KEYCODE_DPAD_DOWN->true
                    pinned.isEmpty() && code==KeyEvent.KEYCODE_DPAD_UP->true
                    else->false
                }
            }
        },LinearLayout.LayoutParams(-1,context.dp(AppLayout.RAIL_ROW_HEIGHT)))
    }
    private fun launch(app: AppEntry) {
        if(!app.launchable) { SystemLinks.appInfo(context,app.id);return }
        if(graph.apps.launch(app.id)) { if(floating)dismiss() else show(null) }
    }
    fun back() {
        if(dismissing)return
        controlCenter?.let {it.back();return}
        if(actionMenu!=null) {closeActions();return}
        if(mode=="apps" && libraryManaging) {libraryManaging=false;show("apps");return}
        if(editing!=null) { editing=null;changed();return }
        when(mode) { "licenses","default_home"->show("settings");"settings"->show(null);"apps","customize","components"->show(null);null->if(floating)dismiss() else firstRail();else->if(floating)dismiss() else show(null) }
    }
    fun refreshStatus() { controlCenter?.let {it.refresh();return};if(mode in listOf("settings","default_home"))show(mode) }
    private fun openControlCenter() {
        controlCenter?.let {it.refresh();return}
        closeActions(immediate=true,restore=false)
        val button=rootView.findViewWithTag<View>("桌面设置按钮")
        val origin=IntArray(2);getLocationOnScreen(origin)
        val location=IntArray(2);button?.getLocationOnScreen(location)
        val anchor=if(button!=null)RectF((location[0]-origin[0]).toFloat(),(location[1]-origin[1]).toFloat(),
            (location[0]-origin[0]+button.width).toFloat(),(location[1]-origin[1]+button.height).toFloat())
            else RectF((width-context.dp(AppLayout.HOME_RIGHT_MARGIN+36)).toFloat(),context.dp(20).toFloat(),(width-context.dp(AppLayout.HOME_RIGHT_MARGIN)).toFloat(),context.dp(56).toFloat())
        // Capture before changing visibility: clock, wallpaper and cards remain the same desktop.
        val snapshot=if(!floating&&rootView.width>0&&rootView.height>0)runCatching {
            val w=480;val h=(rootView.height*w.toFloat()/rootView.width).roundToInt().coerceAtLeast(1)
            Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also {b->val canvas=Canvas(b);canvas.scale(w.toFloat()/rootView.width,h.toFloat()/rootView.height);rootView.draw(canvas)}
        }.getOrNull() else null
        outgoing?.let{it.animate().cancel();removeView(it)};outgoing=null
        panels.animate().cancel();panels.removeAllViews();panels.alpha=1f;panels.translationX=0f;panels.translationY=0f
        railScroll.animate().cancel();railScroll.alpha=1f;railScroll.translationX=0f
        railScroll.visibility=VISIBLE;railScroll.descendantFocusability=FOCUS_BLOCK_DESCENDANTS
        mode="settings"
        val center=ControlCenterView(context,graph,anchor,{
            val current=controlCenter;controlCenter=null;current?.dispose();panels.removeAllViews();mode=null
            railScroll.descendantFocusability=FOCUS_AFTER_DESCENDANTS
            changed();button?.requestFocus()?:firstRail()
        },{show("components")})
        controlCenter=center;panels.addView(center,LayoutParams(-1,-1));changed()
        center.prepare(snapshot,wallpaper?:fallbackWallpaper)
    }
    fun show(kind: String?) {
        if(dismissing)return
        if(kind=="settings") {openControlCenter();return}
        controlCenter?.let {
            if(kind==null){it.closeToDesktop();return}
            it.dispose();controlCenter=null;panels.removeAllViews()
        }
        closeActions(immediate=true,restore=false)
        if(mode==null)restoreTag=findFocus()?.tag?.toString()
        if(mode in listOf("apps","customize","components")) {
            val grid=panels.findViewWithTag<GridView>("library")
            if(grid!=null) {librarySelection=grid.selectedItemPosition.coerceAtLeast(0);libraryMode=mode}
        }
        val previous=mode
        refreshLibraryPins=null
        val animateChange=isAttachedToWindow && previous!=kind
        outgoing?.let { it.animate().cancel();removeView(it) };outgoing=null
        if(animateChange && panels.childCount>0) {
            val old=panels
            old.clearFocus();old.descendantFocusability=FOCUS_BLOCK_DESCENDANTS
            old.setOnTouchListener { _,_->true }
            outgoing=old
            old.animate().cancel()
            old.animate().alpha(0f).translationY(context.dp(8).toFloat()).setDuration(170)
                .withEndAction { removeView(old);if(outgoing===old)outgoing=null }.start()
            panels=FrameLayout(context).apply {clipChildren=false;clipToPadding=false}
            addView(panels,LayoutParams(-1,-1))
        } else {panels.animate().cancel();panels.removeAllViews();panels.alpha=1f;panels.translationX=0f;panels.translationY=0f}
        mode=kind
        if(kind!="apps")libraryManaging=false
        railScroll.animate().cancel()
        railScroll.descendantFocusability=if(kind==null)FOCUS_AFTER_DESCENDANTS else FOCUS_BLOCK_DESCENDANTS
        if(animateChange && previous==null && kind!=null) {
            railScroll.animate().alpha(0f).translationX(railOffscreenX()).setDuration(200)
                .withEndAction {if(mode!=null)railScroll.visibility=GONE}.start()
        } else railScroll.visibility=if(kind==null)VISIBLE else GONE
        when(kind) {
            "apps","customize","components"->buildLibrary(kind)
            "settings","licenses","default_home"->buildPanel(kind)
            null->post { restoreTag?.let { findViewWithTag<View>(it)?.requestFocus() } ?: firstRail() }
        }
        if(animateChange)enter(if(kind==null)railScroll else panels,kind==null)
        changed()
    }
    private fun toggle(app: AppEntry) {
        graph.scope.launchSafely {
            if(!graph.store.toggle(app.id)) com.popkter.glass_comonpent.GlassMessages.show(context,"最多显示 10 个应用，请先移除一个")
        }
    }
    private fun closeActions(immediate:Boolean=false,restore:Boolean=true,after:()->Unit={}) {
        val menu=actionMenu
        if(menu==null){after();return}
        menu.restoreFocusOnDismiss=restore
        if(immediate){menu.close();actionMenu=null;actionAnchor?.actionsExpanded=false;actionAnchor=null;after()}
        else menu.dismiss {after()}
    }
    private fun manage(app:AppEntry,anchor:View?) {
        val card=anchor as? AppCardView ?: return
        closeActions(immediate=true,restore=false);editing=null
        actionAnchor=card;card.actionsExpanded=true
        val pinned=app.id in prefs.pins
        val menu=AppIconMenu(card,listOf(
            GlassMenuItem(if(pinned)"remove" else "add",if(pinned)"从桌面移除" else "添加到桌面",context.getDrawable(if(pinned)R.drawable.f7_minus_circle else R.drawable.f7_plus_circle),app.launchable),
            GlassMenuItem("edit","编辑排序",context.getDrawable(R.drawable.f7_line_horizontal_3),pinned && app.launchable),
            GlassMenuItem("info","应用信息",context.getDrawable(R.drawable.f7_info_circle))
        )) {action->when(action.key) {
            "add","remove"->toggle(app)
            "edit"->{editing=app.id;show(null);post {findViewWithTag<View>("app:"+app.id)?.requestFocus()};GlassMessages.show(context,"上下调整位置，确定或返回完成")}
            "info"->{if(floating)dismiss();SystemLinks.appInfo(context,app.id)}
        }}
        menu.returnFocusTo=if(card.library)panels.findViewWithTag<View>("library") ?: card else card
        menu.onDismiss={card.actionsExpanded=false;if(actionMenu===menu){actionMenu=null;actionAnchor=null}}
        actionMenu=menu;menu.show()
    }
    private fun buildLibrary(kind:String) {
        val managing=kind=="customize" || (kind=="apps" && libraryManaging)
        val launchable=apps.filter{it.launchable}
        var items=when {
            kind=="components"->apps.filter{!it.launchable}
            kind=="apps" && libraryPinnedOnly && !managing->prefs.pins.mapNotNull{id->launchable.find{it.id==id}}
            else->launchable
        }
        // The library has no sheet surface, edge, tint or shadow. Only the wallpaper
        // behind its content is blurred, across the entire HOME window.
        if(!floating || wallpaper!=null)panels.addView(PanelBackdrop(context,wallpaper ?: fallbackWallpaper,0).apply {
            tag="library:backdrop"
        },LayoutParams(-1,-1))
        val frame=FrameLayout(context).apply {tag="library:content"}
        val widthDp=min(844,(context.resources.displayMetrics.widthPixels/context.resources.displayMetrics.density).toInt()-48)
        panels.addView(frame,LayoutParams(context.dp(widthDp),-1,Gravity.CENTER).apply {
            topMargin=context.dp(24);bottomMargin=context.dp(24)
        })
        val box=LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(context.dp(30),context.dp(12),context.dp(30),context.dp(12))
        }
        frame.addView(box,LayoutParams(-1,-1))
        val header=LinearLayout(context).apply {gravity=Gravity.CENTER_VERTICAL}
        header.addView(AppListGlyph(context),LinearLayout.LayoutParams(context.dp(22),context.dp(22)).apply {marginEnd=context.dp(13)})
        header.addView(label(context,when(kind){"customize"->"自定义主屏幕";"components"->"系统组件";else->"App List"},26f).apply {
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);letterSpacing=-.025f
        },LinearLayout.LayoutParams(0,-2,1f))
        val appCount=label(context,if(managing)"已添加 ${prefs.pins.size} / 10" else items.size.toString()+" 个 App",11f,true)
        header.addView(appCount,LinearLayout.LayoutParams(-2,-2).apply {marginEnd=context.dp(18)})
        var manageButton:GlassButton?=null
        if(kind=="apps") {
            manageButton=GlassTextButton(context).apply {
                title=if(managing)"完成" else "管理桌面应用";tag="library:manage";contentLayout=GlassContentLayout.ICON_TEXT
                glassStyle=glassStyle.copy(contentPadding=8f,cornerRadius=20f);titleView.textSize=12f
                setOnClickListener{libraryManaging=!libraryManaging;show("apps")}
            }
            header.addView(manageButton,LinearLayout.LayoutParams(context.dp(112),context.dp(34)).apply {marginEnd=context.dp(12)})
        }
        val close=closeControl(context,"收起 AppList") {
            if(kind=="apps" && libraryManaging) {libraryManaging=false;show("apps")} else show(null)
        }.apply{tag="library:close"}
        header.addView(close,LinearLayout.LayoutParams(context.dp(44),context.dp(44)))
        box.addView(header,LinearLayout.LayoutParams(-1,context.dp(44)))
        val alignedRows=mutableListOf<View>(header)
        var refreshFilter:()->Unit={}
        var filterFocus:View=manageButton ?: close
        if(kind=="apps" && !managing) {
            val tabs=GlassSegmentedControl(context).apply {
                tag="library:tabs";glassStyle=glassStyle.copy(contentPadding=7f,cornerRadius=16f)
                submitTabs(listOf(GlassOption("all","所有 App"),GlassOption("pinned","主屏幕")),if(libraryPinnedOnly)"pinned" else "all")
            }
            val tabRow=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
            tabRow.addView(tabs,LinearLayout.LayoutParams(-2,context.dp(40)))
            alignedRows.add(tabRow)
            val all=tabs.tabView("all")!!;val pinned=tabs.tabView("pinned")!!
            listOf(all,pinned).forEach { tab->
                tab.titleView.textSize=11f
                tab.layoutParams=(tab.layoutParams as LinearLayout.LayoutParams).apply{width=context.dp(109);height=context.dp(32);marginEnd=if(tab===pinned)0 else context.dp(8)}
                tab.setOnKeyListener{_,code,event->
                    if(event.action==KeyEvent.ACTION_DOWN && code==(if(layoutDirection==View.LAYOUT_DIRECTION_RTL)KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT) && tab===pinned){(manageButton ?: close).requestFocus();true}else false
                }
            }
            all.tag="library:all";pinned.tag="library:pinned"
            filterFocus=if(libraryPinnedOnly)pinned else all
            tabs.onTabSelected={key->
                libraryPinnedOnly=key=="pinned";librarySelection=0;libraryMode=kind
                filterFocus=tabs.tabView(key)!!;refreshFilter()
            }
            tabs.onTabFocused={key->filterFocus=tabs.tabView(key)!!;tabs.select(key)}
            tabs.onTabConfirmed={panels.findViewWithTag<GridView>("library")?.requestFocus()}
            val rtl=layoutDirection==View.LAYOUT_DIRECTION_RTL
            if(rtl){
                all.nextFocusLeftId=pinned.id;pinned.nextFocusRightId=all.id
                pinned.nextFocusLeftId=manageButton?.id ?: close.id
                manageButton?.nextFocusRightId=pinned.id;manageButton?.nextFocusLeftId=close.id;close.nextFocusRightId=manageButton?.id ?: pinned.id
            }else{
                all.nextFocusRightId=pinned.id;pinned.nextFocusLeftId=all.id
                pinned.nextFocusRightId=manageButton?.id ?: close.id
                manageButton?.nextFocusLeftId=pinned.id;manageButton?.nextFocusRightId=close.id;close.nextFocusLeftId=manageButton?.id ?: pinned.id
            }
            box.addView(tabRow,LinearLayout.LayoutParams(-1,context.dp(40)).apply{topMargin=context.dp(17);bottomMargin=context.dp(10)})
        } else {
            box.addView(label(context,if(managing)"选择 App，按确定添加或移除  ·  带勾选标记的 App 已在桌面" else "这些组件暂未识别到启动入口",11f,true),
                LinearLayout.LayoutParams(-1,context.dp(32)).apply{topMargin=context.dp(12)})
        }
        val totalRows=(items.size+AppLayout.LIBRARY_COLUMNS-1)/AppLayout.LIBRARY_COLUMNS
        val page=label(context,if(items.isEmpty())"" else "1 / "+totalRows+" 排",10f,true)
        val grid=LibraryGridView(context).apply {
            id=View.generateViewId();tag="library";numColumns=AppLayout.LIBRARY_COLUMNS
            nextFocusUpId=filterFocus.id
            verticalSpacing=context.dp(10);horizontalSpacing=context.dp(18)
            clipChildren=false;clipToPadding=true
            setPadding(context.dp(8),context.dp(9),context.dp(8),context.dp(12))
            overScrollMode=OVER_SCROLL_NEVER;isVerticalScrollBarEnabled=false
            isVerticalFadingEdgeEnabled=true
            setFadingEdgeLength(context.dp(40))
            selector=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            adapter=object:BaseAdapter() {
                override fun getCount()=items.size
                override fun getItem(position:Int)=items[position]
                override fun getItemId(position:Int)=position.toLong()
                override fun getView(position:Int,convertView:View?,parent:ViewGroup):View {
                    return ((convertView as? AppCardView)?:AppCardView(context)).apply {
                        isFocusable=false;isClickable=false;isLongClickable=false
                        showPinBadge=managing
                        bind(items[position],true,items[position].id in prefs.pins)
                        layoutParams=AbsListView.LayoutParams(-1,context.dp(AppLayout.LIBRARY_ROW_HEIGHT))
                    }
                }
            }
            setOnItemSelectedListener(object:AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:AdapterView<*>?) {}
                override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                    invalidateViewsFocus(parent)
                    page.text=(position/AppLayout.LIBRARY_COLUMNS+1).toString()+" / "+((items.size+AppLayout.LIBRARY_COLUMNS-1)/AppLayout.LIBRARY_COLUMNS)+" 排"
                }
            })
            onItemClickListener=AdapterView.OnItemClickListener{_,_,position,_->if(managing)toggle(items[position]) else launch(items[position])}
            onItemLongClickListener=AdapterView.OnItemLongClickListener{_,view,position,_->if(!managing)manage(items[position],view);true}
            setOnFocusChangeListener{_,_->invalidateViewsFocus(this)}
            setOnKeyListener{_,code,event->
                if(code==KeyEvent.KEYCODE_DPAD_UP && event.action==KeyEvent.ACTION_DOWN &&
                    selectedItemPosition in 0 until AppLayout.LIBRARY_COLUMNS) {
                    filterFocus.requestFocus()
                    true
                } else false
            }
        }
        filterFocus.nextFocusDownId=grid.id
        close.nextFocusDownId=grid.id
        manageButton?.nextFocusDownId=grid.id
        val gridBox=FrameLayout(context)
        gridBox.addView(grid,LayoutParams(-1,-1))
        // Leave a faded preview of the next row so overflow is visible before scrolling.
        // GridView also reserves its fading edge when bringing a selected row into view.
        gridBox.addOnLayoutChangeListener{_,_,top,_,bottom,_,_,_,_->
            grid.fitViewport(bottom-top)
        }
        val empty=label(context,if(kind=="apps" && libraryPinnedOnly)"还没有固定到主屏幕的 App" else "暂无应用",14f,true).apply {gravity=Gravity.CENTER}
        gridBox.addView(empty,LayoutParams(-1,-1));grid.emptyView=empty
        box.addView(gridBox,LinearLayout.LayoutParams(-1,0,1f))
        val footer=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
        alignedRows.add(footer)
        footer.addView(label(context,if(managing)"确定添加或移除  ·  返回完成" else "确定打开  ·  长按整理  ·  返回收起",10f,true),LinearLayout.LayoutParams(0,-2,1f))
        footer.addView(page,LinearLayout.LayoutParams(-2,-2))
        box.addView(footer,LinearLayout.LayoutParams(-1,context.dp(23)).apply{topMargin=context.dp(9)})
        refreshFilter={
            items=if(libraryPinnedOnly)prefs.pins.mapNotNull{id->launchable.find{it.id==id}} else launchable
            appCount.text="${items.size} 个 App"
            empty.text=if(libraryPinnedOnly)"还没有固定到主屏幕的 App" else "暂无应用"
            (grid.adapter as BaseAdapter).notifyDataSetChanged()
            grid.setSelection(0)
            page.text=if(items.isEmpty())"" else "1 / ${(items.size+AppLayout.LIBRARY_COLUMNS-1)/AppLayout.LIBRARY_COLUMNS} 排"
            grid.nextFocusUpId=filterFocus.id
            filterFocus.nextFocusDownId=grid.id
        }
        refreshLibraryPins={
            if(kind=="apps" && libraryPinnedOnly && !managing) {
                // Removing an item from the pinned filter really changes its contents.
                show(kind)
            } else {
                if(managing)appCount.text="已添加 ${prefs.pins.size} / 10"
                // Keep the adapter, children, scroll offset and selection intact. Rebuilding
                // here briefly displayed the first page before restoring an offscreen item.
                for(index in 0 until grid.childCount) {
                    val card=grid.getChildAt(index) as? AppCardView ?: continue
                    val entry=card.entry ?: continue
                    card.bind(entry,true,entry.id in prefs.pins)
                }
            }
        }
        // Align chrome to the artwork edges, including the grid cell's internal inset.
        grid.addOnLayoutChangeListener{_,left,_,right,_,_,_,_,_->
            val cell=(right-left-grid.paddingLeft-grid.paddingRight-context.dp(18)*(AppLayout.LIBRARY_COLUMNS-1))/AppLayout.LIBRARY_COLUMNS
            val artwork=min(cell-context.dp(16),context.dp(AppLayout.LIBRARY_ROW_HEIGHT-35)*16/9)
            val inset=grid.paddingLeft+(cell-artwork)/2
            alignedRows.forEach {it.setPadding(inset,0,inset,0)}
        }
        grid.post {
            if(items.isEmpty())filterFocus.requestFocus()
            else {
                grid.requestFocus()
                grid.setSelection(if(libraryMode==kind)librarySelection.coerceIn(0,items.lastIndex) else 0)
            }
        }
    }
    private fun invalidateViewsFocus(parent:AdapterView<*>?) {
        if(parent==null)return
        for(i in 0 until parent.childCount) {
            val child=parent.getChildAt(i)
            child.isSelected=parent.hasFocus() && parent.firstVisiblePosition+i==parent.selectedItemPosition
            child.invalidate()
        }
    }
    private fun button(title:String,subtitle:String="",action:()->Unit):GlassListRow = GlassListRow(context).apply {
        bind(GlassListItem(title,title,subtitle));tag=title;setOnClickListener{action()}
    }
    private fun buildPanel(kind: String) {
        val frame=FrameLayout(context).apply {
            tag="panel:"+kind
            background=GlassBackground(context)
            clipToOutline=true
        }
        panels.addView(frame,LayoutParams(context.dp(342),-1,Gravity.END or Gravity.TOP).apply { setMargins(context.dp(20),context.dp(20),context.dp(20),context.dp(20)) })
        if(!floating && wallpaper!=null)frame.addView(PanelBackdrop(context,wallpaper!!),LayoutParams(-1,-1))
        val column=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;val inset=context.dp(20);setPadding(inset,inset,inset,context.dp(14)) }
        frame.addView(column,LayoutParams(-1,-1))
        val title=when(kind){"settings"->"桌面设置";"licenses"->"许可与来源";"default_home"->"默认桌面";else->"桌面设置"}
        val heading=LinearLayout(context).apply { gravity=Gravity.CENTER_VERTICAL }
        heading.addView(label(context,title,25f),LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(closeControl(context,"关闭"){back()},LinearLayout.LayoutParams(context.dp(44),context.dp(44)))
        column.addView(heading)
        column.addView(label(context,"PTV Launcher",10f,true),LinearLayout.LayoutParams(-1,context.dp(24)).apply { topMargin=context.dp(6) })
        val scroll=GlassScrollView(context).apply { isFillViewport=false;clipToPadding=false;clipChildren=false }
        val content=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;clipChildren=false;clipToPadding=false
        }
        scroll.addView(content);column.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        var first:View?=null
        fun row(title:String,subtitle:String="",action:()->Unit) {
            val view=button(title,subtitle,action);if(first==null)first=view
            content.addView(view,LinearLayout.LayoutParams(-1,context.dp(if(subtitle.isEmpty())42 else 55)).apply { bottomMargin=context.dp(8) })
        }
        fun note(text:String) { content.addView(label(context,text,10f,true).apply { setLineSpacing(context.dp(4).toFloat(),1f) },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=context.dp(10) }) }
        when(kind) {
            "settings"->{
                row("自定义桌面应用","已选择 "+prefs.pins.size+" / 10"){show("customize")}
                row("长按返回显示应用列表",ShortcutRecovery.status(context)){
                    val enable=!ShortcutRecovery.enabled(context)
                    ShortcutRecovery.setEnabled(context,enable);show("settings")
                    if(enable)ShortcutRecovery.requestPermission(context)
                }
                if(ShortcutRecovery.enabled(context) && !SystemLinks.accessibilityEnabled(context))
                    row("授权快捷服务","打开系统无障碍授权页面"){ShortcutRecovery.requestPermission(context)}
                row("默认桌面",homeLabel()){show("default_home")}
                row("画报等待时间",if(prefs.idleMinutes==0)"已关闭" else "桌面闲置 "+prefs.idleMinutes+" 分钟"){
                    val choices=listOf(1,3,5,10,0);graph.scope.launchSafely{graph.store.idle(choices[(choices.indexOf(prefs.idleMinutes)+1)%choices.size]);post{prefs=graph.store.state.value;show("settings")}}
                }
                row("壁纸",if(prefs.customWallpaper)"自定义 · "+if(prefs.customRotate)"轮播" else "固定一张" else "必应壁纸轮播"){context.startActivity(android.content.Intent(context,WallpaperSettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))}
                row("系统组件","查看暂未识别启动入口的组件"){show("components")}
                row("许可与来源","源码公开 · 非商业使用许可"){show("licenses")}
                note("快捷服务处理遥控器与窗口切换；背景截图仅在内存中用于模糊，不保存或上传。")
            }
            "default_home"->{
                val status=DefaultHomeController.inspect(context)
                note(when {
                    !status.selected->"在系统选择器中选择 PTV Launcher。若小米固件隐藏了选择器，请按安装说明通过 ADB 设置。"
                    status.systemRoutesToPtv->"系统主页入口已指向 PTV。"
                    SwitcherService.connected.value->"PTV 已持有 HOME 角色。小米固件仍强制打开原桌面，当前通过已连接的快捷服务转接主页键。"
                    else->"PTV 已持有 HOME 角色，但小米固件仍强制打开原桌面。快捷服务未连接，主页键兼容当前不可用。"
                })
                row(if(status.selected)"更改默认桌面" else "设为默认桌面","打开系统桌面选择器"){DefaultHomeController.openChooser(context)}
                row("主页键兼容",serviceLabel()){SystemLinks.open(context,Settings.ACTION_ACCESSIBILITY_SETTINGS)}
                row("验证主页键","返回默认桌面并检查实际跳转"){DefaultHomeController.testHome(context)}
                note("保留原系统桌面。恢复时在系统选择器选择原桌面，兼容转接随之停止。无障碍权限若被固件关闭，需要重新启用。")
            }
            "licenses"->{
                val text=listOf("open_source_notices.txt","licenses/PolyForm-Noncommercial-1.0.0.txt","licenses/Apache-2.0.txt").joinToString("\n\n"){context.assets.open(it).bufferedReader().use{reader->reader.readText()}}
                note(text)
                scroll.isFocusable=true;first=scroll
                scroll.setOnKeyListener { _,code,event->
                    if(event.action==KeyEvent.ACTION_DOWN && code in listOf(KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_UP)) {scroll.smoothScrollBy(0,context.dp(if(code==KeyEvent.KEYCODE_DPAD_DOWN)100 else -100));true}else false
                }
            }
        }
        first?.post { first?.requestFocus() }
    }
    private fun serviceLabel()=when {
        SwitcherService.connected.value->"已连接 · 长按返回键快捷切换"
        SystemLinks.accessibilityEnabled(context)->"已授权但未连接 · 请关闭后重新开启"
        else->"未启用 · 点击开启 PTV 快速切换"
    }
    private fun homeLabel():String {
        val status=DefaultHomeController.inspect(context)
        return when {
            !status.selected->"点击设为默认桌面"
            status.systemRoutesToPtv->"已设为默认桌面"
            SwitcherService.connected.value->"HOME 角色已选择 · 主页键兼容已连接"
            else->"HOME 角色已选择 · 主页键兼容未连接"
        }
    }
}

/** Original vector grid for the app library. */
private class AppListGlyph(context:Context):GlassIconView(context) {
    init{setImageResource(R.drawable.f7_square_grid_2x2)}
}

/** Draw an actual preblurred image with filtered scaling, never a pixelated thumbnail. */
private class PanelBackdrop(context:Context,bitmap:Bitmap,private val tint:Int=0xb0222933.toInt()):View(context) {
    var bitmap:Bitmap=bitmap
        set(value) {field=value;invalidate()}
    private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
    override fun onDraw(canvas:Canvas) {
        // Display lists can survive translation animations. Screen coordinates include that
        // temporary translation and would bake a transparent strip into the cached drawing.
        val root=rootView
        var x=0;var y=0;var node:View=this
        while(node!==root) {
            x+=node.left;y+=node.top
            val parent=node.parent as? View ?: break
            x-=parent.scrollX;y-=parent.scrollY;node=parent
        }
        val scale=max(root.width.toFloat()/bitmap.width,root.height.toFloat()/bitmap.height)
        val w=bitmap.width*scale;val h=bitmap.height*scale
        canvas.drawBitmap(bitmap,null,RectF((root.width-w)/2-x,(root.height-h)/2-y,(root.width+w)/2-x,(root.height+h)/2-y),paint)
        canvas.drawColor(tint)
    }
}

internal fun CoroutineScope.launchSafely(block: suspend () -> Unit) = launch { block() }
