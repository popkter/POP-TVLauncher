package com.popkter.ptvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperOrderTest {
    @Test fun movingAcrossRowsPreservesAllOtherRelativePositions() {
        assertEquals(listOf("b","c","d","e","a","f"),WallpaperOrder.move(listOf("a","b","c","d","e","f"),"a",4))
    }
    @Test fun boundsAndMissingImageAreSafe() {
        val names=listOf("a","b","c")
        assertEquals(names,WallpaperOrder.move(names,"missing",1))
        assertEquals(names,WallpaperOrder.move(names,"a",-4))
        assertEquals(listOf("c","a","b"),WallpaperOrder.move(names,"c",-4))
        assertEquals(emptyList<String>(),WallpaperOrder.move(emptyList(),"a",1))
    }
}
