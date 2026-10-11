package com.enigmacast.domain.tuner

import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.osdinfo.OrbitalPosition
import com.enigmacast.domain.osdinfo.ServiceOrigin

/** A satellite the signal meter can tune, with the first TV channel found on that position. */
data class TunerSatellite(
    val orbital: OrbitalPosition,
    val label: String,
    val channelName: String?,
    val serviceReference: String?,
)

/**
 * Satellites configured on the receiver's tuners, from `/etc/enigma2/settings`.
 * Simple mode uses `config.Nims.N.diseqcA`–`D` according to `diseqcMode`.
 * Advanced mode uses the orbital position in `config.Nims.N.advanced.sat.<tenths>`.
 * A tuner in `equal` or `loop` mode follows the tuner named by `connectedTo`.
 */
object TunerSatellites {
    fun positions(settings: String): List<OrbitalPosition> {
        val values = linkedMapOf<String, String>()
        for (line in settings.lineSequence()) {
            val text = line.trim()
            if (text.isEmpty() || text.startsWith("#")) continue
            val split = text.indexOf('=')
            if (split <= 0) continue
            values[text.substring(0, split).trim()] = text.substring(split + 1).trim()
        }
        val tuners = values.keys.mapNotNull { key ->
            NIM_INDEX.find(key)?.groupValues?.get(1)?.toIntOrNull()
        }.toSortedSet()
        val found = linkedSetOf<Int>()
        val visiting = mutableSetOf<Int>()
        for (tuner in tuners) found += collect(values, tuner, visiting)
        return found.map(::OrbitalPosition)
    }

    /**
     * [configured] is the tuner list. When it is empty, the satellites are the ones
     * that appear on a TV channel. The first channel on a position is the one to zap.
     */
    fun merge(configured: List<OrbitalPosition>, channels: List<BouquetEntry>): List<TunerSatellite> {
        val first = linkedMapOf<Int, BouquetEntry>()
        for (channel in channels) {
            val orbital = ServiceOrigin.orbital(channel.reference) ?: continue
            first.putIfAbsent(orbital.tenths, channel)
        }
        val positions = if (configured.isNotEmpty()) {
            configured.distinctBy { it.tenths }
        } else {
            first.keys.map(::OrbitalPosition)
        }
        return positions.map { orbital ->
            val channel = first[orbital.tenths]
            TunerSatellite(
                orbital = orbital,
                label = label(orbital),
                channelName = channel?.name?.takeIf { it.isNotBlank() },
                serviceReference = channel?.reference?.takeIf { it.isNotBlank() },
            )
        }
    }

    fun label(orbital: OrbitalPosition): String {
        val name = orbital.satelliteName
        return if (name.isNullOrBlank()) orbital.label else "${orbital.label} · $name"
    }

    private fun collect(values: Map<String, String>, tuner: Int, visiting: MutableSet<Int>): List<Int> {
        if (!visiting.add(tuner)) return emptyList()
        val mode = values["config.Nims.$tuner.configMode"]?.lowercase()
        if (mode == "nothing") return emptyList()
        if (mode == "equal" || mode == "loop") {
            val linked = values["config.Nims.$tuner.connectedTo"]?.toIntOrNull()
                ?: values["config.Nims.$tuner.connected"]?.toIntOrNull()
            return if (linked == null) emptyList() else collect(values, linked, visiting)
        }
        val advanced = advancedOf(values, tuner)
        val simple = simpleOf(values, tuner)
        return when (mode) {
            "advanced", "satposdepends", "advanced_satposdepends" -> advanced.ifEmpty { simple }
            "simple", null -> simple.ifEmpty { advanced }
            else -> {
                val diseqc = values["config.Nims.$tuner.diseqcMode"]?.lowercase()
                if (diseqc == "positioner") (simple + advanced).distinct() else simple.ifEmpty { advanced }
            }
        }
    }

    private fun simpleOf(values: Map<String, String>, tuner: Int): List<Int> {
        val diseqc = values["config.Nims.$tuner.diseqcMode"]?.lowercase()
        val slots = when (diseqc) {
            "single", null -> listOf("diseqcA")
            "toneburst_a_b", "diseqc_a_b" -> listOf("diseqcA", "diseqcB")
            "diseqc_a_b_c_d", "positioner" -> listOf("diseqcA", "diseqcB", "diseqcC", "diseqcD")
            else -> listOf("diseqcA", "diseqcB", "diseqcC", "diseqcD")
        }
        return slots.mapNotNull { slot -> values["config.Nims.$tuner.$slot"]?.let(::orbital) }
    }

    private fun advancedOf(values: Map<String, String>, tuner: Int): List<Int> {
        val prefix = "config.Nims.$tuner.advanced.sat."
        val seen = linkedSetOf<Int>()
        for (key in values.keys) {
            if (!key.startsWith(prefix)) continue
            val position = key.removePrefix(prefix).substringBefore('.').toIntOrNull() ?: continue
            if (position in 1..3599) seen += position
        }
        return seen.toList()
    }

    private fun orbital(raw: String): Int? = raw.trim().toIntOrNull()?.takeIf { it in 1..3599 }

    private val NIM_INDEX = Regex("""config\.Nims\.(\d+)\.""")
}
