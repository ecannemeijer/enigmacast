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

    /** Buffer times for a live Enigma2 stream (Media3 DefaultLoadControl). */
    data class LiveDurations(val minMs: Int, val maxMs: Int, val playbackMs: Int, val rebufferMs: Int)

    /** Stable buffer: 2 s before the picture starts and after a stall. */
    val STABLE = LiveDurations(minMs = 2_000, maxMs = 15_000, playbackMs = 2_000, rebufferMs = 2_000)

    /**
     * Stable buffer with "Snel starten": the picture starts after 0.75 s of stream. A stall
     * still waits the full 2 s, and the 2–15 s target is unchanged, so a stream that runs
     * keeps the same cushion as before; only the first start (each zap) is quicker.
     */
    val FAST_START = LiveDurations(minMs = 2_000, maxMs = 15_000, playbackMs = 750, rebufferMs = 2_000)

    /**
     * The live buffer for these settings. The low-latency buffer ([custom]) wins when it is on;
     * values are kept in the order Media3 requires (playback, rebuffer <= min <= max).
     */
    fun live(lowLatency: Boolean, fastStart: Boolean, custom: LiveDurations): LiveDurations {
        val chosen = when {
            lowLatency -> custom
            fastStart -> FAST_START
            else -> STABLE
        }
        val min = chosen.minMs.coerceAtLeast(500)
        val max = chosen.maxMs.coerceAtLeast(min)
        return LiveDurations(
            minMs = min,
            maxMs = max,
            playbackMs = chosen.playbackMs.coerceIn(250, min),
            rebufferMs = chosen.rebufferMs.coerceIn(250, min),
        )
    }

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
