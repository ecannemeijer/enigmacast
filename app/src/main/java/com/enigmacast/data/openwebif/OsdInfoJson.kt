package com.enigmacast.data.openwebif

import com.enigmacast.domain.osdinfo.OrbitalPosition
import com.enigmacast.domain.osdinfo.Polarisation
import com.enigmacast.domain.osdinfo.ReceiverCurrent
import com.enigmacast.domain.osdinfo.SignalReading
import com.enigmacast.domain.osdinfo.StreamClientInfo
import com.enigmacast.domain.osdinfo.Transponder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * OpenWebif answers for the OSD info. openATV (oe-alliance OpenWebif) and OpenPLi
 * (E2OpenPlugins) differ in details: `snr_db` is "12.50" in `tunersignal` but "12.50 dB" in
 * `getcurrent`, and when the tuner reports no dB value `snr_db` simply repeats the SNR
 * percentage as a number; missing values are "", "N/A" or absent.
 */
internal object OsdInfoJson {
    /** `/api/tunersignal` or `/api/signal`. */
    fun parseSignal(body: String): SignalReading {
        val root = parse(body)
        val info = root["info"] as? JsonObject ?: root
        return signalFrom(info)
    }

    /** `/api/getcurrent`: the `info` object, with the signal values it carries. */
    fun parseCurrent(body: String): ReceiverCurrent {
        val root = parse(body)
        val info = root["info"] as? JsonObject ?: root
        val reference = info.text("ref") ?: info.text("srefv2") ?: info.text("sref")?.replace('_', ':').orEmpty()
        val teletext = info.text("ttext") ?: info.text("txtpid")
        return ReceiverCurrent(
            reference = reference,
            name = info.text("name").orEmpty(),
            namespace = info.text("namespace")?.toLongOrNull(),
            width = info.int("width"),
            height = info.int("height"),
            aspect = info.int("aspect"),
            widescreen = info.bool("wide") ?: info.bool("iswidescreen"),
            teletext = teletext?.let { it.toIntOrNull()?.let { pid -> pid > 0 } ?: if (it.equals("N/A", true)) false else null },
            crypted = info.text("crypt")?.let { it.toIntOrNull()?.let { value -> value != 0 } ?: info.bool("crypt") },
            dolby = info.bool("dolby"),
            audioDescription = info.text("audio_desc").orEmpty(),
            tunerNumber = info.int("tunernumber"),
            tunerType = info.text("tunertype")?.takeUnless { it.equals("N/A", true) || it.equals("UNKNOWN", true) },
            orbitalText = info.text("orbital_position"),
            signal = signalFrom(info).takeIf { it.hasValues },
        )
    }

    /** The stream clients in `/api/about` (`info.streams`), with tuner and transponder where the image has them. */
    fun parseStreamClients(aboutBody: String): List<StreamClientInfo> {
        val root = parse(aboutBody)
        val info = root["info"] as? JsonObject ?: root
        return (info["streams"] as? JsonArray).orEmpty().mapNotNull { element ->
            val stream = element as? JsonObject ?: return@mapNotNull null
            StreamClientInfo(
                reference = stream.text("ref").orEmpty(),
                address = stream.text("ip").orEmpty().removePrefix("::ffff:"),
                tunerNumber = stream.int("tuner_number")?.takeIf { it >= 0 },
                transponder = (stream["transponder"] as? JsonObject)?.let(::transponder),
            )
        }
    }

    /**
     * A transponder dict as Enigma2's `getTransponderData` gives it: raw numbers (frequency in
     * kHz for satellite and cable, Hz for terrestrial; symbol rate in symbols/s; polarisation,
     * FEC, system and modulation as enum numbers) or, on some images, readable strings.
     */
    fun transponder(data: JsonObject): Transponder {
        val type = data.text("tuner_type")?.uppercase().orEmpty()
        val satellite = type.startsWith("DVB-S") || data["orbital_position"] != null
        val cable = type.startsWith("DVB-C")
        val system = data.text("system")?.let { raw ->
            raw.toIntOrNull()?.let { number ->
                val base = when {
                    cable -> "DVB-C"
                    type.startsWith("DVB-T") -> "DVB-T"
                    satellite -> "DVB-S"
                    else -> type.ifBlank { null }
                }
                base?.let { if (number == 1 && !cable) "${it}2" else it }
            } ?: raw.uppercase()
        } ?: type.ifBlank { null }
        return Transponder(
            frequencyKhz = data.text("frequency")?.toDoubleOrNull()?.let { value ->
                when {
                    value > 30_000_000 -> (value / 1000).toInt()
                    value < 100_000 -> (value * 1000).toInt()
                    else -> value.toInt()
                }
            },
            symbolRate = data.text("symbol_rate")?.toDoubleOrNull()?.let { value ->
                if (value in 1.0..99_999.0) (value * 1000).toInt() else value.toInt()
            }?.takeIf { it > 0 },
            polarisation = data.text("polarization")?.let(::polarisation),
            fec = data.text("fec_inner")?.let(::fec),
            system = system,
            modulation = data.text("modulation")?.let { modulation(it, cable) },
            orbital = data.text("orbital_position")?.toIntOrNull()?.takeIf { satellite && it in 0..3599 }?.let(::OrbitalPosition),
        )
    }

    private fun signalFrom(info: JsonObject): SignalReading {
        val snr = info.int("snr")?.takeIf { it in 0..100 }
        val tuner = info.int("tunernumber")?.takeIf { it >= 0 }
        // getcurrent fills 0 for everything when the current service has no frontend (IPTV, a recording).
        if (tuner == null && (snr ?: 0) == 0 && (info.int("agc") ?: 0) == 0) {
            return SignalReading(inStandby = info.bool("inStandby"))
        }
        return SignalReading(
            snrPercent = snr,
            snrDb = snrDb(info["snr_db"], snr),
            agcPercent = info.int("agc")?.takeIf { it in 0..100 },
            ber = info.int("ber")?.takeIf { it >= 0 },
            tunerNumber = tuner,
            tunerType = info.text("tunertype")?.takeUnless { it.equals("N/A", true) || it.equals("UNKNOWN", true) },
            inStandby = info.bool("inStandby"),
        )
    }

    /** "12.50", "12.50 dB" or 12.5 → 12.5; a bare integer equal to the SNR % is that percentage again → null. */
    private fun snrDb(element: JsonElement?, snrPercent: Int?): Float? {
        val primitive = element as? JsonPrimitive ?: return null
        val raw = primitive.contentOrNull?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("N/A", true)) return null
        val number = raw.removeSuffix("dB").removeSuffix("db").trim().replace(',', '.').toFloatOrNull() ?: return null
        val explicit = raw.contains('.') || raw.contains("dB", ignoreCase = true)
        if (!explicit && snrPercent != null && number.toInt() == snrPercent) return null
        if (!explicit && number > 40f) return null
        return number.takeIf { it in -10f..60f }
    }

    private fun polarisation(raw: String): Polarisation? = when (raw.trim().uppercase()) {
        "0", "H", "HORIZONTAL" -> Polarisation.HORIZONTAL
        "1", "V", "VERTICAL" -> Polarisation.VERTICAL
        "2", "L", "CIRCULAR LEFT", "LEFT" -> Polarisation.CIRCULAR_LEFT
        "3", "R", "CIRCULAR RIGHT", "RIGHT" -> Polarisation.CIRCULAR_RIGHT
        else -> null
    }

    private val FEC_BY_NUMBER = mapOf(
        1 to "1/2", 2 to "2/3", 3 to "3/4", 4 to "5/6", 5 to "7/8", 6 to "8/9",
        7 to "3/5", 8 to "4/5", 9 to "9/10", 10 to "6/7",
    )

    private fun fec(raw: String): String? {
        raw.trim().toIntOrNull()?.let { return FEC_BY_NUMBER[it] }
        val text = raw.trim().uppercase().removePrefix("FEC").trim('_', ' ').replace('_', '/')
        return text.takeIf { FRACTION.matches(it) }
    }

    private val FRACTION = Regex("""\d{1,2}/\d{1,2}""")

    private val SAT_MODULATION = mapOf(1 to "QPSK", 2 to "8PSK", 3 to "QAM16", 4 to "16APSK", 5 to "32APSK")
    private val CABLE_MODULATION = mapOf(1 to "QAM16", 2 to "QAM32", 3 to "QAM64", 4 to "QAM128", 5 to "QAM256")

    private fun modulation(raw: String, cable: Boolean): String? {
        raw.trim().toIntOrNull()?.let { return (if (cable) CABLE_MODULATION else SAT_MODULATION)[it] }
        return raw.trim().uppercase().takeIf { it.isNotEmpty() && it != "AUTO" && it != "N/A" }
    }

    private fun parse(body: String): JsonObject =
        runCatching { openWebifJson.parseToJsonElement(body).jsonObject }.getOrElse { JsonObject(emptyMap()) }

    private fun JsonObject.text(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JsonObject.int(key: String): Int? = text(key)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

    private fun JsonObject.bool(key: String): Boolean? = when (text(key)?.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}
