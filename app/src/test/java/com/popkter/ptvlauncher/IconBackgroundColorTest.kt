package com.popkter.ptvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class IconBackgroundColorTest {
    @Test fun prefersBackgroundOverLargeCentralLogo() {
        val background=0xfff8f9fa.toInt()
        val pixels=IntArray(100){background}
        for(y in 2..7)for(x in 2..7)pixels[y*10+x]=0xffff0000.toInt()
        assertEquals(background,IconBackgroundColor.extract(pixels,10,10))
    }
    @Test fun ignoresTransparentPaddingAndKeepsExactColor() {
        val pixels=IntArray(100)
        for(y in 3..6)for(x in 3..6)pixels[y*10+x]=0xff23a6ed.toInt()
        assertEquals(0xff23a6ed.toInt(),IconBackgroundColor.extract(pixels,10,10))
    }
    @Test fun transparentArtworkHasStableFallback() {
        assertEquals(0xff454a55.toInt(),IconBackgroundColor.extract(IntArray(100),10,10))
    }
    @Test fun extendsDifferentLeftAndRightGradientsWithoutASeam() {
        val pixels=IntArray(100) {i->if(i%10<5)0xff22aa33.toInt() else 0xff9933ee.toInt()}
        val output=IconEdgeBackdrop.extend(pixels,10,10,18,0xff000000.toInt())
        for(y in 0..9) {
            assertEquals(pixels[y*10],output[y*18])
            assertEquals(pixels[y*10+9],output[y*18+17])
        }
    }
    @Test fun fillsTransparentRoundedCornersFromVisibleBackground() {
        val pixels=IntArray(100){0xffea3399.toInt()}
        for(x in 0..1) {pixels[x]=0;pixels[9-x]=0}
        val output=IconEdgeBackdrop.extend(pixels,10,10,18,0xff000000.toInt())
        assertEquals(0xffea3399.toInt(),output[0])
        assertEquals(0xffea3399.toInt(),output[17])
    }
}
