package com.enigmacast.domain.xtream

/**
 * IPTV live behind the dashboard: Back on the bare live picture keeps the channel playing in
 * the mini player (Home, Live TV, Guide) until another stream is started; OK on that mini
 * player goes back to it full screen.
 */
object IptvMini {
    /** A live channel (not a film, an episode or catch-up from the archive) continues. */
    fun continues(section: XtreamSection?, episode: Boolean, catchup: Boolean): Boolean =
        section == XtreamSection.LIVE && !episode && !catchup

    /** The listing on air for the mini player's line, when known. */
    fun nowTitle(listings: List<XtreamEpg>?, nowSec: Long): String? =
        listings?.airingAt(nowSec)?.title?.trim()?.takeIf { it.isNotEmpty() }

    /** The badge of an Enigma2 picture: the receiver's own name, else "Enigma2". */
    fun receiverBadge(receiverName: String?): String =
        receiverName?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > MAX_BADGE) it.take(MAX_BADGE - 1) + "…" else it } ?: ENIGMA2

    /** What OK on the Home mini player opens full screen; NONE leaves it out of the focus order. */
    enum class Target { IPTV, ENIGMA2, NONE }

    /**
     * IPTV live in the mini player wins (it is what the mini player draws). Enigma2 counts while
     * its player is shown and has a stream open (live, timeshift, a recording): buffering or
     * ready, also paused. Idle or ended has nothing to show full screen.
     */
    fun target(iptvLive: Boolean, enigma2Shown: Boolean, enigma2State: String): Target = when {
        iptvLive -> Target.IPTV
        enigma2Shown && enigma2State in OPEN_STATES -> Target.ENIGMA2
        else -> Target.NONE
    }

    private val OPEN_STATES = setOf("BUFFERING", "READY")

    const val IPTV = "IPTV"
    const val ENIGMA2 = "Enigma2"
    private const val MAX_BADGE = 16
}
