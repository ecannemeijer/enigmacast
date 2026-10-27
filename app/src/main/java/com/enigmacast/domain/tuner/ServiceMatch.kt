package com.enigmacast.domain.tuner

import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.service.ServiceStreams
import java.text.Normalizer

/**
 * Puts programmes from a whole-bouquet EPG answer (`/api/epgnow?bRef=`, `/api/epgmulti`) on the
 * channels of that bouquet.
 *
 * Normally the reference in the answer is the bouquet's own ([NowEvents.key] matches). It is not
 * always the same string: Enigma2 writes the reference again from its EPG cache, so the DVB
 * namespace can differ in its lower 16 bits (the frequency part some satellites such as Hot Bird
 * 13°E use), and an alternative (`1:134:…`) comes back as the service it resolved to. The
 * per-channel answers (OSD, Right in the list) never needed matching, which is why those showed
 * the programme while the list and the guide did not.
 *
 * Fallbacks, in this order, each used only when it points at exactly one channel of the bouquet
 * and that channel has no programme that matched exactly:
 * 1. SID, TSID, ONID and orbital position (namespace upper 16 bits);
 * 2. SID, TSID and ONID alone;
 * 3. the channel name (`sname`), ignoring case, accents, spaces and punctuation.
 * Matched programmes get the bouquet channel's reference; anything unmatched is left as it was.
 */
object ServiceMatch {
    /** DVB `SID:TSID:ONID:orbital`, or null for streams, alternatives and references without a SID. */
    fun orbitalKey(reference: String): String? {
        val fields = dvbFields(reference) ?: return null
        val namespace = fields[6].toLongOrNull(16) ?: return null
        return "${fields[3]}:${fields[4]}:${fields[5]}:${(namespace shr 16).toString(16).uppercase()}"
    }

    /** DVB `SID:TSID:ONID`, or null like [orbitalKey]. */
    fun transportKey(reference: String): String? {
        val fields = dvbFields(reference) ?: return null
        return "${fields[3]}:${fields[4]}:${fields[5]}"
    }

    /** "Rai 1 HD" → "rai1hd", "TV2000" → "tv2000", "Rete 4" = "rete4", "La7d" = "la7d"; null when nothing is left. */
    fun nameKey(name: String): String? {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { null }
    }

    private fun dvbFields(reference: String): List<String>? {
        if (reference.contains("FROM BOUQUET", ignoreCase = true) || ServiceStreams.isInternet(reference)) return null
        val fields = NowEvents.key(reference).split(':')
        if (fields.size < 10 || fields[0] != "1" || fields[3] == "0") return null
        return fields
    }

    /** The channels of one bouquet, looked up by reference and name. */
    class Channels(channels: List<BouquetEntry>) {
        private val exact = LinkedHashMap<String, BouquetEntry>()
        private val orbital: Map<String, BouquetEntry>
        private val transport: Map<String, BouquetEntry>
        private val names: Map<String, BouquetEntry>

        init {
            channels.forEach { exact.putIfAbsent(NowEvents.key(it.reference), it) }
            val unique = exact.values.toList()
            orbital = uniqueBy(unique) { orbitalKey(it.reference) }
            transport = uniqueBy(unique) { transportKey(it.reference) }
            names = uniqueBy(unique) { nameKey(it.name) }
        }

        fun exact(reference: String): BouquetEntry? = exact[NowEvents.key(reference)]

        /** The fallbacks of the class description, without the exact match. */
        fun loose(reference: String, name: String): BouquetEntry? =
            orbitalKey(reference)?.let(orbital::get)
                ?: transportKey(reference)?.let(transport::get)
                ?: nameKey(name)?.let(names::get)

        private fun uniqueBy(channels: List<BouquetEntry>, key: (BouquetEntry) -> String?): Map<String, BouquetEntry> =
            channels.mapNotNull { channel -> key(channel)?.let { it to channel } }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size == 1 }
                .mapValues { it.value.single() }
    }

    /**
     * [events] with each reference set to the bouquet channel it belongs to (see the class
     * description). [references] gives an event's reference first and any alternatives after it.
     */
    fun <T> remap(
        events: List<T>,
        channels: List<BouquetEntry>,
        references: (T) -> List<String>,
        name: (T) -> String,
        rewrite: (T, String) -> T,
    ): List<T> {
        if (events.isEmpty() || channels.isEmpty()) return events
        val match = Channels(channels)
        val exact = events.map { event -> references(event).firstNotNullOfOrNull(match::exact) }
        val taken = exact.filterNotNull().mapTo(HashSet()) { NowEvents.key(it.reference) }
        return events.mapIndexed { index, event ->
            exact[index]?.let { channel ->
                val own = references(event).first()
                return@mapIndexed if (NowEvents.key(own) == NowEvents.key(channel.reference)) event else rewrite(event, channel.reference)
            }
            val refs = references(event)
            val channel = refs.firstNotNullOfOrNull { match.loose(it, "") } ?: match.loose("", name(event))
            if (channel == null || NowEvents.key(channel.reference) in taken) event else rewrite(event, channel.reference)
        }
    }

    fun nowEvents(events: List<NowEvent>, channels: List<BouquetEntry>): List<NowEvent> =
        remap(events, channels, { listOf(it.reference) + it.alternatives }, { it.serviceName }) { event, reference ->
            event.copy(reference = reference)
        }

    fun guideEvents(events: List<GuideEvent>, channels: List<BouquetEntry>): List<GuideEvent> =
        remap(events, channels, { listOf(it.reference) }, { it.serviceName }) { event, reference ->
            event.copy(reference = reference)
        }
}
