package com.enigmacast.domain.tuner

import kotlin.math.abs

/** A service stored in the receiver's `lamedb`, with the satellite transponder it is on. */
data class LamedbService(
    val name: String,
    val reference: String,
    val type: Int,
    val orbitalTenths: Int,
    val frequencyKhz: Int,
    val symbolRate: Int,
    val horizontal: Boolean,
)

/**
 * Reads Enigma2 `lamedb` (version 4) and `lamedb5` enough to find one service on a
 * reference transponder. Tuning needs a real service; the frequency itself is not a zap target.
 */
object Lamedb {
    private val TV_TYPES = setOf(1, 4, 5, 6, 17, 22, 25, 27, 31, 32)

    /** The first listed transponder that has a service, preferring a television service. */
    fun serviceOn(text: String, transponders: List<ReferenceTransponder>): Pair<ReferenceTransponder, LamedbService>? {
        val services = services(text)
        for (transponder in transponders) {
            val onTransponder = services.filter { matches(it, transponder) }
            val hit = onTransponder.firstOrNull { it.type in TV_TYPES } ?: onTransponder.firstOrNull()
            if (hit != null) return transponder to hit
        }
        return null
    }

    fun services(text: String): List<LamedbService> {
        val transponders = linkedMapOf<String, Transponder>()
        val found = mutableListOf<LamedbService>()
        var section = Section.NONE
        var pending: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) continue
            if (trimmed.isEmpty()) {
                if (section == Section.SERVICES && pending != null) {
                    parseService4(pending, "", transponders)?.let { found += it }
                    pending = null
                }
                continue
            }
            when (trimmed) {
                "transponders" -> {
                    section = Section.TRANSPONDERS
                    continue
                }
                "services" -> {
                    section = Section.SERVICES
                    pending = null
                    continue
                }
                "end" -> {
                    section = Section.NONE
                    pending = null
                    continue
                }
            }
            when (section) {
                Section.TRANSPONDERS -> {
                    if (trimmed.startsWith("s ") || trimmed.startsWith("s:")) {
                        val key = pending ?: continue
                        parseSatellite(trimmed)?.let { transponders[key] = it }
                    } else if (!trimmed.startsWith("/") && !trimmed.startsWith("t ") && !trimmed.startsWith("c ") && !trimmed.startsWith("a ")) {
                        pending = channelKey(trimmed)
                    }
                }
                Section.SERVICES -> {
                    val id = pending
                    if (id == null) {
                        if (trimmed.contains(':') && !trimmed.startsWith("p:") && !trimmed.startsWith("s:")) pending = trimmed
                        else if (trimmed.startsWith("s:")) parseService5(trimmed, transponders)?.let { found += it }
                    } else {
                        if (!trimmed.startsWith("p:")) {
                            parseService4(id, trimmed, transponders)?.let { found += it }
                        }
                        pending = null
                    }
                }
                Section.NONE -> {
                    if (trimmed.startsWith("t:")) parseTransponder5(trimmed)?.let { (key, tp) -> transponders[key] = tp }
                    if (trimmed.startsWith("s:")) parseService5(trimmed, transponders)?.let { found += it }
                }
            }
        }
        return found
    }

    private fun matches(service: LamedbService, transponder: ReferenceTransponder): Boolean {
        if (service.orbitalTenths != transponder.orbitalTenths) return false
        if (service.horizontal != transponder.horizontal) return false
        if (abs(service.frequencyKhz / 1000 - transponder.frequencyMhz) > 2) return false
        val ksym = if (service.symbolRate >= 100_000) service.symbolRate / 1000 else service.symbolRate
        return abs(ksym - transponder.symbolRateKsym) <= 50
    }

    private fun parseSatellite(line: String): Transponder? {
        val payload = line.trim().removePrefix("s").removePrefix(":").trim()
        val parts = payload.split(':')
        if (parts.size < 5) return null
        val frequency = parts[0].toIntOrNull() ?: return null
        val symbolRate = parts[1].toIntOrNull() ?: return null
        val polarisation = parts[2].toIntOrNull() ?: return null
        val orbital = parts[4].toIntOrNull() ?: return null
        val tenths = when {
            orbital < 0 -> 3600 + orbital
            else -> orbital
        }
        if (tenths !in 1..3599) return null
        return Transponder(tenths, frequency, symbolRate, horizontal = polarisation == 0)
    }

    private fun parseService4(idLine: String, name: String, transponders: Map<String, Transponder>): LamedbService? {
        val parts = idLine.trim().split(':')
        if (parts.size < 5) return null
        val sid = parts[0].toIntOrNull(16) ?: return null
        val namespace = parts[1].toLongOrNull(16) ?: return null
        val tsid = parts[2].toIntOrNull(16) ?: return null
        val onid = parts[3].toIntOrNull(16) ?: return null
        val type = parts[4].toIntOrNull() ?: return null
        val transponder = transponders[channelKey("${parts[1]}:${parts[2]}:${parts[3]}")] ?: return null
        return service(name, type, sid, tsid, onid, namespace, transponder)
    }

    private fun parseTransponder5(line: String): Pair<String, Transponder>? {
        val comma = line.indexOf(',')
        if (comma < 0) return null
        val head = line.substring(2, comma).split(':')
        if (head.size < 3) return null
        val satellite = parseSatellite(line.substring(comma + 1)) ?: return null
        return channelKey("${head[0]}:${head[1]}:${head[2]}") to satellite
    }

    private fun parseService5(line: String, transponders: Map<String, Transponder>): LamedbService? {
        val comma = line.indexOf(',')
        val head = (if (comma < 0) line else line.substring(0, comma)).removePrefix("s:")
        val parts = head.split(':')
        if (parts.size < 5) return null
        val sid = parts[0].toIntOrNull(16) ?: return null
        val namespace = parts[1].toLongOrNull(16) ?: return null
        val tsid = parts[2].toIntOrNull(16) ?: return null
        val onid = parts[3].toIntOrNull(16) ?: return null
        val type = parts[4].toIntOrNull() ?: return null
        val transponder = transponders[channelKey("${parts[1]}:${parts[2]}:${parts[3]}")] ?: return null
        val name = if (comma < 0) "" else line.substring(comma + 1).trim().removeSurrounding("\"")
        return service(name, type, sid, tsid, onid, namespace, transponder)
    }

    private fun service(
        name: String,
        type: Int,
        sid: Int,
        tsid: Int,
        onid: Int,
        namespace: Long,
        transponder: Transponder,
    ): LamedbService = LamedbService(
        name = name.trim(),
        reference = "1:0:${type.toString(16)}:${sid.toString(16)}:${tsid.toString(16)}:${onid.toString(16)}:${namespace.toString(16)}:0:0:0:".uppercase(),
        type = type,
        orbitalTenths = transponder.orbitalTenths,
        frequencyKhz = transponder.frequencyKhz,
        symbolRate = transponder.symbolRate,
        horizontal = transponder.horizontal,
    )

    /** Namespace, tsid and onid compared without leading zeros, so v4 and v5 keys meet. */
    private fun channelKey(raw: String): String =
        raw.trim().split(':').joinToString(":") { it.trim().trimStart('0').ifEmpty { "0" }.uppercase() }

    private enum class Section { NONE, TRANSPONDERS, SERVICES }

    private data class Transponder(
        val orbitalTenths: Int,
        val frequencyKhz: Int,
        val symbolRate: Int,
        val horizontal: Boolean,
    )
}
