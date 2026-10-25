package com.enigmacast.playback

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class StreamConnectionsTest {
    /** A server that sends the headers and then nothing: the reader blocks like a stalled IPTV stream. */
    @Test
    fun closeAllEndsAStalledStreamingRequestAtOnce() {
        val server = ServerSocket(0)
        val serverClosed = CountDownLatch(1)
        thread(isDaemon = true) {
            server.accept().use { s ->
                s.getInputStream().bufferedReader().let { r -> while (r.readLine()?.isNotEmpty() == true) Unit }
                s.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: 100000000\r\n\r\n".toByteArray())
                    write(ByteArray(188))
                    flush()
                }
                // Wait until the client hangs up (read returns -1 or throws).
                runCatching { while (s.getInputStream().read() >= 0) Unit }
                serverClosed.countDown()
            }
        }
        val connections = StreamConnections(connectTimeoutMs = 5_000, readTimeoutMs = 60_000)
        val reading = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        thread(isDaemon = true) {
            try {
                connections.client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/movie/1.ts").build()).execute().use { r ->
                    val body = r.body.byteStream()
                    body.read(ByteArray(188))
                    reading.countDown()
                    body.read(ByteArray(188)) // blocks: no more data
                }
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.countDown()
            }
        }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        assertEquals(1, connections.openCount())
        val started = System.nanoTime()
        assertEquals(1, connections.closeAll())
        assertTrue("reader still blocked", done.await(3, TimeUnit.SECONDS))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000)
        assertTrue(failure.get() is java.io.IOException || failure.get() is SocketException)
        assertTrue("socket not closed", serverClosed.await(3, TimeUnit.SECONDS))
        assertEquals(0, connections.openCount())
        server.close()
    }

    @Test
    fun finishedRequestsAreNotTracked() {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { s ->
                s.getInputStream().bufferedReader().let { r -> while (r.readLine()?.isNotEmpty() == true) Unit }
                s.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray()); flush() }
            }
        }
        val connections = StreamConnections()
        connections.client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()).execute().use { assertEquals("ok", it.body.string()) }
        assertEquals(0, connections.openCount())
        assertEquals(0, connections.closeAll())
        server.close()
    }
}
