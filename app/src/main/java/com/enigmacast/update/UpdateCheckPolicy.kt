package com.enigmacast.update

/**
 * When the app looks for a new release and when it may ask. On a TV the process stays alive for
 * days, so besides the start it checks when the app comes back to the front (at most once per
 * [FOREGROUND_GAP_MS]) and every [PERIODIC_MS] while it runs. All times are a monotonic clock.
 */
object UpdateCheckPolicy {
    const val FOREGROUND_GAP_MS = 30 * 60_000L
    const val PERIODIC_MS = 4 * 60 * 60_000L

    /** A version the viewer closed is not asked again for this long. */
    const val DISMISSED_QUIET_MS = FOREGROUND_GAP_MS

    enum class Reason { START, FOREGROUND, PERIODIC }

    /** [lastCheckAt] is when the previous check started (null: never). */
    fun shouldCheck(reason: Reason, nowMs: Long, lastCheckAt: Long?): Boolean = when {
        lastCheckAt == null -> true
        reason == Reason.START -> true
        else -> nowMs - lastCheckAt >= FOREGROUND_GAP_MS
    }

    /**
     * Ask about [remoteVersionCode]? Only a newer build, never in hotel mode, never over a prompt
     * that is up or a download or install that runs, and not again right after the viewer closed
     * that same version ([dismissedVersion] at [dismissedAt]).
     */
    fun shouldPrompt(
        localVersionCode: Int,
        remoteVersionCode: Int,
        hotelMode: Boolean,
        promptShowing: Boolean,
        busy: Boolean,
        dismissedVersion: Int?,
        dismissedAt: Long?,
        nowMs: Long,
    ): Boolean {
        if (remoteVersionCode <= localVersionCode || hotelMode || promptShowing || busy) return false
        val recentlyDismissed = dismissedVersion != null && dismissedAt != null &&
            remoteVersionCode <= dismissedVersion && nowMs - dismissedAt < DISMISSED_QUIET_MS
        return !recentlyDismissed
    }
}
