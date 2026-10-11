package com.enigmacast.domain.oscam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/** One Oscam reader (`type` `r`) or proxy (`type` `p`) from `oscamapi.json?part=status`. */
data class OscamReader(
    val name: String,
    val protocol: String,
    val connection: String,
    val channel: String = "",
    val ecmMs: String = "",
)

/** A connected client (`type` `c`), usually the receiver's dvbapi user. */
data class OscamUser(
    val name: String,
    val channel: String = "",
    val answeredBy: String = "",
    val ecmMs: String = "",
)

/** Version, readers and users from one status document. */
data class OscamServer(
    val version: String = "",
    val uptime: String = "",
    val readers: List<OscamReader> = emptyList(),
    val users: List<OscamUser> = emptyList(),
)

/** New lines from `logpoll.html`. [lastId] is absent when the body did not carry one. */
data class OscamLogPage(
    val lastId: Long?,
    val lines: List<String>,
    val debug: Int? = null,
)

object OscamStatus {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val readerTypes = setOf("r", "p")

    fun readers(body: String): List<OscamReader> = snapshot(body).readers

    fun snapshot(body: String): OscamServer {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return OscamServer()
        val oscam = root["oscam"] as? JsonObject
        val status = (root["status"] as? JsonObject) ?: (oscam?.get("status") as? JsonObject) ?: return OscamServer(
            version = oscam.textOrEmpty("version"),
            uptime = oscam.textOrEmpty("uptime"),
        )
        val clients = when (val client = status["client"]) {
            is JsonArray -> client.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(client)
            else -> emptyList()
        }
        val readers = mutableListOf<OscamReader>()
        val users = mutableListOf<OscamUser>()
        for (client in clients) {
            val type = client.text("type")?.lowercase().orEmpty()
            when (type) {
                in readerTypes -> {
                    val name = client.text("name") ?: client.text("rname_enc") ?: client.text("name_enc") ?: continue
                    readers += OscamReader(
                        name = name,
                        protocol = client.text("protocol").orEmpty(),
                        connection = connection(client),
                        channel = client.childText("request", "chname"),
                        ecmMs = client.childText("request", "ecmtime").ifBlank { client.childText("request", "msvalue") },
                    )
                }
                "c" -> {
                    val name = client.text("name") ?: client.text("name_enc") ?: continue
                    users += OscamUser(
                        name = name,
                        channel = client.childText("request", "chname"),
                        answeredBy = client.childText("request", "answered"),
                        ecmMs = client.childText("request", "ecmtime").ifBlank { client.childText("request", "msvalue") },
                    )
                }
            }
        }
        return OscamServer(
            version = oscam.textOrEmpty("version"),
            uptime = oscam.textOrEmpty("uptime"),
            readers = readers,
            users = users,
        )
    }

    /**
     * Oscam 2.26 puts the live log inside `oscam.lines`. Each `line` is base64.
     * Older builds put plain strings in a top-level `lines` array and a `lastid` field.
     * The next poll wants the highest id: `lastid` is exclusive.
     */
    fun logPage(body: String): OscamLogPage {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return OscamLogPage(lastId = null, lines = emptyList())
        val oscam = root["oscam"] as? JsonObject
        val declared = (root["lastid"] as? JsonPrimitive)?.longOrNull ?: root.text("lastid")?.toLongOrNull()
        val debug = oscam?.text("debug")?.toIntOrNull() ?: root.text("debug")?.toIntOrNull()
        val raw = (root["lines"] as? JsonArray) ?: (oscam?.get("lines") as? JsonArray)
            ?: return OscamLogPage(lastId = declared, lines = emptyList(), debug = debug)
        val entries = raw.mapNotNull { line ->
            when (line) {
                is JsonPrimitive -> LogEntry(null, line.contentOrNull)
                is JsonObject -> LogEntry(
                    id = line.text("id")?.toLongOrNull(),
                    text = line.text("line") ?: line.text("text"),
                )
                else -> null
            }
        }
        val lines = entries.mapNotNull { entry ->
            entry.text?.let(::decodeLogLine)?.trimEnd()?.takeIf { it.isNotEmpty() }
        }
        val lastId = listOfNotNull(declared, entries.mapNotNull { it.id }.maxOrNull()).maxOrNull()
        return OscamLogPage(lastId = lastId, lines = lines, debug = debug)
    }

    private data class LogEntry(val id: Long?, val text: String?)

    /** Oscam 2.26 stores the log text as base64. A plain line is left as it is. */
    private fun decodeLogLine(raw: String): String {
        val text = raw.trim()
        if (text.length < 8 || text.length % 4 != 0) return text
        if (!text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) return text
        val decoded = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return text
        val utf = decoded.toString(Charsets.UTF_8)
        if (utf.any { it.code < 9 || it.code in 14..31 }) return text
        return utf
    }

    private fun connection(client: JsonObject): String = when (val value = client["connection"]) {
        is JsonPrimitive -> value.contentOrNull?.trim().orEmpty()
        is JsonObject -> value.text("status") ?: value.text("ip").orEmpty()
        else -> ""
    }

    private fun JsonObject.text(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JsonObject?.textOrEmpty(key: String): String = this?.text(key).orEmpty()

    private fun JsonObject.childText(objectKey: String, key: String): String =
        (this[objectKey] as? JsonObject)?.text(key).orEmpty()
}
