package com.enigmacast.domain.xtream

/**
 * Library, Home, search and the TV's Watch Next row name the playlist of a film or episode by
 * its account key (the source of its resume, history and favorite rows). With several providers
 * that is not always the one the IPTV page has open, so the request is matched to its provider:
 * "" for the active account, the provider id for another configured provider, null when the key
 * belongs to none of them (the request waits until that account is the active one).
 */
object IptvRequestSource {
    fun providerFor(source: String, activeKey: String?, providerKeys: Map<String, String>): String? =
        providerKeys.entries.firstOrNull { it.value == source }?.key
            ?: if (activeKey != null && activeKey == source) "" else null
}
