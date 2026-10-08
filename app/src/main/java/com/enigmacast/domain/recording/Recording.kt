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

/** What the confirm dialog shows before a recording is stored. */
data class RecordAsk(
    val event: GuideEvent,
    val channelName: String,
    val place: RecordingTarget,
)
