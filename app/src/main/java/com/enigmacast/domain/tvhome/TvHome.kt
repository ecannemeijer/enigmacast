package com.enigmacast.domain.tvhome

import com.enigmacast.data.db.IptvLibraryRow
import com.enigmacast.data.db.IptvResumeRow
import com.enigmacast.domain.model.AppSettings
import com.enigmacast.domain.model.LastChannel
import com.enigmacast.domain.resume.ResumePoint
import com.enigmacast.domain.resume.ResumePolicy
import com.enigmacast.domain.xtream.IptvLibraryRows
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Android TV home-screen preview channels ("EnigmaCast – Favorieten", "– IPTV-favorieten",
 * "– Verder kijken" and "– Opnames"):
 * which cards they hold and the deep links behind them. Pure; the publisher writes them to the
 * TV provider. Links carry only ids (never stream addresses or logins), because the TV provider
 * is readable by the launcher.
 */
object TvHome {
    const val FAVORITES_KEY = "enigmacast.favorites"
    const val RECORDINGS_KEY = "enigmacast.recordings"
    const val IPTV_FAVORITES_KEY = "enigmacast.iptvfavorites"
    const val CONTINUE_KEY = "enigmacast.continue"
    const val MAX_CHANNELS = 12
    const val MAX_RECORDINGS = 12
    const val MAX_LIBRARY = 12

    /**
     * The rows, in the order they are created (Android TV adds a new row below the others):
     * live first (what you zap to most), then what you saved, then what you were watching, then
     * the receiver's recordings. [FAVORITES] is the default channel (shown without asking).
     */
    enum class Row(val key: String) {
        FAVORITES(FAVORITES_KEY),
        IPTV_FAVORITES(IPTV_FAVORITES_KEY),
        CONTINUE(CONTINUE_KEY),
        RECORDINGS(RECORDINGS_KEY),
    }

    val ALL_KEYS: List<String> = Row.entries.map { it.key }

    /** Rows to publish: none when the setting is off or in hotel mode, else the switched-on ones. */
    fun rows(settings: AppSettings, hotel: Boolean): List<Row> {
        if (!settings.tvHomeChannels || hotel) return emptyList()
        return Row.entries.filter { row ->
            when (row) {
                Row.FAVORITES -> settings.tvHomeFavorites
                Row.IPTV_FAVORITES -> settings.tvHomeIptvFavorites
                Row.CONTINUE -> settings.tvHomeContinue
                Row.RECORDINGS -> settings.tvHomeRecordings
            }
        }
    }

    private const val SCHEME = "enigmacast"
    private const val HOST = "tvhome"

    sealed interface Link {
        /** A satellite channel by service reference. */
        data class Sat(val reference: String, val name: String) : Link
        /** An IPTV live channel: playlist account key, stream id, and what is needed to show it before the list loads. */
        data class Iptv(val source: String, val id: String, val name: String, val category: String) : Link
        /** A recording on the receiver by its file path; [resume] plays from the saved place. */
        data class Recording(val file: String, val resume: Boolean = false) : Link
        /** An IPTV favorite: a film ("m") plays, a series ("s") opens its page. */
        data class Library(val source: String, val kind: String, val id: String) : Link
        /** An IPTV film ("m") or episode ("e") in Verder kijken: plays from the saved place. */
        data class Resume(val source: String, val kind: String, val id: String) : Link
        /** The channel row itself: just open the app. */
        data object Open : Link
    }

    /**
     * One card in the Favorieten row. [logo] is the IPTV logo address the app downloads itself
     * (it may carry a login, so it never goes to the TV provider as is).
     */
    data class LiveCard(val link: Link, val title: String, val logo: String)

    fun uri(link: Link): String = when (link) {
        is Link.Sat -> build("sat", "ref" to link.reference, "name" to link.name)
        is Link.Iptv -> build("iptv", "source" to link.source, "id" to link.id, "name" to link.name, "cat" to link.category)
        is Link.Recording -> if (link.resume) build("rec", "file" to link.file, "resume" to "1") else build("rec", "file" to link.file)
        is Link.Library -> build("lib", "source" to link.source, "kind" to link.kind, "id" to link.id)
        is Link.Resume -> build("cont", "source" to link.source, "kind" to link.kind, "id" to link.id)
        Link.Open -> "$SCHEME://$HOST/open"
    }

    /** The link in [uri], or null when it is not one of ours (or misses its id). */
    fun parse(uri: String?): Link? {
        val prefix = "$SCHEME://$HOST/"
        if (uri == null || !uri.startsWith(prefix, ignoreCase = true)) return null
        val rest = uri.substring(prefix.length)
        val path = rest.substringBefore('?')
        val query = rest.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { part ->
            decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
        }
        return when (path) {
            "sat" -> query["ref"]?.takeIf { it.isNotBlank() }?.let { Link.Sat(it, query["name"].orEmpty()) }
            "iptv" -> {
                val source = query["source"]?.takeIf { it.isNotBlank() } ?: return null
                val id = query["id"]?.takeIf { it.isNotBlank() } ?: return null
                Link.Iptv(source, id, query["name"].orEmpty(), query["cat"].orEmpty())
            }
            "rec" -> query["file"]?.takeIf { it.isNotBlank() }?.let { Link.Recording(it, query["resume"] == "1") }
            "lib", "cont" -> {
                val source = query["source"]?.takeIf { it.isNotBlank() } ?: return null
                val kind = query["kind"]?.takeIf { it.isNotBlank() } ?: return null
                val id = query["id"]?.takeIf { it.isNotBlank() } ?: return null
                if (path == "lib") Link.Library(source, kind, id) else Link.Resume(source, kind, id)
            }
            "open" -> Link.Open
            else -> null
        }
    }

    /**
     * The Favorieten row: recent satellite and IPTV live channels, interleaved with the source that
     * was watched last first ([iptvFirst]), without duplicates, at most [max].
     */
    fun liveCards(
        sat: List<LastChannel>,
        iptv: List<Pair<String, LastChannel>>,
        iptvFirst: Boolean,
        max: Int = MAX_CHANNELS,
    ): List<LiveCard> {
        val satCards = sat.filter { it.reference.isNotBlank() }.distinctBy { it.reference }.map {
            LiveCard(Link.Sat(it.reference, it.name), it.name, "")
        }
        val iptvCards = iptv.filter { it.second.reference.isNotBlank() }.distinctBy { it.first + "/" + it.second.reference }.map { (source, ch) ->
            LiveCard(Link.Iptv(source, ch.reference, ch.name, ch.bouquetReference), ch.name, TvHomeArt.downloadableLogo(ch.logo).orEmpty())
        }
        val (first, second) = if (iptvFirst) iptvCards to satCards else satCards to iptvCards
        val out = ArrayList<LiveCard>()
        for (i in 0 until maxOf(first.size, second.size)) {
            first.getOrNull(i)?.let(out::add)
            second.getOrNull(i)?.let(out::add)
        }
        return out.take(max)
    }

    /** One card in IPTV-favorieten or Verder kijken. [poster] may need a download (see [publicLogo]). */
    data class LibraryCard(
        val link: Link,
        val title: String,
        val subtitle: String,
        val poster: String?,
        /** A movie poster (2:3) rather than a wide picture. */
        val tall: Boolean,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val updatedMs: Long = 0L,
        /** Receiver channel name of a recording (its picon is the fallback picture). */
        val channel: String = "",
    )

    /** A receiver recording with a saved place, for Verder kijken. */
    data class RecordingResume(
        val file: String,
        val title: String,
        val channel: String,
        val positionMs: Long,
        val durationMs: Long,
        val updatedMs: Long,
    )

    /** IPTV-favorieten: the viewer's starred films and series of playlists in use, newest first. */
    fun iptvFavorites(rows: List<IptvLibraryRow>, sources: Set<String>, max: Int = MAX_LIBRARY): List<LibraryCard> =
        IptvLibraryRows.favorites(rows, emptyMap(), sources)
            .filter { it.kind == "m" || it.kind == "s" }
            .take(max)
            .map { card ->
                LibraryCard(Link.Library(card.source, card.kind, card.id), card.title, card.subtitle, card.poster?.takeIf { it.isNotBlank() }, tall = true)
            }

    /**
     * Verder kijken: IPTV films and episodes (one per series, as in Library) and receiver
     * recordings with a place worth resuming, newest first.
     */
    fun continueCards(
        iptv: List<IptvResumeRow>,
        sources: Set<String>,
        recordings: List<RecordingResume>,
        max: Int = MAX_LIBRARY,
    ): List<LibraryCard> {
        val byKey = iptv.associateBy { "${it.source}/${it.kind}/${it.id}" }
        val iptvCards = IptvLibraryRows.continueWatching(iptv, sources).mapNotNull { card ->
            val row = byKey["${card.source}/${card.kind}/${card.id}"] ?: return@mapNotNull null
            LibraryCard(
                Link.Resume(card.source, card.kind, card.id), card.title, card.subtitle,
                card.poster?.takeIf { it.isNotBlank() }, tall = true,
                positionMs = row.positionMs, durationMs = row.durationMs, updatedMs = row.updatedMs,
            )
        }
        val recordingCards = recordings
            .filter { it.file.isNotBlank() && ResumePolicy.offer(ResumePoint(it.file, it.positionMs, it.durationMs)) != null }
            .distinctBy { it.file }
            .map {
                LibraryCard(
                    Link.Recording(it.file, resume = true), it.title, ResumePolicy.clock(it.positionMs), null, tall = false,
                    positionMs = it.positionMs, durationMs = it.durationMs, updatedMs = it.updatedMs, channel = it.channel,
                )
            }
        return (iptvCards + recordingCards).sortedByDescending { it.updatedMs }.take(max)
    }

    /**
     * A logo address the launcher may load: http(s) without a login or query (IPTV panels sometimes
     * put the account in logo links). Anything else is left out.
     */
    fun publicLogo(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) return null
        val authority = value.substringAfter("://").substringBefore('/')
        if ('@' in authority || '?' in value || ' ' in value) return null
        return value
    }

    private fun build(path: String, vararg params: Pair<String, String>): String =
        "$SCHEME://$HOST/$path?" + params.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun decode(value: String) = runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)
}
