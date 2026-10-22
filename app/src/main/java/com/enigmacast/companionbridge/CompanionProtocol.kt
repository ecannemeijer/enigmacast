package com.enigmacast.companionbridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.encodeToString

/**
 * Het Companion-protocol v1, TV-kant. Houd dit exact gelijk aan
 * `shared/core-protocol` in de Companion-repo en aan `protocol/v1`.
 */
@Serializable
data class CompanionEnvelope(
    val v: Int = 1,
    val id: String? = null,
    val seq: Long? = null,
    val type: String,
    val ts: Long? = null,
    val payload: JsonElement? = null,
)

object CompanionProtocol {
    const val VERSION = 1
    const val PORT = 8750
    const val WS_PATH = "/ws"
    const val HEALTH_PATH = "/api/v1/health"
    const val PAIR_PATH = "/api/v1/pair"
    const val INFO_PATH = "/api/v1/info"

    const val PAIR_CODE_TTL_MS = 120_000L
    const val AUTH_SKEW_MS = 120_000L
    const val MAX_FRAME_BYTES = 64 * 1024
    const val MAX_BODY_BYTES = 64 * 1024
    const val MAX_PHOTO_BYTES = 10 * 1024 * 1024

    const val TYPE_HELLO = "hello"
    const val TYPE_ACK = "ack"
    const val TYPE_PONG = "pong"
    const val TYPE_ERROR = "error"
    const val TYPE_PING = "ping"
    const val TYPE_STATE_PLAYER = "state.player"
    const val TYPE_STATE_PROFILES = "state.profiles"
    const val TYPE_STATE_SETTINGS = "state.settings"
    const val TYPE_STATE_LIBRARY = "state.library"
    const val TYPE_STATE_RECENT = "state.recent"
    const val TYPE_STATE_IPTV = "state.iptv"
    const val TYPE_FORM = "form"
    const val TYPE_TOAST = "toast"
    const val TYPE_CMD_KEY = "cmd.key"
    const val TYPE_CMD_NAV = "cmd.nav"
    const val TYPE_CMD_ZAP = "cmd.zap"
    const val TYPE_CMD_VOLUME = "cmd.volume"
    const val TYPE_CMD_PLAYER = "cmd.player"
    const val TYPE_CMD_FORM = "cmd.form"
    const val TYPE_CMD_SETTINGS = "cmd.settings"
    const val TYPE_CMD_PROFILE = "cmd.profile"
    const val TYPE_CMD_LIBRARY = "cmd.library"
    const val TYPE_CMD_RECENT = "cmd.recent"
    const val TYPE_CMD_POWER = "cmd.power"
    const val TYPE_CMD_SYNC = "cmd.sync"

    const val ERR_PAIR_CODE = "PAIR_CODE_INVALID"
    const val ERR_PAIR_RATE = "PAIR_RATE_LIMITED"
    const val ERR_AUTH_MISSING = "AUTH_MISSING"
    const val ERR_AUTH_REPLAY = "AUTH_REPLAY"
    const val ERR_AUTH_SKEW = "AUTH_SKEW"
    const val ERR_AUTH_SIGNATURE = "AUTH_SIGNATURE"
    const val ERR_DEVICE_REVOKED = "DEVICE_REVOKED"
    const val ERR_APP_NOT_FOREGROUND = "APP_NOT_FOREGROUND"
    const val ERR_SETTINGS_CONFLICT = "SETTINGS_CONFLICT"
    const val ERR_SETTINGS_LOCKED = "SETTINGS_LOCKED"
    const val ERR_VALIDATION = "VALIDATION"
    const val ERR_NOT_FOUND = "NOT_FOUND"
    const val ERR_PAYLOAD = "PAYLOAD_TOO_LARGE"
    const val ERR_INTERNAL = "INTERNAL"

    val JSON: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    val CAPABILITIES = listOf(
        "key", "nav", "zap", "volume", "player", "text.form", "settings", "photo", "profile", "library", "history", "iptv", "state", "power",
    )

    data class CompanionProfile(
        val id: String,
        val name: String,
        val avatar: Int,
        val photo: Long,
        val hasPin: Boolean,
    )

    fun encode(envelope: CompanionEnvelope): String = JSON.encodeToString(CompanionEnvelope.serializer(), envelope)

    fun decode(text: String): CompanionEnvelope? =
        runCatching { JSON.decodeFromString(CompanionEnvelope.serializer(), text) }.getOrNull()

    fun envelope(type: String, payload: JsonElement?, id: String? = null, seq: Long? = null): CompanionEnvelope =
        CompanionEnvelope(
            v = VERSION,
            id = id,
            seq = seq,
            type = type,
            ts = System.currentTimeMillis(),
            payload = payload,
        )

    fun ackPayload(latencyMs: Long): JsonElement = buildJsonObject {
        put("ok", true)
        put("latencyMs", latencyMs)
    }

    fun errorPayload(code: String, message: String, retryable: Boolean = false): JsonElement = buildJsonObject {
        put("code", code)
        put("message", message)
        put("retryable", retryable)
    }

    fun toastPayload(message: String, level: String = "info"): JsonElement = buildJsonObject {
        put("level", level)
        put("message", message)
    }

    fun tvJson(id: String, name: String, app: String): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("app", app)
    }

    fun profilesJson(activeId: String, profiles: List<CompanionProfile>): JsonObject = buildJsonObject {
        put("activeId", activeId)
        putJsonArray("profiles") {
            profiles.forEach { profile ->
                add(
                    buildJsonObject {
                        put("id", profile.id)
                        put("name", profile.name)
                        put("avatar", profile.avatar)
                        put("photoStamp", profile.photo)
                        put("hasPin", profile.hasPin)
                    },
                )
            }
        }
    }
}
