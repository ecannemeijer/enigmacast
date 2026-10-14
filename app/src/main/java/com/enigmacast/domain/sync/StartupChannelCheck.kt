package com.enigmacast.domain.sync

/**
 * App start/resume: only the box's bouquet list (one `api/bouquets` call) is compared with the
 * cached one. Channels inside the bouquets come from Room and are refreshed by the scheduled
 * sync, first connect or a manual refresh, never by fetching every bouquet on start.
 */
object StartupChannelCheck {
    /** True when the box's bouquets (order, reference, name) differ from the cache. */
    fun bouquetsChanged(cached: List<Pair<String, String>>, live: List<Pair<String, String>>): Boolean =
        live.isNotEmpty() && cached != live
}
