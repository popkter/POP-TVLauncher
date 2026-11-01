package com.popkter.ptvlauncher
import android.content.Context
import com.popkter.glass_comonpent.GlassArtworkCard
import com.popkter.glass_comonpent.GlassArtworkEntry
import com.popkter.glass_comonpent.GlassAppListButton
internal object AppLayout {
    const val HOME_RIGHT_MARGIN=30
    const val RAIL_TOP=70
    const val RAIL_SCROLL_INSET=8
    const val CARD_INSET=8
    const val RAIL_CARD_WIDTH=112
    const val RAIL_CARD_HEIGHT=63
    const val RAIL_ROW_HEIGHT=83
    const val RAIL_WIDTH=246
    const val LIBRARY_COLUMNS=5
    const val LIBRARY_ROW_HEIGHT=98
}


internal class AppCardView(context:Context):GlassArtworkCard(context) {
    var entry:AppEntry?=null
        private set
    fun bind(app:AppEntry,inLibrary:Boolean,isPinned:Boolean) {
        entry=app
        bind(GlassArtworkEntry(app.id,app.label){canvas,rect,radius->AppIconArtwork.draw(canvas,app,rect,radius)},inLibrary,isPinned)
    }
}
internal class AppListButton(context:Context):GlassAppListButton(context)
