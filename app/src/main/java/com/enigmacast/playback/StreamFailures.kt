package com.enigmacast.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.enigmacast.domain.tuner.StreamFailure
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
}

/** Short Dutch descriptions of the playing audio and subtitle tracks for the channel banner. */
@OptIn(UnstableApi::class)
internal object TrackLabels {
    fun audio(format: Format?): String {
        format ?: return ""
        val codec = when (format.sampleMimeType) {
            "audio/ac3" -> "Dolby Digital"
            "audio/eac3" -> "Dolby Digital+"
            "audio/mp4a-latm", "audio/mp4a-latm-loas", "audio/aac" -> "AAC"
            "audio/mpeg", "audio/mpeg-L2" -> "MPEG"
            else -> format.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT).orEmpty()
        }
        val channels = when (format.channelCount) {
            1 -> "mono"
            2 -> "stereo"
            6 -> "5.1"
            8 -> "7.1"
            Format.NO_VALUE -> ""
            else -> "${format.channelCount} kan."
        }
        return listOf(language(format.language ?: format.label), codec, channels)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    fun subtitle(tracks: Tracks, enabled: Boolean): String {
        if (!enabled) return ""
        val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected } ?: return ""
        val index = (0 until group.length).firstOrNull { group.isTrackSelected(it) } ?: 0
        val format = group.getTrackFormat(index)
        return language(format.language ?: format.label).ifBlank { "Aan" }
    }

    fun language(code: String?): String {
        if (code.isNullOrBlank() || code == "und") return ""
        val known = mapOf(
            "dut" to "Nederlands", "nld" to "Nederlands", "nl" to "Nederlands",
            "eng" to "Engels", "en" to "Engels",
            "ger" to "Duits", "deu" to "Duits", "de" to "Duits",
            "fre" to "Frans", "fra" to "Frans", "fr" to "Frans",
            "qaa" to "Origineel", "mul" to "Meertalig", "nar" to "Audiodescriptie",
        )
        return known[code.lowercase(Locale.ROOT)] ?: code.uppercase(Locale.ROOT)
    }
}
