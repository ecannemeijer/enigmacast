package com.enigmacast.update

import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copies the update APK with large buffers and reports progress sparingly: only when the whole
 * percent changes (known size) or at most every [UNKNOWN_INTERVAL_MS] (unknown size, -1 then).
 */
object UpdateDownload {
    const val READ_BUFFER = 256 * 1024
    const val WRITE_BUFFER = 1024 * 1024
    const val UNKNOWN_INTERVAL_MS = 250L

    /**
     * Returns the number of bytes copied. [checkActive] runs before every chunk (throws to cancel).
     * A body shorter than the announced [total] is an error: a truncated APK must not be installed.
     */
    suspend fun copy(
        input: InputStream,
        output: OutputStream,
        total: Long,
        onProgress: suspend (Int) -> Unit,
        checkActive: () -> Unit = {},
        nowMs: () -> Long = System::currentTimeMillis,
    ): Long {
        val buffer = ByteArray(READ_BUFFER)
        var copied = 0L
        var lastPercent = -1
        var lastReportMs = 0L
        val out = BufferedOutputStream(output, WRITE_BUFFER)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            out.write(buffer, 0, count)
            copied += count
            if (total > 0) {
                val percent = percent(copied, total)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(percent)
                }
            } else {
                val now = nowMs()
                if (now - lastReportMs >= UNKNOWN_INTERVAL_MS) {
                    lastReportMs = now
                    onProgress(-1)
                }
            }
        }
        out.flush()
        if (total > 0 && copied != total) throw IOException("Download incomplete ($copied of $total bytes)")
        if (lastPercent != 100) onProgress(100)
        return copied
    }

    fun percent(copied: Long, total: Long): Int =
        if (total <= 0) 0 else ((copied * 100) / total).toInt().coerceIn(0, 100)
}
