package com.enigmacast.domain.player

import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.tuner.NowEvents

/** The tiles of the quick options bar, left to right. */
enum class QuickOption {
    SEARCH,
    RECORD,
    SUBTITLES,
    AUDIO,
    PICTURE,
    AUDIO_DELAY,
    SUBTITLE_DELAY,
    QUALITY,
    GUIDE,
    RECENT,
    SLEEP,
    STREAM_INFO,
    RESTART,
    PIP,
}

object QuickOptions {
    /**
     * Own tuner gets every tile. Following the receiver, zapping belongs to the receiver,
     * so the guide and recent channels are left out.
     */
    fun tiles(ownTuner: Boolean, file: Boolean = false): List<QuickOption> = when {
        file -> listOf(
            QuickOption.SUBTITLES,
            QuickOption.AUDIO,
            QuickOption.PICTURE,
            QuickOption.AUDIO_DELAY,
            QuickOption.SUBTITLE_DELAY,
            QuickOption.STREAM_INFO,
            QuickOption.RESTART,
            QuickOption.SLEEP,
        )
        ownTuner -> QuickOption.entries
        else -> QuickOption.entries - setOf(QuickOption.GUIDE, QuickOption.RECENT, QuickOption.PIP)
    }

    /**
     * IPTV (Xtream / playlist) in the full player: the same row as the own tuner, minus what
     * belongs to the receiver. Record is a box timer and quality is receiver transcoding.
     * The guide and recent channels are the IPTV list, so they only show for live.
     */
    fun iptvTiles(vod: Boolean): List<QuickOption> = buildList {
        add(QuickOption.SEARCH)
        add(QuickOption.SUBTITLES)
        add(QuickOption.AUDIO)
        add(QuickOption.PICTURE)
        add(QuickOption.AUDIO_DELAY)
        add(QuickOption.SUBTITLE_DELAY)
        if (!vod) {
            add(QuickOption.GUIDE)
            add(QuickOption.RECENT)
            add(QuickOption.PIP)
        }
        add(QuickOption.SLEEP)
        add(QuickOption.STREAM_INFO)
        add(QuickOption.RESTART)
    }
}

/** Sleep timer of the app itself: it closes EnigmaCast (and so the stream) when it runs out. */
object SleepTimerChoices {
    val minutes = listOf(0, 15, 30, 45, 60, 90, 120)

    /** Shown one minute before the end, so it can still be switched off. */
    const val WARNING_MS = 60_000L

    /** Whole minutes left, rounded up; 0 when the timer is off or over. */
    fun minutesLeft(endsAtMs: Long, nowMs: Long): Int {
        if (endsAtMs <= 0L || endsAtMs <= nowMs) return 0
        return ((endsAtMs - nowMs + 59_999L) / 60_000L).toInt()
    }
}

/** The last channels watched with an own tuner, newest first. */
object RecentChannels {
    const val MAX = 10

    fun push(list: List<LastChannel>, channel: LastChannel): List<LastChannel> {
        if (channel.reference.isBlank()) return list
        val key = NowEvents.key(channel.reference)
        return (listOf(channel) + list.filterNot { NowEvents.key(it.reference) == key }).take(MAX)
    }
}

/** Audio delay values offered in the quick bar picker. */
object AudioDelayChoices {
    fun values(min: Int, max: Int, step: Int): List<Int> = (min..max step step).toList()
}
