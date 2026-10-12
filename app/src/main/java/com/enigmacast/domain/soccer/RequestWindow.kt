package com.enigmacast.domain.soccer

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * At most [max] request starts per sliding [windowMs] (football-data.org free plan: 10/min).
 * After an HTTP 429 [pauseUntil] holds every request until the server's Retry-After has passed.
 */
class RequestWindow(
    private val max: Int = 10,
    private val windowMs: Long = 61_000,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private val starts = ArrayDeque<Long>()
    private var blockedUntilMs = 0L

    /** Wait time a new request would have now, without taking a slot. */
    fun waitMs(): Long {
        val now = clockMs()
        while (starts.isNotEmpty() && now - starts.first() >= windowMs) starts.removeFirst()
        val forWindow = if (starts.size < max) 0L else starts.first() + windowMs - now
        return maxOf(forWindow, blockedUntilMs - now, 0L)
    }

    suspend fun acquire() = mutex.withLock {
        while (true) {
            val wait = waitMs()
            if (wait <= 0) break
            sleep(wait)
        }
        starts.addLast(clockMs())
    }

    fun pauseUntil(untilMs: Long) {
        if (untilMs > blockedUntilMs) blockedUntilMs = untilMs
    }

    companion object {
        /** Seconds from Retry-After (or X-RequestCounter-Reset); unparsable → [fallbackSec]. */
        fun retryAfterSec(header: String?, fallbackSec: Long = 60): Long =
            header?.trim()?.toLongOrNull()?.coerceIn(1, 3600) ?: fallbackSec
    }
}
