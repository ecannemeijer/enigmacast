package com.enigmacast.ui.xtream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/** Channel logos decoded at the size they are shown: subsampled while decoding, then scaled. */
internal object LogoDecode {
    /** The largest power of two that keeps [width] at least [maxPx]. */
    fun sampleSize(width: Int, maxPx: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= maxPx) sample *= 2
        return sample
    }

    fun decode(bytes: ByteArray, maxPx: Int): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, maxPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val scaled = if (bmp.width > maxPx) {
            Bitmap.createScaledBitmap(bmp, maxPx, (bmp.height * maxPx.toFloat() / bmp.width).toInt().coerceAtLeast(1), true).also { if (it !== bmp) bmp.recycle() }
        } else {
            bmp
        }
        scaled.asImageBitmap()
    }.getOrNull()
}
