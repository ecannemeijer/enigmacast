package com.enigmacast.data.tmdb

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** At most [permitsPerSecond] starts per second, spaced evenly. TMDB allows about 40/s; we stay at 20. */
class RateLimiter(permitsPerSecond: Int, private val clockMs: () -> Long = System::currentTimeMillis) {
    private val spacingMs = 1000L / permitsPerSecond.coerceAtLeast(1)
    private val mutex = Mutex()
    private var nextMs = 0L

    suspend fun acquire() {
        val wait = mutex.withLock {
            val now = clockMs()
            val start = maxOf(now, nextMs)
            nextMs = start + spacingMs
            start - now
        }
        if (wait > 0) delay(wait)
    }
}
