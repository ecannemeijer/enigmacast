package com.enigmacast.domain.epg

import com.enigmacast.domain.tuner.NowEvents

/** One programme from OpenWebif's EPG (`/api/epgservice`, `/api/epgmulti`). */
data class GuideEvent(
    val eventId: Int,
    val reference: String,
    val title: String,
    val shortDescription: String,
    val plot: String,
    val beginEpochSec: Long,
    val durationSec: Long,
    /** DVB content byte from OpenWebif's `genreid` (0x10–0x1F is Movie/Drama); -1 when unknown. */
    val genreId: Int = -1,
    /** OpenWebif's genre text, for example "Movie/Drama"; blank when the receiver sends none. */
    val genre: String = "",
) {
    val endEpochSec: Long get() = beginEpochSec + durationSec

    fun isAiring(nowSec: Long): Boolean = nowSec in beginEpochSec until endEpochSec

    fun progress(nowSec: Long): Float {
        if (durationSec <= 0) return 0f
        return ((nowSec - beginEpochSec).toFloat() / durationSec).coerceIn(0f, 1f)
    }

    /** Identifies the programme across guide reloads (event ids are only unique per channel). */
    val programmeKey: String get() = "${NowEvents.key(reference)}|$beginEpochSec"

    /** The long description when there is one, else the short one. */
    val description: String get() = plot.ifBlank { shortDescription }
}

/** The receiver's answer to "record this programme". */
data class TimerResult(val success: Boolean, val message: String)

/** OK on a programme that has not started yet: AutoSwitch, Record or Cancel. [autoSwitchSet] offers to cancel AutoSwitch instead. */
data class ProgrammePrompt(val event: GuideEvent, val channelName: String, val autoSwitchSet: Boolean)
