package com.enigmacast.data.torrent

import com.enigmacast.data.security.SecretStore
import com.enigmacast.domain.torrent.ManifestError
import com.enigmacast.domain.torrent.ManifestException
import com.enigmacast.domain.torrent.ScraperManifest
import com.enigmacast.domain.torrent.ScraperUrl
import com.enigmacast.domain.torrent.StremioManifest
import com.enigmacast.domain.torrent.StremioStreams
import com.enigmacast.domain.torrent.TorrentStream
import com.enigmacast.util.EnigmaLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Where the scraper comes from: a custom manifest URL wins over the Torrentio toggle. */
enum class ScraperSource { NONE, TORRENTIO, CUSTOM }

sealed interface ScraperStatus {
    data object None : ScraperStatus
    data object Checking : ScraperStatus
    data class Valid(val manifest: ScraperManifest) : ScraperStatus
    data class Invalid(val error: ManifestError, val code: Int = 0) : ScraperStatus
}

/**
 * The Stremio addon (e.g. Torrentio) used to find streams. Its manifest URL can carry a debrid
 * key, so it lives in the encrypted [SecretStore] and only the masked form is ever logged or shown.
 * The manifest is checked when the app starts and when a new URL is saved.
 */
@Singleton
class ScraperRepository @Inject constructor(private val secrets: SecretStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val _status = MutableStateFlow<ScraperStatus>(ScraperStatus.None)
    val status: StateFlow<ScraperStatus> = _status.asStateFlow()
    private val _torrentio = MutableStateFlow(false)
    /** Settings › Scrapers › Enable with Torrentio (persisted). */
    val torrentio: StateFlow<Boolean> = _torrentio.asStateFlow()

    init {
        scope.launch {
            _torrentio.value = secrets.read(KEY_TORRENTIO) == "1"
            check()
        }
    }

    private fun custom(): ScraperUrl? = secrets.read(KEY)?.let(StremioManifest::normalize)

    /** The custom URL when set, else Torrentio when enabled. */
    fun source(): ScraperSource = when {
        custom() != null -> ScraperSource.CUSTOM
        _torrentio.value -> ScraperSource.TORRENTIO
        else -> ScraperSource.NONE
    }

    /** The scraper in use (a custom one is secret: never log it; show [masked]). */
    fun url(): ScraperUrl? = custom() ?: if (_torrentio.value) StremioManifest.normalize(TORRENTIO_MANIFEST) else null
    /** The custom URL, masked; null when none is saved. */
    fun masked(): String? = custom()?.let { StremioManifest.mask(it.manifest) }

    /** Turns the Torrentio preset on or off and checks the scraper in use again. */
    suspend fun setTorrentio(enabled: Boolean): ScraperStatus {
        withContext(Dispatchers.IO) { secrets.write(KEY_TORRENTIO, if (enabled) "1" else null) }
        _torrentio.value = enabled
        return check()
    }

    /** Saves and checks [input]; an empty value removes the scraper. */
    suspend fun save(input: String): ScraperStatus {
        if (input.isBlank()) {
            // Removing the custom URL falls back to Torrentio when that is on.
            withContext(Dispatchers.IO) { secrets.write(KEY, null) }
            return check()
        }
        val url = StremioManifest.normalize(input) ?: return ScraperStatus.Invalid(ManifestError.URL).also { _status.value = it }
        withContext(Dispatchers.IO) { secrets.write(KEY, url.manifest) }
        return check()
    }

    suspend fun check(): ScraperStatus = withContext(Dispatchers.IO) {
        val url = url() ?: return@withContext ScraperStatus.None.also { _status.value = it }
        _status.value = ScraperStatus.Checking
        val result = runCatching {
            client.newCall(Request.Builder().url(StremioManifest.httpUrl(url.manifest)).header("Accept", "application/json").build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching ScraperStatus.Invalid(ManifestError.HTTP, r.code)
                StremioManifest.parse(r.body.string()).fold(
                    { ScraperStatus.Valid(it) },
                    { ScraperStatus.Invalid((it as? ManifestException)?.error ?: ManifestError.JSON) },
                )
            }
        }.getOrElse { ScraperStatus.Invalid(ManifestError.HTTP) }
        EnigmaLog.network("scraper ${StremioManifest.mask(url.manifest)}: ${result.javaClass.simpleName}")
        _status.value = result
        result
    }

    /** Streams for a movie or an episode, best first. Empty on any error. */
    suspend fun streams(imdb: String, season: Int? = null, episode: Int? = null): Result<List<TorrentStream>> = withContext(Dispatchers.IO) {
        val base = url()?.base ?: return@withContext Result.failure(IllegalStateException("no scraper"))
        val address = StremioStreams.url(base, imdb, season, episode) ?: return@withContext Result.failure(IllegalArgumentException("imdb"))
        runCatching {
            client.newCall(Request.Builder().url(StremioManifest.httpUrl(address)).header("Accept", "application/json").build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                StremioStreams.sorted(StremioStreams.parse(r.body.string()))
            }
        }.onFailure { EnigmaLog.network("scraper streams failed: ${it.javaClass.simpleName}") }
    }

    companion object {
        private const val KEY = "torrent_scraper_manifest"
        private const val KEY_TORRENTIO = "torrent_scraper_torrentio"
        /** Torrentio sorted by seeders, 1080p and 720p only (qualityfilter excludes the rest). */
        const val TORRENTIO_MANIFEST = "https://torrentio.strem.fun/sort=seeders|qualityfilter=1080p,720p/manifest.json"
    }
}
