package com.enigmacast.domain.player

/**
 * The "next episode" card and autoplay, for every series player (IPTV, torrents).
 *
 * The card shows in the last [WINDOW_MS] of an episode and counts down with the playback
 * itself (pausing pauses the countdown). When the episode ends the next one starts at once,
 * unless the viewer cancelled the card. Before this the card ran its own 60 s wall-clock
 * countdown that started with 60 s of video left, so it always finished after the end; at the
 * end the card vanished (0 s left no longer counted as "in the last minute") and nothing
 * listened to the end of playback, so the next episode never started.
 */
object UpNext {
    const val WINDOW_MS = 60_000L

    /** Seconds to show on the card, or null when there is no card (yet). 0 = ended. */
    fun seconds(durationMs: Long, positionMs: Long, ended: Boolean): Int? {
        if (ended) return 0
        if (durationMs <= 0L) return null
        val left = durationMs - positionMs.coerceAtLeast(0L)
        if (left > WINDOW_MS) return null
        return ((left.coerceAtLeast(0L) + 999L) / 1000L).toInt()
    }

    /** At the end of an episode: start the next one? */
    fun advanceOnEnd(hasNext: Boolean, dismissed: Boolean): Boolean = hasNext && !dismissed

    /**
     * Some IPTV servers close the connection at the very end instead of ending cleanly: an error
     * within [ERROR_END_MS] of the end counts as the end of the episode.
     */
    const val ERROR_END_MS = 5_000L

    fun errorIsEnd(durationMs: Long, positionMs: Long): Boolean =
        durationMs > 0L && positionMs >= durationMs - ERROR_END_MS
}
