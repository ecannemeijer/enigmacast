package com.enigmacast.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassBlurTest {
    @Test
    fun aUniformFieldStaysUniform() {
        val src = IntArray(9) { 0xFF406080.toInt() }
        assertArrayEquals(src, GlassBlur.boxBlur(src, 3, 3, radius = 1, passes = 2))
    }

    @Test
    fun aSpikeIsSmoothed() {
        val src = IntArray(25) { 0 }
        src[12] = 0xFF0000FF.toInt()
        val out = GlassBlur.boxBlur(src, 5, 5, radius = 1, passes = 1)
        assertTrue((out[12] and 0xFF) < 0xFF)
        assertTrue((out[7] and 0xFF) > 0)
        assertTrue((out[0] and 0xFF) == 0)
    }

    @Test
    fun sourceRectIsTheCoveredPartOfTheImage() {
        // Image 1920×1080 covering a 1920×1080 screen: a box at (100,200) of 300×150 is exactly that crop.
        val (off, size) = GlassBlur.sourceRect(IntSize(1920, 1080), IntSize(1920, 1080), Offset(100f, 200f), IntSize(300, 150))
        assertEquals(IntOffset(100, 200), off)
        assertEquals(IntSize(300, 150), size)
    }

    @Test
    fun sourceRectClampsToTheImage() {
        val (off, size) = GlassBlur.sourceRect(IntSize(100, 50), IntSize(200, 100), Offset(180f, 90f), IntSize(40, 20))
        assertTrue(off.x + size.width <= 100)
        assertTrue(off.y + size.height <= 50)
    }
}
