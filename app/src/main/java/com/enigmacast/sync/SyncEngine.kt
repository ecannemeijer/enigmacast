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
    private val seriesRecorder: dagger.Lazy<com.enigmacast.live.SeriesRecorder>,
    private val channelMemory: dagger.Lazy<ChannelMemoryCarrier>,
    private val listEvents: ChannelListEvents,
) {
    private val gate = Mutex()
    private val _progress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

    fun receiverKey(receiver: ReceiverConfig) = CacheMappers.receiverKey(receiver.id, receiver.host, receiver.httpPort)

    /** Returns when the bouquet and guide sync that is running now has finished. */
    suspend fun awaitIdle() {
        gate.withLock { }
    }

    /**
     * True when something was refreshed. Errors are stored per step and do not throw.
     * [check] compares the box's channel lists with the cache even when the channel step is not
     * due (app start/resume). [carryFrom] is the receiver key the user just switched away from.
     */
    suspend fun run(receiver: ReceiverConfig, force: Boolean, refilm: Boolean = false, check: Boolean = false, carryFrom: String? = null): Boolean = gate.withLock {
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

            val hasChannels = dao.channelCount(rk) > 0
            val channelsDue = force || SyncPolicy.isDue(values.channelsPerDay, last(rk, SyncKind.CHANNELS), nowMs, hasChannels)
            // App start/resume: one bouquet-list call against Room; channels only when that changed.
            val startupChanged = check && !channelsDue && carryFrom == null && runCatching {
                val live = api.televisionBouquets(receiver).map { it.reference to it.name }
                val cached = dao.bouquets(rk).sortedBy { it.position }.map { it.reference to it.name }
                com.enigmacast.domain.sync.StartupChannelCheck.bouquetsChanged(cached, live)
            }.getOrDefault(false)
            if (check && !channelsDue && carryFrom == null) EnigmaLog.service("startup channel check: bouquets changed=$startupChanged")
            if (channelsDue || startupChanged || carryFrom != null) {
                step(rk, 2, R.string.sync_step_channels)
                attempt(rk, SyncKind.CHANNELS, keepHash = true) {
                    val outcome = syncChannels(receiver, rk, force = channelsDue, carryFrom = carryFrom?.takeIf { it != rk })
                    if (outcome.replaced) changed = true
                }
            }
            val horizonSec = today.startSec + values.epgDays * 24 * 3600L
            val epgState = dao.syncState(rk, SyncKind.EPG.name)
            val epgReason = com.enigmacast.domain.sync.EpgSyncDecision.decide(
                force = force,
                channelsChanged = changed,
                hasGuideToday = dao.eventCount(rk, today.startSec, today.endSec) > 0,
                timesPerDay = values.epgPerDay,
                lastSuccessMs = epgState?.lastSuccessMs ?: 0L,
                lastAttemptMs = epgState?.lastAttemptMs ?: 0L,
                nowMs = nowMs,
            )
            EnigmaLog.service("sync epg decision receiver=$rk: $epgReason")
            if (com.enigmacast.domain.sync.EpgSyncDecision.runs(epgReason)) {
                // Marked as started first, so a download that never finishes is not redone on every start.
                dao.putSyncState(SyncStateRow(rk, SyncKind.EPG.name, epgState?.lastSuccessMs ?: 0L, nowMs, epgState?.error.orEmpty(), epgState?.contentHash.orEmpty()))
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
            // Series rules: add timers for episodes that the new guide shows.
            runCatching { seriesRecorder.get().rescan(receiver) }
                .onFailure { EnigmaLog.service("series rescan failed: ${it.javaClass.simpleName}") }
            changed
        } finally {
            _progress.value = null
        }
    }

    /** Result of a channel-list sync, for Settings' "Refresh channels & bouquets now". */
    data class ChannelOutcome(val replaced: Boolean, val bouquets: Int, val channels: Int)

    /**
     * Settings › Receivers: fetches the box's bouquets and channels now and replaces the cache
     * when anything differs. Throws when the box cannot be reached. The guide follows in the background.
     * With [force] the cache is replaced even when the content hash matches (after a settings import).
     * Screens are told through [ChannelListEvents] whenever the cache was replaced.
     */
    suspend fun refreshChannelsNow(receiver: ReceiverConfig, force: Boolean = false): ChannelOutcome {
        val rk = receiverKey(receiver)
        val outcome = gate.withLock {
            try {
                step(rk, 2, R.string.sync_step_channels)
                var result: ChannelOutcome? = null
                var failure: Exception? = null
                attempt(rk, SyncKind.CHANNELS, keepHash = true, onError = { failure = it }) {
                    result = syncChannels(receiver, rk, force = force, carryFrom = null)
                }
                failure?.let { throw it }
                result!!
            } finally {
                _progress.value = null
            }
        }
        if (outcome.replaced) listEvents.notifyChanged(receiver.id)
        return outcome
    }

    /**
     * Fetches all TV bouquets and their channels and compares their [ChannelListHash] with the
     * stored one. When it differs (or [force]), this receiver's bouquets and channels are fully
     * replaced in one transaction, so removed, renamed and reordered entries never linger.
     */
    private suspend fun syncChannels(receiver: ReceiverConfig, rk: String, force: Boolean, carryFrom: String?): ChannelOutcome {
        val bouquets = api.televisionBouquets(receiver)
        require(bouquets.isNotEmpty()) { "no bouquets" }
        val snapshots = ArrayList<com.enigmacast.domain.sync.BouquetSnapshot>()
        val bouquetRows = bouquets.mapIndexed { index, b -> BouquetRow(rk, b.reference, b.name, index) }
        val channelRows = ArrayList<ChannelRow>()
        var position = 0
        bouquets.forEachIndexed { index, bouquet ->
            step(rk, 2 + 13 * (index + 1) / bouquets.size, R.string.sync_step_channels)
            // A bouquet that fails to load keeps its cached channels instead of being emptied.
            val channels = runCatching { api.channelsInBouquet(receiver, bouquet.reference) }.getOrElse {
                dao.channelsIn(rk, bouquet.reference).map { com.enigmacast.domain.model.BouquetEntry(it.name, it.reference, it.number, it.picon) }
            }
            snapshots += com.enigmacast.domain.sync.BouquetSnapshot(bouquet.reference, bouquet.name, channels.map { it.reference })
            channels.forEach { c -> channelRows += ChannelRow(rk, bouquet.reference, c.reference, c.name, c.picon, c.number, position++, NowEvents.key(c.reference)) }
        }
        val hash = com.enigmacast.domain.sync.ChannelListHash.of(snapshots)
        val stored = dao.syncState(rk, SyncKind.CHANNELS.name)
        val hasCache = dao.channelCount(rk) > 0
        val action = com.enigmacast.domain.sync.ChannelSyncDecision.decide(force, hasCache, stored?.contentHash.orEmpty(), hash)
        val now = System.currentTimeMillis()
        val state = SyncStateRow(rk, SyncKind.CHANNELS.name, now, now, "", hash)
        if (action == com.enigmacast.domain.sync.ChannelSyncDecision.Action.KEEP) {
            dao.putSyncState(state)
            EnigmaLog.service("sync channels receiver=$rk unchanged")
            return ChannelOutcome(false, bouquets.size, channelRows.size)
        }
        val previousHash = carryFrom?.let { dao.syncState(it, SyncKind.CHANNELS.name)?.contentHash }.orEmpty()
        val same = carryFrom != null && com.enigmacast.domain.sync.ChannelSyncDecision.carryOver(previousHash, hash)
        if (same && !hasCache && carryFrom != null) {
            // Same lists as the box we came from: reuse its guide and the user's choices; the EPG refreshes later.
            dao.replaceChannels(rk, bouquetRows, channelRows)
            dao.putSyncState(state)
            dao.copyEvents(carryFrom, rk)
            if (bouquetStore.selected(rk) == null) bouquetStore.selected(carryFrom)?.let { bouquetStore.set(rk, it) }
            runCatching { channelMemory.get().carryOver(carryFrom, receiver.id) }
            EnigmaLog.service("sync channels receiver=$rk same lists as $carryFrom: cache carried over")
            return ChannelOutcome(true, bouquets.size, channelRows.size)
        }
        dao.replaceChannelLists(rk, bouquetRows, channelRows, state)
        EnigmaLog.service("sync channels receiver=$rk bouquets=${bouquets.size} channels=${channelRows.size} replaced")
        return ChannelOutcome(true, bouquets.size, channelRows.size)
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
                    // Per-channel requests only when the bouquet request itself failed: a bouquet whose
                    // channels have no guide (an empty answer) would otherwise cost 40 calls every sync.
                    val multi = runCatching { api.bouquetEvents(receiver, bouquet.reference, fromSec, minutes) }
                        .onFailure { if (it is CancellationException) throw it }
                    var events = multi.getOrDefault(emptyList())
                    if (multi.isFailure) {
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
        syncSeries(rk, today, horizonSec, zone, countryCode, language, names)
    }

    /** TV Series page: recurring / episode-marked programmes matched with TMDB TV. */
    private suspend fun syncSeries(
        rk: String,
        today: DayWindow,
        horizonSec: Long,
        zone: ZoneId,
        countryCode: String,
        language: String,
        names: Map<String, BouquetChannel>,
    ) {
        val nowSec = System.currentTimeMillis() / 1000
        val events = dao.events(rk, nowSec - 4 * 3600, horizonSec).map { CacheMappers.event(it) }
        val candidates = com.enigmacast.domain.series.SeriesDetector.candidates(events) { e ->
            names[com.enigmacast.domain.tuner.NowEvents.key(e.reference)]?.let { it.name to it.picon }
        }
        val shows = com.enigmacast.domain.series.HomeSeriesBuilder(tmdb, RoomFilmCache(dao, cacheMs())).build(candidates, countryCode, language) { done, total ->
            step(rk, 99, R.string.sync_step_films)
        }
        val rows = shows.map { film ->
            val day = Instant.ofEpochSecond(film.event.beginEpochSec).atZone(zone).toLocalDate().toString()
            CacheMappers.homeFilmRow(rk, film, day, language, com.enigmacast.data.db.HomeKinds.SERIES)
        }
        dao.replaceHomeFilms(rk, today.startSec - 24 * 3600, horizonSec + 24 * 3600, rows, com.enigmacast.data.db.HomeKinds.SERIES)
        EnigmaLog.service("sync series receiver=$rk candidates=${candidates.size} matched=${rows.size}")
    }

    /** How long TMDB answers stay valid: the films interval, at least 6 h; a week when Off. */
    private fun cacheMs(): Long {
        val interval = SyncPolicy.intervalMs(settings.values.value.effectiveFilmsPerDay) ?: (7L * 24 * 3600 * 1000)
        return interval.coerceAtLeast(6L * 3600 * 1000)
    }

    private suspend fun attempt(rk: String, kind: SyncKind, keepHash: Boolean = false, onError: (Exception) -> Unit = {}, block: suspend () -> Unit) {
        val start = System.currentTimeMillis()
        val previous = dao.syncState(rk, kind.name)
        try {
            block()
            val hash = if (keepHash) dao.syncState(rk, kind.name)?.contentHash.orEmpty() else ""
            dao.putSyncState(SyncStateRow(rk, kind.name, System.currentTimeMillis(), start, "", hash))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EnigmaLog.service("sync $kind failed: ${e.javaClass.simpleName} ${e.message}")
            dao.putSyncState(SyncStateRow(rk, kind.name, previous?.lastSuccessMs ?: 0L, start, e.message ?: e.javaClass.simpleName, previous?.contentHash.orEmpty()))
            onError(e)
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
