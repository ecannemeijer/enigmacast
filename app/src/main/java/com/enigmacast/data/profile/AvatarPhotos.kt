package com.enigmacast.data.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import com.enigmacast.domain.profile.photo.PhotoImage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Profile photos set from a phone: one square WebP per profile uuid in app storage (not synced). */
@Singleton
class AvatarPhotos @Inject constructor(@ApplicationContext private val context: Context) {
    class Rejected(val code: String) : Exception(code)

    fun file(uuid: String): File = fileFor(context, uuid)

    /**
     * Checks, crops and scales [bytes] and stores the result for [uuid]. Throws [Rejected] with
     * "size" or "image". Call off the main thread.
     */
    fun save(uuid: String, bytes: ByteArray, x: Double?, y: Double?, side: Double?) {
        val webp = process(bytes, x, y, side)
        val target = file(uuid)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(webp)
        if (!temp.renameTo(target)) { target.delete(); temp.renameTo(target) }
    }

    fun delete(uuid: String) { file(uuid).delete() }

    private fun process(bytes: ByteArray, x: Double?, y: Double?, side: Double?): ByteArray {
        if (bytes.isEmpty() || bytes.size > PhotoImage.MAX_BYTES) throw Rejected("size")
        PhotoImage.sniff(bytes) ?: throw Rejected("image")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw Rejected("image")
        if (bounds.outWidth.toLong() * bounds.outHeight > PhotoImage.MAX_PIXELS) throw Rejected("size")
        val orientation = PhotoImage.jpegOrientation(bytes)
        val swap = PhotoImage.swapsAxes(orientation)
        val shownW = if (swap) bounds.outHeight else bounds.outWidth
        val shownH = if (swap) bounds.outWidth else bounds.outHeight
        val crop = PhotoImage.square(shownW, shownH, x, y, side)
        val sample = PhotoImage.sampleSize(bounds.outWidth, bounds.outHeight, crop.side)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw Rejected("image")
        val upright = rotate(decoded, orientation)
        try {
            val f = upright.width.toDouble() / shownW
            val side2 = (crop.side * f).toInt().coerceIn(1, minOf(upright.width, upright.height))
            val left = (crop.left * f).toInt().coerceIn(0, upright.width - side2)
            val top = (crop.top * f).toInt().coerceIn(0, upright.height - side2)
            val out = minOf(PhotoImage.OUTPUT_PX, side2)
            val matrix = Matrix().apply { val k = out.toFloat() / side2; setScale(k, k) }
            val square = Bitmap.createBitmap(upright, left, top, side2, side2, matrix, true)
            return ByteArrayOutputStream().use { stream ->
                @Suppress("DEPRECATION")
                val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                square.compress(format, 88, stream)
                if (square !== upright) square.recycle()
                stream.toByteArray()
            }
        } finally {
            if (upright !== decoded) upright.recycle()
            decoded.recycle()
        }
    }

    private fun rotate(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            2 -> m.setScale(-1f, 1f)
            3 -> m.setRotate(180f)
            4 -> m.setScale(1f, -1f)
            5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.setRotate(90f)
            7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            8 -> m.setRotate(-90f)
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    companion object {
        fun fileFor(context: Context, uuid: String): File =
            File(File(context.filesDir, "avatars"), uuid.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "default" } + ".webp")
    }
}
