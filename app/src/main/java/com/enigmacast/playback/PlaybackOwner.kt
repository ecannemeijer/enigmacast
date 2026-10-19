package com.enigmacast.playback

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one player that may sound at a time, app-wide. The Enigma2 player (full screen, mini
 * player, recordings) and the IPTV player each register how to stop themselves; whoever starts
 * playing claims the output and the other one is stopped first, so two streams never play at once.
 * The picture-in-picture inset is a third player: it never registers and never claims, so the
 * large picture keeps the output and the sound.
 * Called on the main thread (players are driven from there).
 */
@Singleton
class PlaybackOwner @Inject constructor() {
    enum class Owner { ENIGMA2, IPTV, TORRENT }

    private val stoppers = LinkedHashMap<Owner, () -> Unit>()

    var current: Owner? = null
        private set

    /** Which player's screen is in front (IPTV page); background starts of the other are refused. */
    var foreground: Owner? = null

    /** Debug log hook (owner transitions only; never URLs). */
    var log: (String) -> Unit = {}

    fun register(owner: Owner, stop: () -> Unit) {
        stoppers[owner] = stop
    }

    fun unregister(owner: Owner) {
        stoppers.remove(owner)
        if (current == owner) current = null
    }

    /**
     * [owner] starts playing: every other registered player stops (and releases its decoder)
     * first, synchronously, before the caller prepares its own player. A zap inside the same
     * owner stops nothing.
     */
    fun claim(owner: Owner) {
        log("owner claim $owner (was $current, front $foreground)")
        if (current != owner) {
            stoppers.filterKeys { it != owner }.forEach { (other, stop) ->
                log("owner stop $other for $owner")
                runCatching { stop() }
            }
        }
        current = owner
    }

    /** The app left the screen: stop whoever is playing so the sound does not continue. */
    fun stopCurrent() {
        val owner = current ?: return
        log("owner stop $owner (background)")
        runCatching { stoppers[owner]?.invoke() }
    }

    /**
     * A start that the user didn't ask for on this screen (reconnect, resume, preview) may not
     * take over while the other player owns the output and its screen is in front.
     */
    fun mayStart(owner: Owner): Boolean = PlaybackRules.mayStart(owner, current, foreground)

    /**
     * Another player than [owner] holds the output (IPTV live continuing in the mini player, a
     * torrent): starts of [owner] that nobody asked for must wait.
     */
    fun otherPlays(owner: Owner): Boolean = current.let { it != null && it != owner }

    /** [owner] stopped by itself. */
    fun release(owner: Owner) {
        log("owner release $owner (was $current)")
        if (current == owner) current = null
    }
}

object PlaybackRules {
    fun mayStart(owner: PlaybackOwner.Owner, current: PlaybackOwner.Owner?, foreground: PlaybackOwner.Owner?): Boolean =
        current == null || current == owner || foreground != current
}
