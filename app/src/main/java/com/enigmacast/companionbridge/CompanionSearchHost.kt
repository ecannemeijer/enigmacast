package com.enigmacast.companionbridge

import com.enigmacast.data.torrent.ScraperRepository
import com.enigmacast.data.torrent.ScraperStatus
import com.enigmacast.domain.account.PremiumFeature
import com.enigmacast.domain.search.SearchHit
import com.enigmacast.domain.search.SearchQuery
import com.enigmacast.domain.torrent.TmdbTitle
import com.enigmacast.domain.torrent.TorrentKind
import com.enigmacast.domain.torrent.TorrentTmdb
import com.enigmacast.domain.xtream.IptvLibraryCard
import com.enigmacast.live.PremiumGate
import com.enigmacast.live.SearchOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search for the phone app: the same sources as the TV's Search panel (guide, channels, recordings,
 * IPTV, TMDB people and the Torrents row). Results are opened on the TV by key.
 */
@Singleton
class CompanionSearchHost @Inject constructor(
    private val programmeSearch: com.enigmacast.live.ProgrammeSearch,
    private val recordings: com.enigmacast.live.RecordingBook,
    private val player: com.enigmacast.live.SearchPlayer,
    private val iptvSearch: com.enigmacast.data.xtream.IptvSearchSource,
    private val iptvLibrary: com.enigmacast.data.xtream.IptvLibrary,
    private val gate: PremiumGate,
    private val tmdb: com.enigmacast.data.tmdb.TmdbHttp,
    private val country: com.enigmacast.live.CountryDetector,
    private val scraper: ScraperRepository,
    private val torrentLibrary: com.enigmacast.data.torrent.TorrentLibrary,
) {
    private sealed interface Target {
        data class Hit(val hit: SearchHit) : Target
        data class Iptv(val card: IptvLibraryCard) : Target
        data class Torrent(val title: TmdbTitle) : Target
        data class Person(val name: String) : Target
    }

    private val targets = java.util.concurrent.ConcurrentHashMap<String, Target>()

    /** Same rule as the TV menu: a valid scraper and Premium. */
    fun torrentsEnabled(): Boolean =
        scraper.status.value is ScraperStatus.Valid && gate.allows(PremiumFeature.TORRENT)

    val scraperStatus get() = scraper.status

    /** [scope] "all" or "torrents" (the phone's Torrents tab). */
    suspend fun search(query: String, scope: String): JsonObject {
        val nowSec = System.currentTimeMillis() / 1000
        val parsed = SearchQuery.parse(query, nowSec)
        val text = parsed.text.ifBlank { query }.trim()
        val sections = mutableListOf<Pair<String, List<Pair<String, JsonObject>>>>()
        targets.clear()
        coroutineScope {
            val torrents = async { if (torrentsEnabled()) findTorrents(text) else emptyList() }
            if (scope != SCOPE_TORRENTS && parsed.isSearchable) {
                val iptv = async { runCatching { iptvSearch.search(query) }.getOrNull() }
                val people = async { findPeople(text) }
                val guide = async {
                    runCatching { programmeSearch.search(query, recordings.items.value, nowSec) }.getOrNull()
                }
                guide.await()?.let { r ->
                    sections += "now" to r.now.map { hitItem(it) }
                    sections += "channels" to r.stations.map { hitItem(it) }
                    sections += "later" to r.later.map { hitItem(it) }
                    sections += "recordings" to r.recordings.map { hitItem(it) }
                }
                iptv.await()?.let { r ->
                    sections += "films" to r.films.map { iptvItem(it) }
                    sections += "series" to r.series.map { iptvItem(it) }
                    sections += "iptv" to r.live.map { iptvItem(it) }
                }
                sections += "people" to people.await().map { p ->
                    val key = "p:${p.id}:${p.name}"
                    targets[key] = Target.Person(p.name)
                    key to item(key, p.name, if (p.director) "director" else "", image(p.profilePath))
                }
            }
            sections += "torrents" to torrents.await().map { t ->
                val key = "t:${t.key}"
                targets[key] = Target.Torrent(t)
                key to item(key, t.title, listOfNotNull(t.year?.toString(), if (t.kind == TorrentKind.TV) "series" else "film").joinToString(" · "), image(t.poster))
            }
        }
        return buildJsonObject {
            put("query", query)
            put("scope", scope)
            put("torrents", torrentsEnabled())
            put(
                "sections",
                buildJsonArray {
                    sections.filter { it.second.isNotEmpty() }.forEach { (id, items) ->
                        add(
                            buildJsonObject {
                                put("id", id)
                                put("items", buildJsonArray { items.take(MAX_PER_SECTION).forEach { add(it.second) } })
                            },
                        )
                    }
                },
            )
        }
    }

    /** Opens a result on the TV; false when the key is unknown or the feature is locked. */
    suspend fun open(key: String, hooks: CompanionHooks): Boolean {
        when (val target = targets[key] ?: return false) {
            is Target.Hit -> when (val outcome = player.open(target.hit)) {
                SearchOutcome.Played -> hooks.openPlayer()
                is SearchOutcome.Bouquet -> hooks.openSearch(target.hit.title)
                is SearchOutcome.Failed -> return false
            }
            is Target.Iptv -> {
                if (!gate.allows(PremiumFeature.XTREAM)) return false
                iptvLibrary.openRequest.value = com.enigmacast.data.xtream.IptvOpenRequest(
                    target.card.source, target.card.open, detail = true,
                )
                hooks.openXtream()
            }
            is Target.Torrent -> {
                if (!torrentsEnabled()) return false
                torrentLibrary.openRequest.value = com.enigmacast.domain.torrent.TorrentOpenRequest(target.title, origin = null)
                hooks.openTorrent()
            }
            is Target.Person -> hooks.openSearch(target.name)
        }
        return true
    }

    private fun hitItem(hit: SearchHit): Pair<String, JsonObject> {
        val key = "h:${hit.kind}:${hit.serviceReference}:${hit.recordingId}:${hit.beginEpochSec}:${hit.title.hashCode()}"
        targets[key] = Target.Hit(hit)
        val time = if (hit.beginEpochSec > 0) {
            java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date(hit.beginEpochSec * 1000))
        } else {
            ""
        }
        val sub = listOf(hit.channelName.takeIf { it != hit.title }.orEmpty(), time).filter { it.isNotBlank() }.joinToString(" · ")
        return key to item(key, hit.title, sub, null)
    }

    private fun iptvItem(card: IptvLibraryCard): Pair<String, JsonObject> {
        val key = "i:${card.key}"
        targets[key] = Target.Iptv(card)
        return key to item(key, card.title, card.subtitle, card.poster)
    }

    private fun item(key: String, title: String, subtitle: String, image: String?) = buildJsonObject {
        put("key", key)
        put("title", title)
        put("subtitle", subtitle)
        put("image", image.orEmpty())
    }

    private fun image(path: String?): String? = path?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http", true)) it else "https://image.tmdb.org/t/p/w185$it"
    }

    private suspend fun findTorrents(q: String): List<TmdbTitle> = try {
        if (q.length < com.enigmacast.domain.search.TorrentSearch.MIN_QUERY) emptyList() else coroutineScope {
            val language = java.util.Locale.getDefault().toLanguageTag()
            val films = async { TorrentTmdb.parsePage(tmdb.searchPage(false, q, 1, language), TorrentKind.MOVIE).items }
            val series = async { TorrentTmdb.parsePage(tmdb.searchPage(true, q, 1, language), TorrentKind.TV).items }
            com.enigmacast.domain.search.TorrentSearch.merge(films.await(), series.await())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        emptyList()
    }

    private suspend fun findPeople(name: String): List<com.enigmacast.domain.films.TmdbCastMember> = try {
        if (name.length < 3) emptyList() else {
            val cc = runCatching { country.country() }.getOrDefault("NL")
            val language = com.enigmacast.domain.films.CountryResolver.tmdbLanguage(country.appLanguage(), cc)
            tmdb.searchPerson(name, language)?.let { com.enigmacast.data.tmdb.TmdbJson.parsePeople(it) }.orEmpty()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        emptyList()
    }

    companion object {
        const val SCOPE_TORRENTS = "torrents"
        const val MAX_PER_SECTION = 30

        /** Payload of [CompanionProtocol.TYPE_STATE_FEATURES]. */
        fun featuresJson(torrents: Boolean): JsonObject = buildJsonObject { put("torrents", torrents) }
    }
}
