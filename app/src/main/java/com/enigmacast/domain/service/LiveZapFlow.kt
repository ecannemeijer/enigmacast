package com.enigmacast.domain.service

import com.enigmacast.util.EnigmaLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/**
 * Releases the live HTTP stream, zaps, and opens the next stream only after
 * Enigma2 reports the target service. A stale token never starts playback.
 */
internal object LiveZapFlow {
    sealed interface Outcome {
        data object Started : Outcome
        data object Stale : Outcome
        data class ZapFailed(val error: Throwable) : Outcome
        data object TimedOut : Outcome
    }

    suspend fun run(
        token: Int,
        isCurrent: (Int) -> Boolean,
        reference: String,
        streamUrl: String,
        releaseStream: () -> Unit,
        zap: suspend () -> Unit,
        currentReference: suspend () -> String,
        startStream: () -> Unit,
        timeoutMs: Long = 2_500L,
        pollMs: Long = 80L,
        cleanupDelayMs: Long = 400L,
        log: (String) -> Unit = { EnigmaLog.service(it) },
    ): Outcome {
        log("tune requested: $reference")
        log("releasing old stream")
        releaseStream()
        if (cleanupDelayMs > 0) delay(cleanupDelayMs)
        log("old stream released")
        if (!isCurrent(token)) {
            log("stale tune ignored")
            return Outcome.Stale
        }
        log("sending zap")
        try {
            zap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (!isCurrent(token)) {
                log("stale tune ignored")
                return Outcome.Stale
            }
            log("zap failed")
            return Outcome.ZapFailed(error)
        }
        if (!isCurrent(token)) {
            log("stale tune ignored")
            return Outcome.Stale
        }
        log("zap accepted")
        log("waiting for current service")
        val confirmed = waitUntilTuned(
            expectedReference = reference,
            token = token,
            isCurrent = isCurrent,
            currentReference = currentReference,
            timeoutMs = timeoutMs,
            pollMs = pollMs,
            log = log,
        )
        if (!isCurrent(token)) {
            log("stale tune ignored")
            return Outcome.Stale
        }
        if (!confirmed) {
            log("tune timeout")
            return Outcome.TimedOut
        }
        log("target service confirmed")
        if (!isCurrent(token)) {
            log("stale tune ignored")
            return Outcome.Stale
        }
        log("starting stream: $streamUrl")
        startStream()
        if (!isCurrent(token)) {
            releaseStream()
            log("stale tune ignored")
            return Outcome.Stale
        }
        return Outcome.Started
    }

    suspend fun waitUntilTuned(
        expectedReference: String,
        token: Int,
        isCurrent: (Int) -> Boolean,
        currentReference: suspend () -> String,
        timeoutMs: Long = 2_500L,
        pollMs: Long = 80L,
        log: (String) -> Unit = { EnigmaLog.service(it) },
    ): Boolean {
        return try {
            withTimeout(timeoutMs) {
                pollUntilCurrent(expectedReference, token, isCurrent, currentReference, pollMs, log)
            }
        } catch (_: TimeoutCancellationException) {
            false
        }
    }

    private suspend fun pollUntilCurrent(
        expectedReference: String,
        token: Int,
        isCurrent: (Int) -> Boolean,
        currentReference: suspend () -> String,
        pollMs: Long,
        log: (String) -> Unit,
    ): Boolean {
        while (true) {
            coroutineContext.ensureActive()
            if (!isCurrent(token)) return false
            val reference = try {
                currentReference()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (!isCurrent(token)) return false
                delay(pollMs)
                continue
            }
            log("current service: $reference")
            if (BouquetChannels.sameService(reference, expectedReference)) return true
            if (!isCurrent(token)) return false
            delay(pollMs)
        }
    }
}
