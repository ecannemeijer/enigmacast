package com.enigmacast.domain.torrent

import kotlin.math.abs

/**
 * The stream the next episode plays with, picked without asking: the same torrent when it has
 * the next episode too (a season pack), else the same kind (torrent or link) at the closest
 * resolution, the most seeders first; ties keep the scraper's order.
 */
object NextStream {
    fun pick(previous: TorrentStream?, streams: List<TorrentStream>): TorrentStream? {
        if (streams.isEmpty()) return null
        previous ?: return streams.first()
        previous.infoHash?.let { hash -> streams.firstOrNull { it.infoHash.equals(hash, ignoreCase = true) }?.let { return it } }
        val pool = streams.filter { it.isTorrent == previous.isTorrent }.ifEmpty { streams }
        val wanted = previous.resolution
        return pool.minWithOrNull(
            compareBy<TorrentStream> { if (wanted > 0 && it.resolution > 0) abs(it.resolution - wanted) else if (wanted > 0) Int.MAX_VALUE else 0 }
                .thenByDescending { it.seeders ?: -1 },
        )
    }
}
