package com.enigmacast.data.xtream

import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.account.PremiumFeature
import com.enigmacast.domain.model.IptvPlaylistKind
import com.enigmacast.domain.model.IptvSourceMode
import com.enigmacast.domain.xtream.IptvSearch
import com.enigmacast.domain.xtream.IptvSearchResults
import com.enigmacast.live.PremiumGate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Search in IPTV titles (Premium XTREAM only). Playlist mode: a LIKE query on Room, 50 per
 * section. Receiver (XStreamity) mode: only lists already fetched this session, never the server.
 */
@Singleton
class IptvSearchSource @Inject constructor(
    private val settings: SettingsRepository,
    private val gate: PremiumGate,
    private val playlistStore: IptvPlaylistStore,
    private val playlist: IptvPlaylistSync,
    private val repository: XtreamRepository,
    private val profiles: com.enigmacast.data.profile.ProfileStore,
) {
    suspend fun search(query: String): IptvSearchResults {
        if (!IptvSearch.searchable(query) || !gate.allows(PremiumFeature.XTREAM)) return IptvSearchResults()
        return runCatching {
            val s = settings.current()
            if (s.iptvSource == IptvSourceMode.PLAYLIST) {
                if (!profiles.seesPlaylist()) return IptvSearchResults()
                val providers = playlistStore.providers.value.filter { it.enabled && it.configured() }
                if (providers.isNotEmpty()) {
                    val found = playlist.search(query)
                    val all = providers.mapNotNull { provider ->
                        val account = provider.account() ?: return@mapNotNull null
                        val items = found.filter { it.providerId == provider.id }
                        if (items.isEmpty()) null else IptvSearch.results(account.key, items)
                    }
                    IptvSearchResults(
                        live = all.flatMap { it.live }.take(com.enigmacast.domain.xtream.IptvSearch.LIMIT),
                        films = all.flatMap { it.films }.take(com.enigmacast.domain.xtream.IptvSearch.LIMIT),
                        series = all.flatMap { it.series }.take(com.enigmacast.domain.xtream.IptvSearch.LIMIT),
                    )
                } else {
                    val login = playlistStore.login.value
                    val source = when (s.iptvPlaylistKind) {
                        IptvPlaylistKind.XTREAM -> login.xtreamAccount()?.key
                        IptvPlaylistKind.M3U -> if (login.configured(IptvPlaylistKind.M3U)) com.enigmacast.ui.xtream.XtreamViewModel.M3U_ACCOUNT.key else null
                    } ?: return IptvSearchResults()
                    IptvSearch.results(source, playlist.search(query))
                }
            } else withContext(Dispatchers.Default) {
                val receiver = s.activeReceiver() ?: return@withContext IptvSearchResults()
                val account = repository.cachedDetection(receiver.id)?.accounts?.firstOrNull() ?: return@withContext IptvSearchResults()
                IptvSearch.results(account.key, IptvSearch.filter(repository.cachedItems(account), query, Int.MAX_VALUE))
            }
        }.getOrDefault(IptvSearchResults())
    }
}
