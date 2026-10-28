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
 * stay readable. Null when the bytes are not an image. SVG logos are decoded by the publisher
 * and come in through the [Bitmap] overload.
 */
internal object TvHomeCardArt {
    const val POSTER_WIDTH = 342
    const val POSTER_HEIGHT = 513

    fun card(bytes: ByteArray): ByteArray? {
        val logo = decode(bytes, TvHomeArt.CARD_WIDTH, TvHomeArt.CARD_HEIGHT) ?: return null
        return try { card(logo) } finally { logo.recycle() }
    }

    /** A bitmap Android decodes itself (PNG, JPEG, WebP, GIF), sampled down to about [maxWidth]×[maxHeight]. */
    fun decode(bytes: ByteArray, maxWidth: Int, maxHeight: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth || bounds.outHeight / (sample * 2) >= maxHeight) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** [logo] on the dark 16:9 card. The caller keeps (and recycles) [logo]. */
    fun card(logo: Bitmap): ByteArray? {
        if (logo.width <= 0 || logo.height <= 0) return null
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
            return png(out)
        } finally {
            out.recycle()
        }
    }

    /** A film or series poster as a 2:3 PNG (cropped to fill), for IPTV-favorieten and Verder kijken. */
    fun poster(bytes: ByteArray): ByteArray? {
        val picture = decode(bytes, POSTER_WIDTH, POSTER_HEIGHT) ?: return null
        val out = Bitmap.createBitmap(POSTER_WIDTH, POSTER_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val scale = maxOf(POSTER_WIDTH.toFloat() / picture.width, POSTER_HEIGHT.toFloat() / picture.height)
            val srcWidth = (POSTER_WIDTH / scale).toInt().coerceIn(1, picture.width)
            val srcHeight = (POSTER_HEIGHT / scale).toInt().coerceIn(1, picture.height)
            val left = (picture.width - srcWidth) / 2
            val top = (picture.height - srcHeight) / 2
            Canvas(out).apply {
                drawColor(Color.rgb(0x12, 0x15, 0x1C))
                drawBitmap(
                    picture,
                    Rect(left, top, left + srcWidth, top + srcHeight),
                    Rect(0, 0, POSTER_WIDTH, POSTER_HEIGHT),
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
                )
            }
            return png(out)
        } finally {
            picture.recycle()
            out.recycle()
        }
    }

    private fun png(bitmap: Bitmap): ByteArray? = ByteArrayOutputStream().use { stream ->
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) null else stream.toByteArray()
    }
}
