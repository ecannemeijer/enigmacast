package com.enigmacast.data.download

import com.enigmacast.domain.xtream.IptvHeaders
import com.enigmacast.domain.xtream.IptvRequestHeaders
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createTempDirectory

class VodDownloaderTest {
    private lateinit var dir: File
    private var server: RangeServer? = null

    @Before
    fun setUp() {
        dir = createTempDirectory("vod-dl").toFile()
    }

    @After
    fun tearDown() {
        server?.close()
        dir.deleteRecursively()
    }

    @Test
    fun downloadsTheWholeFile() {
        val body = ByteArray(8 * 1024) { it.toByte() }
        val url = start(RangeServer(body))
        val dest = File(dir, "film.mp4")
        val result = VodDownloader().download(url, dest, headers())
        assertTrue(result.error, result.ok)
        assertEquals(body.size.toLong(), result.downloadedBytes)
        assertEquals(body.size.toLong(), dest.length())
        assertTrue(dest.readBytes().contentEquals(body))
    }

    @Test
    fun resumesWithHttpRange() {
        val body = ByteArray(6 * 1024) { it.toByte() }
        val url = start(RangeServer(body))
        val dest = File(dir, "film.mp4")
        dest.writeBytes(body.copyOfRange(0, 2000))
        val requests = CopyOnWriteArrayList<String>()
        server!!.onRequest = { requests += it }
        val result = VodDownloader().download(url, dest, headers())
        assertTrue(result.ok)
        assertEquals(body.size.toLong(), result.downloadedBytes)
        assertTrue(dest.readBytes().contentEquals(body))
        assertTrue(requests.any { it.contains("Range: bytes=2000-") })
    }

    @Test
    fun restartsWhenTheServerIgnoresRange() {
        val body = ByteArray(4 * 1024) { 7 }
        val url = start(RangeServer(body, ignoreRange = true))
        val dest = File(dir, "film.mp4")
        dest.writeBytes(ByteArray(1000) { 1 })
        val result = VodDownloader().download(url, dest, headers())
        assertTrue(result.ok)
        assertEquals(body.size.toLong(), dest.length())
        assertTrue(dest.readBytes().contentEquals(body))
    }

    @Test
    fun alreadyCompleteWhenServerReturns416() {
        val body = ByteArray(512) { 3 }
        val url = start(RangeServer(body, force416 = true))
        val dest = File(dir, "film.mp4")
        dest.writeBytes(body)
        val result = VodDownloader().download(url, dest, headers())
        assertTrue(result.ok)
        assertEquals(body.size.toLong(), result.downloadedBytes)
        assertTrue(dest.readBytes().contentEquals(body))
    }

    @Test
    fun maxConnectionsIsRetryable() {
        val url = start(RangeServer(ByteArray(8), code = 458))
        val result = VodDownloader().download(url, File(dir, "film.mp4"), headers())
        assertFalse(result.ok)
        assertTrue(result.retryable)
        assertEquals("max_connections", result.error)
    }

    @Test
    fun serviceUnavailableIsRetryable() {
        val url = start(RangeServer(ByteArray(8), code = 503))
        val result = VodDownloader().download(url, File(dir, "film.mp4"), headers())
        assertFalse(result.ok)
        assertTrue(result.retryable)
        assertEquals("HTTP 503", result.error)
    }

    @Test
    fun notFoundIsNotRetryable() {
        val url = start(RangeServer(ByteArray(8), code = 404))
        val result = VodDownloader().download(url, File(dir, "film.mp4"), headers())
        assertFalse(result.ok)
        assertFalse(result.retryable)
        assertEquals("HTTP 404", result.error)
    }

    @Test
    fun sendsProviderHeaders() {
        val seen = CopyOnWriteArrayList<String>()
        val url = start(RangeServer(ByteArray(32) { 2 }).also { it.onRequest = { seen += it } })
        val headers = IptvRequestHeaders("EnigmaCast-TV", mapOf("Referer" to "http://provider.example/"))
        val result = VodDownloader().download(url, File(dir, "film.mp4"), headers)
        assertTrue(result.error, result.ok)
        val blob = seen.joinToString("\n")
        assertTrue(blob, blob.contains("EnigmaCast-TV"))
        assertTrue(blob, blob.contains("http://provider.example/"))
    }

    @Test
    fun pauseStopsAndKeepsPartialFile() {
        val body = ByteArray(64 * 1024) { 9 }
        val url = start(RangeServer(body, stallAfter = 100))
        val dest = File(dir, "film.mp4")
        val pause = AtomicBoolean(false)
        val thread = Thread {
            Thread.sleep(50)
            pause.set(true)
        }
        thread.start()
        val result = VodDownloader().download(url, dest, headers(), paused = { pause.get() })
        thread.join()
        assertFalse(result.ok)
        assertEquals("paused", result.error)
        assertTrue(dest.length() > 0L)
        assertTrue(dest.length() < body.size)
    }

    @Test
    fun rejectsHlsPlaylists() {
        assertTrue(VodDownloader.isPlaylist("application/vnd.apple.mpegurl", "http://x/film"))
        assertTrue(VodDownloader.isPlaylist("application/x-mpegURL", "http://x/film.ts"))
        assertTrue(VodDownloader.isPlaylist("video/mp4", "http://x/film.m3u8"))
        assertTrue(VodDownloader.isPlaylist(null, "http://x/manifest.mpd?token=1"))
        assertFalse(VodDownloader.isPlaylist("video/mp4", "http://x/movie/1.mp4"))
        val url = start(RangeServer("#EXTM3U\n#EXTINF:1,\nseg.ts\n".toByteArray(), contentType = "application/vnd.apple.mpegurl"))
        val dest = File(dir, "film.m3u8")
        val result = VodDownloader().download(url, dest, headers())
        assertFalse(result.ok)
        assertFalse(result.retryable)
        assertEquals("not_a_file", result.error)
    }

    private fun headers() = IptvHeaders.resolve()

    private fun start(server: RangeServer): String {
        this.server = server
        return server.start()
    }
}

private class RangeServer(
    private val body: ByteArray,
    private val ignoreRange: Boolean = false,
    private val force416: Boolean = false,
    private val code: Int = 200,
    private val stallAfter: Int = -1,
    private val contentType: String = "video/mp4",
) : AutoCloseable {
    var onRequest: (String) -> Unit = {}
    private val executor = Executors.newSingleThreadExecutor()
    private var socket: ServerSocket? = null

    fun start(): String {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        socket = server
        executor.execute {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                client.use { conn ->
                    val input = conn.getInputStream()
                    val header = StringBuilder()
                    while (true) {
                        val line = buildString {
                            while (true) {
                                val c = input.read()
                                if (c < 0 || c == '\n'.code) break
                                if (c != '\r'.code) append(c.toChar())
                            }
                        }
                        if (line.isEmpty()) break
                        header.append(line).append('\n')
                    }
                    val request = header.toString()
                    onRequest(request)
                    val range = Regex("Range: bytes=(\\d+)-").find(request)?.groupValues?.get(1)?.toInt()
                    val out = conn.getOutputStream()
                    when {
                        code != 200 -> respond(out, code, byteArrayOf())
                        force416 && range != null -> respond(out, 416, byteArrayOf())
                        range != null && !ignoreRange -> {
                            val start = range.coerceIn(0, body.size)
                            val slice = body.copyOfRange(start, body.size)
                            respond(out, 206, slice, start)
                        }
                        else -> respond(out, 200, body)
                    }
                }
            }
        }
        return "http://127.0.0.1:${server.localPort}/film.mp4"
    }

    private fun respond(out: java.io.OutputStream, status: Int, payload: ByteArray, rangeStart: Int = 0) {
        val reason = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            416 -> "Range Not Satisfiable"
            458 -> "Max Connections"
            503 -> "Service Unavailable"
            404 -> "Not Found"
            else -> "Error"
        }
        val headers = StringBuilder()
        headers.append("HTTP/1.1 $status $reason\r\n")
        headers.append("Content-Type: $contentType\r\n")
        if (status == 206) {
            headers.append("Content-Range: bytes $rangeStart-${body.size - 1}/${body.size}\r\n")
        }
        headers.append("Content-Length: ${payload.size}\r\n")
        headers.append("Connection: close\r\n\r\n")
        out.write(headers.toString().toByteArray())
        if (stallAfter >= 0 && payload.isNotEmpty()) {
            val first = payload.size.coerceAtMost(stallAfter.coerceAtLeast(1))
            out.write(payload, 0, first)
            out.flush()
            Thread.sleep(400)
            if (first < payload.size) out.write(payload, first, payload.size - first)
        } else if (payload.isNotEmpty()) {
            out.write(payload)
        }
        out.flush()
    }

    override fun close() {
        runCatching { socket?.close() }
        executor.shutdownNow()
        executor.awaitTermination(2, TimeUnit.SECONDS)
    }
}
