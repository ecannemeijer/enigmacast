package com.enigmacast.domain.resume

import com.enigmacast.data.db.EpisodeHistoryRow

/**
 * The watch history behind the progress bars on episode cards. A newer save moves the
 * position; "watched" never goes back (watching an episode again keeps its mark).
 */
object EpisodeHistory {
    /** Merges [incoming] over what is stored ([current]); the result is what to keep. */
    fun merge(current: EpisodeHistoryRow?, incoming: EpisodeHistoryRow): EpisodeHistoryRow {
        current ?: return incoming
        val newer = if (incoming.updatedMs >= current.updatedMs) incoming else current
        val older = if (newer === incoming) current else incoming
        return newer.copy(
            seriesId = newer.seriesId?.takeIf { it.isNotBlank() } ?: older.seriesId,
            durationMs = newer.durationMs.takeIf { it > 0 } ?: older.durationMs,
            watched = current.watched || incoming.watched,
            updatedMs = maxOf(current.updatedMs, incoming.updatedMs),
        )
    }

    /** Worth a history entry: played at all, or watched. */
    fun worthKeeping(positionMs: Long, durationMs: Long): Boolean =
        positionMs >= ResumePolicy.MIN_KEEP_MS || ResumePolicy.watched(positionMs, durationMs)

    /** 0..1 for the card's bar: full when watched. */
    fun fraction(row: EpisodeHistoryRow?): Float {
        row ?: return 0f
        if (row.watched) return 1f
        if (row.durationMs <= 0L || row.positionMs <= 0L) return 0f
        return (row.positionMs.toFloat() / row.durationMs).coerceIn(0f, 1f)
    }

    /** The entry of a save from the player. */
    fun entry(source: String, kind: String, id: String, seriesId: String?, positionMs: Long, durationMs: Long, nowMs: Long, profileId: String) =
        EpisodeHistoryRow(
            source = source, kind = kind, id = id, seriesId = seriesId,
            positionMs = positionMs, durationMs = durationMs.coerceAtLeast(0L),
            watched = ResumePolicy.watched(positionMs, durationMs),
            updatedMs = nowMs, profileId = profileId,
        )
}
