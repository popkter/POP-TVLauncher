package com.popkter.ptvlauncher
import com.popkter.glass_comonpent.*

import android.app.Activity
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class WallpaperSettingsActivity:Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val graph by lazy{LauncherGraph.get(this)}
    private var server:WallpaperUploadServer?=null
    private var uploading=false
    private var started=false
    private var receiverJob:Job?=null
    private var collector:Job?=null
    private var prefs=HomePreferences()
    private var entries:List<Pair<WallpaperEntry,Bitmap?>> = emptyList()
    private var status=""
    private var backdrop:Bitmap?=null
    private var selecting=false
    private var sorting=false
    private var moving:String?=null
    private var confirmingDelete=false
    private val selected=mutableSetOf<String>()
    private var gridPosition=0
    private var focusedPhoto:String?=null
    private var busy=false
    private var closingToControlCenter=false
    private var sharedBackdrop:Bitmap?=null
    private lateinit var scene:FrameLayout
    private lateinit var contentLayer:FrameLayout
    private var backdropLayer:View?=null
    private fun showScene(content:View) {
        if(!::scene.isInitialized) {
            scene=FrameLayout(this).apply{tag="wallpaper:scene"}
            sharedBackdrop?.let{image->
                backdropLayer=object:View(this) {
                    private val paint=android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                    override fun onDraw(canvas:android.graphics.Canvas) {
                        canvas.drawBitmap(image,null,android.graphics.RectF(0f,0f,width.toFloat(),height.toFloat()),paint)
                        canvas.drawColor(0x36121e2c)
                    }
                }.apply{tag="wallpaper:fixed-background";alpha=0f}
                scene.addView(backdropLayer,FrameLayout.LayoutParams(-1,-1))
            }
            contentLayer=FrameLayout(this).apply{tag="wallpaper:content";alpha=0f;translationX=dp(24).toFloat()}
            scene.addView(contentLayer,FrameLayout.LayoutParams(-1,-1));setContentView(scene)
            scene.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw():Boolean {
                    scene.viewTreeObserver.removeOnPreDrawListener(this)
                    contentLayer.animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    backdropLayer?.animate()?.alpha(1f)?.setDuration(320)?.start()
                    return true
                }
            })
        }
        contentLayer.removeAllViews();contentLayer.addView(content,FrameLayout.LayoutParams(-1,-1))
    }
    private fun finishToControlCenter() {
        @Suppress("DEPRECATION") val origin=intent.getParcelableExtra<android.graphics.Rect>("control_wallpaper_origin")
        if(origin==null){finish();return}
        if(closingToControlCenter)return
        closingToControlCenter=true
        backdropLayer?.animate()?.alpha(0f)?.setDuration(260)?.start()
        contentLayer.animate().alpha(0f).translationX(dp(24).toFloat())
            .setDuration(260).setInterpolator(android.view.animation.PathInterpolator(.4f,0f,.2f,1f))
            .withEndAction{finish();@Suppress("DEPRECATION") overridePendingTransition(0,0)}.start()
    }
    override fun onCreate(state:Bundle?) {super.onCreate(state);sharedBackdrop=WallpaperTransitionBackdrop.take();if(android.os.Build.VERSION.SDK_INT>=33)onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT){goBack()};window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);render()}
    override fun onStart() {
        super.onStart();started=true
        collector=scope.launch {
            combine(graph.store.state,graph.customWallpapers.revision){p,r->p to r}.collect { (p,_)->
                prefs=p
                entries=withContext(Dispatchers.IO) {graph.customWallpapers.entries().map {entry->
                    entry to BitmapFactory.decodeFile(entry.file.path,BitmapFactory.Options().apply{inSampleSize=8})
                }}
                selected.retainAll(entries.map{it.first.file.name}.toSet())
                if(backdrop==null && sharedBackdrop==null) {
                    backdrop=graph.wallpaperBackdrop ?: withContext(Dispatchers.IO) {
                        val file=if(prefs.customWallpaper)entries.firstOrNull{it.first.file.name==prefs.fixedWallpaper}?.first?.file ?: entries.firstOrNull()?.first?.file
                            else graph.wallpaper.loadCached().entries.firstOrNull()?.file
                        file?.let{BitmapFactory.decodeFile(it.path,BitmapFactory.Options().apply{inSampleSize=2})}?.let{source->
                            BackdropBlur.create(source).also{source.recycle()}
                        }
                    }
                }
                render()
            }
        }
        if(uploading)startReceiver()
    }
    override fun onStop() {started=false;collector?.cancel();receiverJob?.cancel();server?.close();server=null;super.onStop()}
    override fun onDestroy() {scope.cancel();sharedBackdrop?.recycle();sharedBackdrop=null;super.onDestroy()}
    private fun goBack() {
        when {
            uploading->{uploading=false;receiverJob?.cancel();server?.close();server=null;render()}
            moving!=null->{moving=null;render()}
            selecting || sorting->{selecting=false;sorting=false;selected.clear();confirmingDelete=false;render()}
            else->finishToControlCenter()
        }
    }
    // Physical TV remotes need paired key handling; gestures use the registered callback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event:android.view.KeyEvent):Boolean {
        if(event.keyCode==android.view.KeyEvent.KEYCODE_BACK) {
            if(event.action==android.view.KeyEvent.ACTION_UP && !event.isCanceled)goBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    private fun startReceiver() {
        receiverJob?.cancel()
        receiverJob=scope.launch {
            status="正在建立上传连接…";render()
            var opened:WallpaperUploadServer?=null
            var retained=false
            try {
                val created=withContext(Dispatchers.IO){WallpaperUploadServer{graph.customWallpapers.add(it)}.also{opened=it}}
                if(!started || !uploading) {created.close();return@launch}
                server?.close();server=created;retained=true
                status=if(created.url==null)"未找到局域网地址，请连接 Wi-Fi 或有线网络后重试" else ""
            } catch(e:Exception) {if(e is CancellationException)throw e;status="无法启动上传，请重试"} finally {if(!retained)opened?.close()}
            render()
        }
    }
    private fun backgroundRoot():FrameLayout = FrameLayout(this).apply {
        if(sharedBackdrop==null)addView(WallpaperView(this@WallpaperSettingsActivity).apply{bitmap=backdrop},FrameLayout.LayoutParams(-1,-1))
    }
    private fun mutate(block: suspend ()->Unit) {
        if(busy)return
        busy=true
        scope.launch {
            try {block()} catch(e:Exception) {
                if(e is CancellationException)throw e
                com.popkter.glass_comonpent.GlassMessages.show(this@WallpaperSettingsActivity,"操作失败，请重试")
            } finally {busy=false}
        }
    }
    private fun control(title:String,key:String,active:Boolean=false,action:()->Unit):GlassButton = GlassButton(this).apply {
        this.title=title;tag=key;isSelected=active;contentLayout=GlassContentLayout.ICON_TEXT
        glassStyle=glassStyle.copy(contentPadding=12f,cornerRadius=16f)
        if(key=="confirm" || key=="delete")titleView.setTextColor(0xffff6961.toInt())
        setOnClickListener{action()}
    }
    private fun render() {
        if(uploading){renderUpload();return}
        if(isFinishing)return
        val previous=currentFocus?.tag
        val restorePhoto=moving ?: focusedPhoto
        val root=backgroundRoot()
        val content=LinearLayout(this).apply{tag="wallpaper:layout";orientation=LinearLayout.VERTICAL;setPadding(dp(36),0,dp(36),0)}
        root.addView(content,FrameLayout.LayoutParams(-1,dp(ControlCenterLayout.HEIGHT)).apply{topMargin=dp(ControlCenterLayout.TOP)})
        val heading=LinearLayout(this).apply{tag="wallpaper:header";gravity=Gravity.CENTER_VERTICAL}
        heading.addView(label(this,"壁纸",23f),LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(closeControl(this,"返回桌面"){finishToControlCenter()}.apply{tag="back"},LinearLayout.LayoutParams(dp(36),dp(36)))
        content.addView(heading,LinearLayout.LayoutParams(-1,dp(ControlCenterLayout.HEADER_HEIGHT)))
        val columns=LinearLayout(this).apply{tag="wallpaper:body"}
        content.addView(columns,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=dp(ControlCenterLayout.CONTENT_TOP-ControlCenterLayout.HEADER_HEIGHT)})
        val side=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        columns.addView(side,LinearLayout.LayoutParams(dp(202),-1).apply{marginEnd=dp(26)})
        side.addView(label(this,"壁纸来源",11f),LinearLayout.LayoutParams(-1,dp(28)))
        val sideButtons=mutableListOf<View>()
        fun option(title:String,key:String,active:Boolean,action:()->Unit) {
            val button=control(title,key,active,action)
            sideButtons.add(button)
            side.addView(button,LinearLayout.LayoutParams(-1,dp(44)).apply{bottomMargin=dp(10)})
        }
        option("必应壁纸轮播","bing",!prefs.customWallpaper){scope.launch{graph.store.wallpaperSource(false)}}
        option("自定义相册","custom",prefs.customWallpaper){scope.launch{graph.store.wallpaperSource(true)}}
        side.addView(label(this,"自定义播放方式",11f),LinearLayout.LayoutParams(-1,dp(34)).apply{topMargin=dp(20)})
        option("顺序轮播 · 每 30 秒","rotate",prefs.customWallpaper && prefs.customRotate){scope.launch{graph.store.wallpaperSource(true);graph.store.customRotation(true)}}
        option("固定一张","fixed",prefs.customWallpaper && !prefs.customRotate){
            entries.getOrNull(gridPosition)?.first?.let{scope.launch{graph.store.fixedWallpaper(it.file.name)}}
                ?: com.popkter.glass_comonpent.GlassMessages.show(this,"请先上传图片")
        }
        side.addView(label(this,"相册顺序即轮播顺序\n选中图片可固定显示",11f),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(18)})
        val album=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        columns.addView(album,LinearLayout.LayoutParams(0,-1,1f))
        val toolbar=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        toolbar.addView(label(this,if(selecting)"已选 ${selected.size} 张" else "自定义相册 · ${entries.size} 张",14f),LinearLayout.LayoutParams(0,-2,1f))
        fun tool(title:String,key:String,action:()->Unit) {
            toolbar.addView(control(title,key,false,action),LinearLayout.LayoutParams(-2,dp(44)).apply{marginStart=dp(7)})
        }
        if(selecting) {
            if(confirmingDelete) {
                tool("确认删除 ${selected.size} 张","confirm"){
                    val names=selected.toSet();mutate {withContext(Dispatchers.IO){graph.customWallpapers.deleteMany(names)};selected.clear();confirmingDelete=false;render()}
                }
                tool("取消","canceldelete"){confirmingDelete=false;render()}
            } else {
                tool("全选","all"){selected.clear();selected.addAll(entries.map{it.first.file.name});render()}
                tool("删除","delete"){if(selected.isNotEmpty()){confirmingDelete=true;render()}}
                tool("完成","doneSelect"){selecting=false;selected.clear();render()}
            }
        } else if(sorting) {
            tool("完成排序","doneSort"){sorting=false;moving=null;render()}
        } else {
            tool("上传","upload"){uploading=true;startReceiver()}
            tool("批量删除","select"){selecting=true;render()}
            tool("排序","sort"){sorting=true;render()}
        }
        album.addView(toolbar,LinearLayout.LayoutParams(-1,dp(44)))
        val hint=label(this,when {
            confirmingDelete->"确认后将从电视删除所选图片"
            moving!=null->"方向键移动图片 · 确定放下 · 返回结束移动"
            sorting->"选择图片并按确定拿起，再用方向键移动"
            selecting->"按确定勾选图片，再选择顶部删除"
            else->"确定固定当前图片 · 长按进入排序"
        },10f)
        album.addView(hint,LinearLayout.LayoutParams(-1,dp(27)).apply{topMargin=dp(8)})
        val grid=GlassGridView(this).apply {
            id=View.generateViewId();tag="album:grid";numColumns=4
            if(android.os.Build.VERSION.SDK_INT>=26)defaultFocusHighlightEnabled=false
            horizontalSpacing=dp(12);verticalSpacing=dp(12)
            setPadding(dp(6),dp(6),dp(6),dp(6));clipToPadding=false
            selector=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled=false
            adapter=object:BaseAdapter() {
                override fun getCount()=entries.size
                override fun getItem(position:Int)=entries[position]
                override fun getItemId(position:Int)=position.toLong()
                override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View {
                    val (entry,bitmap)=entries[position]
                    val fixed=prefs.customWallpaper && !prefs.customRotate && (prefs.fixedWallpaper==entry.file.name || (entries.none{it.first.file.name==prefs.fixedWallpaper} && position==0))
                    return WallpaperThumbnail(this@WallpaperSettingsActivity,bitmap,position+1,
                        if(selecting)entry.file.name in selected else fixed,entry.file.name==moving).apply {
                        layoutParams=AbsListView.LayoutParams(-1,dp(104));contentDescription="图片 ${position+1}"+(if(fixed)"，当前固定壁纸" else "")
                    }
                }
            }
            fun refreshSelection() {
                for(i in 0 until childCount)getChildAt(i).isSelected=hasFocus() && firstVisiblePosition+i==selectedItemPosition
            }
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(p:AdapterView<*>?) {}
                override fun onItemSelected(p:AdapterView<*>?,v:View?,position:Int,id:Long) {
                    gridPosition=position;focusedPhoto=entries.getOrNull(position)?.first?.file?.name;refreshSelection()
                }
            }
            setOnFocusChangeListener{_,_->refreshSelection()}
            onItemClickListener=AdapterView.OnItemClickListener{_,_,position,_->
                val name=entries[position].first.file.name;focusedPhoto=name
                when {
                    selecting->{if(!selected.add(name))selected.remove(name);confirmingDelete=false;render()}
                    sorting->{moving=if(moving==name)null else name;render()}
                    else->scope.launch{graph.store.fixedWallpaper(name)}
                }
            }
            onItemLongClickListener=AdapterView.OnItemLongClickListener{_,_,position,_->
                if(!selecting){sorting=true;moving=entries[position].first.file.name;focusedPhoto=moving;render()};true
            }
            setOnKeyListener{_,code,event->
                val direction=when(code){19->-4;20->4;21->-1;22->1;else->0}
                if(moving!=null && direction!=0) {
                    if(event.action==android.view.KeyEvent.ACTION_DOWN && !busy) {
                        val from=entries.indexOfFirst{it.first.file.name==moving};val to=from+direction
                        if(to in entries.indices && (kotlin.math.abs(direction)!=1 || from/4==to/4)) {
                            val name=moving!!;mutate{withContext(Dispatchers.IO){graph.customWallpapers.move(name,direction)}}
                        }
                    };true
                } else if(event.action==android.view.KeyEvent.ACTION_DOWN && code==21 && selectedItemPosition%4==0) {sideButtons[1].requestFocus();true}
                else false
            }
        }
        val gridBox=FrameLayout(this)
        gridBox.addView(grid,FrameLayout.LayoutParams(-1,-1))
        if(entries.isEmpty())gridBox.addView(label(this,"还没有照片\n选择右上角“上传”，用手机扫码添加",16f).apply{gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-1))
        album.addView(gridBox,LinearLayout.LayoutParams(-1,0,1f))
        for(button in sideButtons)button.nextFocusRightId=grid.id
        for(i in 1 until toolbar.childCount)toolbar.getChildAt(i).nextFocusDownId=grid.id
        showScene(root)
        grid.post {
            val position=entries.indexOfFirst{it.first.file.name==restorePhoto}.takeIf{it>=0} ?: gridPosition.coerceIn(0,(entries.size-1).coerceAtLeast(0))
            grid.setSelection(position)
            (previous?.let{root.findViewWithTag<View>(it)} ?: root.findViewWithTag(if(confirmingDelete)"confirm" else if(selecting)"all" else if(sorting)"album:grid" else "custom"))?.requestFocus()
        }
    }
    private fun renderUpload() {
        if(isFinishing)return
        val oldFocus=currentFocus?.tag
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(42),dp(24),dp(42),dp(24));setBackgroundColor(0xff182333.toInt())}
        box.setBackgroundColor(Color.TRANSPARENT)
        val scroll=GlassScrollView(this).apply{isFillViewport=true;addView(box)}
        fun text(value:String,size:Float=15f)=label(this,value,size).also{box.addView(it,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})}
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        header.addView(label(this,"扫码上传壁纸",28f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(closeControl(this,"返回壁纸设置"){goBack()}.apply{tag="done"},LinearLayout.LayoutParams(dp(44),dp(44)))
        box.addView(header,LinearLayout.LayoutParams(-1,dp(48)).apply{bottomMargin=dp(12)})
        if(uploading) {
            text("手机与电视连接同一局域网 · 图片直接保存到电视",13f)
            val address=server?.url
            if(address!=null) {
                val bits=QRCodeWriter().encode(address,BarcodeFormat.QR_CODE,360,360)
                val pixels=IntArray(360*360){if(bits[it%360,it/360])Color.BLACK else Color.WHITE}
                val bitmap=Bitmap.createBitmap(pixels,360,360,Bitmap.Config.ARGB_8888)
                box.addView(GlassIconView(this).apply{setImageBitmap(bitmap);contentDescription="扫码上传壁纸"},LinearLayout.LayoutParams(dp(190),dp(190)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(12)})
                text(address,13f)
                text("已保存 ${entries.size} / 30 张 · 支持 JPG、PNG、WebP，每张不超过 12 MB",13f)
            } else text(status,14f)
            box.addView(control("刷新二维码","retry"){startReceiver()},LinearLayout.LayoutParams(dp(160),dp(44)).apply{gravity=Gravity.CENTER_HORIZONTAL})
        }
        showScene(backgroundRoot().apply{addView(scroll)})
        (oldFocus?.let{box.findViewWithTag<View>(it)} ?: box.findViewWithTag(if(uploading)"done" else "bing"))?.requestFocus()
    }
}
