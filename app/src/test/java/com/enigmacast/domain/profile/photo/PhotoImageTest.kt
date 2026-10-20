package com.enigmacast.domain.profile.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoImageTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun sniffsRealImageTypes() {
        assertEquals(PhotoImage.Format.JPEG, PhotoImage.sniff(b(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(PhotoImage.Format.PNG, PhotoImage.sniff(b(0x89, 'P'.code, 'N'.code, 'G'.code, 13, 10, 26, 10)))
        assertEquals(PhotoImage.Format.WEBP, PhotoImage.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(PhotoImage.Format.HEIF, PhotoImage.sniff("\u0000\u0000\u0000\u0018ftypheic".toByteArray(Charsets.ISO_8859_1)))
        assertNull(PhotoImage.sniff("<html><script>".toByteArray()))
        assertNull(PhotoImage.sniff(ByteArray(0)))
    }

    /** A JPEG with only SOI + an APP1 Exif segment holding one Orientation entry. */
    private fun jpeg(orientation: Int, little: Boolean): ByteArray {
        fun u16(v: Int) = if (little) b(v and 0xff, v shr 8) else b(v shr 8, v and 0xff)
        fun u32(v: Int) = if (little) b(v and 0xff, (v shr 8) and 0xff, 0, 0) else b(0, 0, (v shr 8) and 0xff, v and 0xff)
        val tiff = (if (little) "II".toByteArray() else "MM".toByteArray()) + u16(42) + u32(8) +
            u16(1) + u16(0x0112) + u16(3) + u32(1) + u16(orientation) + u16(0) + u32(0)
        val app1 = "Exif".toByteArray() + b(0, 0) + tiff
        val len = app1.size + 2
        return b(0xFF, 0xD8, 0xFF, 0xE1, len shr 8, len and 0xff) + app1 + b(0xFF, 0xD9)
    }

    @Test fun readsExifOrientationInBothByteOrders() {
        assertEquals(6, PhotoImage.jpegOrientation(jpeg(6, little = true)))
        assertEquals(8, PhotoImage.jpegOrientation(jpeg(8, little = false)))
        assertEquals(1, PhotoImage.jpegOrientation(b(0xFF, 0xD8, 0xFF, 0xD9)))
        assertEquals(1, PhotoImage.jpegOrientation(b(0x89, 0x50)))
        // A truncated segment is ignored, not a crash.
        assertEquals(1, PhotoImage.jpegOrientation(jpeg(6, true).copyOf(20)))
        assertEquals(true, PhotoImage.swapsAxes(6))
        assertEquals(false, PhotoImage.swapsAxes(3))
    }

    @Test fun cropIsSquareAndInsideTheImage() {
        assertEquals(PhotoImage.Square(500, 0, 3000), PhotoImage.square(4000, 3000, null, null, null))
        assertEquals(PhotoImage.Square(1000, 750, 1000), PhotoImage.square(4000, 3000, 0.25, 0.25, 0.25))
        // Out of range values are pulled back inside.
        assertEquals(PhotoImage.Square(1000, 0, 3000), PhotoImage.square(4000, 3000, 0.9, -0.5, 2.0))
        assertEquals(PhotoImage.Square(0, 100, 1000), PhotoImage.square(1000, 2000, -1.0, 0.05, 1.0))
        assertEquals(PhotoImage.Square(500, 0, 3000), PhotoImage.square(4000, 3000, Double.NaN, 0.1, 0.5))
    }

    @Test fun samplesDownButKeepsEnoughPixels() {
        assertEquals(4, PhotoImage.sampleSize(4000, 3000, 3000))
        assertEquals(1, PhotoImage.sampleSize(800, 600, 600))
        assertEquals(1, PhotoImage.sampleSize(4000, 3000, 600))
        assertEquals(2, PhotoImage.sampleSize(8000, 6000, 600)) // 48 MP: memory bound
    }
}
