// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher
import com.popkter.glass_comonpent.*

import android.animation.*
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.*
import android.graphics.*
import android.net.wifi.ScanResult
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import kotlinx.coroutines.*
import kotlin.math.*

/** Settings-only UI: the desktop and app-library hierarchies are never rebuilt here. */
@SuppressLint("MissingPermission")
internal class ControlCenterView(context:Context,private val graph:LauncherGraph,
    private val entry:RectF,private val dismissed:()->Unit,private val components:()->Unit):FrameLayout(context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val services=ControlCenterServices(context){onServicesChanged()}
    private val backdrop=object:View(context) {
        init {alpha=0f;tag="control:backdrop"}
        var image:Bitmap?=null
        private val paint=Paint(Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas:Canvas) {
            image?.let {canvas.drawBitmap(it,null,RectF(0f,0f,width.toFloat(),height.toFloat()),paint)}
            canvas.drawColor(0x36121e2c)
        }
    }
    private val home=FrameLayout(context).apply{tag="control:center";clipChildren=false;clipToPadding=false}
    private val detail=LinearLayout(context).apply {
        tag="control:detail";orientation=LinearLayout.VERTICAL;visibility=INVISIBLE
        setPadding(context.dp(14),context.dp(14),context.dp(14),context.dp(14));background=ControlGlass(context)
    }
    private val title=label(context,"",16f)
    private val body=LinearLayout(context).apply{orientation=LinearLayout.VERTICAL}
    private val scroll=GlassScrollView(context).apply{isVerticalScrollBarEnabled=false;addView(body)}
    private val backButton=iconButton(R.drawable.f7_chevron_left,"返回控制中心"){back()}
    private val tiles=linkedMapOf<String,ControlTile>()
    private var page:String?=null
    private var anchor:View?=null
    private var animation:ValueAnimator?=null
    private var busy=false
    private var pendingBack=false
    internal val animationRunning get()=busy
    private var ready=false
    private var disposed=false
    private var pendingRefresh=false
    private var network:ScanResult?=null
    private var selectedBluetooth:String?=null
    private var bluetoothReturnTag:String?=null
    private var bluetoothScroll=0
    private var networkReturnTag:String?=null
    private var selectedSaved:NetworkBridgeClient.SavedNetwork?=null
    private var passwordParent="network"
    private var renderedPage:String?=null
    private var networkScroll=0
    private var returningFromWallpaper=false
    private val pageFocus=mutableMapOf<String,String>()
    private var note=""
    private var ownedBitmap:Bitmap?=null
    private var unit=context.dp(51)
    private val gap=context.dp(7)
    private val panelWidth get()=unit*4+gap*3
    private val panelTop get()=context.dp(ControlCenterLayout.TOP)
    init {
        tag="panel:settings";clipChildren=false;clipToPadding=false
        isClickable=true
        addView(backdrop,LayoutParams(-1,-1))
        addView(home,LayoutParams(panelWidth,context.dp(ControlCenterLayout.HEIGHT),Gravity.TOP or Gravity.END).apply{marginEnd=context.dp(AppLayout.HOME_RIGHT_MARGIN);topMargin=panelTop})
        val header=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(label(context,"桌面设置",23f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(iconButton(R.drawable.f7_xmark,"关闭桌面设置"){close()},LinearLayout.LayoutParams(context.dp(36),context.dp(36)))
        home.addView(header,LayoutParams(-1,context.dp(ControlCenterLayout.HEADER_HEIGHT)))
        tile("wallpaper",R.drawable.f7_photo,"壁纸",0,0,2){
            val source=tiles.getValue("wallpaper")
            returningFromWallpaper=true
            val bounds=Rect();source.getGlobalVisibleRect(bounds)
            val intent=Intent(context,WallpaperSettingsActivity::class.java).putExtra("control_wallpaper_origin",bounds)
            WallpaperTransitionBackdrop.prepare(backdrop.image)
            if(context is android.app.Activity)context.startActivity(intent,android.app.ActivityOptions.makeCustomAnimation(context,0,0).toBundle())
            else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        tile("idle",R.drawable.f7_moon_fill,"画报",0,1,2){open("idle")}
        tile("source",R.drawable.f7_tv,"信号源",2,0,2,2){open("source")}
        tile("shortcut",R.drawable.f7_arrow_uturn_left,"长按返回显示应用列表",0,2){
            val enable=!ShortcutRecovery.enabled(context);ShortcutRecovery.setEnabled(context,enable);refresh()
            if(enable&&!SystemLinks.accessibilityEnabled(context))open("shortcut")
        }
        tile("home",R.drawable.f7_house_fill,"默认桌面",1,2){open("home")}
        tile("components",R.drawable.f7_square_stack_3d_up,"系统组件",2,2){components()}
        tile("about",R.drawable.f7_info_circle,"关于 PTV",3,2){open("about")}
        tile("network",R.drawable.f7_wifi,"网络",0,3,2){open("network")}
        tile("bluetooth",R.drawable.cc_bluetooth,"蓝牙",2,3,2){open("bluetooth")}
        tile("brightness",R.drawable.f7_sun_max,"亮度",0,4,4,slider=true){}
        tile("volume",R.drawable.f7_speaker_3_fill,"音量",0,5,4,slider=true){}
        tiles.getValue("brightness").adjust={value->
            if(services.brightness(value))services.brightness() else {
                if(page==null&&!busy)open("brightness")
                services.brightness()
            }
        }
        tiles.getValue("volume").adjust={services.volume(it)}
        val hint=label(context,"方向键移动 · 确定选择 · 返回收起",9f,true).apply{gravity=Gravity.CENTER}
        home.addView(hint,LayoutParams(-1,context.dp(24)).apply{topMargin=context.dp(64)+unit*6+gap*5+context.dp(12)})
        val detailHeader=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
        detailHeader.addView(backButton,LinearLayout.LayoutParams(context.dp(36),context.dp(36)))
        detailHeader.addView(title,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=context.dp(10)})
        detail.addView(detailHeader);detail.addView(scroll,LinearLayout.LayoutParams(-1,-2).apply{topMargin=context.dp(20)})
        addView(detail,LayoutParams(panelWidth,-2,Gravity.TOP or Gravity.END).apply{marginEnd=context.dp(AppLayout.HOME_RIGHT_MARGIN);topMargin=panelTop})
        home.visibility=INVISIBLE
        refresh()
    }
    private fun iconButton(icon:Int,label:String,action:()->Unit)=GlassButton(context).apply {
        tag="control:$label";title=label;gridSize=GlassSize.X1Y1;iconOnly=true
        this.icon=context.getDrawable(icon)?.apply{isAutoMirrored=true};glassStyle=glassStyle.copy(iconSize=22f,cornerRadius=100f)
        setOnClickListener{if(!busy)action()}
    }
    private fun tile(key:String,icon:Int,text:String,col:Int,row:Int,cols:Int=1,rows:Int=1,slider:Boolean=false,action:()->Unit) {
        val view=ControlTile(context,key,icon,text,large=rows==2,iconOnly=cols==1,slider=slider)
        view.setOnClickListener{if(!busy)action()};tiles[key]=view
        home.addView(view,LayoutParams(unit*cols+gap*(cols-1),unit*rows+gap*(rows-1),Gravity.TOP or Gravity.START).apply{marginStart=(unit+gap)*col;topMargin=context.dp(ControlCenterLayout.CONTENT_TOP)+(unit+gap)*row})
    }
    fun prepare(snapshot:Bitmap?,fallback:Bitmap?) {
        scope.launch {
            var created:Bitmap?=null
            try {
                if(snapshot!=null)withContext(Dispatchers.Default){created=BackdropBlur.create(snapshot)}
                if(disposed)return@launch
                ownedBitmap=created;created=null;backdrop.image=ownedBitmap?:fallback;backdrop.invalidate()
                post {if(!disposed){ready=true;enter()}}
            } finally {snapshot?.recycle();created?.recycle()}
        }
    }
    private fun rect(view:View):RectF {
        val r=Rect();view.getDrawingRect(r);offsetDescendantRectToMyCoords(view,r);return RectF(r)
    }
    private fun motion(duration:Long=360,reverse:Boolean=false,frame:(Float)->Unit,done:()->Unit) {
        animation?.cancel();busy=true
        animation=ValueAnimator.ofFloat(0f,1f).apply {
            this.duration=duration;interpolator=if(reverse)PathInterpolator(.4f,0f,.2f,1f) else PathInterpolator(.22f,.8f,.2f,1f)
            addUpdateListener{frame(it.animatedValue as Float)}
            addListener(object:AnimatorListenerAdapter(){private var cancelled=false
                override fun onAnimationCancel(animation:Animator){cancelled=true}
                override fun onAnimationEnd(animation:Animator){if(!cancelled&&!disposed){busy=false;done();if(pendingRefresh){pendingRefresh=false;refreshDetail()};if(pendingBack&&!disposed){pendingBack=false;post{back()}}}}
            });frame(0f);start()
        }
    }
    private fun transform(view:View,from:RectF,to:RectF,p:Float) {
        val base=rectWithoutTransform(view)
        view.pivotX=0f;view.pivotY=0f
        view.scaleX=(from.width()+(to.width()-from.width())*p)/base.width().coerceAtLeast(1f)
        view.scaleY=(from.height()+(to.height()-from.height())*p)/base.height().coerceAtLeast(1f)
        view.translationX=from.left+(to.left-from.left)*p-base.left
        view.translationY=from.top+(to.top-from.top)*p-base.top
    }
    private fun rectWithoutTransform(view:View)=RectF(view.left.toFloat(),view.top.toFloat(),view.right.toFloat(),view.bottom.toFloat())
    private fun reset(view:View){view.scaleX=1f;view.scaleY=1f;view.translationX=0f;view.translationY=0f;view.alpha=1f}
    private fun morphRadius(start:Float,end:Float,p:Float) {
        val glass=detail.background as ControlGlass
        val radius=start+(end-start)*p
        glass.radiusX=radius/detail.scaleX.coerceAtLeast(.01f);glass.radiusY=radius/detail.scaleY.coerceAtLeast(.01f)
        glass.invalidateSelf()
    }
    private fun resetRadius(){(detail.background as ControlGlass).apply{radiusX=null;radiusY=null;invalidateSelf()}}
    private fun enter() {
        home.visibility=VISIBLE;val target=rectWithoutTransform(home)
        motion(400,frame={p->transform(home,entry,target,p);home.alpha=(p*2.5f).coerceAtMost(1f);backdrop.alpha=p}) {
            reset(home);tiles.getValue("wallpaper").requestFocus()
        }
    }
    fun close() {
        if(busy)return
        if(!ready){dismissed();return}
        if(page!=null){back();return}
        val from=rectWithoutTransform(home)
        motion(420,reverse=true,frame={p->transform(home,from,entry,p);home.alpha=((1f-p)/.18f).coerceIn(0f,1f);backdrop.alpha=1f-p}){dismissed()}
    }
    fun closeToDesktop() {
        animation?.cancel();busy=false;page=null;detail.visibility=INVISIBLE
        home.visibility=VISIBLE;reset(home);close()
    }
    fun back() {
        if(busy){pendingBack=true;return}
        if(page=="forget-bluetooth" || page=="disconnect-bluetooth"){page="bluetooth-device";render();(detail.findViewWithTag<View>("control:row:忽略设备")?:backButton).requestFocus();return}
        if(page=="bluetooth-device" || page=="bluetooth-off"){page="bluetooth";render();restoreBluetoothFocus();return}
        if(page=="wifi-password"){
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(windowToken,0)
            network=null;page=passwordParent;render()
            if(page=="network")restoreNetworkFocus() else (detail.findViewWithTag<View>("control:row:重新输入密码")?:backButton).requestFocus()
            return
        }
        if(page=="forget-network"){page="network-details";render();(detail.findViewWithTag<View>("control:row:忘记网络")?:backButton).requestFocus();return}
        if(page=="network-details"){page="network";render();restoreNetworkFocus();return}
        if(page=="licenses"){page="about";render();(pageFocus["about"]?.let{detail.findViewWithTag<View>(it)}?:backButton).requestFocus();return}
        if(page==null){close();return}
        val from=rectWithoutTransform(detail);val target=anchor?:tiles.getValue("wallpaper");val to=rect(target)
        target.visibility=INVISIBLE
        home.visibility=VISIBLE;home.alpha=0f
        val targetRadius=if((target as? ControlTile)?.iconOnly==true)to.height()/context.resources.displayMetrics.density/2 else 26f
        motion(380,reverse=true,frame={p->transform(detail,from,to,p);morphRadius(24f,targetRadius,p);detail.getChildAt(0).alpha=1f-p;scroll.alpha=1f-p;home.alpha=p;detail.alpha=1f}) {
            detail.visibility=INVISIBLE;reset(detail);resetRadius();detail.getChildAt(0).alpha=1f;scroll.alpha=1f;reset(home);target.visibility=VISIBLE
            page=null;body.removeAllViews();refresh();target.requestFocus()
        }
    }
    private fun open(kind:String) {
        if(busy||!ready)return
        busy=true
        anchor=tiles[kind]?:tiles.getValue("wallpaper");page=kind;note="";render()
        detail.visibility=VISIBLE;detail.alpha=0f
        detail.post {
            if(disposed||page!=kind)return@post
            val from=rect(anchor!!);val to=rectWithoutTransform(detail)
            anchor!!.visibility=INVISIBLE
            val startRadius=if((anchor as? ControlTile)?.iconOnly==true)from.height()/context.resources.displayMetrics.density/2 else 26f
            motion(360,frame={p->detail.alpha=1f;transform(detail,from,to,p);home.alpha=1f-p
                morphRadius(startRadius,24f,p)
                detail.getChildAt(0).alpha=((p-.4f)/.6f).coerceIn(0f,1f);scroll.alpha=((p-.4f)/.6f).coerceIn(0f,1f)}) {
                home.visibility=INVISIBLE;anchor?.visibility=VISIBLE;reset(home);reset(detail);resetRadius();detail.getChildAt(0).alpha=1f;scroll.alpha=1f;backButton.requestFocus()
            }
        }
    }
    fun refresh() {
        if(returningFromWallpaper&&(context as? android.app.Activity)?.hasWindowFocus()==true&&!busy&&page==null) {
            returningFromWallpaper=false;tiles.getValue("wallpaper").requestFocus()
        }
        services.refreshSavedNetworks()
        val prefs=graph.store.state.value
        tiles["wallpaper"]?.subtitle=if(prefs.customWallpaper)"自定义壁纸" else "必应画报"
        tiles["idle"]?.subtitle=if(prefs.idleMinutes==0)"已关闭" else "${prefs.idleMinutes} 分钟"
        tiles["source"]?.subtitle="选择输入";tiles["network"]?.subtitle=services.networkSummary()
        services.bluetoothControl.refresh()
        tiles["bluetooth"]?.subtitle=if(services.bluetoothControl.enabled)"已开启" else "已关闭";tiles["bluetooth"]?.checked=services.bluetoothControl.enabled
        tiles["shortcut"]?.checked=ShortcutRecovery.enabled(context)
        tiles["brightness"]?.progress=services.brightness();tiles["volume"]?.progress=services.volume()
        tiles.values.forEach{it.invalidate()};refreshDetail()
    }
    private fun onServicesChanged() {
        tiles["brightness"]?.progress=services.brightness();tiles["volume"]?.progress=services.volume()
        tiles["network"]?.let{it.subtitle=services.networkSummary();it.invalidate()}
        tiles["bluetooth"]?.checked=services.bluetoothControl.enabled
        if(page=="forget-bluetooth" && services.bluetoothControl.status=="已忽略设备") {page="bluetooth";render();restoreBluetoothFocus();return}
        if(page in listOf("network","bluetooth","bluetooth-device","forget-bluetooth","bluetooth-off","disconnect-bluetooth"))refreshDetail()
    }
    private fun refreshDetail() {
        if(page==null||page=="wifi-password")return
        if(busy){pendingRefresh=true;return}
        render()
    }
    private fun note(text:String) {body.addView(label(context,text,11f,true).apply{setLineSpacing(context.dp(3).toFloat(),1f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=context.dp(8);bottomMargin=context.dp(10)})}
    private fun row(text:String,sub:String="",key:String=text,icon:Int?=null,enabled:Boolean=true,action:()->Unit) {
        val row=GlassListRow(context).apply {
            glassStyle=glassStyle.copy(contentPadding=10f,cornerRadius=16f)
            bind(GlassListItem(key,text,sub,icon?.let{context.getDrawable(it)},enabled=enabled))
            tag="control:row:$key"
            setOnClickListener{if(!busy && isEnabled){page?.let{pageFocus[it]=tag.toString()};action()}}
        }
        body.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=context.dp(4)})
    }
    private fun render() {
        val pageAtRender=page
        val focus=findFocus()
        val focusTag=focus?.tag
        val samePage=renderedPage==page
        val hadBodyFocus=body.hasFocus()
        val oldScroll=scroll.scrollY
        val oldIndex=(0 until body.childCount).firstOrNull{body.getChildAt(it).hasFocus()}?:0
        renderedPage=page
        body.removeAllViews();scroll.scrollTo(0,0)
        title.text=when(page){"idle"->"画报";"network","wifi-password","network-details","forget-network"->"Wi-Fi";"bluetooth","bluetooth-device","forget-bluetooth","bluetooth-off","disconnect-bluetooth"->"蓝牙";"source"->"信号源";"about"->"关于 PTV";"licenses"->"许可与来源";"home"->"默认桌面";"shortcut"->"长按返回";else->"亮度"}
        when(page) {
            "idle"->{note("桌面闲置多久后开始画报？");listOf(1,3,5,10,0).forEach{value->
                row(if(value==0)"关闭画报" else "$value 分钟",if(graph.store.state.value.idleMinutes==value)"已选择" else "",key="idle:$value") {
                    scope.launch {graph.store.idle(value);back()}
                }
            }}
            "about"->{val version=context.packageManager.getPackageInfo(context.packageName,0).versionName;note("PTV Launcher\n$version\n\n为遥控器而生的轻盈桌面。");note("源码公开 · 非商业使用许可\n背景截图仅在内存中用于模糊，不保存或上传。");row("许可与来源"){page="licenses";render()}}
            "licenses"->{note(listOf("open_source_notices.txt","licenses/PolyForm-Noncommercial-1.0.0.txt").joinToString("\n\n"){context.assets.open(it).bufferedReader().use{r->r.readText()}});scroll.isFocusable=true}
            "home"->{note(if(DefaultHomeController.isSelected(context))"PTV 已被选择为默认桌面" else "选择按主页键时打开的桌面");row("打开桌面选择器"){DefaultHomeController.openChooser(context)}}
            "shortcut"->{note(ShortcutRecovery.status(context));row("授权快捷服务"){ShortcutRecovery.requestPermission(context)}}
            "brightness"->{note(if(!services.canSetBrightness())"允许 PTV 修改系统设置后，可调整亮度。" else "此电视暂未接受亮度调整，请使用系统显示设置。");row("允许修改系统设置"){if(!services.brightnessPermission()){note="此电视未提供授权页面，请用 PTV 安装工具重新配置亮度权限。";render()}};row("系统显示设置"){SystemLinks.open(context,Settings.ACTION_DISPLAY_SETTINGS)}}
            "network"->renderNetwork()
            "wifi-password"->renderPassword()
            "network-details"->{selectedSaved?.let{saved->
                note(saved.ssid+if(services.connectedSsid()==saved.ssid)" · 已连接" else " · 已保存")
                row("连接","使用已保存的密码") {services.savedNetworkAction(saved,false){status->note=status;if(page=="network-details")render()}}
                services.wifiNetworks().firstOrNull{it.SSID==saved.ssid}?.let{scan->row("重新输入密码"){openWifiNetwork(scan,"network-details")}}
                row("忘记网络"){page="forget-network";render();(detail.findViewWithTag<View>("control:row:取消")?:backButton).requestFocus()}
            }}
            "forget-network"->{selectedSaved?.let{saved->
                note("忘记 ${saved.ssid}？下次连接需要重新输入密码。"+if(services.connectedSsid()==saved.ssid)"\n当前连接将会断开。" else "")
                row("取消"){back()}
                row("确认忘记"){services.savedNetworkAction(saved,true){status->
                    note=status
                    if(page=="forget-network"){if(status=="已忘记网络"){page="network";render();restoreNetworkFocus()}else render()}
                }}
            }}
            "bluetooth"->renderBluetooth()
            "bluetooth-device"->renderBluetoothDevice()
            "forget-bluetooth"->{
                val device=services.bluetoothControl.devices().firstOrNull{it.address==selectedBluetooth}
                note("忽略 ${device?.name?:"此设备"}？下次使用需要重新配对。"+if(device?.isInput==true)"\n这会断开遥控器或输入设备，请先准备其他操作方式。" else "")
                row("取消"){back()}
                row("确认忽略",enabled=device!=null&&!services.bluetoothControl.busy(device.address)){device?.let{note=services.bluetoothControl.action(it,13);render()}}
                if(services.bluetoothControl.status.isNotBlank())note(services.bluetoothControl.status)
            }
            "disconnect-bluetooth"->{
                val device=services.bluetoothControl.devices().firstOrNull{it.address==selectedBluetooth}
                note("断开 ${device?.name?:"此设备"}？如果这是当前遥控器或输入设备，请先准备其他操作方式。")
                row("取消"){back()};row("确认断开",enabled=device!=null&&!services.bluetoothControl.busy(device.address)){device?.let{note=services.bluetoothControl.action(it,12);page="bluetooth-device";render()}}
            }
            "bluetooth-off"->{note("关闭蓝牙会断开所有蓝牙设备，包括正在使用的遥控器。请先准备其他操作方式。");row("取消"){back()};row("确认关闭蓝牙"){note=services.bluetoothControl.action(null,17);page="bluetooth";render()}}
            "source"->{
                val inputs=services.sourceList()
                if(inputs.isEmpty())note("系统没有提供可用信号源")
                inputs.forEach {input->
                    val state=runCatching{services.inputs?.getInputState(input.id)}.getOrNull()
                    row(input.loadLabel(context).toString(),when(state){android.media.tv.TvInputManager.INPUT_STATE_DISCONNECTED->"未连接";android.media.tv.TvInputManager.INPUT_STATE_CONNECTED_STANDBY->"待机";else->"电视输入"},input.id,R.drawable.f7_tv) {
                        if(!services.openSource(input)){note="系统未提供此信号源的播放入口";render()}
                    }
                }
                row("系统信号源设置"){SystemLinks.open(context,"android.settings.TV_INPUT_SETTINGS")}
            }
        }
        if(note.isNotEmpty())note(note)
        // Lists may grow after discovery. Keep header and back control outside the scroll area.
        body.measure(MeasureSpec.makeMeasureSpec(panelWidth-context.dp(28),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        scroll.layoutParams=LinearLayout.LayoutParams(-1,min(body.measuredHeight,(height-panelTop-context.dp(104)).coerceAtLeast(context.dp(100)))).apply{topMargin=context.dp(20)}
        if(samePage) {
            val matching=focusTag?.let{detail.findViewWithTag<View>(it)}
            val rows=(0 until body.childCount).map{body.getChildAt(it)}.filter{it.isFocusable}
            val target=matching?:if(hadBodyFocus)rows.getOrNull(oldIndex.coerceAtMost((rows.size-1).coerceAtLeast(0)))?:backButton else null
            target?.requestFocus()
            scroll.post{if(page==pageAtRender)scroll.scrollTo(0,oldScroll)}
        }
    }
    private fun renderNetwork() {
        note(services.networkSummary())
        if(services.wifiConnectionStatus.isNotEmpty())note(services.wifiConnectionStatus)
        if(!services.networkBridge.installed())note("直接连接需要安装 PTV 网络兼容组件")
        else if(!services.networkBridge.authorized())row("授权直接连接 Wi-Fi"){services.networkBridge.authorize()}
        val saved=services.savedNetworks.sortedBy{it.ssid}
        if(saved.isNotEmpty()) {
            note("已保存网络")
            saved.forEach{entry->row(entry.ssid,if(services.connectedSsid()==entry.ssid)"已连接" else "已保存",key="saved:${entry.id}",icon=R.drawable.f7_wifi){
                networkScroll=scroll.scrollY;selectedSaved=entry;networkReturnTag="control:row:saved:${entry.id}";page="network-details";note="";render()
                (detail.findViewWithTag<View>("control:row:连接")?:backButton).requestFocus()
            }}
        }
        val connected=services.connectedSsid()
        if(connected!=null)note("$connected · 已连接")
        if(!services.wifiAllowed())row("允许查找 Wi-Fi","需要定位权限"){services.requestPermissions(false)}
        else {
            val list=services.wifiNetworks()
            if(list.isEmpty())note("暂无附近网络，请刷新并确认定位已开启")
            note("附近网络")
            list.filter{scan->saved.none{it.ssid==scan.SSID}}.forEach{result->row(result.SSID,if(result.SSID==connected)"已连接" else if(result.capabilities.contains("PSK")||result.capabilities.contains("SAE"))"安全网络" else "Wi-Fi",result.SSID,R.drawable.f7_wifi){
                if(result.SSID!=connected)openWifiNetwork(result)
            }}
            row("刷新附近网络"){note=services.scanWifi();render()}
        }
        row("更多网络设置"){SystemLinks.open(context,Settings.ACTION_WIFI_SETTINGS)}
    }
    private fun renderPassword() {
        val net=network?:return;note(net.SSID)
        val secure=net.capabilities.contains("PSK")||net.capabilities.contains("SAE")
        val field=GlassTextField(context).apply{glassStyle=glassStyle.copy(cornerRadius=16f)}
        val password=field.editText.apply {tag="control:wifi-password";hint="Wi-Fi 密码"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;isSingleLine=true
            if(Build.VERSION.SDK_INT>=26)importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS}
        if(secure)body.addView(field,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=context.dp(10)})
        if(secure) {
            fun keyboard(){password.requestFocus();password.postDelayed({if(password.isAttachedToWindow){
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(password,0)
                if(Build.VERSION.SDK_INT>=30)password.windowInsetsController?.show(WindowInsets.Type.ime())
            }},120)}
            password.setOnClickListener{keyboard()};password.post{keyboard()}
        }
        row("连接") {
            note=services.saveWifi(net,password.text.toString());password.text.clear();page="network";network=null;render()
        }
    }
    private fun restoreNetworkFocus() {
        (networkReturnTag?.let{detail.findViewWithTag<View>(it)}?:
            (0 until body.childCount).map{body.getChildAt(it)}.firstOrNull{it.isFocusable}?:backButton).requestFocus()
        scroll.post{if(page=="network")scroll.scrollTo(0,networkScroll)}
    }
    internal fun openWifiNetwork(result:ScanResult,parent:String="network") {
        passwordParent=parent
        if(parent=="network"){networkScroll=scroll.scrollY;networkReturnTag="control:row:${result.SSID}"}
        network=result;page="wifi-password";note="";render()
    }
    private fun renderBluetooth() {
        val controller=services.bluetoothControl
        if(!services.bluetoothAllowed()){row("允许查找蓝牙设备"){services.requestPermissions(true)};return}
        note(if(controller.enabled)"蓝牙已开启" else "蓝牙已关闭")
        if(!controller.ready)note("完整设备管理需要激活本地管理服务；请使用 PTV 安装工具重新配置。电视重启后需重新激活。")
        val devices=controller.devices()
        listOf("已连接" to devices.filter{it.connected},
            "已配对" to devices.filter{!it.connected&&it.bond!=BluetoothDevice.BOND_NONE},
            "附近设备" to devices.filter{it.bond==BluetoothDevice.BOND_NONE}).forEach{(heading,list)->
            if(list.isNotEmpty())note(heading)
            list.forEach{device->row(device.name,controller.summary(device),key="bluetooth:${device.address}",icon=if(device.isInput)R.drawable.f7_gamecontroller else R.drawable.f7_headphones){
                bluetoothScroll=scroll.scrollY;selectedBluetooth=device.address;bluetoothReturnTag="control:row:bluetooth:${device.address}";controller.clearStatus();page="bluetooth-device";note="";render()
                (body.findViewWithTag<View>("control:row:连接")?:body.findViewWithTag("control:row:配对并连接")?:backButton).requestFocus()
            }}
        }
        if(devices.isEmpty())note("暂无设备，请确认设备已开启并处于可被发现状态。")
        if(controller.enabled){
            row(if(controller.scanning)"正在搜索附近设备…" else "搜索附近设备",key="bluetooth:scan",enabled=!controller.scanning){note=services.scanBluetooth();render()}
            row("关闭蓝牙",enabled=controller.ready){page="bluetooth-off";render();(body.findViewWithTag<View>("control:row:取消")?:backButton).requestFocus()}
        }else row("开启蓝牙"){if(controller.ready)note=controller.action(null,16)else if(!services.enableBluetooth())note="系统无法开启蓝牙";render()}
        if(controller.status.isNotBlank())note(controller.status)
        row("更多蓝牙设置"){SystemLinks.open(context,Settings.ACTION_BLUETOOTH_SETTINGS)}
    }
    private fun renderBluetoothDevice() {
        val controller=services.bluetoothControl
        val device=controller.devices().firstOrNull{it.address==selectedBluetooth}
        if(device==null){note("此设备不在列表中");row("返回蓝牙列表"){back()};return}
        note(device.name+"\n"+controller.summary(device))
        if(device.battery in 0..100)note("电量 ${device.battery}%")
        val enabled=controller.ready&&!controller.busy(device.address)
        when {
            device.bond==BluetoothDevice.BOND_BONDING || controller.operation(device.address)==15->row("取消配对",enabled=controller.ready){note=controller.action(device,14);render()}
            device.state==android.bluetooth.BluetoothProfile.STATE_CONNECTING || controller.operation(device.address)==11->row("取消连接",enabled=controller.ready){note=controller.action(device,12);render()}
            device.connected->row("断开连接",if(device.isInput)"这可能断开当前遥控器" else "",enabled=enabled){
                page="disconnect-bluetooth";render();(body.findViewWithTag<View>("control:row:取消")?:backButton).requestFocus()
            }
            device.bond==BluetoothDevice.BOND_BONDED->row("连接",enabled=enabled){note=controller.action(device,11);render()}
            else->row("配对并连接",enabled=enabled){note=controller.action(device,15);render()}
        }
        if(device.bond!=BluetoothDevice.BOND_NONE)row("忽略设备","删除配对记录",enabled=enabled){page="forget-bluetooth";render();(body.findViewWithTag<View>("control:row:取消")?:backButton).requestFocus()}
        if(!controller.ready)note("本地管理服务未激活，请使用 PTV 安装工具重新激活。")
        if(controller.status.isNotBlank())note(controller.status)
    }
    private fun restoreBluetoothFocus(){
        (bluetoothReturnTag?.let{detail.findViewWithTag<View>(it)}?:detail.findViewWithTag<View>("control:row:bluetooth:scan")?:backButton).requestFocus()
        scroll.post{if(page=="bluetooth")scroll.scrollTo(0,bluetoothScroll)}
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(busy)return true
        if(event.keyCode==KeyEvent.KEYCODE_BACK){if(event.action==KeyEvent.ACTION_UP)back();return true}
        return super.dispatchKeyEvent(event)
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();services.start()}
    override fun onDetachedFromWindow(){dispose();super.onDetachedFromWindow()}
    fun dispose(){if(disposed)return;disposed=true;animation?.cancel();services.stop();scope.cancel();backdrop.image=null;ownedBitmap?.recycle();ownedBitmap=null}
}
