package com.enigmacast.data.tvhome

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import com.enigmacast.domain.tvhome.TvHomeArt
import java.io.ByteArrayOutputStream

/**
 * A channel logo (receiver picon or IPTV logo, any format Android decodes) laid on a dark 16:9
 * card as a PNG: the launcher shows it whole instead of cropping it, and transparent white picons
 * stay readable. Null when the bytes are not an image.
 */
internal object TvHomeCardArt {
    fun card(bytes: ByteArray): ByteArray? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TvHomeArt.CARD_WIDTH || bounds.outHeight / (sample * 2) >= TvHomeArt.CARD_HEIGHT) sample *= 2
        val logo = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val out = Bitmap.createBitmap(TvHomeArt.CARD_WIDTH, TvHomeArt.CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(out)
            val background = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, TvHomeArt.CARD_HEIGHT.toFloat(),
                    Color.rgb(0x26, 0x2B, 0x36), Color.rgb(0x12, 0x15, 0x1C), Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, TvHomeArt.CARD_WIDTH.toFloat(), TvHomeArt.CARD_HEIGHT.toFloat(), background)
            val box = TvHomeArt.fit(logo.width, logo.height)
            canvas.drawBitmap(logo, null, Rect(box.left, box.top, box.right, box.bottom), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            return ByteArrayOutputStream().use { stream ->
                if (!out.compress(Bitmap.CompressFormat.PNG, 100, stream)) null else stream.toByteArray()
            }
        } finally {
            logo.recycle()
            out.recycle()
        }
    }
}
