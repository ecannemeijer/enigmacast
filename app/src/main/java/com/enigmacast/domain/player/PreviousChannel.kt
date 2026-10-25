package com.enigmacast.domain.player

import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.domain.xtream.XtreamItem

/**
 * "Previous channel" for Right in full-screen live TV: A → B → Right → A → Right → B …
 * The recent list is newest first and the channel that plays is at its head, so the
 * previous one is the first entry that is not the current channel.
 */
object PreviousChannel {
    fun satellite(recent: List<LastChannel>, currentReference: String?): LastChannel? {
        val current = currentReference?.takeIf { it.isNotBlank() }?.let(NowEvents::key) ?: return null
        return recent.firstOrNull { it.reference.isNotBlank() && NowEvents.key(it.reference) != current }
    }

    /** One IPTV channel: the same stream id at the same provider. */
    fun sameIptv(a: XtreamItem, b: XtreamItem): Boolean = a.id == b.id && a.providerId == b.providerId

    fun iptv(previous: XtreamItem?, current: XtreamItem): XtreamItem? = previous?.takeUnless { sameIptv(it, current) }
}
