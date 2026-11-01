// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.content.Context
import android.graphics.Rect
import android.widget.ScrollView

/** ScrollView's default focus calculation includes clipped padding in its viewport.
 * Keep the entire focused row inside the drawable viewport. Its artwork and the
 * maximum focus halo fit within that row, including during the scale animation. */
internal class RailScrollView(context:Context):ScrollView(context) {
    init { isVerticalFadingEdgeEnabled=false }
    override fun computeScrollDeltaToGetChildRectOnScreen(rect:Rect):Int {
        if(childCount==0)return 0
        val top=scrollY+paddingTop
        val bottom=scrollY+height-paddingBottom
        val delta=when {
            rect.top<top -> rect.top-top
            rect.bottom>bottom -> rect.bottom-bottom
            else -> 0
        }
        val maximum=(getChildAt(0).bottom+paddingBottom-height).coerceAtLeast(0)
        return (scrollY+delta).coerceIn(0,maximum)-scrollY
    }
}
