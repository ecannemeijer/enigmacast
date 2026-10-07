package com.enigmacast.domain.tuner

/**
 * Limits automatic stream restarts (quality fallback, receiver back, unreachable stream
 * port) so a stream that keeps failing can never cause an endless zap cycle: at most
 * [maxAttempts] restarts with a growing pause, then the caller shows the error and waits for
 * the user. A stream that plays, or a channel the user picks, starts a fresh budget.
 */
class RestartBudget(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val firstDelayMs: Long = FIRST_DELAY_MS,
    private val maxDelayMs: Long = MAX_DELAY_MS,
) {
    var attempts: Int = 0
        private set

    val exhausted: Boolean get() = attempts >= maxAttempts

    /** The pause before the next automatic restart, or null when the budget is used up. */
    fun next(): Long? {
        if (exhausted) return null
        val delay = (firstDelayMs shl attempts).coerceAtMost(maxDelayMs)
        attempts += 1
        return delay
    }

    fun reset() {
        attempts = 0
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val FIRST_DELAY_MS = 1_000L
        const val MAX_DELAY_MS = 8_000L
    }
}
