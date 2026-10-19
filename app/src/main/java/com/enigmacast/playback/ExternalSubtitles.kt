package com.enigmacast.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import java.io.File

/**
 * A SubSource file shown next to the playing media instead of inside it.
 *
 * It used to be added as a subtitle configuration of a new media item, which rebuilt and
 * re-prepared the media source. Progressive files (MKV/MP4 over the torrent server or an Xtream
 * VOD link) then reopened from byte 0: the episode restarted, and while the new source had no
 * seek map the duration stayed unknown, so the progress bar stopped and seeks were ignored. Now
 * the file is parsed once off the main thread and its cues are looked up by the player position;
 * the media item, the player and its seek/progress state are never touched.
 */
class CueTimeline<T>(entries: List<Entry<T>>) {
    data class Entry<T>(val startUs: Long, val endUs: Long, val cues: List<T>)

    private val sorted = entries.sortedBy { it.startUs }

    val size: Int get() = sorted.size

    /** Every cue showing at [positionUs] (overlapping lines included). */
    fun at(positionUs: Long): List<T> {
        if (sorted.isEmpty() || positionUs < sorted.first().startUs) return emptyList()
        // Last entry starting at or before the position, then back over overlapping ones.
        var lo = 0
        var hi = sorted.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (sorted[mid].startUs <= positionUs) lo = mid else hi = mid - 1
        }
        val out = ArrayList<T>()
        var i = lo
        // Cues rarely last longer than a minute; stop looking back past that.
        while (i >= 0 && positionUs - sorted[i].startUs <= MAX_LOOKBACK_US) {
            val e = sorted[i]
            if (positionUs < e.endUs) out.addAll(0, e.cues)
            i--
        }
        return out
    }

    companion object {
        const val MAX_LOOKBACK_US = 10L * 60 * 1_000_000

        /** An open-ended cue (no duration) lasts until the next one starts. */
        fun <T> close(entries: List<Entry<T>>): List<Entry<T>> {
            val sorted = entries.sortedBy { it.startUs }
            return sorted.mapIndexed { i, e ->
                if (e.endUs != Long.MAX_VALUE) e
                else e.copy(endUs = sorted.getOrNull(i + 1)?.startUs ?: (e.startUs + 5_000_000))
            }
        }
    }
}

@OptIn(UnstableApi::class)
object ExternalSubtitles {
    /** Parses [file] (SRT, VTT, SSA/ASS); blocking, call it on an IO dispatcher. Null when unreadable. */
    fun parse(file: File, mimeType: String): CueTimeline<Cue>? = runCatching {
        val format = Format.Builder().setSampleMimeType(mimeType).build()
        val factory = DefaultSubtitleParserFactory()
        if (!factory.supportsFormat(format)) return null
        val parser = factory.create(format)
        val entries = ArrayList<CueTimeline.Entry<Cue>>()
        parser.parse(file.readBytes(), SubtitleParser.OutputOptions.allCues()) { c ->
            val end = if (c.durationUs == C.TIME_UNSET) Long.MAX_VALUE else c.startTimeUs + c.durationUs
            entries += CueTimeline.Entry(c.startTimeUs, end, c.cues)
        }
        parser.reset()
        CueTimeline(CueTimeline.close(entries))
    }.getOrNull()

    /** Cues for the player at [positionMs], with the subtitle delay of Settings › Playback. */
    fun cuesAt(timeline: CueTimeline<Cue>, positionMs: Long, delayMs: Int): List<Cue> =
        timeline.at(SubtitleSync.shiftPositionUs(positionMs * 1000, delayMs))
}
