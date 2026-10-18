package com.enigmacast.domain.subsource

import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** One language SubSource can search. [slug] is the `language` query value. */
data class SubSourceLanguage(val slug: String, val iso: String, val label: String)

/** A movie or series hit from `movies/search`. */
data class SubSourceTitle(val movieId: Long, val title: String, val year: Int?, val alternateTitle: String? = null)

/** One subtitle file offered for a title. [id] is what `subtitles/{id}/download` expects. */
data class SubSourceHit(
    val id: Long,
    val language: String,
    val releaseInfo: List<String> = emptyList(),
    val hearingImpaired: Boolean = false,
)

/** What to ask SubSource for a film or one episode. */
data class SubSourceQuery(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val languages: List<String> = emptyList(),
)

/** Bytes of one subtitle file taken out of a download. */
data class SubSourceFile(val extension: String, val bytes: ByteArray)

object SubSourceLanguages {
    private val app = listOf(
        SubSourceLanguage("dutch", "nl", "Dutch"),
        SubSourceLanguage("english", "en", "English"),
        SubSourceLanguage("german", "de", "German"),
        SubSourceLanguage("french", "fr", "French"),
        SubSourceLanguage("italian", "it", "Italian"),
    )
    private val rest = listOf(
        SubSourceLanguage("arabic", "ar", "Arabic"),
        SubSourceLanguage("brazilian_portuguese", "pt-BR", "Brazilian Portuguese"),
        SubSourceLanguage("bulgarian", "bg", "Bulgarian"),
        SubSourceLanguage("chinese_bg_code", "zh", "Chinese"),
        SubSourceLanguage("croatian", "hr", "Croatian"),
        SubSourceLanguage("czech", "cs", "Czech"),
        SubSourceLanguage("danish", "da", "Danish"),
        SubSourceLanguage("farsi_persian", "fa", "Persian"),
        SubSourceLanguage("finnish", "fi", "Finnish"),
        SubSourceLanguage("greek", "el", "Greek"),
        SubSourceLanguage("hebrew", "he", "Hebrew"),
        SubSourceLanguage("hindi", "hi", "Hindi"),
        SubSourceLanguage("hungarian", "hu", "Hungarian"),
        SubSourceLanguage("indonesian", "id", "Indonesian"),
        SubSourceLanguage("japanese", "ja", "Japanese"),
        SubSourceLanguage("korean", "ko", "Korean"),
        SubSourceLanguage("norwegian", "no", "Norwegian"),
        SubSourceLanguage("polish", "pl", "Polish"),
        SubSourceLanguage("portuguese", "pt", "Portuguese"),
        SubSourceLanguage("romanian", "ro", "Romanian"),
        SubSourceLanguage("russian", "ru", "Russian"),
        SubSourceLanguage("serbian", "sr", "Serbian"),
        SubSourceLanguage("slovak", "sk", "Slovak"),
        SubSourceLanguage("slovenian", "sl", "Slovenian"),
        SubSourceLanguage("spanish", "es", "Spanish"),
        SubSourceLanguage("swedish", "sv", "Swedish"),
        SubSourceLanguage("thai", "th", "Thai"),
        SubSourceLanguage("turkish", "tr", "Turkish"),
        SubSourceLanguage("ukrainian", "uk", "Ukrainian"),
        SubSourceLanguage("vietnamese", "vi", "Vietnamese"),
    )

    /** App languages first, then the rest by name. */
    val ordered: List<SubSourceLanguage> = app + rest.sortedBy { it.label }

    fun bySlug(slug: String): SubSourceLanguage? = ordered.firstOrNull { it.slug == slug }

    /** Slug for the app language, when SubSource has that language. */
    fun forAppTag(tag: String?): String? = when (tag?.substringBefore('-')?.substringBefore('_')?.lowercase()) {
        "nl" -> "dutch"
        "en" -> "english"
        "de" -> "german"
        "fr" -> "french"
        "it" -> "italian"
        else -> null
    }

    /** Name in [locale], with the country when the slug is a regional variant. */
    fun display(language: SubSourceLanguage, locale: Locale = Locale.getDefault()): String {
        val loc = Locale.forLanguageTag(language.iso)
        val name = loc.getDisplayLanguage(locale).replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(locale) else char.toString()
        }
        if (name.isBlank()) return language.label
        val country = loc.country.takeIf { it.isNotEmpty() }?.let { loc.getDisplayCountry(locale) }.orEmpty()
        return if (country.isBlank()) name else "$name ($country)"
    }
}

object SubSourceMatch {
    private val yearInName = Regex("""(?:\(|\[|\b)((?:19|20)\d{2})(?:\)|\]|\b)""")
    private val noise = Regex(
        """\b(2160p|1080p|720p|480p|4k|uhd|hdr|hd|bluray|blu-ray|webrip|web-dl|webdl|hdtv|hdrip|x264|x265|h264|h265|hevc|aac|dts|ac3|multi|subs?|nl|ned|eng|dual)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val seasonEpisode = Regex("""(?i)\bS(\d{1,2})[ ._-]*E(\d{1,3})\b""")
    private val seasonXEpisode = Regex("""(?i)\b(\d{1,2})x(\d{2,3})\b""")
    private val seasonOnly = Regex("""(?i)\bS(\d{1,2})\b""")

    /** "The Matrix (1999) 1080p" → title and year. */
    fun cleanName(raw: String): Pair<String, Int?> {
        val year = yearInName.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val title = raw
            .replace(noise, " ")
            .replace(Regex("""[\[\](){}]"""), " ")
            .replace(Regex("""\b(?:19|20)\d{2}\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '|', '.', '_', ':')
        return title to year
    }

    fun normalize(text: String): String {
        val plain = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    /** First title whose name contains the query. A known year wins over a title-only hit. */
    fun pickTitle(results: List<SubSourceTitle>, query: String, year: Int?): SubSourceTitle? {
        val wanted = normalize(query)
        if (wanted.length < 2) return null
        val matches = results.filter { title ->
            listOfNotNull(title.title, title.alternateTitle).map(::normalize).any { name ->
                name == wanted || name.contains(wanted) || (wanted.contains(name) && name.length >= 3)
            }
        }
        if (matches.isEmpty()) return null
        if (year != null) return matches.firstOrNull { it.year == year } ?: matches.firstOrNull { it.year == null }
        return matches.first()
    }

    /**
     * Keeps subtitles for [season] and [episode]. A season pack (season, no episode number)
     * stays. Releases without any season marker stay, because the request already asked
     * the API for that episode.
     */
    fun forEpisode(hits: List<SubSourceHit>, season: Int, episode: Int): List<SubSourceHit> =
        hits.filter { matchesEpisode(it.releaseInfo, season, episode) }

    fun matchesEpisode(releases: List<String>, season: Int, episode: Int): Boolean {
        if (releases.isEmpty()) return true
        var sawMarker = false
        var seasonPack = false
        for (info in releases) {
            val se = seasonEpisode.find(info) ?: seasonXEpisode.find(info)
            if (se != null) {
                sawMarker = true
                if (se.groupValues[1].toInt() == season && se.groupValues[2].toInt() == episode) return true
                continue
            }
            val only = seasonOnly.find(info)
            if (only != null && only.groupValues[1].toInt() == season) {
                sawMarker = true
                seasonPack = true
            }
        }
        return seasonPack || !sawMarker
    }

    /**
     * Within each language (kept in [hits]' order), the subtitles whose release names share the
     * most words with the video's file name first ("…1080p.WEB-DL.x264-GROUP" → same release,
     * so the timing fits). Without a file name the order stays as it is.
     */
    fun byRelease(hits: List<SubSourceHit>, fileName: String?): List<SubSourceHit> {
        val words = releaseWords(fileName ?: return hits)
        if (words.isEmpty()) return hits
        val languageOrder = hits.map { it.language }.distinct()
        return hits.withIndex().sortedWith(
            compareBy<IndexedValue<SubSourceHit>> { languageOrder.indexOf(it.value.language) }
                .thenByDescending { hit -> hit.value.releaseInfo.maxOfOrNull { (releaseWords(it) intersect words).size } ?: 0 }
                .thenBy { it.index },
        ).map { it.value }
    }

    private val videoExtension = Regex("""(?i)\.(mkv|mp4|avi|m4v|mov|ts|webm|wmv|mpg|mpeg)$""")

    private fun releaseWords(name: String): Set<String> =
        normalize(name.replace(videoExtension, "")).split(' ').filter { it.length >= 2 }.toSet()

    /** "Dutch · WEB-DL" for the options list. */
    fun label(hit: SubSourceHit): String {
        val language = SubSourceLanguages.bySlug(hit.language)?.label ?: hit.language.replace('_', ' ')
        val release = hit.releaseInfo.firstOrNull().orEmpty()
        val hearing = if (hit.hearingImpaired) "HI" else ""
        return listOf(language, release, hearing).filter { it.isNotBlank() }.joinToString(" · ")
    }
}

object SubSourceJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun titles(body: String): List<SubSourceTitle> {
        val data = array(body) ?: return emptyList()
        return data.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = long(o, "movieId") ?: return@mapNotNull null
            val title = str(o, "title") ?: return@mapNotNull null
            SubSourceTitle(id, title, int(o, "releaseYear"), str(o, "alternateTitle"))
        }
    }

    fun subtitles(body: String): List<SubSourceHit> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        if ((root["success"] as? JsonPrimitive)?.booleanOrNull == false) return emptyList()
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = long(o, "subtitleId") ?: return@mapNotNull null
            val language = str(o, "language")?.lowercase(Locale.ROOT) ?: return@mapNotNull null
            val impaired = when (val flag = o["hearingImpaired"]) {
                is JsonPrimitive -> flag.booleanOrNull == true || flag.intOrNull == 1 || flag.contentOrNull == "1"
                else -> false
            }
            SubSourceHit(id, language, releases(o), impaired)
        }
    }

    private fun array(body: String): JsonArray? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        return root["data"] as? JsonArray
    }

    private fun releases(o: JsonObject): List<String> = when (val value = o["releaseInfo"]) {
        is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { text -> text.isNotBlank() } }
        is JsonPrimitive -> listOfNotNull(value.contentOrNull?.takeIf { it.isNotBlank() })
        else -> emptyList()
    }

    private fun str(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun int(o: JsonObject, key: String): Int? =
        (o[key] as? JsonPrimitive)?.intOrNull ?: str(o, key)?.toIntOrNull()

    private fun long(o: JsonObject, key: String): Long? {
        val value = o[key] as? JsonPrimitive ?: return null
        return value.longOrNull ?: value.contentOrNull?.toLongOrNull()
    }
}

object SubSourceArchive {
    private val subtitleExtensions = listOf("srt", "vtt", "ass", "ssa")

    /** The first subtitle inside a zip, or the bytes themselves when they are already a subtitle. */
    fun extract(bytes: ByteArray): SubSourceFile? {
        if (bytes.size < 4) return null
        if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()) {
            return SubSourceFile("srt", bytes)
        }
        var best: SubSourceFile? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (extension in subtitleExtensions) {
                        val file = SubSourceFile(extension, zip.readBytes())
                        if (best == null || rank(extension) < rank(best.extension)) best = file
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return best
    }

    private fun rank(extension: String): Int = when (extension) {
        "srt" -> 0
        "vtt" -> 1
        else -> 2
    }
}
