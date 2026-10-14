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
) {
    suspend fun search(query: String): IptvSearchResults {
        if (!IptvSearch.searchable(query) || !gate.allows(PremiumFeature.XTREAM)) return IptvSearchResults()
        return runCatching {
            val s = settings.current()
            if (s.iptvSource == IptvSourceMode.PLAYLIST) {
                val login = playlistStore.login.value
                val source = when (s.iptvPlaylistKind) {
                    IptvPlaylistKind.XTREAM -> login.xtreamAccount()?.key
                    IptvPlaylistKind.M3U -> if (login.configured(IptvPlaylistKind.M3U)) com.enigmacast.ui.xtream.XtreamViewModel.M3U_ACCOUNT.key else null
                } ?: return IptvSearchResults()
                IptvSearch.results(source, playlist.search(query))
            } else withContext(Dispatchers.Default) {
                val receiver = s.activeReceiver() ?: return@withContext IptvSearchResults()
                val account = repository.cachedDetection(receiver.id)?.accounts?.firstOrNull() ?: return@withContext IptvSearchResults()
                IptvSearch.results(account.key, IptvSearch.filter(repository.cachedItems(account), query, Int.MAX_VALUE))
            }
        }.getOrDefault(IptvSearchResults())
    }
}
