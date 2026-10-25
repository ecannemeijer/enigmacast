package com.enigmacast.playback

import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * The HTTP client for one player's media requests, which can close every open request at once.
 *
 * Stopping or re-preparing ExoPlayer cancels its loader, but a socket that is blocked waiting
 * for data is not interrupted: the old request stays open until the read timeout. IPTV
 * accounts often allow one connection, so a retry would then be refused by the provider
 * ("max connections", HTTP 458) because of our own previous attempt. [closeAll] cancels the
 * calls, which closes their sockets immediately, also while a response body is streaming.
 * No idle keep-alive connections are kept for the same reason.
 */
class StreamConnections(
    connectTimeoutMs: Long = 10_000,
    readTimeoutMs: Long = 20_000,
) {
    private val open: MutableSet<Call> = Collections.synchronizedSet(LinkedHashSet())

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .followRedirects(true)
        .followSslRedirects(true)
        .eventListenerFactory { call ->
            open.add(call)
            object : EventListener() {
                override fun callEnd(call: Call) { open.remove(call) }
                override fun callFailed(call: Call, ioe: IOException) { open.remove(call) }
                override fun canceled(call: Call) { open.remove(call) }
                override fun responseBodyEnd(call: Call, byteCount: Long) { open.remove(call) }
            }
        }
        .build()

    /** Requests that are still connecting, waiting or streaming. */
    fun openCount(): Int = open.size

    /** Cancels every open request (their sockets close now). Returns how many there were. */
    fun closeAll(): Int {
        val calls = synchronized(open) { open.toList().also { open.clear() } }
        calls.forEach { runCatching { it.cancel() } }
        return calls.size
    }
}
