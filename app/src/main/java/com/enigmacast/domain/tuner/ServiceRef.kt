package com.enigmacast.domain.tuner

/**
 * Reads the parts of an Enigma2 service reference that matter for tuner use:
 * `1:0:19:SID:TSID:ONID:NAMESPACE:0:0:0:`. Two services with the same TSID, ONID and
 * namespace are on the same transponder, so Enigma2 can serve both from one tuner.
 */
object ServiceRef {
    enum class Delivery { SATELLITE, CABLE, TERRESTRIAL, UNKNOWN }

    private const val CABLE_NAMESPACE = 0xFFFFL
    private const val TERRESTRIAL_NAMESPACE = 0xEEEEL
    private const val MARKER_FLAG = 64

    private fun fields(reference: String): List<String> = reference.trim().split(':')

    /** IPTV and file services stream without a tuner; DVB services (type 1) need one. */
    fun needsTuner(reference: String): Boolean {
        val parts = fields(reference)
        if (parts.size < 7) return false
        if (parts[0] != "1") return false
        if (reference.contains("://") || reference.contains("%3a//", ignoreCase = true)) return false
        val flags = parts[1].toIntOrNull() ?: 0
        if (flags and MARKER_FLAG != 0) return false
        return parts.subList(3, 7).any { (it.toLongOrNull(16) ?: 0L) != 0L }
    }

    /** "TSID:ONID:NS" in canonical hex, or null when the reference has no DVB transponder. */
    fun transponderKey(reference: String): String? {
        if (!needsTuner(reference)) return null
        val parts = fields(reference)
        val tsid = parts[4].toLongOrNull(16) ?: return null
        val onid = parts[5].toLongOrNull(16) ?: return null
        val namespace = parts[6].toLongOrNull(16) ?: return null
        if (tsid == 0L && onid == 0L && namespace == 0L) return null
        return "%X:%X:%X".format(tsid, onid, namespace)
    }

    fun sameTransponder(left: String, right: String): Boolean {
        val a = transponderKey(left) ?: return false
        return a == transponderKey(right)
    }

    /** The namespace's upper 16 bits hold the orbital position, or 0xFFFF/0xEEEE for cable/terrestrial. */
    fun delivery(reference: String): Delivery {
        if (!needsTuner(reference)) return Delivery.UNKNOWN
        val namespace = fields(reference)[6].toLongOrNull(16) ?: return Delivery.UNKNOWN
        return when (namespace shr 16) {
            CABLE_NAMESPACE -> Delivery.CABLE
            TERRESTRIAL_NAMESPACE -> Delivery.TERRESTRIAL
            in 1L..3600L -> Delivery.SATELLITE
            else -> Delivery.UNKNOWN
        }
    }

    /** Whether a tuner described by OpenWebif (`"BCM45208 (DVB-S2)"`, `"Si2169 (DVB-T2/C)"`) can receive [delivery]. */
    fun tunerSupports(tunerType: String, delivery: Delivery): Boolean {
        val type = tunerType.uppercase()
        return when (delivery) {
            Delivery.SATELLITE -> "DVB-S" in type
            Delivery.CABLE -> "DVB-C" in type || Regex("""DVB-T2?/C""").containsMatchIn(type)
            Delivery.TERRESTRIAL -> "DVB-T" in type || Regex("""DVB-C/T""").containsMatchIn(type)
            Delivery.UNKNOWN -> true
        }
    }

    /**
     * The picon file OpenWebif serves under `/picon/`. Enigma2 keeps every TV service
     * under type `1`, so an HD reference `1:0:19:…` is the file `1_0_1_….png`. Radio stays `2`.
     */
    fun piconPath(reference: String): String? {
        if (!needsTuner(reference)) return null
        val parts = fields(reference).take(10).toMutableList()
        if (parts.size < 3) return null
        if (parts[2] != "1" && parts[2] != "2") parts[2] = "1"
        return "/picon/${parts.joinToString("_")}.png"
    }

    /** Enigma2 reports stream clients as "::ffff:192.168.1.20"; compare the plain address. */
    fun normalizeAddress(address: String): String {
        return address.trim()
            .removePrefix("::ffff:")
            .removePrefix("::FFFF:")
            .substringBefore('%')
            .lowercase()
    }
}
