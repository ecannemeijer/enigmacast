package com.enigmacast.data.epg

import android.content.Context
import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.db.FallbackEpgChannelRow
import com.enigmacast.data.db.FallbackEpgDao
import com.enigmacast.data.db.FallbackEpgProgrammeRow
import com.enigmacast.data.db.IptvDao
import com.enigmacast.data.settings.SettingsRepository
import com.enigmacast.domain.epg.FallbackChannel
import com.enigmacast.domain.epg.FallbackEpgCountries
import com.enigmacast.domain.epg.FallbackEpgNames
import com.enigmacast.domain.epg.FallbackEpgPolicy
import com.enigmacast.domain.epg.FallbackEpgXmltv
import com.enigmacast.domain.epg.FallbackProgramme
import com.enigmacast.domain.epg.FallbackWanted
import com.enigmacast.live.CountryDetector
import com.enigmacast.locale.AppLanguageStore
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads the iptv-epg.org country files in the background, parses them as a stream, and
 * keeps only programmes that match channels still without a guide of their own. Failures are
 * logged by type and otherwise ignored. At most once about every 12 hours per country.
 */
@Singleton
class FallbackEpgSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: FallbackEpgDao,
    private val cache: CacheDao,
    private val iptv: IptvDao,
    private val settings: SettingsRepository,
    private val fallback: FallbackEpg,
    private val detector: CountryDetector,
) {
    private val mutex = Mutex()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(6, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** Countries that would be downloaded now (for Settings). */
    suspend fun automaticCountries(): Set<String> = withContext(Dispatchers.IO) {
        val snapshot = settings.current()
        FallbackEpgCountries.auto(
            texts = texts(),
            appLanguage = AppLanguageStore.current(context).tag,
            localeCountry = runCatching { detector.country() }.getOrNull(),
            extra = emptyList(),
        )
    }

    /**
     * Refresh country files that are due (or all of them when [force] is true). Does nothing
     * when the fallback is switched off. Never throws.
     */
    suspend fun refreshIfDue(force: Boolean = false) {
        val snapshot = runCatching { settings.current() }.getOrNull() ?: return
        if (!snapshot.fallbackEpgEnabled) return
        if (!mutex.tryLock()) return
        try {
            withContext(Dispatchers.IO) { refreshLocked(force) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            EnigmaLog.networkError("fallback epg refresh failed: ${e.javaClass.simpleName}")
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun refreshLocked(force: Boolean) {
        val snapshot = settings.current()
        if (!snapshot.fallbackEpgEnabled) return
        val texts = texts()
        val countries = FallbackEpgCountries.auto(
            texts = texts,
            appLanguage = AppLanguageStore.current(context).tag,
            localeCountry = runCatching { detector.country() }.getOrNull(),
            extra = snapshot.fallbackEpgCountries,
        )
        if (countries.isEmpty()) return
        val wanted = wanted(texts)
        if (wanted.isEmpty) return
        val now = System.currentTimeMillis()
        var stored = 0
        countries.forEach { country ->
            val fetched = runCatching { dao.meta(country)?.fetchedAtMs }.getOrNull()
            if (!force && !FallbackEpgPolicy.isDue(fetched, now)) return@forEach
            stored += download(country, wanted, now)
        }
        if (stored > 0) fallback.invalidate()
        EnigmaLog.service("fallback epg refresh countries=${countries.size} programmes=$stored")
    }

    private suspend fun download(country: String, wanted: FallbackWanted, nowMs: Long): Int {
        val url = FallbackEpgCountries.url(country)
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Encoding", "identity")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    EnigmaLog.networkError("fallback epg $country failed: HTTP ${response.code}")
                    return 0
                }
                val body = response.body ?: return 0
                val channels = ArrayList<FallbackEpgChannelRow>()
                val programmes = ArrayList<FallbackEpgProgrammeRow>()
                GZIPInputStream(BufferedInputStream(body.byteStream(), 64 * 1024)).use { gzip ->
                    FallbackEpgXmltv.parse(
                        gzip,
                        wanted,
                        onProgramme = { programme -> programmes += row(programme) },
                        onChannel = { channel -> channels += row(country, channel) },
                    )
                }
                dao.replaceCountry(country, channels, programmes, nowMs)
                programmes.size
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            EnigmaLog.networkError("fallback epg $country failed: ${e.javaClass.simpleName}")
            0
        }
    }

    /** Channel names and group titles used to pick countries and to match programmes. */
    private suspend fun texts(): List<String> {
        val out = ArrayList<String>()
        val receiver = settings.current().activeReceiver()
        if (receiver != null) {
            val key = com.enigmacast.data.db.CacheMappers.receiverKey(receiver.id, receiver.host, receiver.httpPort)
            runCatching { cache.channelNames(key) }.getOrDefault(emptyList()).forEach { out += it.name }
        }
        runCatching { iptv.liveItemNames() }.getOrDefault(emptyList()).forEach { out += it }
        runCatching { iptv.liveCategoryNames() }.getOrDefault(emptyList()).forEach { out += it }
        return out
    }

    /**
     * Keys of channels that still need a guide: satellite channels without events of their own,
     * and every IPTV live name (their Xtream guide is not known up front).
     */
    private suspend fun wanted(allTexts: List<String>): FallbackWanted {
        val keys = LinkedHashSet<String>()
        val receiver = settings.current().activeReceiver()
        if (receiver != null) {
            val key = com.enigmacast.data.db.CacheMappers.receiverKey(receiver.id, receiver.host, receiver.httpPort)
            val nowSec = System.currentTimeMillis() / 1000
            val withGuide = runCatching { cache.serviceKeysWithEvents(key, nowSec) }.getOrDefault(emptyList()).toHashSet()
            runCatching { cache.channelMatchRows(key) }.getOrDefault(emptyList()).forEach { row ->
                val service = row.serviceKey.ifBlank { com.enigmacast.domain.tuner.NowEvents.key(row.reference) }
                if (service.isNotEmpty() && service !in withGuide) {
                    FallbackEpgNames.key(row.name).takeIf { it.isNotEmpty() }?.let { keys += it }
                }
            }
        }
        runCatching { iptv.liveItemNames() }.getOrDefault(emptyList()).forEach { name ->
            FallbackEpgNames.key(name).takeIf { it.isNotEmpty() }?.let { keys += it }
        }
        // Name keys of every collected title, so a country file can also match by a prefixed form.
        if (keys.isEmpty()) {
            allTexts.forEach { name -> FallbackEpgNames.key(name).takeIf { it.isNotEmpty() }?.let { keys += it } }
        }
        return FallbackWanted(keys)
    }

    private fun row(country: String, channel: FallbackChannel) = FallbackEpgChannelRow(
        xmltvId = channel.xmltvId,
        country = country,
        displayName = channel.displayName,
        nameKey = FallbackEpgNames.key(channel.displayName.ifBlank { channel.xmltvId }),
    )

    private fun row(programme: FallbackProgramme) = FallbackEpgProgrammeRow(
        xmltvId = programme.xmltvId,
        beginSec = programme.beginSec,
        endSec = programme.endSec,
        title = programme.title,
        description = programme.description,
    )

    private companion object {
        const val USER_AGENT = "EnigmaCast"
    }
}
