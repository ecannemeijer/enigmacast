package com.enigmacast.domain.service

/**
 * A zap to the channel that is already playing must not tear the stream down and open it again.
 * A retry, or a stream that is not actually playing, still rebuilds.
 */
object StreamReuse {
    fun alreadyPlaying(
        currentReference: String?,
        requestedReference: String,
        playing: Boolean,
        force: Boolean = false,
    ): Boolean {
        if (force || !playing) return false
        if (currentReference.isNullOrBlank()) return false
        return BouquetChannels.sameService(currentReference, requestedReference)
    }
}
