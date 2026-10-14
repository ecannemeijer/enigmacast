package com.enigmacast.playback

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one player that may sound at a time, app-wide. The Enigma2 player (full screen, mini
 * player, recordings) and the IPTV player each register how to stop themselves; whoever starts
 * playing claims the output and the other one is stopped first, so two streams never play at once.
 * Called on the main thread (players are driven from there).
 */
@Singleton
class PlaybackOwner @Inject constructor() {
    enum class Owner { ENIGMA2, IPTV }

    private val stoppers = LinkedHashMap<Owner, () -> Unit>()

    var current: Owner? = null
        private set

    fun register(owner: Owner, stop: () -> Unit) {
        stoppers[owner] = stop
    }

    fun unregister(owner: Owner) {
        stoppers.remove(owner)
        if (current == owner) current = null
    }

    /** [owner] starts playing: every other registered player stops. */
    fun claim(owner: Owner) {
        if (current != owner) {
            stoppers.filterKeys { it != owner }.values.toList().forEach { stop -> runCatching { stop() } }
        }
        current = owner
    }

    /** [owner] stopped by itself. */
    fun release(owner: Owner) {
        if (current == owner) current = null
    }
}
