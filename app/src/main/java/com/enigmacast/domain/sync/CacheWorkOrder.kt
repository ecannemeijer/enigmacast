package com.enigmacast.domain.sync

/**
 * Bouquet and guide downloads finish before an IPTV playlist load, so the playlist does not
 * compete with those transfers. IPTV is one step for the device, not one step per profile.
 */
object CacheWorkOrder {
    enum class Step { SATELLITE, IPTV }

    fun steps(hasReceiver: Boolean): List<Step> = buildList {
        if (hasReceiver) add(Step.SATELLITE)
        add(Step.IPTV)
    }
}
