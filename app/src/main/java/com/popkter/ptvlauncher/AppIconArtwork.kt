package com.popkter.ptvlauncher

import android.graphics.*
import java.util.WeakHashMap

/** Extend the original square artwork sideways without stretching or replacing its logo. */
internal object AppIconArtwork {
    private val backgrounds=WeakHashMap<Bitmap,Int>()
    private val backdrops=WeakHashMap<Bitmap,Bitmap>()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip=Path()

    fun background(bitmap:Bitmap?):Int {
        if(bitmap==null)return 0xff454a55.toInt()
        return backgrounds.getOrPut(bitmap) {
            val colors=IntArray(32*32)
            for(y in 0 until 32)for(x in 0 until 32) {
                colors[y*32+x]=bitmap.getPixel(x*(bitmap.width-1)/31,y*(bitmap.height-1)/31)
            }
            IconBackgroundColor.extract(colors,32,32)
        }
    }

    fun draw(canvas:Canvas,app:AppEntry,rect:RectF,radius:Float) {
        val bitmap=app.icon
        paint.shader=null;paint.style=Paint.Style.FILL;paint.color=background(bitmap)
        canvas.drawRoundRect(rect,radius,radius,paint)
        canvas.save()
        clip.reset();clip.addRoundRect(rect,radius,radius,Path.Direction.CW);canvas.clipPath(clip)
        if(bitmap!=null) {
            val scale=minOf(rect.width()/bitmap.width,rect.height()/bitmap.height)
            val w=bitmap.width*scale;val h=bitmap.height*scale
            paint.color=Color.WHITE
            val image=RectF(rect.centerX()-w/2,rect.centerY()-h/2,rect.centerX()+w/2,rect.centerY()+h/2)
            val backdrop=backdrops.getOrPut(bitmap) {
                val pixels=IntArray(bitmap.width*bitmap.height)
                bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                val outWidth=(bitmap.height*16f/9f).toInt().coerceAtLeast(bitmap.width)
                Bitmap.createBitmap(IconEdgeBackdrop.extend(pixels,bitmap.width,bitmap.height,outWidth,background(bitmap)),
                    outWidth,bitmap.height,Bitmap.Config.ARGB_8888)
            }
            canvas.drawBitmap(backdrop,null,rect,paint)
            canvas.drawBitmap(bitmap,null,image,paint)
        } else {
            paint.color=Color.WHITE;paint.textSize=rect.height()*.4f;paint.textAlign=Paint.Align.CENTER
            val baseline=rect.centerY()-(paint.ascent()+paint.descent())/2
            canvas.drawText(app.label.take(1),rect.centerX(),baseline,paint)
        }
        canvas.restore()
    }
}

/** Edge colors describe the background better than the often brightly colored central logo. */
internal object IconBackgroundColor {
    fun extract(pixels:IntArray,width:Int,height:Int):Int {
        require(width>0 && height>0 && pixels.size==width*height)
        fun sample(edgesOnly:Boolean):Int? {
            val bins=HashMap<Int,LongArray>()
            for(y in 0 until height)for(x in 0 until width) {
                if(edgesOnly && x>=width/5 && x<width-width/5 && y>=height/5 && y<height-height/5)continue
                val c=pixels[y*width+x]
                if((c ushr 24)<224)continue
                val r=c ushr 16 and 255;val g=c ushr 8 and 255;val b=c and 255
                val key=(r/16 shl 8) or (g/16 shl 4) or (b/16)
                val bin=bins.getOrPut(key){LongArray(4)}
                bin[0]++;bin[1]+=r;bin[2]+=g;bin[3]+=b
            }
            val b=bins.maxByOrNull{it.value[0]}?.value ?: return null
            return (0xff000000L or ((b[1]/b[0]) shl 16) or ((b[2]/b[0]) shl 8) or (b[3]/b[0])).toInt()
        }
        return sample(true) ?: sample(false) ?: 0xff454a55.toInt()
    }
}

/** Continue each side independently; fill transparent rounded source corners underneath the logo. */
internal object IconEdgeBackdrop {
    fun extend(pixels:IntArray,width:Int,height:Int,outWidth:Int,fallback:Int):IntArray {
        require(width>0 && height>0 && outWidth>=width && pixels.size==width*height)
        fun edge(right:Boolean):IntArray {
            val profile=IntArray(height)
            for(y in 0 until height) {
                for(offset in 0..width/4) {
                    val x=if(right)width-1-offset else offset
                    val c=pixels[y*width+x]
                    if((c ushr 24)>=240) {profile[y]=c or 0xff000000.toInt();break}
                }
            }
            val valid=profile.indices.filter {profile[it]!=0}
            for(y in profile.indices)if(profile[y]==0) {
                profile[y]=valid.minByOrNull{kotlin.math.abs(it-y)}?.let{profile[it]} ?: fallback
            }
            return profile
        }
        val left=edge(false);val right=edge(true)
        val offset=(outWidth-width)/2f
        return IntArray(outWidth*height) {index->
            val y=index/outWidth;val x=index%outWidth
            val t=((x-offset)/(width-1).coerceAtLeast(1)).coerceIn(0f,1f)
            fun channel(shift:Int):Int {
                val l=left[y] ushr shift and 255;val r=right[y] ushr shift and 255
                return (l+(r-l)*t+.5f).toInt()
            }
            0xff000000.toInt() or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}
