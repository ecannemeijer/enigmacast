package com.enigmacast.domain.epg

/**
 * Programmes from the iptv-epg.org fallback, looked up by channel name or tvg-id.
 * Implementations return an empty list when the fallback is off, still loading, or has no match.
 * Callers must only use this when the channel has no guide of its own.
 */
interface FallbackEpgLookup {
    suspend fun eventsFor(
        name: String,
        reference: String,
        tvgId: String = "",
        fromSec: Long,
        toSec: Long,
    ): List<GuideEvent>

    suspend fun eventsByName(
        names: Collection<String>,
        tvgIds: Map<String, String> = emptyMap(),
        fromSec: Long,
        toSec: Long,
    ): Map<String, List<GuideEvent>>

    object None : FallbackEpgLookup {
        override suspend fun eventsFor(name: String, reference: String, tvgId: String, fromSec: Long, toSec: Long) = emptyList<GuideEvent>()
        override suspend fun eventsByName(names: Collection<String>, tvgIds: Map<String, String>, fromSec: Long, toSec: Long) = emptyMap<String, List<GuideEvent>>()
    }
}
