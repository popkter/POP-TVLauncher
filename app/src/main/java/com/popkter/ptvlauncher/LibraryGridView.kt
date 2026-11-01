// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.content.Context
import android.view.KeyEvent
import android.widget.GridView

/** The upper preview needs room for the label below the artwork as well as half an icon. */
internal class LibraryGridView(context:Context):com.popkter.glass_comonpent.GlassGridView(context) {
    private var availableHeight=0
    private var previewAbove=false
    private var alignAbove=false
    private val previewBelow get()=context.dp(48)
    private val extraAbove get()=context.dp(32)

    fun fitViewport(available:Int) {
        availableHeight=available
        updateHeight()
    }

    override fun setSelection(position:Int) {
        previewAbove=numColumns>0 && position>=numColumns && position/numColumns==(count-1)/numColumns
        alignAbove=previewAbove
        updateHeight()
        super.setSelection(position)
    }

    private fun updateHeight() {
        if(availableHeight<=0)return
        val row=context.dp(AppLayout.LIBRARY_ROW_HEIGHT)
        val padding=paddingTop+paddingBottom
        val rows=((availableHeight-padding-previewBelow+verticalSpacing)/(row+verticalSpacing)).coerceAtLeast(1)
        val target=(rows*row+(rows-1)*verticalSpacing+padding+previewBelow+
            if(previewAbove)extraAbove else 0).coerceAtMost(availableHeight)
        if(layoutParams.height!=target)layoutParams=layoutParams.apply {height=target}
    }

    override fun onKeyDown(keyCode:Int,event:KeyEvent):Boolean {
        val before=selectedItemPosition
        val handled=super.onKeyDown(keyCode,event)
        if(selectedItemPosition!=before && (keyCode==KeyEvent.KEYCODE_DPAD_UP || keyCode==KeyEvent.KEYCODE_DPAD_DOWN)) {
            val row=selectedItemPosition/numColumns
            previewAbove=row>0 && (keyCode==KeyEvent.KEYCODE_DPAD_UP || row==(count-1)/numColumns)
            alignAbove=previewAbove
            updateHeight()
            requestLayout()
        }
        return handled
    }

    override fun layoutChildren() {
        super.layoutChildren()
        if(!alignAbove || childCount==0)return
        alignAbove=false
        val selected=selectedView ?: return
        val lastRow=selectedItemPosition/numColumns==(count-1)/numColumns
        val delta=if(lastRow) {
            getChildAt(childCount-1).bottom-(height-paddingBottom)
        } else {
            (selected.top-(paddingTop+previewBelow+extraAbove)).coerceAtMost(0)
        }
        // Move existing children synchronously, before drawing; do not rebuild the adapter
        // or post a second frame that flashes the framework's label-only upper preview.
        if(delta!=0)scrollListBy(delta)
    }
}
