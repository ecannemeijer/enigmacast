package com.enigmacast.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.enigmacast.R
import com.enigmacast.util.UiText
import com.enigmacast.util.isEmpty

/** One audio or subtitle track the viewer can pick. [id] is `group:track` in the player's track list. */
data class TrackChoice(val id: String, val label: UiText, val selected: Boolean)

data class TrackMenu(
    val audio: List<TrackChoice> = emptyList(),
    val subtitles: List<TrackChoice> = emptyList(),
    val subtitlesOn: Boolean = false,
)

/** Bitrate and smoothness of the stream that plays. */
data class StreamStats(
    val bitrateKbps: Int = 0,
    val droppedFrames: Int = 0,
    val stalls: Int = 0,
    val bufferMs: Long = 0,
)

@OptIn(UnstableApi::class)
internal object TrackMenus {
    fun build(tracks: Tracks, subtitlesOn: Boolean): TrackMenu {
        val audio = mutableListOf<TrackChoice>()
        val subtitles = mutableListOf<TrackChoice>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            for (track in 0 until group.length) {
                if (!group.isTrackSupported(track)) continue
                val format = group.getTrackFormat(track)
                val id = "$groupIndex:$track"
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> audio += TrackChoice(id, TrackLabels.audio(format), group.isTrackSelected(track))
                    C.TRACK_TYPE_TEXT -> subtitles += TrackChoice(
                        id,
                        subtitleLabel(format, subtitles.size + 1),
                        subtitlesOn && group.isTrackSelected(track),
                    )
                }
            }
        }
        return TrackMenu(audio, subtitles, subtitlesOn && subtitles.any { it.selected })
    }

    /** The group and track behind [id], when it still exists and has [type]. */
    fun find(tracks: Tracks, id: String, type: Int): Pair<Tracks.Group, Int>? {
        val parts = id.split(':')
        val groupIndex = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val track = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val group = tracks.groups.getOrNull(groupIndex) ?: return null
        if (group.type != type || track !in 0 until group.length) return null
        return group to track
    }

    /** "Dutch · DVB", "English · teletext · hard of hearing", or "Subtitles 2" without a language. */
    fun subtitleLabel(format: Format, number: Int): UiText {
        val language = TrackLabels.language(format.language ?: format.label)
        val kind = when (format.sampleMimeType ?: format.codecs) {
            MimeTypes.APPLICATION_DVBSUBS -> UiText.Raw("DVB")
            "application/x-teletext", "application/dvb-teletext" -> UiText.res(R.string.subtitle_teletext)
            else -> UiText.Empty
        }
        val hearing = if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) {
            UiText.res(R.string.subtitle_hard_of_hearing)
        } else {
            UiText.Empty
        }
        val first = if (language.isEmpty()) UiText.res(R.string.subtitle_number, number) else language
        return UiText.Joined(listOf(first, kind, hearing).filter { !it.isEmpty() }, " · ")
    }
}

/**
 * Average download rate of the stream. A moving average over a few seconds, so one
 * slow second does not make the number jump.
 */
class ThroughputMeter(private val smoothing: Double = 0.3) {
    private var average = -1.0

    val kbps: Int get() = if (average < 0) 0 else average.toInt()

    fun add(bytes: Long, elapsedMs: Long) {
        if (elapsedMs <= 0) return
        val sample = bytes.coerceAtLeast(0) * 8.0 / elapsedMs // bits per ms = kbit/s
        average = if (average < 0) sample else average + smoothing * (sample - average)
    }

    fun reset() {
        average = -1.0
    }
}
