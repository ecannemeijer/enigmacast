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
    /** TMDB vote_average (0..10), 0 when unrated. */
    val rating: Double = 0.0,
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
    private fun JsonObject.d(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    fun image(path: String?, size: String) = path?.let { "https://image.tmdb.org/t/p/$size$it" }

    fun parsePage(body: String?, kind: TorrentKind): TmdbPage {
        val root = obj(body) ?: return TmdbPage(emptyList(), 1, 1)
        val items = (root["results"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.i("id") ?: return@mapNotNull null
            val title = (if (kind == TorrentKind.MOVIE) o.s("title") else o.s("name")) ?: return@mapNotNull null
            val date = if (kind == TorrentKind.MOVIE) o.s("release_date") else o.s("first_air_date")
            TmdbTitle(id, kind, title, date?.take(4)?.toIntOrNull(), image(o.s("poster_path"), "w342"), image(o.s("backdrop_path"), "w1280"), o.s("overview").orEmpty(), o.d("vote_average")?.takeIf { it > 0 } ?: 0.0)
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

    /**
     * The series' episode Play starts: the [chosen] season when it has episodes, else the first
     * season; its first episode. Null without seasons.
     */
    fun playTarget(seasons: List<TmdbSeason>, chosen: Int?, episodes: List<TmdbEpisode>): Pair<Int, Int>? {
        val season = seasons.firstOrNull { it.number == chosen } ?: seasons.firstOrNull() ?: return null
        val episode = episodes.takeIf { season.number == chosen }?.firstOrNull()?.number ?: 1
        return season.number to episode
    }

    /** Next page to load when the focused row nears the end; null when all are loaded or one is loading. */
    fun nextPage(loaded: Int, total: Int, focusedIndex: Int, itemCount: Int, loading: Boolean, threshold: Int = 12): Int? =
        if (loading || loaded >= total || focusedIndex < itemCount - threshold) null else loaded + 1
}
