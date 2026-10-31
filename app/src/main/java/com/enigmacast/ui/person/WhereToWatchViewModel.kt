package com.enigmacast.ui.person

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enigmacast.domain.account.PremiumFeature
import com.enigmacast.domain.films.TmdbCredit
import com.enigmacast.domain.person.IptvEpgHit
import com.enigmacast.domain.person.SourceState
import com.enigmacast.domain.person.WhereToWatch
import com.enigmacast.domain.search.SearchHit
import com.enigmacast.domain.service.ServiceStreams
import com.enigmacast.domain.xtream.IptvLibraryCard
import com.enigmacast.domain.xtream.RowGuide
import com.enigmacast.domain.xtream.XtreamItem
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A row of the IPTV TV guide: a receiver bouquet's stream channel, or an IPTV channel's own guide. */
sealed interface IptvTvItem {
    data class Guide(val hit: SearchHit) : IptvTvItem
    data class Channel(val hit: IptvEpgHit, val channel: XtreamItem) : IptvTvItem {
        val card: IptvLibraryCard get() = com.enigmacast.domain.xtream.IptvSearch.card(hit.source, channel)
    }
}

/** The title on the Torrent page: how many sources the scraper has for it. */
data class TorrentFind(val credit: TmdbCredit, val sources: Int)

/**
 * "Where to watch" on the actor page: every source searched on its own, each with its own
 * state (searching, nothing found, results), with the same searches Search uses.
 */
@HiltViewModel
class WhereToWatchViewModel @Inject constructor(
    private val programmeSearch: com.enigmacast.live.ProgrammeSearch,
    private val recordings: com.enigmacast.live.RecordingBook,
    private val settings: com.enigmacast.data.settings.SettingsRepository,
    private val iptvSearch: com.enigmacast.data.xtream.IptvSearchSource,
    private val iptvDao: com.enigmacast.data.db.IptvDao,
    private val playlist: com.enigmacast.data.xtream.IptvPlaylistSync,
    private val tmdb: com.enigmacast.data.tmdb.TmdbHttp,
    private val scraper: com.enigmacast.data.torrent.ScraperRepository,
    private val gate: com.enigmacast.live.PremiumGate,
) : ViewModel() {
    var vod by mutableStateOf<SourceState<IptvLibraryCard>>(SourceState.Off)
        private set
    var satellite by mutableStateOf<SourceState<SearchHit>>(SourceState.Off)
        private set
    var iptvTv by mutableStateOf<SourceState<IptvTvItem>>(SourceState.Off)
        private set
    var torrents by mutableStateOf<SourceState<TorrentFind>>(SourceState.Off)
        private set
    /** Recordings of the title (shown under the guide rows when there are any). */
    var recorded by mutableStateOf<List<SearchHit>>(emptyList())
        private set

    private var job: Job? = null
    private var searchedKey: String? = null

    fun search(credit: TmdbCredit, torrentAllowed: Boolean) {
        if (searchedKey == credit.key) return
        searchedKey = credit.key
        job?.cancel()
        val queries = WhereToWatch.queries(credit.title, credit.originalTitle)
        val iptvOn = gate.allows(PremiumFeature.XTREAM)
        vod = if (iptvOn) SourceState.Searching else SourceState.Off
        satellite = SourceState.Searching
        iptvTv = SourceState.Searching
        torrents = if (torrentAllowed) SourceState.Searching else SourceState.Off
        recorded = emptyList()
        job = viewModelScope.launch {
            if (iptvOn) launch {
                val found = queries.map { q -> async { safe(emptyList()) { iptvSearch.search(q).let { it.films + it.series } } } }.flatMap { it.await() }
                vod = SourceState.Done(found.distinctBy { "${it.source}/${it.kind}/${it.id}" })
            }
            if (torrentAllowed) launch { torrents = SourceState.Done(listOfNotNull(torrentFind(credit))) }
            val xtreamGuide = async { if (iptvOn) safe(emptyList()) { xtreamGuide(queries) } else emptyList() }
            val receiver = settings.current().activeReceiver()
            val guide = if (receiver == null) emptyList() else queries.map { q ->
                async { safe(com.enigmacast.domain.search.SearchResults()) { programmeSearch.search(q, recordings.items.value, System.currentTimeMillis() / 1000) } }
            }.map { it.await() }
            val (sat, iptv) = WhereToWatch.splitEpg(guide, ServiceStreams::isInternet)
            satellite = if (receiver == null) SourceState.Off else SourceState.Done(sat)
            recorded = WhereToWatch.recordings(guide)
            iptvTv = SourceState.Done(iptv.map { IptvTvItem.Guide(it) } + xtreamGuide.await())
        }
    }

    /** Programmes in the IPTV channels' own guides (stored listings), with their channel. */
    private suspend fun xtreamGuide(queries: List<String>): List<IptvTvItem> {
        val nowMs = System.currentTimeMillis()
        val rows = iptvDao.allRowEpg(nowMs - RowGuide.TTL_MS).mapNotNull { row ->
            RowGuide.decode(row.events)?.let { Triple(row.account, row.streamId, it) }
        }
        val hits = WhereToWatch.iptvEpg(rows, queries, nowMs / 1000)
        if (hits.isEmpty()) return emptyList()
        val channels = playlist.liveChannels(hits.map { it.streamId }).associateBy { (account, item) -> "$account/${item.id}" }
        return hits.mapNotNull { hit -> channels["${hit.source}/${hit.streamId}"]?.let { IptvTvItem.Channel(hit, it.second) } }
    }

    /** The title's IMDb id, then the scraper's sources (a series: its first episode). */
    private suspend fun torrentFind(credit: TmdbCredit): TorrentFind? = safe(null) {
        val imdb = com.enigmacast.domain.torrent.TorrentTmdb.parseImdb(tmdb.externalIds(credit.isSeries, credit.id)) ?: return@safe null
        val streams = scraper.streams(imdb, if (credit.isSeries) 1 else null, if (credit.isSeries) 1 else null).getOrNull().orEmpty()
        if (streams.isEmpty()) null else TorrentFind(credit, streams.size)
    }

    private suspend fun <T> safe(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        EnigmaLog.network("where to watch: ${error.javaClass.simpleName}")
        fallback
    }
}
