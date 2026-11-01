package com.popkter.ptvlauncher
import android.content.Context
import com.popkter.glass_comonpent.*
internal fun closeControl(context:Context,description:String,action:()->Unit)=GlassButton(context).apply {
    gridSize=GlassSize.X1Y1;iconOnly=true;icon=context.getDrawable(R.drawable.f7_xmark);title=description
    glassStyle=glassStyle.copy(iconSize=22f,cornerRadius=100f);setOnClickListener{action()}
}
