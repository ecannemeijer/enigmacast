package com.enigmacast.domain.epg

/** One programme from OpenWebif's EPG (`/api/epgservice`, `/api/epgmulti`). */
data class GuideEvent(
    val eventId: Int,
    val reference: String,
    val title: String,
    val shortDescription: String,
    val plot: String,
    val beginEpochSec: Long,
    val durationSec: Long,
) {
    val endEpochSec: Long get() = beginEpochSec + durationSec

    fun isAiring(nowSec: Long): Boolean = nowSec in beginEpochSec until endEpochSec

    fun progress(nowSec: Long): Float {
        if (durationSec <= 0) return 0f
        return ((nowSec - beginEpochSec).toFloat() / durationSec).coerceIn(0f, 1f)
    }

    /** The long description when there is one, else the short one. */
    val description: String get() = plot.ifBlank { shortDescription }
}

/** The receiver's answer to "record this programme". */
data class TimerResult(val success: Boolean, val message: String)
