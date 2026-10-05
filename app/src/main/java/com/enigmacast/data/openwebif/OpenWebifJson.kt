package com.enigmacast.data.openwebif

import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.EnigmaService
import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.domain.model.PowerStatus
import com.enigmacast.domain.model.ReceiverCapabilities
import com.enigmacast.domain.model.VolumeStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal val openWebifJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

internal object OpenWebifJson {
    fun parseAbout(body: String, https: Boolean, streamingAvailable: Boolean, grabs: GrabSupport): ReceiverCapabilities {
        val root = openWebifJson.parseToJsonElement(body).jsonObject
        return ReceiverCapabilities(
            model = text(root, "model", "boxtype", "friendlyname") ?: "",
            brand = text(root, "brand", "manufacturer") ?: "",
            imageDistro = text(root, "friendlyimagedistro", "imagedistro", "distro") ?: "",
            imageVersion = text(root, "imagever", "imageversion", "imagedistroversion") ?: "",
            enigmaVersion = text(root, "enigmaver", "e2enigmaversion", "enigmaversion") ?: "",
            webifVersion = text(root, "webifver", "e2webifversion", "webifversion") ?: "",
            grabPng = grabs.png,
            grabJpg = grabs.jpg,
            grabOsd = grabs.osd,
            grabAll = grabs.all,
            authenticationRequired = grabs.authenticationRequired,
            https = https,
            streamingAvailable = streamingAvailable,
        )
    }

    fun parseService(body: String): EnigmaService {
        val root = openWebifJson.parseToJsonElement(body).jsonObject
        val reference = text(
            root,
            "ref",
            "serviceref",
            "servicereference",
            "currservice_serviceref",
            "sref",
        ).orEmpty()
        val name = text(
            root,
            "name",
            "servicename",
            "currservice_station",
            "currservice_name",
            "sname",
        ).orEmpty()
        return EnigmaService(
            name = name,
            reference = reference,
            provider = text(root, "provider").orEmpty(),
            videoWidth = int(root, "width") ?: 0,
            videoHeight = int(root, "height") ?: 0,
        )
    }

    fun parseGuide(currentBody: String, epgBody: String): ChannelGuide {
        val current = openWebifJson.parseToJsonElement(currentBody).jsonObject
        val info = current["info"] as? JsonObject
        val channelName = info?.primitive("name").orEmpty()
        val reference = info?.primitive("ref").orEmpty()
        val epg = openWebifJson.parseToJsonElement(epgBody).jsonObject
        val array = epg["events"] as? JsonArray ?: JsonArray(emptyList())
        var clock = 0L
        val parsed = array.mapNotNull { element ->
            val event = element as? JsonObject ?: return@mapNotNull null
            val begin = event.primitive("begin_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val duration = event.primitive("duration_sec")?.toLongOrNull() ?: 0L
            val now = event.primitive("now_timestamp")?.toLongOrNull() ?: 0L
            if (now > 0L) clock = now
            val title = unescape(event.primitive("title").orEmpty())
            if (title.isBlank()) return@mapNotNull null
            EpgEvent(
                title = title,
                shortDescription = unescape(event.primitive("shortdesc").orEmpty()),
                plot = unescape(event.primitive("longdesc").orEmpty()),
                beginLabel = event.primitive("begin").orEmpty(),
                endLabel = event.primitive("end").orEmpty(),
                beginEpochSec = begin,
                durationSec = duration,
                current = now in begin until begin + duration,
            )
        }
        val upcoming = parsed.filter { event ->
            clock == 0L || event.beginEpochSec + event.durationSec > clock
        }.take(24)
        return ChannelGuide(
            channelName = channelName.ifBlank { channelNameFromEvents(array) },
            serviceReference = reference,
            events = upcoming,
        )
    }

    private fun channelNameFromEvents(array: JsonArray): String {
        val first = array.firstOrNull() as? JsonObject ?: return ""
        return unescape(first.primitive("sname").orEmpty())
    }

    private fun unescape(value: String): String {
        val hex = Regex("&#x([0-9a-fA-F]+);")
        val decimal = Regex("&#([0-9]+);")
        return value
            .replace(hex) { match -> match.groupValues[1].toInt(16).toChar().toString() }
            .replace(decimal) { match -> match.groupValues[1].toInt().toChar().toString() }
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    fun parsePower(body: String): PowerStatus {
        val root = openWebifJson.parseToJsonElement(body).jsonObject
        val raw = text(root, "instandby")
        val standby = raw.equals("true", ignoreCase = true) || raw == "1"
        return PowerStatus(standby)
    }

    fun parseVolume(body: String): VolumeStatus {
        val root = openWebifJson.parseToJsonElement(body).jsonObject
        val mutedRaw = text(root, "ismute", "muted")
        val muted = mutedRaw.equals("true", ignoreCase = true) || mutedRaw == "1"
        return VolumeStatus(level = int(root, "current", "volume") ?: 0, muted = muted)
    }

    fun parseBouquets(body: String): List<BouquetEntry> = parseServiceList(body, "bouquets")

    fun parseServices(body: String): List<BouquetEntry> = parseServiceList(body, "services")

    fun isSuccess(body: String): Boolean {
        val root = runCatching { openWebifJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return false
        val result = root["result"] ?: return true
        return when (result) {
            is JsonPrimitive -> result.booleanOrNull
                ?: result.contentOrNull.equals("true", ignoreCase = true)
            else -> true
        }
    }

    private fun parseServiceList(body: String, key: String): List<BouquetEntry> {
        val root = openWebifJson.parseToJsonElement(body).jsonObject
        val array = findArray(root, key) ?: return emptyList()
        return array.mapNotNull { element ->
            when (element) {
                is JsonArray -> {
                    val name = element.getOrNull(0)?.primitiveText().orEmpty()
                    val reference = element.getOrNull(1)?.primitiveText().orEmpty()
                    if (reference.isBlank()) null else BouquetEntry(name, reference)
                }
                is JsonObject -> {
                    val name = text(element, "servicename", "name").orEmpty()
                    val reference = text(element, "servicereference", "reference", "ref").orEmpty()
                    if (reference.isBlank()) null else BouquetEntry(name, reference)
                }
                else -> null
            }
        }
    }

    private fun text(root: JsonObject, vararg keys: String): String? {
        keys.forEach { key ->
            root.primitive(key)?.let { return it }
        }
        listOf("info", "service", "now", "current").forEach { childName ->
            val child = root[childName] as? JsonObject ?: return@forEach
            keys.forEach { key ->
                child.primitive(key)?.let { return it }
            }
            val nested = child["service"] as? JsonObject
            if (nested != null) {
                keys.forEach { key ->
                    nested.primitive(key)?.let { return it }
                }
            }
        }
        return null
    }

    private fun int(root: JsonObject, vararg keys: String): Int? {
        return text(root, *keys)?.toIntOrNull()
    }

    private fun findArray(root: JsonObject, key: String): JsonArray? {
        (root[key] as? JsonArray)?.let { return it }
        val info = root["info"] as? JsonObject
        return info?.get(key) as? JsonArray
    }

    private fun JsonObject.primitive(key: String): String? {
        val element = this[key] ?: return null
        return element.primitiveText()
    }

    private fun JsonElement.primitiveText(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    }
}

internal data class GrabSupport(
    val png: Boolean,
    val jpg: Boolean,
    val osd: Boolean,
    val all: Boolean,
    val authenticationRequired: Boolean,
)
