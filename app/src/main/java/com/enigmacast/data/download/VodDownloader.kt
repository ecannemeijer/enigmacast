package com.enigmacast.data.download

import com.enigmacast.domain.download.VodDownloadSpace
import com.enigmacast.domain.xtream.IptvHeaders
import com.enigmacast.domain.xtream.IptvRequestHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Result of one HTTP pass. [retryable] is true for "max connections" and brief I/O errors. */
data class VodDownloadResult(
    val ok: Boolean,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val error: String = "",
    val retryable: Boolean = false,
    val noSpace: Boolean = false,
)

/**
 * Downloads one file, resuming with HTTP Range when [destination] already has bytes.
 * One call is one attempt; the queue service retries retryable failures.
 */
class VodDownloader(
    private val client: OkHttpClient = defaultClient(),
) {
    fun download(
        url: String,
        destination: File,
        headers: IptvRequestHeaders,
        cancel: () -> Boolean = { false },
        paused: () -> Boolean = { false },
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): VodDownloadResult {
        destination.parentFile?.mkdirs()
        val already = destination.length().coerceAtLeast(0L)
        val request = headers.applyTo(Request.Builder().url(url))
            .header("Accept-Encoding", "identity")
            .apply { if (already > 0L) header("Range", "bytes=$already-") }
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            return VodDownloadResult(false, already, 0L, error.javaClass.simpleName, retryable = true)
        }
        response.use { http ->
            if (isMaxConnections(http)) {
                return VodDownloadResult(false, already, 0L, "max_connections", retryable = true)
            }
            if (http.code == 416) {
                return VodDownloadResult(true, already, already)
            }
            if (!http.isSuccessful) {
                return VodDownloadResult(false, already, 0L, "HTTP ${http.code}", retryable = http.code in 500..599 || http.code == 429)
            }
            val body = http.body ?: return VodDownloadResult(false, already, 0L, "empty")
            val fromRange = http.code == 206
            val contentLength = body.contentLength()
            val total = when {
                contentLength >= 0L && fromRange -> already + contentLength
                contentLength >= 0L -> contentLength
                else -> 0L
            }
            if (!VodDownloadSpace.hasRoom(destination.usableSpace, total, if (fromRange) already else 0L)) {
                return VodDownloadResult(false, already, total, "no_space", noSpace = true)
            }
            if (!fromRange && already > 0L) {
                destination.delete()
            }
            val start = if (fromRange) already else 0L
            var written = start
            try {
                body.byteStream().use { input ->
                    java.io.RandomAccessFile(destination, "rw").use { out ->
                        out.seek(start)
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancel()) return VodDownloadResult(false, written, total, "canceled")
                            if (paused()) return VodDownloadResult(false, written, total, "paused")
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                    }
                }
            } catch (error: IOException) {
                return VodDownloadResult(false, written, total, error.javaClass.simpleName, retryable = true)
            }
            onProgress(written, if (total > 0L) total else written)
            return VodDownloadResult(true, written, if (total > 0L) total else written)
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun isMaxConnections(response: Response): Boolean {
            if (response.code == 458) return true
            val text = response.header("X-Error").orEmpty() + " " + response.message
            return text.contains("max connection", ignoreCase = true)
        }
    }
}
