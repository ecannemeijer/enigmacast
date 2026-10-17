package com.enigmacast.domain.xtream

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One Xtream Codes account, read at runtime from the receiver's XStreamity playlists.
 * [toString] hides the login: accounts end up in logs and crash reports otherwise.
 */
data class XtreamAccount(
    val name: String,
    /** scheme://host[:port], no trailing slash. */
    val server: String,
    val username: String,
    val password: String,
    /** "ts" or "m3u8" from the playlist's `output=`; live streams use it as extension. */
    val output: String = "ts",
) {
    val key: String get() = "$server|$username".hashCode().toString(16)
    val host: String get() = runCatching { URI(server).host }.getOrNull().orEmpty()
    override fun toString(): String = "XtreamAccount($name @ $host)"
}

enum class XtreamSection { LIVE, MOVIES, SERIES }

data class XtreamCategory(val id: String, val name: String)

/** A live channel, a film or a series in a category. [extension] is the container of a film. */
data class XtreamItem(
    val id: String,
    val name: String,
    val section: XtreamSection,
    val categoryId: String,
    val icon: String = "",
    val extension: String = "",
    val number: Int = 0,
    /** Direct stream address (M3U playlists); empty for Xtream items, whose address is built. */
    val url: String = "",
    /** Days of server archive (Xtream `tv_archive_duration`, or M3U `catchup-days`). 0 means live only. */
    val archiveDays: Int = 0,
    /** M3U `catchup-source` template; empty when the archive uses the Xtream timeshift path. */
    val catchupSource: String = "",
) {
    val hasArchive: Boolean get() = archiveDays > 0 || catchupSource.isNotBlank()
}

data class XtreamEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val extension: String,
    val cover: String = "",
)

data class XtreamVodInfo(
    val plot: String,
    val rating: Double,
    val year: Int?,
    val genre: String,
    val cover: String?,
    val backdrop: String?,
    val runtimeMin: Int?,
    val director: String,
    val tmdbId: Int?,
)

data class XtreamEpg(val title: String, val startSec: Long, val endSec: Long, val description: String = "")

/** The listing on air at [nowSec], when one of these covers that moment. */
fun List<XtreamEpg>.airingAt(nowSec: Long): XtreamEpg? =
    firstOrNull { it.startSec <= nowSec && nowSec < it.endSec }

/**
 * The channel row and the programme column share one list. Xtream listings that have
 * already ended lose to the satellite guide when that one has the programme on now.
 */
fun chooseGuide(xtream: List<XtreamEpg>, satellite: List<XtreamEpg>, nowSec: Long): List<XtreamEpg> {
    if (xtream.airingAt(nowSec) != null) return xtream
    if (satellite.airingAt(nowSec) != null) return satellite
    return xtream.ifEmpty { satellite }
}

/** XStreamity's `playlists.txt` and `x-playlists.json`. */
private val NAME_MARK = Regex("\\s#")

object XStreamityPlaylists {
    const val DIR = "/etc/enigma2/xstreamity"
    const val PLAYLISTS_TXT = "$DIR/playlists.txt"
    const val PLAYLISTS_JSON = "$DIR/x-playlists.json"
    const val PLUGIN_DIR = "/usr/lib/enigma2/python/Plugins/Extensions/XStreamity"

    /**
     * One URL per line (`http://host:port/get.php?username=U&password=P&type=m3u_plus&output=ts`).
     * A line that starts with `#` is switched off in XStreamity and skipped; text after ` #` on a
     * line is the playlist's name. Lines without username and password are not Xtream accounts.
     */
    fun parseTxt(text: String): List<XtreamAccount> = text.lineSequence()
        .map { it.trim().removePrefix("\uFEFF") }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val cut = NAME_MARK.find(line)?.range?.first
            val url = (if (cut != null) line.substring(0, cut) else line).trim()
            val name = if (cut != null) line.substring(cut + 2).trim() else ""
            fromUrl(url, name)
        }
        .distinctBy { it.server to it.username }
        .toList()

    fun fromUrl(url: String, name: String = ""): XtreamAccount? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val params = query(uri.rawQuery.orEmpty())
        val user = params["username"]?.takeIf { it.isNotBlank() } ?: return null
        val pass = params["password"] ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val output = params["output"]?.lowercase()?.takeIf { it == "ts" || it == "m3u8" || it == "hls" }?.let { if (it == "hls") "m3u8" else it } ?: "ts"
        return XtreamAccount(name.ifBlank { host }, "$scheme://$host$port", user, pass, output)
    }

    /** XStreamity's own JSON: a list of playlists, each with `playlist_info` (host/url, username, password, name). */
    fun parseJson(text: String): List<XtreamAccount> {
        val root = runCatching { lenient.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<XtreamAccount>()
        fun visit(e: JsonElement) {
            when (e) {
                is JsonArray -> e.forEach(::visit)
                is JsonObject -> {
                    val info = (e["playlist_info"] as? JsonObject) ?: e
                    val full = info.str("full_url")
                    val user = info.str("username")
                    val pass = info.str("password")
                    val name = info.str("name")
                    val account = full?.let { fromUrl(it, name.orEmpty()) }
                        ?: run {
                            val host = info.str("host") ?: info.str("url") ?: info.str("domain")
                            if (host != null && user != null && pass != null) {
                                val base = if (host.contains("://")) host else "http://$host"
                                val withPort = info.str("port")?.takeIf { it.isNotBlank() && !base.substringAfter("://").contains(':') }?.let { "$base:$it" } ?: base
                                fromUrl("${withPort.trimEnd('/')}/get.php?username=${enc(user)}&password=${enc(pass)}&output=${info.str("output") ?: "ts"}", name.orEmpty())
                            } else {
                                null
                            }
                        }
                    if (account != null) out += account else e.values.forEach(::visit)
                }
                else -> Unit
            }
        }
        visit(root)
        return out.distinctBy { it.server to it.username }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun query(raw: String): Map<String, String> = raw.split('&').mapNotNull { part ->
        val k = part.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        URLDecoder.decode(k, "UTF-8").lowercase() to URLDecoder.decode(part.substringAfter('='), "UTF-8")
    }.toMap()
}

/** Stream and API addresses of an account. */
object XtreamUrls {
    fun api(account: XtreamAccount, action: String?, vararg params: Pair<String, String>): String {
        val sb = StringBuilder("${account.server}/player_api.php?username=${enc(account.username)}&password=${enc(account.password)}")
        if (action != null) sb.append("&action=").append(action)
        params.forEach { (k, v) -> sb.append('&').append(k).append('=').append(enc(v)) }
        return sb.toString()
    }

    fun live(account: XtreamAccount, id: String): String =
        "${account.server}/live/${path(account.username)}/${path(account.password)}/$id.${if (account.output == "m3u8") "m3u8" else "ts"}"

    fun movie(account: XtreamAccount, id: String, extension: String): String =
        "${account.server}/movie/${path(account.username)}/${path(account.password)}/$id.${extension.ifBlank { "mp4" }}"

    fun episode(account: XtreamAccount, id: String, extension: String): String =
        "${account.server}/series/${path(account.username)}/${path(account.password)}/$id.${extension.ifBlank { "mp4" }}"

    /** A URL with the login replaced, for logs. */
    fun redact(url: String, account: XtreamAccount): String =
        url.replace(enc(account.password), "***").replace(path(account.password), "***").replace(account.password, "***")
            .replace(path(account.username), "user").replace(enc(account.username), "user").replace(account.username, "user")

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun path(s: String) = enc(s).replace("+", "%20")
}

internal val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }

/** `tv_archive` 1 (or true) plus `tv_archive_duration` in days. A duration alone also counts. */
private fun archiveDays(o: JsonObject): Int {
    val flag = o.str("tv_archive")?.lowercase()
    val days = o.str("tv_archive_duration")?.toIntOrNull() ?: 0
    val on = flag == "1" || flag == "true"
    return when {
        on -> days.coerceAtLeast(1)
        days > 0 && flag != "0" && flag != "false" -> days
        else -> 0
    }
}

/** Xtream answers mix numbers and strings freely; everything is read as text. */
object XtreamJson {
    fun categories(body: String): List<XtreamCategory> = array(body).mapNotNull { o ->
        val id = o.str("category_id") ?: return@mapNotNull null
        XtreamCategory(id, o.str("category_name") ?: id)
    }

    fun items(body: String, section: XtreamSection): List<XtreamItem> = array(body).mapNotNull { item(it, section) }

    fun category(o: JsonObject): XtreamCategory? = o.str("category_id")?.let { XtreamCategory(it, o.str("category_name") ?: it) }

    fun item(o: JsonObject, section: XtreamSection): XtreamItem? {
        val id = (if (section == XtreamSection.SERIES) o.str("series_id") else o.str("stream_id")) ?: return null
        return XtreamItem(
            id = id,
            name = o.str("name") ?: o.str("title") ?: id,
            section = section,
            categoryId = o.str("category_id").orEmpty(),
            icon = o.str("stream_icon") ?: o.str("cover") ?: "",
            extension = o.str("container_extension").orEmpty(),
            number = o.str("num")?.toIntOrNull() ?: 0,
            archiveDays = archiveDays(o),
        )
    }

    fun episodes(body: String): List<XtreamEpisode> {
        val root = runCatching { lenient.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        val seasons = root["episodes"] ?: return emptyList()
        val lists: List<JsonElement> = when (seasons) {
            is JsonObject -> seasons.values.toList()
            is JsonArray -> seasons.toList()
            else -> emptyList()
        }
        return lists.flatMap { (it as? JsonArray).orEmpty() }.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val info = o["info"] as? JsonObject
            val cover = listOf(info?.str("movie_image"), info?.str("cover"), o.str("movie_image"), o.str("cover"))
                .firstOrNull { !it.isNullOrBlank() && it.startsWith("http", true) }
                .orEmpty()
            XtreamEpisode(id, o.str("season")?.toIntOrNull() ?: 0, o.str("episode_num")?.toIntOrNull() ?: 0, o.str("title") ?: id, o.str("container_extension").orEmpty(), cover)
        }.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /**
     * The `info` block of `get_vod_info` / `get_series_info`: the playlist's own artwork and
     * text, used when TMDB has no match (or no key).
     */
    fun vodInfo(body: String): XtreamVodInfo? {
        val root = runCatching { lenient.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val info = root["info"] as? JsonObject ?: return null
        val backdrop = when (val b = info["backdrop_path"]) {
            is JsonArray -> b.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            is kotlinx.serialization.json.JsonPrimitive -> b.content
            else -> null
        }?.takeIf { it.startsWith("http", true) }
        val date = info.str("releasedate") ?: info.str("releaseDate") ?: info.str("release_date")
        return XtreamVodInfo(
            plot = (info.str("plot") ?: info.str("description")).orEmpty().trim(),
            rating = info.str("rating")?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 } ?: 0.0,
            year = date?.take(4)?.toIntOrNull(),
            genre = info.str("genre").orEmpty(),
            cover = (info.str("cover_big") ?: info.str("movie_image") ?: info.str("cover"))?.takeIf { it.startsWith("http", true) },
            backdrop = backdrop,
            runtimeMin = info.str("duration_secs")?.toIntOrNull()?.div(60)?.takeIf { it > 0 } ?: info.str("episode_run_time")?.toIntOrNull(),
            director = info.str("director").orEmpty(),
            tmdbId = info.str("tmdb_id")?.toIntOrNull() ?: info.str("tmdb")?.toIntOrNull(),
        )
    }

    /** `get_short_epg`: titles are base64. */
    fun shortEpg(body: String): List<XtreamEpg> {
        val root = runCatching { lenient.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        val list = root["epg_listings"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val start = o.str("start_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val end = o.str("stop_timestamp")?.toLongOrNull() ?: o.str("end_timestamp")?.toLongOrNull() ?: start
            XtreamEpg(b64(o.str("title").orEmpty()), start, end, b64(o.str("description").orEmpty()))
        }.sortedBy { it.startSec }
    }

    private fun b64(s: String): String = runCatching { String(Base64.getDecoder().decode(s.trim()), Charsets.UTF_8) }.getOrDefault(s)

    private fun array(body: String): List<JsonObject> =
        (runCatching { lenient.parseToJsonElement(body) }.getOrNull() as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
}
