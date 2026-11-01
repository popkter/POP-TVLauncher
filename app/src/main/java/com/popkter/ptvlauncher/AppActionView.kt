package com.popkter.ptvlauncher
import android.content.Context
import com.popkter.glass_comonpent.*
internal class AppActionView(context:Context,kind:String,text:String):GlassButton(context) {
    init {
        gridSize=GlassSize.X1Y1;iconOnly=true;title=text;tag="action:$kind"
        icon=context.getDrawable(when(kind){"remove"->R.drawable.f7_minus_circle;"add"->R.drawable.f7_plus_circle;"edit"->R.drawable.f7_line_horizontal_3;else->R.drawable.f7_info_circle})
    }
}
