package com.enigmacast.domain.osdinfo

import com.enigmacast.domain.service.ServiceStreams

/** Where a channel comes from. */
enum class Delivery { SATELLITE, CABLE, TERRESTRIAL, IPTV, UNKNOWN }

/**
 * A satellite position in tenths of a degree as Enigma2 stores it: 0–1800 is East, above
 * 1800 is West (3592 is 0.8°W).
 */
data class OrbitalPosition(val tenths: Int) {
    val east: Boolean get() = tenths <= 1800
    private val degreeTenths: Int get() = if (east) tenths else 3600 - tenths

    /** "19.2°E", "0.8°W", "13.0°E". */
    val label: String get() = "${degreeTenths / 10}.${degreeTenths % 10}°${if (east) "E" else "W"}"

    /** Common name of the satellites at this position, or null when not in the list. */
    val satelliteName: String? get() = SATELLITES[tenths]

    companion object {
        /** Satellites a European dish usually points at, by position in tenths (West as 3600 - x). */
        private val SATELLITES: Map<Int, String> = mapOf(
            3600 - 300 to "Hispasat",
            3600 - 220 to "SES 4",
            3600 - 150 to "Telstar 12",
            3600 - 80 to "Eutelsat 8 West",
            3600 - 70 to "Nilesat / Eutelsat 7 West",
            3600 - 50 to "Eutelsat 5 West",
            3600 - 40 to "Amos",
            3600 - 10 to "Intelsat 10-02",
            3600 - 8 to "Thor",
            30 to "Eutelsat 3B",
            48 to "Astra 4A",
            50 to "Astra 4A",
            70 to "Eutelsat 7",
            90 to "Eutelsat 9",
            100 to "Eutelsat 10",
            130 to "Hot Bird",
            160 to "Eutelsat 16",
            192 to "Astra 1",
            215 to "Eutelsat 21",
            235 to "Astra 3",
            260 to "Badr",
            282 to "Astra 2",
            283 to "Astra 2",
            305 to "Arabsat",
            315 to "Astra 5",
            360 to "Eutelsat 36",
            390 to "Hellas Sat",
            420 to "Türksat",
            450 to "Intelsat 12",
        )

        /** "19.2° E", "19.2°E", "0.8° W", "30.0W" from OpenWebif texts; null for "DVB-C" and the like. */
        fun parse(text: String?): OrbitalPosition? {
            val match = ORBITAL_TEXT.find(text.orEmpty()) ?: return null
            val whole = match.groupValues[1].toIntOrNull() ?: return null
            val fraction = match.groupValues[2].ifEmpty { "0" }.take(1).toInt()
            val tenths = whole * 10 + fraction
            if (tenths > 1800) return null
            val west = match.groupValues[3].equals("W", ignoreCase = true)
            return OrbitalPosition(if (west && tenths != 0) 3600 - tenths else tenths)
        }

        private val ORBITAL_TEXT = Regex("""(\d{1,3})(?:[.,](\d))?\s*°?\s*([EOW])\b""", RegexOption.IGNORE_CASE)
    }
}

/**
 * What a service reference says about its origin. Field 7 of an Enigma2 reference is the
 * DVB namespace; its upper 16 bits are the orbital position in tenths of a degree for
 * satellite services, 0xFFFF for cable and 0xEEEE for terrestrial. IPTV entries carry a URL.
 */
object ServiceOrigin {
    private const val CABLE = 0xFFFF
    private const val TERRESTRIAL = 0xEEEE
    private const val ATSC = 0xDDDD

    fun delivery(reference: String): Delivery {
        if (ServiceStreams.internetUrl(reference) != null) return Delivery.IPTV
        val top = namespaceTop(reference) ?: return Delivery.UNKNOWN
        return when {
            top == CABLE -> Delivery.CABLE
            top == TERRESTRIAL || top == ATSC -> Delivery.TERRESTRIAL
            top in 1..3599 -> Delivery.SATELLITE
            else -> Delivery.UNKNOWN
        }
    }

    /** The satellite position for a satellite service, else null. */
    fun orbital(reference: String): OrbitalPosition? {
        if (delivery(reference) != Delivery.SATELLITE) return null
        return namespaceTop(reference)?.let(::OrbitalPosition)
    }

    /** The namespace as OpenWebif's `getcurrent` reports it (a decimal number). */
    fun orbitalFromNamespace(namespace: Long?): OrbitalPosition? {
        val top = ((namespace ?: return null) ushr 16).toInt() and 0xFFFF
        return if (top in 1..3599) OrbitalPosition(top) else null
    }

    /** "TSID:ONID:NAMESPACE" of a reference: two services with the same key share a transponder. */
    fun transponderKey(reference: String): String? {
        val fields = reference.trim().split(':')
        if (fields.size < 7) return null
        val key = fields.subList(4, 7).map { it.trim().trimStart('0').uppercase() }
        if (key.all { it.isEmpty() }) return null
        return key.joinToString(":")
    }

    private fun namespaceTop(reference: String): Int? {
        val fields = reference.trim().split(':')
        if (fields.size < 7) return null
        val namespace = fields[6].trim().toLongOrNull(16) ?: return null
        return ((namespace ushr 16) and 0xFFFF).toInt()
    }
}
