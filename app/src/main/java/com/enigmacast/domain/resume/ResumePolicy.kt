package com.enigmacast.domain.resume

/** A saved playback position of one recording. */
data class ResumePoint(val key: String, val positionMs: Long, val durationMs: Long)

/** When to save, offer and forget a resume position. */
object ResumePolicy {
    /** Shorter than this is not worth resuming. */
    const val MIN_RESUME_MS = 60_000L
    /** Within this of the end (or past [WATCHED_FRACTION]) the recording counts as watched. */
    const val END_MARGIN_MS = 120_000L
    const val WATCHED_FRACTION = 0.95
    const val SAVE_EVERY_MS = 5_000L

    fun watched(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return false
        return positionMs >= durationMs - END_MARGIN_MS || positionMs.toDouble() / durationMs >= WATCHED_FRACTION
    }

    /** The position to offer, or null to simply start from the beginning. */
    fun offer(point: ResumePoint?): Long? {
        point ?: return null
        if (point.positionMs < MIN_RESUME_MS) return null
        if (watched(point.positionMs, point.durationMs)) return null
        return point.positionMs
    }

    /** 0..1 for the progress bar on a card; 0 when there is nothing to show. */
    fun fraction(point: ResumePoint?, fallbackDurationMs: Long): Float {
        point ?: return 0f
        val duration = point.durationMs.takeIf { it > 0 } ?: fallbackDurationMs
        if (duration <= 0 || point.positionMs < MIN_RESUME_MS) return 0f
        return (point.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    }

    /** "1:02:03" or "12:34". */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
