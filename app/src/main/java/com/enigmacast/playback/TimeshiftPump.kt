package com.enigmacast.playback

import okhttp3.Call
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** The stream server answered, but not with a body the player can read. */
internal class TimeshiftHttpException(val code: Int) : IOException("HTTP $code")

/**
 * Keeps reading the receiver's MPEG-TS into [store] on its own thread. Pausing ExoPlayer
 * stops the player from reading; this pump does not, so the tuner socket stays open and
 * the missed stretch is still on disk when playback resumes.
 */
internal class TimeshiftPump(
    private val calls: Call.Factory,
    private val store: TimeshiftStore,
    private val url: String,
    private val authorization: String?,
    private val onFull: () -> Unit,
    private val onEnded: () -> Unit,
) {
    private val stopped = AtomicBoolean(false)
    private val thread = Thread(::run, "timeshift").apply { isDaemon = true }
    private var call: Call? = null

    fun start() {
        thread.start()
    }

    /** Hang up. A socket error after this is the hang-up, not a failed stream. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        call?.cancel()
        store.close()
        thread.interrupt()
    }

    private fun run() {
        try {
            val request = okhttp3.Request.Builder().url(url).apply {
                if (!authorization.isNullOrBlank()) header("Authorization", authorization)
            }.build()
            val active = calls.newCall(request)
            call = active
            if (stopped.get()) {
                active.cancel()
                return
            }
            active.execute().use { response ->
                if (stopped.get()) return
                if (!response.isSuccessful) {
                    fail(TimeshiftHttpException(response.code))
                    return
                }
                val input = response.body.byteStream()
                val buffer = ByteArray(32 * 1024)
                while (!stopped.get()) {
                    val n = input.read(buffer)
                    if (n < 0) {
                        fail(EOFException("stream ended"))
                        return
                    }
                    if (n == 0) continue
                    when (store.write(buffer, 0, n)) {
                        WriteResult.ACCEPTED -> Unit
                        WriteResult.FULL -> {
                            if (stopped.compareAndSet(false, true)) onFull()
                            return
                        }
                        WriteResult.CLOSED -> return
                    }
                }
            }
        } catch (error: IOException) {
            if (!stopped.get()) fail(error)
        } catch (error: Exception) {
            if (!stopped.get()) fail(IOException(error.message ?: "timeshift", error))
        }
    }

    private fun fail(error: IOException) {
        if (!stopped.compareAndSet(false, true)) return
        store.fail(error)
        onEnded()
    }
}
