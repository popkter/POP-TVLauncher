package com.popkter.ptvlauncher

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppSnapshotCacheTest {
    @Test fun snapshotSurvivesNewReaderWithArtworkAndOrder() {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"snapshot-test-${System.nanoTime()}")
        root.mkdirs()
        try {
            val icon=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.RED)}
            val banner=Bitmap.createBitmap(32,18,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.BLUE)}
            val input=listOf(AppEntry("app.a","应用 A",icon,true,banner,Color.GREEN),AppEntry("app.b","系统组件",null,false))
            AppSnapshotCache(root).write(input)
            val read=AppSnapshotCache(root).read()
            assertEquals(input.map {it.id},read.map {it.id})
            assertEquals(input.map {it.label},read.map {it.label})
            assertEquals(Color.RED,read[0].icon!!.getPixel(0,0))
            assertEquals(Color.BLUE,read[0].banner!!.getPixel(0,0))
            assertEquals(Color.GREEN,read[0].brandColor)
            assertFalse(read[1].launchable)
            assertNull(read[1].icon)
            assertNull(read[1].banner)
        } finally {root.deleteRecursively()}
    }
    @Test fun stagedReadKeepsOrderAndDecodesPinnedArtworkFirst() = kotlinx.coroutines.runBlocking {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"staged-${System.nanoTime()}")
        root.mkdirs()
        try {
            val icon=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.RED)}
            val cache=AppSnapshotCache(root)
            cache.write(listOf(AppEntry("a","A",icon,true,artworkKey="v1"),AppEntry("b","B",icon,true,icon,artworkKey="v2")))
            var published=false
            val full=cache.readStaged(listOf("b")) { first ->
                published=true
                assertEquals(listOf("a","b"),first.map {it.id})
                assertNull(first[0].icon)
                assertNotNull(first[1].icon)
                assertNotNull(first[1].banner)
                assertEquals("v2",first[1].artworkKey)
            }
            assertTrue(published)
            assertNotNull(full[0].icon)
            assertEquals("v1",full[0].artworkKey)
        } finally {root.deleteRecursively()}
    }
    @Test fun legacySnapshotRemainsReadable() {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"legacy-${System.nanoTime()}")
        root.mkdirs()
        try {
            java.io.DataOutputStream(File(root,"apps-v1.bin").outputStream()).use {
                it.writeInt(0x50545631);it.writeInt(1)
                it.writeUTF("old");it.writeUTF("Old app");it.writeBoolean(true);it.writeInt(Color.RED)
                it.writeInt(0);it.writeInt(0)
            }
            val app=AppSnapshotCache(root).read().single()
            assertEquals("old",app.id)
            assertEquals("",app.artworkKey)
        } finally {root.deleteRecursively()}
    }
    @Test fun missingOrTruncatedSnapshotFallsBackToScan() {
        val root=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"snapshot-test-${System.nanoTime()}")
        root.mkdirs()
        try {
            assertTrue(AppSnapshotCache(root).read().isEmpty())
            File(root,"apps-v1.bin").writeBytes(byteArrayOf(0x50,0x54))
            assertTrue(AppSnapshotCache(root).read().isEmpty())
        } finally {root.deleteRecursively()}
    }
}
