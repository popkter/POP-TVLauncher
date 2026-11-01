// Copyright 2026 popkter. SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
package com.popkter.ptvlauncher

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Three separable box passes approximate a Gaussian without low-resolution blocks.
 * Run off the UI thread for photos and captured frames; the output is shared by panels. */
internal object BackdropBlur {
    fun create(source: Bitmap): Bitmap {
        val width=480.coerceAtMost(source.width)
        val height=(source.height*width.toFloat()/source.width).roundToInt().coerceAtLeast(1)
        val scaled=Bitmap.createScaledBitmap(source,width,height,true)
        val pixels=IntArray(width*height)
        scaled.getPixels(pixels,0,width,0,0,width,height)
        if(scaled!==source)scaled.recycle()
        val scratch=IntArray(pixels.size)
        val radius=(width/60).coerceAtLeast(1)
        repeat(3) {
            pass(pixels,scratch,width,height,radius,true)
            pass(scratch,pixels,width,height,radius,false)
        }
        return Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
    }
    private fun pass(input:IntArray,output:IntArray,w:Int,h:Int,r:Int,horizontal:Boolean) {
        val length=if(horizontal)w else h
        val lines=if(horizontal)h else w
        val divisor=2*r+1
        for(line in 0 until lines) {
            fun index(position:Int)=if(horizontal)line*w+position.coerceIn(0,length-1) else position.coerceIn(0,length-1)*w+line
            var red=0;var green=0;var blue=0
            fun add(color:Int,sign:Int) {red+=((color ushr 16) and 255)*sign;green+=((color ushr 8) and 255)*sign;blue+=(color and 255)*sign}
            for(offset in -r..r)add(input[index(offset)],1)
            for(position in 0 until length) {
                output[index(position)]=(0xff shl 24) or ((red/divisor) shl 16) or ((green/divisor) shl 8) or (blue/divisor)
                add(input[index(position-r)],-1);add(input[index(position+r+1)],1)
            }
        }
    }
}
