package com.enigmacast.domain.tvhome

import com.enigmacast.domain.model.LastChannel
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Android TV home-screen preview channels ("EnigmaCast – Favorieten" and "EnigmaCast – Opnames"):
 * which cards they hold and the deep links behind them. Pure; the publisher writes them to the
 * TV provider. Links carry only ids (never stream addresses or logins), because the TV provider
 * is readable by the launcher.
 */
object TvHome {
    const val FAVORITES_KEY = "enigmacast.favorites"
    const val RECORDINGS_KEY = "enigmacast.recordings"
    const val MAX_CHANNELS = 12
    const val MAX_RECORDINGS = 12

    private const val SCHEME = "enigmacast"
    private const val HOST = "tvhome"

    sealed interface Link {
        /** A satellite channel by service reference. */
        data class Sat(val reference: String, val name: String) : Link
        /** An IPTV live channel: playlist account key, stream id, and what is needed to show it before the list loads. */
        data class Iptv(val source: String, val id: String, val name: String, val category: String) : Link
        /** A recording on the receiver by its file path. */
        data class Recording(val file: String) : Link
        /** The channel row itself: just open the app. */
        data object Open : Link
    }

    /** One card in the Favorieten row. */
    data class LiveCard(val link: Link, val title: String, val logo: String)

    fun uri(link: Link): String = when (link) {
        is Link.Sat -> build("sat", "ref" to link.reference, "name" to link.name)
        is Link.Iptv -> build("iptv", "source" to link.source, "id" to link.id, "name" to link.name, "cat" to link.category)
        is Link.Recording -> build("rec", "file" to link.file)
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
            "rec" -> query["file"]?.takeIf { it.isNotBlank() }?.let { Link.Recording(it) }
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
            LiveCard(Link.Iptv(source, ch.reference, ch.name, ch.bouquetReference), ch.name, publicLogo(ch.logo).orEmpty())
        }
        val (first, second) = if (iptvFirst) iptvCards to satCards else satCards to iptvCards
        val out = ArrayList<LiveCard>()
        for (i in 0 until maxOf(first.size, second.size)) {
            first.getOrNull(i)?.let(out::add)
            second.getOrNull(i)?.let(out::add)
        }
        return out.take(max)
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
