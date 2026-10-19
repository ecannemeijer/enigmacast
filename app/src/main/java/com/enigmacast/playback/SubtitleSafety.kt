package com.enigmacast.playback

import androidx.media3.common.C

/** Where playback was when a subtitle change started. */
data class PlayheadSnapshot(val positionMs: Long, val durationMs: Long, val seekable: Boolean)

/**
 * Rules that keep a subtitle change from restarting a film or episode.
 *
 * Media3 plays an Xtream/torrent file (MKV, MP4, TS) as one progressive stream. Turning on a
 * text track that was not being read (another embedded language, or text after "Off" disabled
 * the type) needs data that was already skipped, so the player seeks the stream to the current
 * position and reads again. On a seekable file that is a short rebuffer. On a file without a
 * seek index (no MKV cues yet, a transcoded or range-less HTTP stream) Media3 treats every seek
 * as a seek to 0: the episode restarted, and seek keys on such a stream jumped to 0 as well.
 * So: "Off" only hides the lines, the auto pick happens near the start, a switch on an
 * unseekable stream is refused with a notice, and a switch that still lands far back is
 * returned to where it was.
 */
object SubtitleSafety {
    /** How long after a switch a jump back is treated as the switch's restart. */
    const val GUARD_MS = 8_000L
    /** A jump back larger than this is a restart, not a rebuffer. */
    const val JUMP_MS = 5_000L
    /** Before this, re-reading from 0 costs nothing (the auto pick and early switches). */
    const val EARLY_MS = 15_000L

    /** Where to seek back to after a switch, or null when nothing went wrong. */
    fun restoreTo(before: PlayheadSnapshot, nowPositionMs: Long, nowSeekable: Boolean): Long? {
        if (before.positionMs < EARLY_MS || !nowSeekable) return null
        return if (nowPositionMs < before.positionMs - JUMP_MS) before.positionMs else null
    }

    /** May an embedded text track be switched now without risking a restart. */
    fun canSwitchEmbedded(seekable: Boolean, positionMs: Long): Boolean = seekable || positionMs < EARLY_MS

    /** May the seek keys move this stream (an unseekable one only ever goes back to 0). */
    fun canSeek(seekable: Boolean, live: Boolean): Boolean = seekable && !live

    /** The bar's length: the player's, else the last one known for this item, else [fallbackMs]. */
    fun durationFor(playerDurationMs: Long, lastKnownMs: Long, fallbackMs: Long = 0L): Long = when {
        playerDurationMs != C.TIME_UNSET && playerDurationMs > 0 -> playerDurationMs
        lastKnownMs > 0 -> lastKnownMs
        else -> fallbackMs.coerceAtLeast(0)
    }

    /** ISO codes for the preferred text languages (Settings › IPTV › Subtitles), so the first pick is right. */
    fun preferredTextLanguages(slugs: List<String>?, isoOf: (String) -> String?): List<String> =
        slugs.orEmpty().mapNotNull { isoOf(it)?.substringBefore('-')?.takeIf { iso -> iso.isNotBlank() } }.distinct()

    fun describe(s: PlayheadSnapshot) = "pos=${s.positionMs}ms dur=${s.durationMs}ms seekable=${s.seekable}"
}
