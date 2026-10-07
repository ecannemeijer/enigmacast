package com.enigmacast.ui.connection

import com.enigmacast.data.discovery.DiscoveredReceiver

/** What the AutoDetect dialog shows. */
sealed interface AutodetectState {
    data object Hidden : AutodetectState

    /** Spinner and Cancel while the network is searched (up to about 10 seconds). */
    data object Searching : AutodetectState

    data class Results(val receivers: List<DiscoveredReceiver>) : AutodetectState

    /** Searched [subnets] ([addresses] addresses) without an Enigma2 answer. */
    data class NothingFound(val subnets: String, val addresses: Int) : AutodetectState

    /** The TV has no private IPv4 address on Wi-Fi or Ethernet to search from. */
    data object NoNetwork : AutodetectState
}
