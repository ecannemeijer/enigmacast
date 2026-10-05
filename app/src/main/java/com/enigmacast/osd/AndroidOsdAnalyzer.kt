package com.enigmacast.osd

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.enigmacast.domain.osd.OsdAnalysis
import com.enigmacast.domain.osd.OsdAnalyzer
import com.enigmacast.domain.osd.OsdHasher
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

class AndroidOsdAnalyzer : OsdAnalyzer {
    private val pool = ArrayDeque<Bitmap>()
    private val published = AtomicReference<Bitmap?>(null)
    private val ready = AtomicReference<Pair<Long, Bitmap>?>(null)

    fun bitmapFor(hash: Long): Bitmap? {
        return ready.get()?.takeIf { it.first == hash }?.second
    }

    fun markPublished(bitmap: Bitmap?) {
        published.set(bitmap)
    }

    override fun analyze(bytes: ByteArray): OsdAnalysis {
        val bitmap = decode(bytes, published.get())
        val hash = OsdHasher.crc32(bytes)
        ready.set(hash to bitmap)
        return OsdAnalysis(
            hash = hash,
            width = bitmap.width,
            height = bitmap.height,
            isEmpty = isMostlyTransparent(bitmap),
        )
    }

    private fun decode(bytes: ByteArray, keep: Bitmap?): Bitmap {
        val reusable = pool.firstOrNull { it !== keep && !it.isRecycled }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            if (reusable != null) inBitmap = reusable
        }
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: IllegalArgumentException) {
            options.inBitmap = null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } ?: error("OSD image could not be decoded")
        if (reusable != null && reusable !== bitmap) {
            pool.remove(reusable)
        } else if (reusable === bitmap) {
            pool.remove(reusable)
        }
        pool.addLast(bitmap)
        while (pool.size > 2) {
            val old = pool.removeFirst()
            if (old !== keep && old !== bitmap && !old.isRecycled) old.recycle()
        }
        return bitmap
    }

    private fun isMostlyTransparent(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return true
        val stepX = (width / 48).coerceAtLeast(1)
        val stepY = (height / 48).coerceAtLeast(1)
        val row = IntArray(width)
        var samples = 0
        var visible = 0
        var y = 0
        while (y < height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            var x = 0
            while (x < width) {
                samples += 1
                if ((row[x] ushr 24) > 12) visible += 1
                x += stepX
            }
            y += stepY
        }
        return samples == 0 || visible.toFloat() / samples < 0.005f
    }
}
