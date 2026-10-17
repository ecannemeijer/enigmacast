package com.enigmacast.live

import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.db.CacheMappers
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.xtream.IptvPlaylistStore
import com.enigmacast.data.xtream.IptvPlaylistSync
import com.enigmacast.data.xtream.XtreamRepository
import com.enigmacast.domain.model.IptvSourceMode
import com.enigmacast.domain.xtream.ChannelMatch
import com.enigmacast.domain.xtream.IptvSearch
import com.enigmacast.domain.xtream.XtreamSection
import com.enigmacast.domain.xtream.XtreamUrls
import javax.inject.Inject
import javax.inject.Singleton

data class FallbackStream(val name: String, val url: String, val authorization: String?)

/** The same channel on the other source, matched without HD, SD, FHD, UHD or 4K. */
@Singleton
class TvFallbackFinder @Inject constructor(
    private val settings: SettingsRepository,
    private val cache: CacheDao,
    private val playlistStore: IptvPlaylistStore,
    private val playlistSync: IptvPlaylistSync,
    private val repository: XtreamRepository,
    private val targets: StreamTargets,
) {
    suspend fun iptv(name: String): FallbackStream? {
        val query = ChannelMatch.query(name)
        if (!IptvSearch.searchable(query)) return null
        val current = settings.current()
        if (current.iptvSource == IptvSourceMode.PLAYLIST || playlistSync.count() > 0) {
            val hits = playlistSync.search(query, IptvSearch.LIMIT).filter { it.section == XtreamSection.LIVE }
            val best = ChannelMatch.best(name, hits) { it.name } ?: return null
            val account = playlistStore.login.value.xtreamAccount()
            val url = best.url.ifBlank { account?.let { XtreamUrls.live(it, best.id) }.orEmpty() }
            if (url.isBlank()) return null
            return FallbackStream(best.name, url, null)
        }
        val receiver = current.activeReceiver() ?: return null
        val account = (repository.cachedDetection(receiver.id) ?: repository.detect(receiver)).accounts.firstOrNull() ?: return null
        val best = ChannelMatch.best(name, repository.liveStreams(account)) { it.name } ?: return null
        return FallbackStream(best.name, best.url.ifBlank { XtreamUrls.live(account, best.id) }, null)
    }

    suspend fun satellite(name: String): FallbackStream? {
        val receiver = settings.current().activeReceiver() ?: return null
        val key = CacheMappers.receiverKey(receiver.id, receiver.host, receiver.httpPort)
        val best = ChannelMatch.best(name, cache.channels(key)) { it.name } ?: return null
        val target = targets.target(receiver, best.reference)
        return FallbackStream(best.name, target.url, target.authorization)
    }
}
