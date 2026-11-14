package com.enigmacast.domain.epg

/** When the fallback guide may refresh, and how it yields to a real guide. */
object FallbackEpgPolicy {
    const val REFRESH_MS = 12 * 60 * 60 * 1000L

    /** Missing or older than about 12 hours. */
    fun isDue(fetchedAtMs: Long?, nowMs: Long): Boolean =
        fetchedAtMs == null || nowMs - fetchedAtMs >= REFRESH_MS

    /** A real (or already borrowed) guide always wins; the fallback is only used when that is empty. */
    fun <T> choose(own: List<T>, fallback: List<T>): List<T> =
        if (own.isNotEmpty()) own else fallback
}
