package com.enigmacast.playback

/**
 * How many bytes of media a player may hold in memory, from the size of the app's Java heap.
 *
 * Media3's own targets do not look at the device: a film/series player asks for about 144 MB
 * (video + audio) and the Enigma2 player, which prioritised time over size, ignored any size
 * until the heap had less than 4 % free. On a TV box with a 192–256 MB heap that fills the heap
 * (two players at once with the IPTV picture-in-picture), loading stops "due to memory pressure"
 * with almost nothing playable buffered, and the playback thread re-checks that every 10 ms.
 */
object BufferPolicy {
    const val MB = 1024 * 1024

    enum class Kind {
        /** Enigma2 live/recordings, the picture-in-picture inset: seconds of buffer are enough. */
        LIVE,
        /** IPTV and Torrent films/series: as much read-ahead as the heap safely allows. */
        VOD,
    }

    /** Below this, a player that stopped loading has nothing it can play (Media3's own floor). */
    const val STARVED_MS = 500L

    fun targetBytes(maxHeapBytes: Long, kind: Kind): Int {
        val (fraction, min, max) = when (kind) {
            Kind.LIVE -> Triple(0.12, 16L * MB, 64L * MB)
            Kind.VOD -> Triple(0.25, 32L * MB, 160L * MB)
        }
        val heap = maxHeapBytes.takeIf { it > 0 && it != Long.MAX_VALUE } ?: (256L * MB)
        return (heap * fraction).toLong().coerceIn(min, max).toInt()
    }
}

/**
 * A player that sits in BUFFERING without loading and with (almost) nothing buffered will not
 * get out of it by itself: the load control keeps refusing to load. True once that has lasted
 * [afterMs]; then it waits the same time again before the next answer.
 */
class StarvedBuffer(private val afterMs: Long) {
    private var since = -1L

    fun update(buffering: Boolean, loading: Boolean, bufferedMs: Long, nowMs: Long): Boolean {
        val starved = buffering && !loading && bufferedMs < BufferPolicy.STARVED_MS
        if (!starved) { since = -1L; return false }
        if (since < 0) { since = nowMs; return false }
        if (nowMs - since < afterMs) return false
        since = -1L
        return true
    }

    fun reset() { since = -1L }
}
