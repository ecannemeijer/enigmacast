package com.enigmacast.domain.sync

/**
 * Whether the scheduled guide download runs. App start/resume and the periodic check only
 * fetch when Room has no guide for today, the schedule interval has passed, or the channel
 * lists were replaced. A download that was started but never finished (app killed, work
 * cancelled) is not retried on every start: it waits [RETRY_MS] after its last attempt.
 */
object EpgSyncDecision {
    const val RETRY_MS = 30 * 60_000L

    enum class Reason { FORCED, CHANNELS_CHANGED, EMPTY, DUE, NOT_DUE, RETRY_WAIT }

    fun decide(
        force: Boolean,
        channelsChanged: Boolean,
        hasGuideToday: Boolean,
        timesPerDay: Int,
        lastSuccessMs: Long,
        lastAttemptMs: Long,
        nowMs: Long,
    ): Reason {
        if (force) return Reason.FORCED
        if (channelsChanged) return Reason.CHANNELS_CHANGED
        val due = SyncPolicy.isDue(timesPerDay, lastSuccessMs, nowMs, hasGuideToday)
        if (!due) return Reason.NOT_DUE
        if (lastAttemptMs > lastSuccessMs && nowMs - lastAttemptMs < RETRY_MS) return Reason.RETRY_WAIT
        return if (!hasGuideToday) Reason.EMPTY else Reason.DUE
    }

    fun runs(reason: Reason) = reason != Reason.NOT_DUE && reason != Reason.RETRY_WAIT
}
