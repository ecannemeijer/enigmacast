package com.enigmacast.domain.recording

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.RecordingTarget
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class RecordingStatus { SCHEDULED, RECORDING, READY, FAILED }

/** Where this recording lives. Kept on the item so changing the setting does not hide it. */
@Serializable
enum class RecordingPlace { TV, RECEIVER }

@Serializable
data class Recording(
    val id: String,
    val title: String,
    val channelName: String,
    val reference: String,
    val eventId: Int,
    val beginEpochSec: Long,
    val endEpochSec: Long,
    val place: RecordingPlace,
    val status: RecordingStatus,
    val receiverId: String,
    /** Local file name, or the receiver movie path once the box has created it. */
    val fileName: String = "",
    val error: String = "",
) {
    fun isActive(): Boolean = status == RecordingStatus.SCHEDULED || status == RecordingStatus.RECORDING

    companion object {
        fun idFor(reference: String, beginEpochSec: Long): String = "$reference|$beginEpochSec"

        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** Recordings stored against one box, on the TV or on that box. */
fun List<Recording>.forReceiver(receiverId: String): List<Recording> =
    if (receiverId.isBlank()) emptyList() else filter { it.receiverId == receiverId }

/** Timers on the receiver that should be refreshed from that box only. */
fun List<Recording>.receiverTimers(receiverId: String): List<Recording> =
    forReceiver(receiverId).filter { it.place == RecordingPlace.RECEIVER }

/** What the confirm dialog shows before a recording is stored. */
data class RecordAsk(
    val event: GuideEvent,
    val channelName: String,
    val place: RecordingTarget,
    /** Later episodes of the same series on that channel in the cached guide (0: not a series). */
    val upcoming: Int = 0,
    /** "Record all upcoming": this one, the others in the guide, and a series rule for new ones. */
    val allUpcoming: Boolean = false,
    /** This episode is already set: the dialog only offers "Record all upcoming". */
    val onlyAll: Boolean = false,
)

/**
 * Is a box timer the same recording as [item]? OpenWebif stores timers with margins
 * (begin − marginbefore, end + marginafter) while the app keeps the programme times, so: the
 * same service and EPG event id, or the same service and overlapping times.
 */
object TimerMatch {
    fun same(timerReference: String, timerBegin: Long, timerEnd: Long, timerEventId: Int, item: Recording): Boolean {
        val sameService = com.enigmacast.domain.tuner.NowEvents.key(timerReference) == com.enigmacast.domain.tuner.NowEvents.key(item.reference)
        if (!sameService) return false
        if (timerEventId >= 0 && item.eventId >= 0) return timerEventId == item.eventId
        return timerBegin < item.endEpochSec && item.beginEpochSec < timerEnd
    }
}
