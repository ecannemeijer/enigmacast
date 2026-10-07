package com.enigmacast.playback

import androidx.media3.common.MimeTypes
import com.enigmacast.domain.tuner.RestartBudget
import com.enigmacast.domain.tuner.StreamFailure
import com.enigmacast.domain.tuner.StreamHealth

/**
 * Internet / IPTV streams are not Enigma2 MPEG-TS: they are usually HLS (`.m3u8`), sometimes
 * DASH or plain TS. Enigma2's own 8001/8002 streams keep the forced MPEG-TS progressive path.
 */
object InternetMedia {
    /** The container for [url] from its path or query, or null to let Media3 work it out. */
    fun mimeTypeFor(url: String): String? {
        val lower = url.trim().lowercase()
        val path = lower.substringBefore('#').substringBefore('?')
        val query = lower.substringAfter('?', "").substringBefore('#')
        return when {
            path.endsWith(".m3u8") || HLS_QUERY.containsMatchIn(query) -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") || DASH_QUERY.containsMatchIn(query) -> MimeTypes.APPLICATION_MPD
            path.endsWith(".ts") || path.endsWith(".mpegts") -> MimeTypes.VIDEO_MP2T
            else -> null
        }
    }

    private val HLS_QUERY = Regex("(^|&)(type|format|output|extension|ext)=(m3u8|hls)(&|$)")
    private val DASH_QUERY = Regex("(^|&)(type|format|output|extension|ext)=(mpd|dash)(&|$)")
}

/** What to do after an internet stream failed. */
sealed interface InternetRetryAction {
    /** Start again after [delayMs]; one of the [RestartBudget.MAX_ATTEMPTS] tries. */
    data class Retry(val delayMs: Long) : InternetRetryAction

    /** The URL did not say what it is and plain parsing failed: open it once more as HLS, at once. */
    data object TryHls : InternetRetryAction

    /** Stop and show [health] (unsupported format or failed) until the viewer tries again. */
    data class GiveUp(val health: StreamHealth) : InternetRetryAction
}

/**
 * Retries for internet streams use the same cap as Enigma2 restarts: at most three tries,
 * 1, 2 and 4 seconds apart, then a message instead of an endless restart loop.
 */
object InternetRetry {
    fun next(
        failure: StreamFailure,
        attempts: Int,
        mimeType: String?,
        hlsTried: Boolean,
        autoReconnect: Boolean,
    ): InternetRetryAction {
        if (failure == StreamFailure.PARSE && mimeType == null && !hlsTried) return InternetRetryAction.TryHls
        val final = if (failure == StreamFailure.PARSE) StreamHealth.INTERNET_UNSUPPORTED else StreamHealth.INTERNET_FAILED
        if (!autoReconnect || attempts >= RestartBudget.MAX_ATTEMPTS) return InternetRetryAction.GiveUp(final)
        val delay = (RestartBudget.FIRST_DELAY_MS shl attempts).coerceAtMost(RestartBudget.MAX_DELAY_MS)
        return InternetRetryAction.Retry(delay)
    }
}
