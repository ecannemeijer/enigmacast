package com.enigmacast.domain.power

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Standby counts only when the receiver explicitly reports it twice; any error means "unknown". */
object StandbyCheck {
    suspend fun confirmed(read: suspend () -> Boolean, pauseMs: Long = 1_500): Boolean? {
        val first = attempt(read) ?: return null
        if (!first) return false
        delay(pauseMs)
        return attempt(read)
    }

    private suspend fun attempt(read: suspend () -> Boolean): Boolean? = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
