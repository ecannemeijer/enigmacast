package com.enigmacast.domain.tuner

/** How a freshly started direct stream is doing. */
enum class StreamHealth {
    IDLE,
    STARTING,
    PLAYING,

    /** The receiver closed the stream without sending a picture: no tuner was free. */
    NO_TUNER,

    /** The stream port asked for a login (Enigma2 "stream authentication"). */
    AUTH_REQUIRED,

    /** Nothing listens on the stream port: receiver off, in deep standby, or the wrong port. */
    UNREACHABLE,

    /** Data arrives but no picture: usually a scrambled channel that the CI/CAM cannot descramble now. */
    NO_PICTURE,
    FAILED,

    /** An internet / IPTV stream whose format the player cannot read, after the capped tries. */
    INTERNET_UNSUPPORTED,

    /** An internet / IPTV stream that kept failing (network, HTTP error), after the capped tries. */
    INTERNET_FAILED,
}

enum class StreamFailure { NONE, HTTP_401, HTTP_OTHER, CONNECT, ENDED, PARSE, OTHER }

/**
 * Enigma2's stream server answers "200 OK" first and only then tries to tune. When no
 * tuner is free it closes the connection, so the player sees a stream that ends almost
 * empty. This turns those signals into one verdict.
 */
object StreamStartCheck {
    /** Less than this many bytes before the stream ended means Enigma2 sent no TS at all (a few PAT/PMT packets at most). */
    const val NO_DATA_BYTES = 64L * 1024
    /** Long enough for a dish motor or a unicable tuner to lock; Enigma2 sends TS right after locking. */
    const val NO_DATA_TIMEOUT_MS = 10_000L
    const val NO_PICTURE_TIMEOUT_MS = 12_000L

    fun evaluate(
        elapsedMs: Long,
        bytes: Long,
        firstFrame: Boolean,
        hasVideoTrack: Boolean,
        audioPlaying: Boolean,
        failure: StreamFailure,
    ): StreamHealth {
        if (firstFrame || (!hasVideoTrack && audioPlaying)) return StreamHealth.PLAYING
        when (failure) {
            StreamFailure.HTTP_401 -> return StreamHealth.AUTH_REQUIRED
            StreamFailure.CONNECT -> return StreamHealth.UNREACHABLE
            StreamFailure.ENDED, StreamFailure.PARSE ->
                return if (bytes < NO_DATA_BYTES) StreamHealth.NO_TUNER else StreamHealth.FAILED
            StreamFailure.HTTP_OTHER, StreamFailure.OTHER -> return StreamHealth.FAILED
            StreamFailure.NONE -> Unit
        }
        if (bytes < NO_DATA_BYTES && elapsedMs >= NO_DATA_TIMEOUT_MS) return StreamHealth.NO_TUNER
        if (elapsedMs >= NO_PICTURE_TIMEOUT_MS) return StreamHealth.NO_PICTURE
        return StreamHealth.STARTING
    }
}
