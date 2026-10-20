package com.enigmacast.domain.profile.photo

/** Pure checks and geometry for an uploaded profile photo (the decoding itself is Android). */
object PhotoImage {
    const val MAX_BYTES = 10 * 1024 * 1024
    const val OUTPUT_PX = 512
    /** Larger images are refused before decoding (decompression bombs). */
    const val MAX_PIXELS = 50_000_000L

    enum class Format { JPEG, PNG, WEBP, GIF, HEIF }

    /** The image type by its first bytes; null when it is not an image we accept. */
    fun sniff(bytes: ByteArray): Format? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xff else -1
        fun ascii(from: Int, text: String) = text.indices.all { at(from + it) == text[it].code }
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> Format.JPEG
            at(0) == 0x89 && ascii(1, "PNG\r\n") -> Format.PNG
            ascii(0, "RIFF") && ascii(8, "WEBP") -> Format.WEBP
            ascii(0, "GIF8") -> Format.GIF
            ascii(4, "ftyp") && listOf("heic", "heix", "mif1", "msf1", "heim", "heis", "avif").any { ascii(8, it) } -> Format.HEIF
            else -> null
        }
    }

    /**
     * EXIF orientation (1–8) of a JPEG, 1 when absent. Phone browsers show photos rotated by this
     * tag, so the crop the user chose is in rotated coordinates.
     */
    fun jpegOrientation(bytes: ByteArray): Int {
        fun u8(i: Int) = bytes[i].toInt() and 0xff
        if (bytes.size < 4 || u8(0) != 0xFF || u8(1) != 0xD8) return 1
        var p = 2
        while (p + 4 <= bytes.size) {
            if (u8(p) != 0xFF) return 1
            val marker = u8(p + 1)
            if (marker == 0xD9 || marker == 0xDA) return 1
            val len = (u8(p + 2) shl 8) or u8(p + 3)
            if (len < 2 || p + 2 + len > bytes.size) return 1
            if (marker == 0xE1 && len >= 8 && String(bytes, p + 4, 4, Charsets.ISO_8859_1) == "Exif") {
                return runCatching { tiffOrientation(bytes, p + 10, p + 2 + len) }.getOrDefault(1)
            }
            p += 2 + len
        }
        return 1
    }

    private fun tiffOrientation(b: ByteArray, start: Int, end: Int): Int {
        val little = b[start] == 'I'.code.toByte()
        fun u16(i: Int): Int {
            require(i + 2 <= end)
            val a = b[i].toInt() and 0xff
            val c = b[i + 1].toInt() and 0xff
            return if (little) a or (c shl 8) else (a shl 8) or c
        }
        fun u32(i: Int): Long = if (little) (u16(i).toLong() or (u16(i + 2).toLong() shl 16)) else ((u16(i).toLong() shl 16) or u16(i + 2).toLong())
        val ifd = start + u32(start + 4).toInt()
        val count = u16(ifd)
        for (k in 0 until count) {
            val entry = ifd + 2 + k * 12
            if (u16(entry) == 0x0112) return u16(entry + 8).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }

    /** Orientations 5–8 swap width and height. */
    fun swapsAxes(orientation: Int) = orientation in 5..8

    data class Square(val left: Int, val top: Int, val side: Int)

    /**
     * The square the phone chose, as fractions of the shown (rotated) image: [x], [y] the top
     * left corner and [side] the edge, both relative to [width] / [height] and the width. Values are
     * clamped into the image; missing or bad values give the centred largest square.
     */
    fun square(width: Int, height: Int, x: Double?, y: Double?, side: Double?): Square {
        val max = minOf(width, height)
        val centred = Square((width - max) / 2, (height - max) / 2, max)
        if (x == null || y == null || side == null || !x.isFinite() || !y.isFinite() || !side.isFinite() || side <= 0) return centred
        val s = (side * width).toInt().coerceIn(1, max)
        val l = (x * width).toInt().coerceIn(0, width - s)
        val t = (y * height).toInt().coerceIn(0, height - s)
        return Square(l, t, s)
    }

    /** Power-of-two sample size: keeps the crop at least [OUTPUT_PX] and the decoded bitmap under ~16 MP. */
    fun sampleSize(width: Int, height: Int, cropSide: Int): Int {
        var sample = 1
        while (cropSide / (sample * 2) >= OUTPUT_PX || width.toLong() * height / (sample.toLong() * sample) > 16_000_000L) sample *= 2
        return sample
    }
}
