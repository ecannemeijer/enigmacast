package com.enigmacast.data.tmdb

import com.enigmacast.domain.films.TmdbCastMember
import com.enigmacast.domain.films.TmdbMovie
import com.enigmacast.domain.films.TmdbSearchHit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Parses TMDB v3 answers by hand, so unknown or missing fields never break the home page. */
object TmdbJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseSearch(body: String): List<TmdbSearchHit> {
        val root = obj(body) ?: return emptyList()
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.int("id") ?: return@mapNotNull null
            TmdbSearchHit(
                id = id,
                title = item.str("title").orEmpty(),
                originalTitle = item.str("original_title").orEmpty(),
                year = yearOf(item.str("release_date")),
                voteAverage = item.double("vote_average") ?: 0.0,
                voteCount = item.int("vote_count") ?: 0,
                popularity = item.double("popularity") ?: 0.0,
                posterPath = item.str("poster_path"),
                backdropPath = item.str("backdrop_path"),
                overview = item.str("overview").orEmpty(),
                genreIds = (item["genre_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }.orEmpty(),
            )
        }
    }

    /**
     * `/movie/{id}?append_to_response=credits,release_dates,images`. [country] picks the
     * certification (falls back to US); [language] (two letters) the logo.
     */
    fun parseMovie(body: String, country: String, language: String): TmdbMovie? {
        val root = obj(body) ?: return null
        val id = root.int("id") ?: return null
        val genres = (root["genres"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val credits = root["credits"] as? JsonObject
        val cast = (credits?.get("cast") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            .sortedBy { it.int("order") ?: Int.MAX_VALUE }
            .take(15)
            .map { TmdbCastMember(it.str("name").orEmpty(), it.str("character").orEmpty(), it.str("profile_path")) }
            .filter { it.name.isNotBlank() }
        val directors = (credits?.get("crew") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            .filter { it.str("job") == "Director" }
            .mapNotNull { it.str("name") }
            .distinct()
        return TmdbMovie(
            id = id,
            title = root.str("title").orEmpty(),
            originalTitle = root.str("original_title").orEmpty(),
            year = yearOf(root.str("release_date")),
            overview = root.str("overview").orEmpty(),
            tagline = root.str("tagline").orEmpty(),
            runtimeMin = root.int("runtime")?.takeIf { it > 0 },
            voteAverage = root.double("vote_average") ?: 0.0,
            voteCount = root.int("vote_count") ?: 0,
            popularity = root.double("popularity") ?: 0.0,
            posterPath = root.str("poster_path"),
            backdropPath = root.str("backdrop_path"),
            logoPath = logo(root["images"] as? JsonObject, language),
            genres = genres.mapNotNull { it.str("name") },
            genreIds = genres.mapNotNull { it.int("id") },
            certification = certification(root["release_dates"] as? JsonObject, country),
            directors = directors,
            cast = cast,
        )
    }

    /** `/search/tv`: same hit shape as films (name → title, first_air_date → year). */
    fun parseTvSearch(body: String): List<TmdbSearchHit> {
        val root = obj(body) ?: return emptyList()
        val results = root["results"] as? JsonArray ?: return emptyList()
        return results.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.int("id") ?: return@mapNotNull null
            TmdbSearchHit(
                id = id,
                title = item.str("name").orEmpty(),
                originalTitle = item.str("original_name").orEmpty(),
                year = yearOf(item.str("first_air_date")),
                voteAverage = item.double("vote_average") ?: 0.0,
                voteCount = item.int("vote_count") ?: 0,
                popularity = item.double("popularity") ?: 0.0,
                posterPath = item.str("poster_path"),
                backdropPath = item.str("backdrop_path"),
                overview = item.str("overview").orEmpty(),
                genreIds = (item["genre_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }.orEmpty(),
            )
        }
    }

    /** `/tv/{id}?append_to_response=aggregate_credits,content_ratings,images` as a [TmdbMovie] with isSeries. */
    fun parseTv(body: String, country: String, language: String): TmdbMovie? {
        val root = obj(body) ?: return null
        val id = root.int("id") ?: return null
        val genres = (root["genres"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val cast = ((root["aggregate_credits"] as? JsonObject)?.get("cast") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            .sortedBy { it.int("order") ?: Int.MAX_VALUE }
            .take(15)
            .map { member ->
                val role = (member["roles"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.str("character") }.orEmpty()
                TmdbCastMember(member.str("name").orEmpty(), role, member.str("profile_path"))
            }
            .filter { it.name.isNotBlank() }
        val creators = (root["created_by"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("name") }.orEmpty()
        val runtime = (root["episode_run_time"] as? JsonArray)?.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
        val ratings = ((root["content_ratings"] as? JsonObject)?.get("results") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val rating = (ratings.firstOrNull { it.str("iso_3166_1") == country } ?: ratings.firstOrNull { it.str("iso_3166_1") == "US" })?.str("rating").orEmpty()
        return TmdbMovie(
            id = com.enigmacast.domain.series.SeriesIds.toRow(id),
            title = root.str("name").orEmpty(),
            originalTitle = root.str("original_name").orEmpty(),
            year = yearOf(root.str("first_air_date")),
            overview = root.str("overview").orEmpty(),
            tagline = root.str("tagline").orEmpty(),
            runtimeMin = runtime?.takeIf { it > 0 },
            voteAverage = root.double("vote_average") ?: 0.0,
            voteCount = root.int("vote_count") ?: 0,
            popularity = root.double("popularity") ?: 0.0,
            posterPath = root.str("poster_path"),
            backdropPath = root.str("backdrop_path"),
            logoPath = logo(root["images"] as? JsonObject, language),
            genres = genres.mapNotNull { it.str("name") },
            genreIds = genres.mapNotNull { it.int("id") },
            certification = rating,
            directors = creators,
            cast = cast,
            isSeries = true,
            seasons = root.int("number_of_seasons"),
            episodes = root.int("number_of_episodes"),
        )
    }

    /** Raster logos only (Coil cannot draw SVG here); the viewer's language, English, no language, then any. */
    private fun logo(images: JsonObject?, language: String): String? {
        val logos = (images?.get("logos") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            .filter { it.str("file_path")?.endsWith(".png", ignoreCase = true) == true }
        val lang = language.take(2)
        return (logos.firstOrNull { it.str("iso_639_1") == lang }
            ?: logos.firstOrNull { it.str("iso_639_1") == "en" }
            ?: logos.firstOrNull { it.str("iso_639_1").isNullOrBlank() }
            ?: logos.firstOrNull())?.str("file_path")
    }

    private fun certification(releases: JsonObject?, country: String): String {
        val results = (releases?.get("results") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        fun of(code: String): String? = results.firstOrNull { it.str("iso_3166_1") == code }
            ?.let { it["release_dates"] as? JsonArray }
            ?.mapNotNull { (it as? JsonObject)?.str("certification")?.trim() }
            ?.firstOrNull { it.isNotBlank() }
        return of(country) ?: of("US").orEmpty()
    }

    fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()?.takeIf { it in 1880..2100 }

    private fun obj(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(key: String): Int? = prim(key)?.contentOrNull?.toDoubleOrNull()?.toInt()
    private fun JsonObject.double(key: String): Double? = prim(key)?.contentOrNull?.toDoubleOrNull()

    @Suppress("unused")
    private fun JsonElement.isNull(): Boolean = this is JsonNull

    /** Image URL at a TMDB size such as w342, w780, w1280 or original. */
    /** A TMDB image path at [size]; a full URL (IPTV playlist covers) is used as it is. */
    fun image(path: String?, size: String): String? = path?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http://", true) || it.startsWith("https://", true)) it else "https://image.tmdb.org/t/p/$size$it"
    }
}
