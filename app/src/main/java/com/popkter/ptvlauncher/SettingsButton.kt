package com.popkter.ptvlauncher
import android.content.Context
import com.popkter.glass_comonpent.*
internal class SettingsButton(context:Context):GlassButton(context) {
    init { gridSize=GlassSize.X1Y1;iconOnly=true;icon=context.getDrawable(R.drawable.f7_gear_alt);title="桌面设置";tag="桌面设置按钮" }
}
