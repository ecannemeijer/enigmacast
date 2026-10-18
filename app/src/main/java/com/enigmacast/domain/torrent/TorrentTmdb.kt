package com.enigmacast.domain.torrent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

enum class TorrentKind { MOVIE, TV }

data class TmdbTitle(
    val id: Int,
    val kind: TorrentKind,
    val title: String,
    val year: Int?,
    val poster: String?,
    val backdrop: String?,
    val overview: String,
) {
    val key get() = "${kind.name}:$id"
}

data class TmdbPage(val items: List<TmdbTitle>, val page: Int, val totalPages: Int)
data class TmdbSeason(val number: Int, val name: String, val episodes: Int)
data class TmdbEpisode(val number: Int, val name: String, val still: String?, val overview: String)

/** TMDB lists for the Torrent page: popular / search pages, IMDb id, seasons and episodes. */
object TorrentTmdb {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun obj(body: String?) = body?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.intOrNull

    fun image(path: String?, size: String) = path?.let { "https://image.tmdb.org/t/p/$size$it" }

    fun parsePage(body: String?, kind: TorrentKind): TmdbPage {
        val root = obj(body) ?: return TmdbPage(emptyList(), 1, 1)
        val items = (root["results"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.i("id") ?: return@mapNotNull null
            val title = (if (kind == TorrentKind.MOVIE) o.s("title") else o.s("name")) ?: return@mapNotNull null
            val date = if (kind == TorrentKind.MOVIE) o.s("release_date") else o.s("first_air_date")
            TmdbTitle(id, kind, title, date?.take(4)?.toIntOrNull(), image(o.s("poster_path"), "w342"), image(o.s("backdrop_path"), "w1280"), o.s("overview").orEmpty())
        }
        return TmdbPage(items, root.i("page") ?: 1, (root.i("total_pages") ?: 1).coerceAtMost(500))
    }

    fun parseImdb(body: String?): String? = obj(body)?.s("imdb_id")?.takeIf { it.startsWith("tt") }

    fun parseSeasons(tvBody: String?): List<TmdbSeason> =
        (obj(tvBody)?.get("seasons") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val n = o.i("season_number") ?: return@mapNotNull null
            val count = o.i("episode_count") ?: 0
            if (n <= 0 || count <= 0) null else TmdbSeason(n, o.s("name") ?: "S$n", count)
        }.sortedBy { it.number }

    fun parseEpisodes(seasonBody: String?): List<TmdbEpisode> =
        (obj(seasonBody)?.get("episodes") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val n = o.i("episode_number") ?: return@mapNotNull null
            TmdbEpisode(n, o.s("name") ?: "E$n", image(o.s("still_path"), "w300"), o.s("overview").orEmpty())
        }.sortedBy { it.number }

    /** Next page to load when the focused row nears the end; null when all are loaded or one is loading. */
    fun nextPage(loaded: Int, total: Int, focusedIndex: Int, itemCount: Int, loading: Boolean, threshold: Int = 12): Int? =
        if (loading || loaded >= total || focusedIndex < itemCount - threshold) null else loaded + 1
}
