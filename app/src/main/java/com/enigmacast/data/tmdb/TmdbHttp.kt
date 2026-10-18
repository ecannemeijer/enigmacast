package com.enigmacast.data.tmdb

import android.content.Context
import com.enigmacast.BuildConfig
import com.enigmacast.domain.films.TmdbApi
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TMDB over HTTPS with a 24 h disk cache, at most 4 requests at once and 20 per second.
 * The key comes from Settings, else from local.properties via BuildConfig.TMDB_API_KEY.
 */
@Singleton
class TmdbHttp @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: HomeFilmSettings,
) : TmdbApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val cache = TmdbDiskCache(File(context.cacheDir, "tmdb"))
    private val limiter = RateLimiter(20)
    private val parallel = Semaphore(4)

    fun apiKey(): String = settings.tmdbKeyOverride().ifBlank { BuildConfig.TMDB_API_KEY }.trim()

    override suspend fun searchMovie(query: String, year: Int?, language: String, region: String): String? {
        val params = buildList {
            add("query" to query)
            add("language" to language)
            add("region" to region)
            add("include_adult" to "false")
            if (year != null) add("year" to year.toString())
        }
        return get("/search/movie", params)
    }

    override suspend fun movie(id: Int, language: String): String? = get(
        "/movie/$id",
        listOf(
            "language" to language,
            "append_to_response" to "credits,release_dates,images",
            "include_image_language" to "${language.take(2)},en,null",
        ),
    )

    override suspend fun searchTv(query: String, language: String): String? =
        get("/search/tv", listOf("query" to query, "language" to language, "include_adult" to "false"))

    override suspend fun tv(id: Int, language: String): String? = get(
        "/tv/$id",
        listOf(
            "language" to language,
            "append_to_response" to "aggregate_credits,content_ratings,images",
            "include_image_language" to "${language.take(2)},en,null",
        ),
    )

    /** Torrent page: popular movies (/movie/popular) or series (/tv/popular), [page] from 1. */
    suspend fun popular(tv: Boolean, page: Int, language: String): String? =
        get(if (tv) "/tv/popular" else "/movie/popular", listOf("language" to language, "page" to page.toString()))

    suspend fun searchPage(tv: Boolean, query: String, page: Int, language: String): String? =
        get(if (tv) "/search/tv" else "/search/movie", listOf("query" to query, "language" to language, "page" to page.toString(), "include_adult" to "false"))

    /** {"imdb_id": "tt…"} for a movie or series. */
    suspend fun externalIds(tv: Boolean, id: Int): String? = get(if (tv) "/tv/$id/external_ids" else "/movie/$id/external_ids", emptyList())

    suspend fun tvBasic(id: Int, language: String): String? = get("/tv/$id", listOf("language" to language))

    suspend fun season(tvId: Int, season: Int, language: String): String? = get("/tv/$tvId/season/$season", listOf("language" to language))

    private suspend fun get(path: String, params: List<Pair<String, String>>): String? {
        val key = apiKey()
        if (key.isBlank()) return null
        val cacheKey = sha1(path + params.joinToString("&") { "${it.first}=${it.second}" })
        // Disk read: never on the caller's thread (the Home build can resume on the main thread).
        withContext(Dispatchers.IO) { cache.read(cacheKey) }?.let { return it }
        return parallel.withPermit {
            limiter.acquire()
            withContext(Dispatchers.IO) {
                val url = "https://api.themoviedb.org/3$path".toHttpUrl().newBuilder().apply {
                    addQueryParameter("api_key", key)
                    params.forEach { addQueryParameter(it.first, it.second) }
                }.build()
                try {
                    client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            EnigmaLog.connection("tmdb $path HTTP ${response.code}")
                            return@withContext null
                        }
                        val body = response.body.string()
                        cache.write(cacheKey, body)
                        body
                    }
                } catch (e: Exception) {
                    EnigmaLog.connection("tmdb $path failed: ${e.javaClass.simpleName}")
                    null
                }
            }
        }
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** One file per answer; older than [ttlMs] counts as missing. */
class TmdbDiskCache(
    private val dir: File,
    private val ttlMs: Long = 24L * 60 * 60 * 1000,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    fun read(key: String): String? {
        val file = File(dir, key)
        if (!file.isFile || clockMs() - file.lastModified() > ttlMs) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun write(key: String, body: String) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$key.tmp")
            tmp.writeText(body)
            tmp.renameTo(File(dir, key))
            File(dir, key).setLastModified(clockMs())
        }
    }
}
