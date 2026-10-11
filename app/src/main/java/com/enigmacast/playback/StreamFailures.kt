package com.enigmacast.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.enigmacast.R
import com.enigmacast.domain.tuner.StreamFailure
import com.enigmacast.util.UiText
import com.enigmacast.util.isNotEmpty
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

/** Maps Media3 load and playback errors onto what the start check needs to know. */
@OptIn(UnstableApi::class)
internal object StreamFailures {
    fun classify(error: Throwable): StreamFailure {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            when (current) {
                is TimeshiftHttpException ->
                    return if (current.code == 401) StreamFailure.HTTP_401 else StreamFailure.HTTP_OTHER
                is HttpDataSource.InvalidResponseCodeException ->
                    return if (current.responseCode == 401) StreamFailure.HTTP_401 else StreamFailure.HTTP_OTHER
                is ConnectException, is NoRouteToHostException, is UnknownHostException, is SocketTimeoutException ->
                    return StreamFailure.CONNECT
                is ParserException -> return StreamFailure.PARSE
                is EOFException -> return StreamFailure.ENDED
            }
            current = current.cause
            depth += 1
        }
        if (error is PlaybackException) {
            return when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> StreamFailure.CONNECT
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> StreamFailure.HTTP_OTHER
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> StreamFailure.PARSE
                else -> StreamFailure.OTHER
            }
        }
        return StreamFailure.OTHER
    }

    /** True when this app closed the socket on purpose. That is not a failed stream. */
    fun isCancellation(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            val message = current.message.orEmpty()
            if (message.equals("Canceled", ignoreCase = true) || message.equals("cancelled", ignoreCase = true)) {
                return true
            }
            current = current.cause
            depth += 1
        }
        return false
    }
}

/** Short descriptions of the playing audio and subtitle tracks for the channel banner. */
@OptIn(UnstableApi::class)
internal object TrackLabels {
    fun audio(format: Format?): UiText {
        format ?: return UiText.Empty
        val codec = when (format.sampleMimeType) {
            "audio/ac3" -> "Dolby Digital"
            "audio/eac3" -> "Dolby Digital+"
            "audio/mp4a-latm", "audio/mp4a-latm-loas", "audio/aac" -> "AAC"
            "audio/mpeg", "audio/mpeg-L2" -> "MPEG"
            else -> format.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT).orEmpty()
        }
        val channels = when (format.channelCount) {
            1 -> UiText.res(R.string.audio_mono)
            2 -> UiText.res(R.string.audio_stereo)
            6 -> UiText.Raw("5.1")
            8 -> UiText.Raw("7.1")
            Format.NO_VALUE -> UiText.Empty
            else -> UiText.res(R.string.audio_channels, format.channelCount)
        }
        val parts = listOf(language(format.language ?: format.label), UiText.Raw(codec), channels).filter { it.isNotEmpty() }
        return if (parts.isEmpty()) UiText.Empty else UiText.Joined(parts, " · ")
    }

    fun subtitle(tracks: Tracks, enabled: Boolean): UiText {
        if (!enabled) return UiText.Empty
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected } ?: return UiText.Empty
        val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: 0
        val format = group.getTrackFormat(index)
        return language(format.language ?: format.label).takeIf { it.isNotEmpty() } ?: UiText.res(R.string.subtitle_on)
    }

    /** Language name for a DVB/ISO code; unknown codes are shown as they are (upper case). */
    fun language(code: String?): UiText {
        if (code.isNullOrBlank() || code == "und") return UiText.Empty
        val known = when (code.lowercase(Locale.ROOT)) {
            "dut", "nld", "nl" -> R.string.lang_dutch
            "eng", "en" -> R.string.lang_english
            "ger", "deu", "de" -> R.string.lang_german
            "fre", "fra", "fr" -> R.string.lang_french
            "qaa" -> R.string.lang_original
            "mul" -> R.string.lang_multi
            "nar" -> R.string.lang_audio_description
            else -> null
        }
        return known?.let { UiText.res(it) } ?: UiText.Raw(code.uppercase(Locale.ROOT))
    }
}
