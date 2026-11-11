package com.enigmacast.domain.recording

/**
 * Whether a new IPTV capture would go past the provider's max_connections.
 * Missing or zero max means the limit is unknown, so there is no warning.
 */
object IptvConnectionLimit {
    /**
     * Connections already counted: the provider's [activeCons] when it reported one,
     * otherwise this device's own estimate (watching + captures in progress).
     */
    fun used(activeCons: Int?, watching: Int, recording: Int): Int {
        if (activeCons != null && activeCons >= 0) return activeCons
        return (watching + recording).coerceAtLeast(0)
    }

    fun wouldExceed(maxConnections: Int?, used: Int, extra: Int = 1): Boolean {
        if (maxConnections == null || maxConnections <= 0) return false
        return used + extra > maxConnections
    }
}
