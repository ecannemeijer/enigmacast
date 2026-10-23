package com.enigmacast.domain.channel

import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.xtream.XtreamAccount
import com.enigmacast.domain.xtream.XtreamItem

/**
 * One way to watch a channel: the satellite bouquet or an IPTV playlist. Names are deliberately
 * not used as identity; [ChannelNameMatcher] compares the meaningful part (SBS6 / NPO1) so
 * "NL - SBS6 HD", "SBS6" and "SBS 6" are the same channel.
 */
data class ChannelAlternative(
    val name: String,
    val source: ChannelSource,
    val subtitle: String,
    val reference: String,
    val item: XtreamItem? = null,
    val account: XtreamAccount? = null,
) {
    val bouquet: BouquetEntry get() = BouquetEntry(name, reference)
}

enum class ChannelSource { ENIGMA2, IPTV }

/** A request to move the live picture to another source/channel. Held by [ChannelSwitchBus]. */
sealed interface ChannelSwitchRequest {
    val id: Long

    data class Enigma(val channel: BouquetEntry, override val id: Long = System.nanoTime()) : ChannelSwitchRequest

    data class Iptv(
        val item: XtreamItem,
        val account: XtreamAccount?,
        override val id: Long = System.nanoTime(),
    ) : ChannelSwitchRequest
}
