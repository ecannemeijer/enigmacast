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
import kotlinx.coroutines.CancellationException
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
    private val repoDates = ConcurrentHashMap<SettingsProvider, Instant>()

    /**
     * The provider's variants, read from its repository tree. When the branch is gone (404),
     * the repository's default branch is tried once, so a master → main rename keeps working;
     * a repository that was removed or renamed itself stays [TransferFailure.NOT_FOUND].
     */
    suspend fun packages(provider: SettingsProvider, reload: Boolean = false): List<TunerPackage> {
        if (!reload) packages[provider]?.let { return it }
        val (branch, body) = try {
            provider.branch to get(provider.treeUrl, github = true, what = provider.repoPath)
        } catch (e: TransferException) {
            if (e.failure != TransferFailure.NOT_FOUND) throw e
            val repo = json.parseToJsonElement(String(get(provider.repoUrl, github = true, what = provider.repoPath), Charsets.UTF_8)).jsonObject
            val default = repo["default_branch"]?.jsonPrimitive?.content
            if (default.isNullOrBlank() || default == provider.branch) throw e
            default to get(provider.treeUrl(default), github = true, what = provider.repoPath)
        }
        val tree = json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonObject["tree"]?.jsonArray
            ?: throw TransferException(TransferFailure.DOWNLOAD_FAILED, "no tree")
        val entries = tree.map {
            val o = it.jsonObject
            SettingsRepoLayout.TreeEntry(o["path"]?.jsonPrimitive?.content.orEmpty(), o["type"]?.jsonPrimitive?.content.orEmpty())
        }
        val list = SettingsRepoLayout.packages(provider, entries, branch)
        if (list.isEmpty()) throw TransferException(TransferFailure.NO_FILES, provider.repoPath)
        return list.also { packages[provider] = it }
    }

    /** Variants already read this session (for the count in the picker), without a request. */
    fun cachedPackages(provider: SettingsProvider): List<TunerPackage>? = packages[provider]

    /** Date of the last change to the folder; null when GitHub does not say (rate limit). */
    suspend fun lastChanged(pkg: TunerPackage): Instant? {
        dates[pkg.id]?.let { return it }
        return runCatching {
            commitDate(get(pkg.provider.lastCommitUrl(pkg.folder), github = true, what = pkg.folder))
        }.getOrNull()?.also { dates[pkg.id] = it }
    }

    /**
     * Date of the newest commit in the provider's repository: one small request per repository
     * and session, shown in the list picker. Null when GitHub does not answer (offline, limit).
     */
    suspend fun repoUpdated(provider: SettingsProvider): Instant? {
        repoDates[provider]?.let { return it }
        val branch = packages[provider]?.firstOrNull()?.branch ?: provider.branch
        return runCatching {
            commitDate(get(provider.repoCommitUrl(branch), github = true, what = provider.repoPath))
        }.getOrNull()?.also { repoDates[provider] = it }
    }

    private fun commitDate(body: ByteArray): Instant? {
        val first = json.parseToJsonElement(String(body, Charsets.UTF_8)).jsonArray.firstOrNull()?.jsonObject ?: return null
        val date = first["commit"]?.jsonObject?.get("committer")?.jsonObject?.get("date")?.jsonPrimitive?.content
        return date?.let { Instant.parse(it) }
    }

    /** All list files of the package, by file name. */
    suspend fun download(pkg: TunerPackage): Map<String, ByteArray> = coroutineScope {
        val gate = Semaphore(8)
        pkg.uploadFiles.map { name ->
            async { gate.withPermit { name to get(pkg.provider.rawUrl("${pkg.folder}/$name", pkg.branch), github = false, what = name) } }
        }.awaitAll().toMap()
    }

    suspend fun playlist(source: IptvSource): String = String(get(source.url, github = false, what = source.title), Charsets.UTF_8)

    private suspend fun get(url: String, github: Boolean, what: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "EnigmaCast")
            .apply { if (github) header("Accept", "application/vnd.github+json") }
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw TransferErrors.fromHttp(response.code, response.header("x-ratelimit-remaining"), response.header("retry-after"), what)
                }
                response.body.bytes()
            }
        } catch (e: TransferException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not reaching GitHub is a download problem, not a receiver problem.
            val failure = if (TransferErrors.isOffline(e)) TransferFailure.OFFLINE else TransferFailure.DOWNLOAD_FAILED
            throw TransferException(failure, TransferErrors.map(e, TransferStage.DOWNLOAD).detail, e)
        }
    }
}
