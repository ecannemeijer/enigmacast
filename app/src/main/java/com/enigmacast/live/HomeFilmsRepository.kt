package com.enigmacast.live

import android.content.Context
import android.telephony.TelephonyManager
import com.enigmacast.data.openwebif.OpenWebifApi
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.data.tmdb.HomeFilmSettings
import com.enigmacast.data.tmdb.TmdbHttp
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.films.CountryResolver
import com.enigmacast.domain.films.FilmCandidate
import com.enigmacast.domain.films.FilmDetector
import com.enigmacast.domain.films.HomeFilm
import com.enigmacast.domain.films.HomeFilmsBuilder
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.domain.tuner.NowEvents
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What the home page shows: today's matched films for [country]. */
data class HomeFilmsResult(
    val country: String,
    val films: List<HomeFilm>,
    val scannedChannels: Int,
    val candidates: Int,
    val missingKey: Boolean = false,
    val fetchedAtMs: Long = System.currentTimeMillis(),
)

/**
 * Collects today's films: every TV bouquet's guide from now until midnight (epgmulti, or
 * epgservice per channel when that is empty), keeps the programmes that look like films and
 * matches them on TMDB. Kept in memory for 30 minutes; TMDB answers are cached on disk for a day.
 */
@Singleton
class HomeFilmsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val api: OpenWebifApi,
    private val tmdb: TmdbHttp,
    private val homeSettings: HomeFilmSettings,
) {
    private val mutex = Mutex()
    private var last: Pair<String, HomeFilmsResult>? = null

    suspend fun load(force: Boolean = false): HomeFilmsResult? = mutex.withLock {
        val receiver = settingsRepository.current().activeReceiver() ?: return null
        val country = country()
        val language = CountryResolver.tmdbLanguage(appLanguage(), country)
        val cacheKey = "${receiver.id}|$country|$language|${tmdb.apiKey().hashCode()}"
        val now = System.currentTimeMillis()
        last?.let { (key, result) ->
            if (!force && key == cacheKey && now - result.fetchedAtMs < MEMORY_TTL_MS) return result
        }
        if (tmdb.apiKey().isBlank()) return HomeFilmsResult(country, emptyList(), 0, 0, missingKey = true)
        val (channels, candidates) = candidates(receiver)
        val films = HomeFilmsBuilder(tmdb).build(candidates, country, language)
        EnigmaLog.service("home films country=$country language=$language channels=$channels candidates=${candidates.size} matched=${films.size}")
        val result = HomeFilmsResult(country, films, channels, candidates.size)
        last = cacheKey to result
        result
    }

    /** Override, then network country, IP lookup (3 s, cached a week), then the TV locale. */
    suspend fun country(): String {
        val override = homeSettings.countryOverride()
        if (override != CountryResolver.AUTO) return CountryResolver.resolve(override, null, null, null)
        val network = runCatching {
            (context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkCountryIso
        }.getOrNull()
        val ip = if (CountryResolver.canonical(network) == null) ipCountry() else null
        return CountryResolver.resolve(null, network, ip, Locale.getDefault().country)
    }

    private suspend fun ipCountry(): String? {
        val now = System.currentTimeMillis()
        homeSettings.cachedIpCountry(now)?.let { return it }
        val code = withTimeoutOrNull(3_000) {
            withContext(Dispatchers.IO) {
                runCatching {
                    ipClient.newCall(Request.Builder().url("https://ipapi.co/country/").build()).execute().use { response ->
                        if (response.isSuccessful) response.body.string().trim() else null
                    }
                }.getOrNull()
            }
        }
        return CountryResolver.canonical(code)?.also { homeSettings.storeIpCountry(it, now) }
    }

    private fun appLanguage(): String = context.resources.configuration.locales[0]?.language ?: "en"

    private suspend fun candidates(receiver: ReceiverConfig): Pair<Int, List<FilmCandidate>> = coroutineScope {
        val zone = ZoneId.systemDefault()
        val nowSec = System.currentTimeMillis() / 1000
        val startSec = nowSec - LOOKBACK_SEC
        // Until midnight, or 03:00 when it is already late in the evening.
        val today = LocalDate.now(zone)
        val midnight = today.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val endSec = if (midnight - nowSec < 3 * 3600) today.plusDays(1).atTime(LocalTime.of(3, 0)).atZone(zone).toEpochSecond() else midnight
        val minutes = ((endSec - startSec) / 60).toInt()
        val bouquets = runCatching { api.televisionBouquets(receiver) }.getOrDefault(emptyList()).take(MAX_BOUQUETS)
        val gate = Semaphore(3)
        val perBouquet = bouquets.map { bouquet ->
            async {
                gate.withPermit {
                    val channels = runCatching { api.channelsInBouquet(receiver, bouquet.reference) }.getOrDefault(emptyList())
                    var events = runCatching { api.bouquetEvents(receiver, bouquet.reference, startSec, minutes) }.getOrDefault(emptyList())
                    if (events.isEmpty() && channels.isNotEmpty()) {
                        events = channels.take(FALLBACK_CHANNELS).flatMap { channel ->
                            runCatching { api.serviceEvents(receiver, channel.reference) }.getOrDefault(emptyList())
                        }
                    }
                    channels to events
                }
            }
        }.awaitAll()
        val names = HashMap<String, BouquetEntry>()
        perBouquet.forEach { (channels, _) -> channels.forEach { names.putIfAbsent(NowEvents.key(it.reference), it) } }
        val seen = HashSet<String>()
        val list = perBouquet.flatMap { it.second }
            .filter { it.endEpochSec > nowSec && it.beginEpochSec < endSec }
            .filter { seen.add(it.programmeKey) }
            .mapNotNull { event -> candidateOf(event, names) }
            .let { dedupeSimulcasts(it) }
        names.size to list
    }

    private fun candidateOf(event: GuideEvent, names: Map<String, BouquetEntry>): FilmCandidate? {
        val channel = names[NowEvents.key(event.reference)] ?: return null
        return FilmDetector.candidate(event, channel.name, channel.picon)
    }

    /** The same film on an HD and an SD channel at the same time: keep the first (HD bouquets come first). */
    private fun dedupeSimulcasts(list: List<FilmCandidate>): List<FilmCandidate> =
        list.distinctBy { Triple(FilmDetector.normalize(it.searchTitle), it.event.beginEpochSec / 300, FilmDetector.normalize(it.channelName.replace(Regex("(?i)\\bhd\\b"), ""))) }

    private val ipClient by lazy {
        OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
    }

    private companion object {
        const val MEMORY_TTL_MS = 30L * 60 * 1000
        const val LOOKBACK_SEC = 4L * 3600
        const val MAX_BOUQUETS = 12
        const val FALLBACK_CHANNELS = 40
    }
}
