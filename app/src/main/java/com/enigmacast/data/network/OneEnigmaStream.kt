package com.enigmacast.data.network

import okhttp3.Call
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference

/**
 * Enigma2's stream server gives every open HTTP connection its own client, and a client
 * keeps a tuner slot until the socket closes. ExoPlayer can open the next request before
 * the previous socket is gone (a restart, a live reopen), so one device ends up as two
 * clients and a two-tuner box reports both tuners busy. A new Enigma2 stream request
 * cancels the previous one first. Internet streams (HLS, DASH) are left alone: those
 * need several connections at once.
 */
internal class OneEnigmaStream(
    private val delegate: Call.Factory,
) : Call.Factory {
    private val active = AtomicReference<Call?>(null)

    override fun newCall(request: Request): Call {
        val call = delegate.newCall(request)
        if (!isEnigmaService(request.url.encodedPath)) return call
        val previous = active.getAndSet(call)
        if (previous != null && previous !== call) previous.cancel()
        return call
    }

    /** Closes the stream socket the player still has open, so the receiver can free the tuner. */
    fun cancel() {
        active.getAndSet(null)?.cancel()
    }

    companion object {
        /** Direct and transcoded stream URLs are `/1:0:…` or the same reference percent-encoded. */
        fun isEnigmaService(encodedPath: String): Boolean {
            return encodedPath.startsWith("/1:", ignoreCase = true) ||
                encodedPath.startsWith("/1%3A", ignoreCase = true)
        }
    }
}
