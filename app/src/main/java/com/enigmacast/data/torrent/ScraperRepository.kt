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

/** Torrent is on exactly when the user saved their own addon manifest URL. There is no preset. */
enum class ScraperSource {
    NONE, CUSTOM;

    companion object {
        fun of(hasUrl: Boolean) = if (hasUrl) CUSTOM else NONE
    }
}

sealed interface ScraperStatus {
    data object None : ScraperStatus
    data object Checking : ScraperStatus
    data class Valid(val manifest: ScraperManifest) : ScraperStatus
    data class Invalid(val error: ManifestError, val code: Int = 0) : ScraperStatus
}

/**
 * The user's own Stremio addon used to find streams. Its manifest URL can carry a debrid
 * key, so it lives in the encrypted [SecretStore] and only the masked form is ever logged or shown.
 * The manifest is checked when the app starts and when a new URL is saved.
 */
@Singleton
class ScraperRepository @Inject constructor(private val secrets: SecretStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val _status = MutableStateFlow<ScraperStatus>(ScraperStatus.None)
    val status: StateFlow<ScraperStatus> = _status.asStateFlow()
    init {
        scope.launch {
            migrateToggle()
            check()
        }
    }

    /**
     * Older versions had an on/off switch next to the URL. A saved URL always won over it, so a
     * URL that was saved with the switch off was in use anyway: it stays and Torrent stays on.
     * Only the switch itself (and the preset it stood for) goes.
     */
    private fun migrateToggle() {
        val old = secrets.read(LEGACY_TOGGLE) ?: return
        secrets.write(LEGACY_TOGGLE, null)
        EnigmaLog.network("scraper: removed the old on/off switch (${if (old == "1") "on" else "off"}), url ${if (custom() != null) "kept" else "none"}")
    }

    private fun custom(): ScraperUrl? = secrets.read(KEY)?.let(StremioManifest::normalize)

    fun source(): ScraperSource = ScraperSource.of(custom() != null)

    /** The scraper in use (a custom one is secret: never log it; show [masked]). */
    fun url(): ScraperUrl? = custom()
    /** The custom URL, masked; null when none is saved. */
    fun masked(): String? = custom()?.let { StremioManifest.mask(it.manifest) }

    /** Saves and checks [input]; an empty value removes the scraper. */
    suspend fun save(input: String): ScraperStatus {
        if (input.isBlank()) {
            // No URL: Torrent is off.
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
        /** The removed on/off switch (secret key name kept so it can be cleared). */
        private const val LEGACY_TOGGLE = "torrent_scraper_torrentio"
    }
}
