package com.enigmacast.data.openwebif

import com.enigmacast.R
import com.enigmacast.domain.epg.GuideEvent
import com.enigmacast.domain.search.EpgSearchEvent
import com.enigmacast.domain.epg.TimerResult
import com.enigmacast.domain.model.BouquetEntry
import com.enigmacast.domain.model.ChannelGuide
import com.enigmacast.domain.model.EnigmaService
import com.enigmacast.domain.model.EpgEvent
import com.enigmacast.domain.model.PowerStatus
import com.enigmacast.domain.model.ReceiverCapabilities
import com.enigmacast.domain.model.ReceiverDetails
import com.enigmacast.domain.model.SleepTimerAction
import com.enigmacast.domain.model.SleepTimerStatus
import com.enigmacast.domain.model.VolumeStatus
import com.enigmacast.domain.tuner.NowEvent
import com.enigmacast.domain.tuner.StreamClient
import com.enigmacast.domain.tuner.TunerSlot
import com.enigmacast.domain.tuner.TunerSnapshot
import com.enigmacast.util.UiText
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
            transcoding = text(root, "transcoding").equals("true", ignoreCase = true),
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
                eventId = event.primitive("id")?.toIntOrNull() ?: -1,
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

    /** `/api/about`: the fields sit under `info`, tuners are objects with `name` and `type`. */
    fun parseReceiverDetails(body: String): ReceiverDetails {
        val root = openWebifJson.parseToJsonElement(body).jsonObject
        val info = root["info"] as? JsonObject ?: root
        val tuners = (info["tuners"] as? JsonArray).orEmpty().mapNotNull { element ->
            val tuner = element as? JsonObject ?: return@mapNotNull null
            val name = tuner.primitive("name").orEmpty()
            val type = tuner.primitive("type").orEmpty()
            when {
                name.isNotBlank() && type.isNotBlank() -> "$name: $type"
                else -> name.ifBlank { type }.ifBlank { null }
            }
        }
        return ReceiverDetails(
            model = text(root, "model", "boxtype", "friendlyname").orEmpty(),
            brand = text(root, "brand", "manufacturer").orEmpty(),
            imageDistro = text(root, "friendlyimagedistro", "imagedistro", "distro").orEmpty(),
            imageVersion = text(root, "imagever", "imageversion", "imagedistroversion").orEmpty(),
            enigmaVersion = text(root, "enigmaver", "e2enigmaversion", "enigmaversion").orEmpty(),
            webifVersion = text(root, "webifver", "e2webifversion", "webifversion").orEmpty(),
            kernelVersion = text(root, "kernelver").orEmpty(),
            chipset = text(root, "friendlychipsettext", "chipset").orEmpty(),
            uptime = text(root, "uptime").orEmpty(),
            tuners = tuners,
        )
    }

    /**
     * `/api/sleeptimer`. Images differ: `minutes` may be a string, the body may be
     * `{"result": false, ...}` on error, and a set call reports refusals as `"ERROR: ..."` in `message`.
     */
    fun parseSleepTimer(body: String): SleepTimerStatus {
        val root = runCatching { openWebifJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw OpenWebifException("Sleep timer not supported", uiText = UiText.res(R.string.error_sleep_unsupported))
        val message = root.primitive("message").orEmpty()
        val failed = (root["result"] as? JsonPrimitive)?.let { result ->
            result.booleanOrNull == false || result.contentOrNull.equals("false", ignoreCase = true)
        } == true
        if (failed || message.startsWith("ERROR", ignoreCase = true)) {
            val reason = message.removePrefix("ERROR:").trim()
            throw if (reason.isBlank()) {
                OpenWebifException("Sleep timer failed", uiText = UiText.res(R.string.error_sleep_failed))
            } else {
                OpenWebifException(reason)
            }
        }
        val enabledRaw = root.primitive("enabled")
        val action = if (root.primitive("action").equals("shutdown", ignoreCase = true)) {
            SleepTimerAction.SHUTDOWN
        } else {
            SleepTimerAction.STANDBY
        }
        return SleepTimerStatus(
            enabled = enabledRaw.equals("true", ignoreCase = true) || enabledRaw == "1",
            minutes = root.primitive("minutes")?.toDoubleOrNull()?.toInt() ?: 0,
            action = action,
            message = message,
        )
    }

    fun parseVolume(body: String): VolumeStatus {
        val root = openWebifJson.parseToJsonElement(body).jsonObject
        val mutedRaw = text(root, "ismute", "muted")
        val muted = mutedRaw.equals("true", ignoreCase = true) || mutedRaw == "1"
        return VolumeStatus(level = int(root, "current", "volume") ?: 0, muted = muted)
    }

    fun bouquetReference(body: String): String? {
        val root = openWebifJson.parseToJsonElement(body).jsonObject
        return text(root, "bqref", "bouquet", "bouquetref", "bouquetreference")
    }

    fun parseBouquets(body: String): List<BouquetEntry> = parseServiceList(body, "bouquets")

    fun parseServices(body: String): List<BouquetEntry> = parseServiceList(body, "services")

    /**
     * `/api/epgnow?bRef=…`: one event per channel with `sref`, `title`, `begin_timestamp`
     * and `duration_sec`. Channels without EPG come back as title "N/A" and begin 0; they
     * are left out.
     */
    fun parseNowEvents(body: String): List<NowEvent> = parseNowEventsOrNull(body).orEmpty()

    /** [parseNowEvents], or null when [body] is no JSON object at all (a broken bouquet answer). */
    fun parseNowEventsOrNull(body: String): List<NowEvent>? {
        val root = epgRoot(body) ?: return null
        val array = findArray(root, "events") ?: return emptyList()
        return array.mapNotNull { element ->
            val event = element as? JsonObject ?: return@mapNotNull null
            val reference = event.primitive("sref") ?: return@mapNotNull null
            val begin = event.primitive("begin_timestamp")?.toLongOrNull() ?: 0L
            val duration = event.primitive("duration_sec")?.toLongOrNull() ?: 0L
            val title = unescape(event.primitive("title").orEmpty()).trim()
            if (begin <= 0L || duration <= 0L || title.isBlank() || title == "N/A") return@mapNotNull null
            NowEvent(
                reference = reference,
                title = title,
                beginEpochSec = begin,
                durationSec = duration,
                eventId = event.primitive("id")?.toIntOrNull() ?: -1,
                alternatives = alternativeRefs(event),
                serviceName = unescape(event.primitive("sname").orEmpty()).trim(),
            )
        }
    }

    /**
     * An EPG answer's root object. Some DVB titles and descriptions carry raw control bytes
     * (character-table and emphasis codes), which some OpenWebif versions pass on unescaped and
     * which make the whole bouquet's answer invalid JSON. Such an answer is read again with those
     * bytes turned into spaces, so one bad programme cannot blank a whole bouquet.
     */
    internal fun epgRoot(body: String): JsonObject? {
        runCatching { openWebifJson.parseToJsonElement(body).jsonObject }.getOrNull()?.let { return it }
        val cleaned = buildString(body.length) {
            for (char in body) append(if (char < ' ' || char in '\u0080'..'\u009f') ' ' else char)
        }
        return runCatching { openWebifJson.parseToJsonElement(cleaned).jsonObject }.getOrNull()
    }

    /** `asrefs` is a list of service references, or one string with several references. */
    private fun alternativeRefs(event: JsonObject): List<String> {
        val element = event["asrefs"] ?: return emptyList()
        val raw = when (element) {
            is JsonArray -> element.mapNotNull { item ->
                when (item) {
                    is JsonPrimitive -> item.contentOrNull
                    is JsonObject -> item.primitive("sref") ?: item.primitive("servicereference")
                    else -> null
                }
            }
            is JsonPrimitive -> listOfNotNull(element.contentOrNull)
            else -> emptyList()
        }
        return raw.flatMap { text -> text.split(Regex("[\\s,]+")) }
            .map { it.trim() }
            .filter { it.count { char -> char == ':' } >= 8 }
    }

    /**
     * Programmes from `/api/epgservice` or `/api/epgmulti`: `id`, `sref`, `title`,
     * `shortdesc`, `longdesc`, `begin_timestamp` and `duration_sec` per event. Events
     * without a reference (older epgservice answers) get [fallbackReference].
     */
    fun parseGuideEvents(body: String, fallbackReference: String = ""): List<GuideEvent> =
        parseGuideEventsOrNull(body, fallbackReference).orEmpty()

    /** [parseGuideEvents], or null when [body] is no JSON object at all (a broken bouquet answer). */
    fun parseGuideEventsOrNull(body: String, fallbackReference: String = ""): List<GuideEvent>? {
        val root = epgRoot(body) ?: return null
        val array = findArray(root, "events") ?: return emptyList()
        return array.mapNotNull { element ->
            val event = element as? JsonObject ?: return@mapNotNull null
            val reference = event.primitive("sref") ?: fallbackReference
            val begin = event.primitive("begin_timestamp")?.toLongOrNull() ?: 0L
            val duration = event.primitive("duration_sec")?.toLongOrNull() ?: 0L
            val title = unescape(event.primitive("title").orEmpty()).trim()
            if (reference.isBlank() || begin <= 0L || duration <= 0L || title.isBlank() || title == "N/A") {
                return@mapNotNull null
            }
            GuideEvent(
                eventId = event.primitive("id")?.toIntOrNull() ?: -1,
                reference = reference,
                title = title,
                shortDescription = unescape(event.primitive("shortdesc").orEmpty()).trim(),
                plot = unescape(event.primitive("longdesc").orEmpty()).trim(),
                beginEpochSec = begin,
                durationSec = duration,
                genreId = event.primitive("genreid")?.toIntOrNull() ?: -1,
                genre = unescape(event.primitive("genre").orEmpty()).trim(),
                serviceName = unescape(event.primitive("sname").orEmpty()).trim(),
            )
        }
    }

    /** `/api/epgsearch`: the same event fields as the guide, plus the channel name in `sname`. */
    fun parseEpgSearch(body: String): List<EpgSearchEvent> {
        val root = epgRoot(body) ?: return emptyList()
        val array = findArray(root, "events") ?: return emptyList()
        return array.mapNotNull { element ->
            val event = element as? JsonObject ?: return@mapNotNull null
            val reference = event.primitive("sref").orEmpty()
            val begin = event.primitive("begin_timestamp")?.toLongOrNull() ?: 0L
            val duration = event.primitive("duration_sec")?.toLongOrNull() ?: 0L
            val title = unescape(event.primitive("title").orEmpty()).trim()
            if (reference.isBlank() || begin <= 0L || duration <= 0L || title.isBlank() || title == "N/A") {
                return@mapNotNull null
            }
            EpgSearchEvent(
                event = GuideEvent(
                    eventId = event.primitive("id")?.toIntOrNull() ?: -1,
                    reference = reference,
                    title = title,
                    shortDescription = unescape(event.primitive("shortdesc").orEmpty()).trim(),
                    plot = unescape(event.primitive("longdesc").orEmpty()).trim(),
                    beginEpochSec = begin,
                    durationSec = duration,
                ),
                channelName = unescape(event.primitive("sname").orEmpty()).trim(),
            )
        }
    }

    /** `/api/timeraddbyeventid`: `{"result": true|false, "message": "…"}`. */
    fun parseTimerResult(body: String): TimerResult {
        val root = runCatching { openWebifJson.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return TimerResult(false, "")
        val result = (root["result"] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull.equals("true", true) } ?: false
        return TimerResult(result, unescape(root.primitive("message").orEmpty()))
    }

    data class ReceiverTimer(
        val reference: String,
        val name: String,
        val begin: Long,
        val end: Long,
        val state: Int,
        val eventId: Int,
        /** Seconds the box adds before/after the programme (begin/end include them). */
        val marginBefore: Long = 0,
        val marginAfter: Long = 0,
        /** A zap-only timer: it switches the box but records nothing. */
        val justPlay: Boolean = false,
    ) {
        val eventBegin: Long get() = begin + marginBefore
        val eventEnd: Long get() = (end - marginAfter).coerceAtLeast(eventBegin)
    }

    data class ReceiverMovie(
        val name: String,
        val filename: String,
        val serviceName: String,
        val begin: Long,
        /** From "length" ("90:12" or "1:30:12"); 0 when unknown. */
        val lengthSec: Long = 0L,
        val description: String = "",
    )

    /** openATV sends seconds (180); some images send minutes (3). Small values are read as minutes. */
    internal fun marginSec(raw: String?): Long {
        val v = raw?.toDoubleOrNull()?.toLong() ?: return 0L
        return if (v in 1..59) v * 60 else v.coerceAtLeast(0)
    }

    fun parseTimers(body: String): List<ReceiverTimer> {
        val root = runCatching { openWebifJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val array = findArray(root, "timers") ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val reference = item.primitive("serviceref") ?: item.primitive("sref") ?: return@mapNotNull null
            ReceiverTimer(
                reference = reference,
                name = unescape(item.primitive("name") ?: item.primitive("title") ?: ""),
                begin = item.primitive("begin")?.toLongOrNull() ?: 0L,
                end = item.primitive("end")?.toLongOrNull() ?: 0L,
                state = item.primitive("state")?.toIntOrNull() ?: 0,
                eventId = item.primitive("eit")?.toIntOrNull() ?: -1,
                marginBefore = marginSec(item.primitive("marginbefore")),
                marginAfter = marginSec(item.primitive("marginafter")),
                justPlay = item.primitive("justplay")?.let { it == "1" || it.equals("true", true) } == true,
            )
        }
    }

    /** "90:12" → 5412 s, "1:30:12" → 5412 s; anything else 0. */
    fun movieLength(text: String?): Long {
        val parts = text?.trim()?.split(':')?.map { it.trim().toLongOrNull() ?: return 0L } ?: return 0L
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0L
        }
    }

    fun parseMovies(body: String): List<ReceiverMovie> {
        val root = runCatching { openWebifJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val array = findArray(root, "movies") ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val filename = item.primitive("filename") ?: return@mapNotNull null
            ReceiverMovie(
                name = unescape(item.primitive("eventname") ?: item.primitive("title") ?: ""),
                filename = filename,
                serviceName = unescape(item.primitive("servicename") ?: ""),
                begin = item.primitive("begintime")?.toLongOrNull() ?: item.primitive("recordingtime")?.toLongOrNull() ?: 0L,
                lengthSec = movieLength(item.primitive("length")),
                description = unescape(item.primitive("description") ?: ""),
            )
        }
    }

    /** Root `/api/getservices`: bouquet reference → number of channels before that bouquet. */
    fun parseBouquetStartPositions(body: String): Map<String, Int> {
        val root = runCatching { openWebifJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyMap()
        val array = findArray(root, "services") ?: return emptyMap()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val reference = item.primitive("servicereference") ?: return@mapNotNull null
            val start = item.primitive("startpos")?.toIntOrNull() ?: return@mapNotNull null
            reference to start
        }.toMap()
    }

    /**
     * Tuner use from `/api/about` (tuners with `live`/`rec`/`stream` texts and the stream
     * clients), `/api/statusinfo` (standby and the service on screen) and, when given,
     * `/api/timerlist` (running recordings, state 2, not zap-only).
     */
    fun parseTunerSnapshot(aboutBody: String, statusBody: String?, timerBody: String?): TunerSnapshot {
        val root = openWebifJson.parseToJsonElement(aboutBody).jsonObject
        val info = root["info"] as? JsonObject ?: root
        val tuners = (info["tuners"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
            val tuner = element as? JsonObject ?: return@mapIndexedNotNull null
            TunerSlot(
                index = index,
                name = tuner.primitive("name").orEmpty(),
                type = tuner.primitive("type").orEmpty(),
                live = tuner.primitive("live").orEmpty(),
                recording = tuner.primitive("rec").orEmpty(),
                stream = tuner.primitive("stream").orEmpty(),
            )
        }
        val streams = (info["streams"] as? JsonArray).orEmpty().mapNotNull { element ->
            val stream = element as? JsonObject ?: return@mapNotNull null
            StreamClient(
                reference = stream.primitive("ref").orEmpty(),
                name = unescape(stream.primitive("name").orEmpty()),
                address = stream.primitive("ip").orEmpty(),
                tunerNumber = stream.primitive("tuner_number")?.toIntOrNull()?.takeIf { it >= 0 },
                transcoding = stream.primitive("type").equals("T", ignoreCase = true),
            )
        }
        val status = statusBody?.let { runCatching { openWebifJson.parseToJsonElement(it).jsonObject }.getOrNull() }
        val standby = status?.primitive("inStandby")?.let { it.equals("true", ignoreCase = true) }
        val live = status?.primitive("currservice_serviceref").orEmpty()
        val recordings = timerBody?.let { body ->
            val timers = runCatching { openWebifJson.parseToJsonElement(body).jsonObject }.getOrNull()
            (timers?.get("timers") as? JsonArray).orEmpty().mapNotNull { element ->
                val timer = element as? JsonObject ?: return@mapNotNull null
                val running = timer.primitive("state")?.toIntOrNull() == 2
                val zapOnly = timer.primitive("justplay")?.let { it == "1" || it.equals("true", true) } == true
                if (running && !zapOnly) timer.primitive("serviceref") else null
            }
        }.orEmpty()
        return TunerSnapshot(
            tuners = tuners,
            streams = streams,
            inStandby = standby,
            liveReference = if (standby == true) "" else live,
            recordingReferences = recordings,
        )
    }

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
                    val first = element.getOrNull(0)?.primitiveText().orEmpty()
                    val second = element.getOrNull(1)?.primitiveText().orEmpty()
                    val referenceFirst = looksLikeReference(first)
                    val name = if (referenceFirst) second else first
                    val reference = if (referenceFirst) first else second
                    if (!looksLikeReference(reference)) null else BouquetEntry(name.ifBlank { reference }, reference)
                }
                is JsonObject -> {
                    val name = text(element, "servicename", "name").orEmpty()
                    val reference = text(element, "servicereference", "reference", "ref").orEmpty()
                    val position = element.primitive("pos")?.toIntOrNull() ?: 0
                    val picon = element.primitive("picon").orEmpty().takeUnless { it.contains("default_picon") }.orEmpty()
                    if (reference.isBlank()) null else BouquetEntry(name, reference, position, picon)
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

    private fun looksLikeReference(value: String): Boolean = value.count { it == ':' } >= 8

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
