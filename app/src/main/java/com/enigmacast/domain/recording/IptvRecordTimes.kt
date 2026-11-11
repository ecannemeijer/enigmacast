package com.enigmacast.domain.recording

/** Start and end of an IPTV capture, with the configured margins. */
object IptvRecordTimes {
    /** When the current programme is unknown, record this long from now. */
    const val DEFAULT_LIVE_DURATION_SEC = 2 * 60 * 60L

    /** Offered start-early / end-late margins, in minutes. */
    val MARGIN_MINUTES = listOf(0, 1, 2, 5, 10)

    /**
     * Capture window: a future programme starts [marginBeforeSec] early; a programme
     * already on air starts now (the past cannot be recorded). Both end [marginAfterSec]
     * after the programme. The window is at least one minute.
     */
    fun window(
        programmeBeginSec: Long,
        programmeEndSec: Long,
        marginBeforeSec: Int,
        marginAfterSec: Int,
        nowSec: Long,
    ): Pair<Long, Long> {
        val before = marginBeforeSec.coerceAtLeast(0)
        val after = marginAfterSec.coerceAtLeast(0)
        val start = if (programmeBeginSec <= nowSec) nowSec else programmeBeginSec - before
        val end = maxOf(programmeEndSec + after, start + 60L)
        return start to end
    }
}
