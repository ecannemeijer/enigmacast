package com.enigmacast.playback

/** A stream start: what [LivePlaybackController.play] was asked to play. */
data class StreamRequest(val url: String, val authorization: String?, val checkStart: Boolean, val internet: Boolean = false)

/**
 * Bookkeeping for pausing live playback during an app update download. While held, start
 * requests are not played but remembered (latest wins), so releasing can resume exactly
 * what the app wanted to show by then.
 */
class PlaybackHold {
    var held: Boolean = false
        private set
    private var pending: StreamRequest? = null

    /** Starts holding; [playing] is the stream that was running, if any. Returns false when already held. */
    fun hold(playing: StreamRequest?): Boolean {
        if (held) return false
        held = true
        pending = playing
        return true
    }

    /** Returns true when [request] may play now; while held it is remembered instead. */
    fun admit(request: StreamRequest): Boolean {
        if (!held) return true
        pending = request
        return false
    }

    /** Stops holding; returns the stream to start again, or null. */
    fun release(resume: Boolean): StreamRequest? {
        if (!held) return null
        held = false
        val request = pending
        pending = null
        return if (resume) request else null
    }
}
