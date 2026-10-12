package com.enigmacast.sync

import android.content.Context
import com.enigmacast.R
import com.enigmacast.data.db.BouquetRow
import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.db.CacheMappers
import com.enigmacast.data.db.ChannelRow
import com.enigmacast.data.db.EpgRow
import com.enigmacast.data.db.SyncStateRow
import com.enigmacast.data.db.TmdbMatchRow
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.tmdb.SyncSettings
import com.enigmacast.data.tmdb.TmdbHttp
import com.enigmacast.domain.films.CountryResolver
import com.enigmacast.domain.films.FilmCache
import com.enigmacast.domain.films.FilmDetector
import com.enigmacast.domain.films.HomeFilmsBuilder
import com.enigmacast.domain.films.TmdbMovie
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.sync.DayWindow
import com.enigmacast.domain.sync.BouquetChannel
import com.enigmacast.domain.sync.HomeBouquets
import com.enigmacast.domain.sync.SyncKind
import com.enigmacast.domain.sync.SyncPolicy
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.live.CountryDetector
import com.enigmacast.util.EnigmaLog
import com.enigmacast.util.UiText
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** A running sync: [percent] 0–100 and the step in words. */
data class SyncProgress(val receiverKey: String, val percent: Int, val step: UiText)

/**
 * Fills the Room cache for one receiver: bouquets → channels → EPG (today plus the set number
 * of days) → film detection → TMDB. Each step runs when it is due (see [SyncPolicy]) or forced,
 * and a step that ran makes the later ones run too. One sync at a time.
 */
@Singleton
class SyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CacheDao,
    private val api: OpenWebifApi,
    private val tmdb: TmdbHttp,
    private val settings: SyncSettings,
    private val country: CountryDetector,
    private val bouquetStore: com.enigmacast.data.tmdb.HomeBouquetStore,
) {
    private val gate = Mutex()
    private val _progress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

    fun receiverKey(receiver: ReceiverConfig) = CacheMappers.receiverKey(receiver.id, receiver.host, receiver.httpPort)

    /** True when something was refreshed. Errors are stored per step and do not throw. */
    suspend fun run(receiver: ReceiverConfig, force: Boolean, refilm: Boolean = false): Boolean = gate.withLock {
        val rk = receiverKey(receiver)
        val zone = ZoneId.systemDefault()
        val nowMs = System.currentTimeMillis()
        val today = DayWindow.of(nowMs, zone)
        val values = settings.values.value
        try {
            var changed = false
            dao.purgeEvents(today.purgeBeforeSec)
            dao.purgeHomeFilms(today.purgeBeforeSec)
            dao.purgeMatches(nowMs)

            val channelsDue = force || SyncPolicy.isDue(values.channelsPerDay, last(rk, SyncKind.CHANNELS), nowMs, dao.channelCount(rk) > 0)
            if (channelsDue) {
                step(rk, 2, R.string.sync_step_channels)
                attempt(rk, SyncKind.CHANNELS) { syncChannels(receiver, rk) }
                changed = true
            }
            val horizonSec = today.startSec + values.epgDays * 24 * 3600L
            val epgDue = changed || force ||
                SyncPolicy.isDue(values.epgPerDay, last(rk, SyncKind.EPG), nowMs, dao.eventCount(rk, today.startSec, today.endSec) > 0)
            if (epgDue) {
                attempt(rk, SyncKind.EPG) { syncEpg(receiver, rk, today.startSec - 4 * 3600, horizonSec) }
                changed = true
            }
            val filmsToday = lastDay(rk, SyncKind.FILMS, zone) == today.date.toString()
            val filmsDue = changed || force || refilm || !filmsToday ||
                SyncPolicy.isDue(values.effectiveFilmsPerDay, last(rk, SyncKind.FILMS), nowMs, true)
            if (filmsDue && tmdb.apiKey().isNotBlank()) {
                attempt(rk, SyncKind.FILMS) { syncFilms(rk, today, horizonSec, zone) }
                changed = true
            }
            changed
        } finally {
            _progress.value = null
        }
    }

    private suspend fun syncChannels(receiver: ReceiverConfig, rk: String) {
        val bouquets = api.televisionBouquets(receiver)
        require(bouquets.isNotEmpty()) { "no bouquets" }
        val bouquetRows = bouquets.mapIndexed { index, b -> BouquetRow(rk, b.reference, b.name, index) }
        val channelRows = ArrayList<ChannelRow>()
        var position = 0
        bouquets.forEachIndexed { index, bouquet ->
            step(rk, 2 + 13 * (index + 1) / bouquets.size, R.string.sync_step_channels)
            val channels = runCatching { api.channelsInBouquet(receiver, bouquet.reference) }.getOrDefault(emptyList())
            channels.forEach { c -> channelRows += ChannelRow(rk, bouquet.reference, c.reference, c.name, c.picon, c.number, position++, NowEvents.key(c.reference)) }
        }
        dao.replaceChannels(rk, bouquetRows, channelRows)
        EnigmaLog.service("sync channels receiver=$rk bouquets=${bouquets.size} channels=${channelRows.size}")
    }

    private suspend fun syncEpg(receiver: ReceiverConfig, rk: String, fromSec: Long, toSec: Long) = coroutineScope {
        val bouquets = dao.bouquets(rk)
        val channels = dao.channels(rk).groupBy { it.bouquetReference }
        val minutes = ((toSec - fromSec) / 60).toInt()
        val gate = Semaphore(3)
        var done = 0
        val perBouquet = bouquets.map { bouquet ->
            async {
                gate.withPermit {
                    var events = runCatching { api.bouquetEvents(receiver, bouquet.reference, fromSec, minutes) }.getOrDefault(emptyList())
                    if (events.isEmpty()) {
                        events = channels[bouquet.reference].orEmpty().take(FALLBACK_CHANNELS).flatMap { channel ->
                            runCatching { api.serviceEvents(receiver, channel.reference) }.getOrDefault(emptyList())
                        }
                    }
                    synchronized(this@SyncEngine) { done++ }
                    step(rk, 15 + 50 * done / bouquets.size.coerceAtLeast(1), R.string.sync_step_epg)
                    events
                }
            }
        }.awaitAll()
        val rows = HashMap<Pair<String, Long>, EpgRow>()
        perBouquet.flatten()
            .filter { it.endEpochSec > fromSec && it.beginEpochSec < toSec }
            .forEach { event ->
                val row = CacheMappers.epgRow(rk, event)
                rows.putIfAbsent(row.serviceKey to row.beginSec, row)
            }
        require(rows.isNotEmpty() || bouquets.isEmpty()) { "receiver sent no guide" }
        dao.replaceEvents(rk, fromSec, toSec, rows.values.toList())
        EnigmaLog.service("sync epg receiver=$rk events=${rows.size}")
    }

    private suspend fun syncFilms(rk: String, today: DayWindow, horizonSec: Long, zone: ZoneId) {
        step(rk, 66, R.string.sync_step_films)
        val countryCode = country.country()
        val language = CountryResolver.tmdbLanguage(country.appLanguage(), countryCode)
        val allBouquets = dao.bouquets(rk).map { it.reference }
        val selected = HomeBouquets.effective(bouquetStore.selected(rk), allBouquets)
        val names = HomeBouquets.channels(
            dao.channels(rk).map { BouquetChannel(it.bouquetReference, it.reference, it.name, it.picon) },
            selected,
        )
        val nowSec = System.currentTimeMillis() / 1000
        val candidates = dao.events(rk, nowSec - 4 * 3600, horizonSec)
            .mapNotNull { row ->
                val channel = names[row.serviceKey] ?: return@mapNotNull null
                FilmDetector.candidate(CacheMappers.event(row), channel.name, channel.picon)
            }
            .distinctBy { Triple(FilmDetector.normalize(it.searchTitle), it.event.beginEpochSec / 300, FilmDetector.normalize(it.channelName.replace(Regex("(?i)\\bhd\\b"), ""))) }
        val films = HomeFilmsBuilder(tmdb, RoomFilmCache(dao, cacheMs())).build(candidates, countryCode, language) { done, total ->
            step(rk, 66 + 33 * done / total.coerceAtLeast(1), R.string.sync_step_films)
        }
        val rows = films.map { film ->
            val day = Instant.ofEpochSecond(film.event.beginEpochSec).atZone(zone).toLocalDate().toString()
            CacheMappers.homeFilmRow(rk, film, day, language)
        }
        dao.replaceHomeFilms(rk, today.startSec - 24 * 3600, horizonSec + 24 * 3600, rows)
        EnigmaLog.service("sync films receiver=$rk country=$countryCode candidates=${candidates.size} matched=${rows.size}")
    }

    /** How long TMDB answers stay valid: the films interval, at least 6 h; a week when Off. */
    private fun cacheMs(): Long {
        val interval = SyncPolicy.intervalMs(settings.values.value.effectiveFilmsPerDay) ?: (7L * 24 * 3600 * 1000)
        return interval.coerceAtLeast(6L * 3600 * 1000)
    }

    private suspend fun attempt(rk: String, kind: SyncKind, block: suspend () -> Unit) {
        val start = System.currentTimeMillis()
        val previous = dao.syncState(rk, kind.name)
        try {
            block()
            dao.putSyncState(SyncStateRow(rk, kind.name, System.currentTimeMillis(), start, ""))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EnigmaLog.service("sync $kind failed: ${e.javaClass.simpleName} ${e.message}")
            dao.putSyncState(SyncStateRow(rk, kind.name, previous?.lastSuccessMs ?: 0L, start, e.message ?: e.javaClass.simpleName))
        }
    }

    private suspend fun last(rk: String, kind: SyncKind): Long = dao.syncState(rk, kind.name)?.lastSuccessMs ?: 0L

    private suspend fun lastDay(rk: String, kind: SyncKind, zone: ZoneId): String? =
        dao.syncState(rk, kind.name)?.lastSuccessMs?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }

    private fun step(rk: String, percent: Int, label: Int) {
        _progress.value = SyncProgress(rk, percent.coerceIn(0, 100), UiText.res(label))
    }

    private companion object {
        const val FALLBACK_CHANNELS = 40
    }
}

/** TMDB answers in Room, valid for [ttlMs]. */
class RoomFilmCache(private val dao: CacheDao, private val ttlMs: Long) : FilmCache {
    override suspend fun match(key: String): FilmCache.CachedMatch? =
        dao.match(key, System.currentTimeMillis())?.let { FilmCache.CachedMatch(it.tmdbId) }

    override suspend fun putMatch(key: String, tmdbId: Int?, confidence: Double) =
        dao.insertMatch(TmdbMatchRow(key, tmdbId, confidence, System.currentTimeMillis() + ttlMs))

    override suspend fun movie(id: Int, language: String): TmdbMovie? =
        dao.movie(id, language, System.currentTimeMillis())?.let(CacheMappers::movie)

    override suspend fun putMovie(movie: TmdbMovie, language: String) =
        dao.insertMovie(CacheMappers.movieRow(movie, language, System.currentTimeMillis() + ttlMs))
}
