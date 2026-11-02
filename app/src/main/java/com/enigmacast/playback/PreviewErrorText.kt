package com.enigmacast.playback

import com.enigmacast.R

/**
 * The small Home preview never shows a raw player code such as ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED.
 * A receiver in standby, or one that has not zapped yet, answers the stream port with no
 * transport stream, so a parsing or connection error there means "no picture from the receiver yet".
 */
object PreviewErrorText {
    fun forError(code: String?): Int? {
        if (code.isNullOrBlank()) return null
        return when {
            code.contains("PARSING") || code.contains("DECODING") -> R.string.preview_no_picture
            code.contains("NETWORK") || code.contains("TIMEOUT") || code.contains("HTTP") -> R.string.preview_no_connection
            else -> R.string.preview_no_picture
        }
    }
}
