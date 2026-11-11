package com.enigmacast.domain.xtream

import com.enigmacast.domain.model.IptvRefreshInterval

/**
 * When a background playlist refresh should run. Pure, so the interval choices can be tested
 * without Android. A first-time load ([armed]) or a catch-up index still waiting always runs;
 * [IptvRefreshInterval.MANUAL] never runs on a timer.
 */
object IptvRefresh {
    fun isDue(
        interval: IptvRefreshInterval,
        loadedAtMs: Long,
        nowMs: Long,
        armed: Boolean = false,
        indexCatchup: Boolean = false,
    ): Boolean {
        if (armed || indexCatchup) return true
        val maxAge = interval.maxAgeMs ?: return false
        return nowMs - loadedAtMs >= maxAge
    }
}
