package com.enigmacast.data.subsource

import android.content.Context
import com.enigmacast.BuildConfig
import com.enigmacast.domain.subsource.SubSourceArchive
import com.enigmacast.domain.subsource.SubSourceFile
import com.enigmacast.domain.subsource.SubSourceHit
import com.enigmacast.domain.subsource.SubSourceJson
import com.enigmacast.domain.subsource.SubSourceMatch
import com.enigmacast.domain.subsource.SubSourceQuery
import com.enigmacast.util.EnigmaLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** SubSource lookups for IPTV films and series. The key stays in BuildConfig, never in a URL. */
@Singleton
class SubSourceClient @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val cache = File(context.cacheDir, "subsource").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun apiKey(): String = BuildConfig.SUBSOURCE_API_KEY.trim()

    /** Titles that match [query], at most a few files per enabled language. */
    suspend fun find(query: SubSourceQuery): List<SubSourceHit> = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isBlank() || query.title.isBlank() || query.languages.isEmpty()) return@withContext emptyList()
        val search = buildList {
            add("searchType" to "text")
            add("q" to query.title.lowercase())
            if (query.season != null && query.season > 0) add("season" to query.season.toString())
        }
        val titles = SubSourceJson.titles(get("movies/search", search, key) ?: return@withContext emptyList())
        val movie = SubSourceMatch.pickTitle(titles, query.title, query.year) ?: return@withContext emptyList()
        query.languages.distinct().flatMap { language ->
            val params = buildList {
                add("language" to language)
                add("movieId" to movie.movieId.toString())
                add("limit" to "40")
                if (query.season != null && query.season > 0) add("seasonNumber" to query.season.toString())
                if (query.episode != null && query.episode > 0) add("episodeNumber" to query.episode.toString())
            }
            val hits = SubSourceJson.subtitles(get("subtitles", params, key) ?: return@flatMap emptyList())
                .filter { it.language == language }
            val episode = if (query.season != null && query.episode != null && query.season > 0) {
                SubSourceMatch.forEpisode(hits, query.season, query.episode)
            } else {
                hits
            }
            episode.sortedBy { it.hearingImpaired }.take(PER_LANGUAGE)
        }
    }

    /** A cached subtitle file for [id], downloaded and unzipped when it is not here yet. */
    suspend fun file(id: Long): File? = withContext(Dispatchers.IO) {
        cache.listFiles()?.firstOrNull { it.name.startsWith("$id.") && it.length() > 0 }?.let { return@withContext it }
        val key = apiKey()
        if (key.isBlank()) return@withContext null
        val bytes = bytes("subtitles/$id/download", key) ?: return@withContext null
        val extracted = SubSourceArchive.extract(bytes) ?: return@withContext null
        write(id, extracted)
    }

    private fun write(id: Long, extracted: SubSourceFile): File {
        val file = File(cache, "$id.${extracted.extension}")
        file.writeBytes(extracted.bytes)
        return file
    }

    private fun get(path: String, params: List<Pair<String, String>>, key: String): String? {
        val url = "$BASE$path".toHttpUrl().newBuilder().apply {
            params.forEach { addQueryParameter(it.first, it.second) }
        }.build()
        return call(url, key)?.use { response ->
            if (!response.isSuccessful) {
                EnigmaLog.network("SubSource $path HTTP ${response.code}")
                return null
            }
            response.body?.string()
        }
    }

    private fun bytes(path: String, key: String): ByteArray? {
        val url = "$BASE$path".toHttpUrl()
        return call(url, key)?.use { response ->
            if (!response.isSuccessful) {
                EnigmaLog.network("SubSource $path HTTP ${response.code}")
                return null
            }
            response.body?.bytes()
        }
    }

    private fun call(url: okhttp3.HttpUrl, key: String) = runCatching {
        client.newCall(
            Request.Builder().url(url).header("X-API-Key", key).header("Accept", "application/json").build(),
        ).execute()
    }.getOrElse {
        EnigmaLog.network("SubSource failed: ${it.javaClass.simpleName}")
        null
    }

    private companion object {
        const val BASE = "https://api.subsource.net/api/v1/"
        const val PER_LANGUAGE = 8
    }
}
