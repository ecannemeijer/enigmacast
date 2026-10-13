package com.enigmacast.data.settingsimport

import com.enigmacast.domain.settingsimport.SettingsProvider
import com.enigmacast.domain.settingsimport.SettingsRepoLayout
import com.enigmacast.domain.settingsimport.TransferErrors
import com.enigmacast.domain.settingsimport.TransferException
import com.enigmacast.domain.settingsimport.TransferFailure
import com.enigmacast.domain.settingsimport.TransferStage
import com.enigmacast.domain.settingsimport.TunerPackage
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** The M3U playlists offered for IPTV bouquets. */
enum class IptvSource(val title: String, val url: String) {
    /** iptv-org, one group per category (News, Sports, Movies …). */
    IPTV_ORG_CATEGORIES("iptv-org (categories)", "https://iptv-org.github.io/iptv/index.m3u"),
    /** iptv-org, one group per country. */
    IPTV_ORG_COUNTRIES("iptv-org (countries)", "https://iptv-org.github.io/iptv/index.country.m3u"),
    /** Free-TV/IPTV: free-to-air channels, one group per country. */
    FREE_TV("Free-TV", "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"),
    ;

    companion object {
        fun byName(name: String?): IptvSource? = entries.firstOrNull { it.name == name }
    }
}

/** Downloads from GitHub and the playlist hosts. The GitHub API is only used for the folder list and dates. */
@Singleton
class SettingsImportSources @Inject constructor() {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val packages = ConcurrentHashMap<SettingsProvider, List<TunerPackage>>()
    private val dates = ConcurrentHashMap<String, Instant>()

    suspend fun packages(provider: SettingsProvider, reload: Boolean = false): List<TunerPackage> {
        if (!reload) packages[provider]?.let { return it }
        val body = String(get(provider.treeUrl, github = true), Charsets.UTF_8)
        val tree = json.parseToJsonElement(body).jsonObject["tree"]?.jsonArray
            ?: throw TransferException(TransferFailure.DOWNLOAD_FAILED, "no tree")
        val entries = tree.map {
            val o = it.jsonObject
            SettingsRepoLayout.TreeEntry(o["path"]?.jsonPrimitive?.content.orEmpty(), o["type"]?.jsonPrimitive?.content.orEmpty())
        }
        return SettingsRepoLayout.packages(provider, entries).also { packages[provider] = it }
    }

    /** Date of the last change to the folder; null when GitHub does not say (rate limit). */
    suspend fun lastChanged(pkg: TunerPackage): Instant? {
        dates[pkg.id]?.let { return it }
        return runCatching {
            val body = String(get(pkg.provider.lastCommitUrl(pkg.folder), github = true), Charsets.UTF_8)
            val first = json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject ?: return null
            val date = first["commit"]?.jsonObject?.get("committer")?.jsonObject?.get("date")?.jsonPrimitive?.content
            date?.let { Instant.parse(it) }
        }.getOrNull()?.also { dates[pkg.id] = it }
    }

    /** All list files of the package, by file name. */
    suspend fun download(pkg: TunerPackage): Map<String, ByteArray> = coroutineScope {
        val gate = Semaphore(8)
        pkg.uploadFiles.map { name ->
            async { gate.withPermit { name to get(pkg.provider.rawUrl("${pkg.folder}/$name"), github = false) } }
        }.awaitAll().toMap()
    }

    private val epgHeaders = ConcurrentHashMap<IptvSource, List<String>>()

    suspend fun playlist(source: IptvSource): String =
        String(get(source.url, github = false), Charsets.UTF_8).also { epgHeaders[source] = com.enigmacast.domain.settingsimport.IptvEpg.headerUrls(it) }

    /** EPG URLs from the header of the last downloaded copy of [source]. */
    fun epgHeader(source: IptvSource): List<String> = epgHeaders[source].orEmpty()

    private suspend fun get(url: String, github: Boolean): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "EnigmaCast")
            .apply { if (github) header("Accept", "application/vnd.github+json") }
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw TransferException(TransferFailure.DOWNLOAD_FAILED, "HTTP ${response.code} ${url.substringAfterLast('/')}")
                }
                response.body.bytes()
            }
        } catch (e: TransferException) {
            throw e
        } catch (e: Exception) {
            throw TransferErrors.map(e, TransferStage.DOWNLOAD).let {
                // Not reaching GitHub is a download problem, not a receiver problem.
                TransferException(TransferFailure.DOWNLOAD_FAILED, it.detail, e)
            }
        }
    }
}
