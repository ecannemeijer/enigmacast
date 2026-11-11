package com.enigmacast.domain.xtream

import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Xtream [user_info] from player_api.php (no action): expiry, status and connections.
 * Missing or unreadable fields stay empty, so M3U providers and incomplete answers are safe.
 */
@Serializable
data class XtreamAccountInfo(
    val status: String = "",
    val expDateEpochSec: Long? = null,
    val createdAtEpochSec: Long? = null,
    val activeCons: Int? = null,
    val maxConnections: Int? = null,
    val isTrial: Boolean = false,
    val fetchedAtMs: Long = 0,
) {
    fun daysLeft(nowSec: Long): Long? = expDateEpochSec?.let { (it - nowSec) / 86_400L }

    fun isExpired(nowSec: Long): Boolean = expDateEpochSec != null && nowSec >= expDateEpochSec

    /** True when the account expires within [warnDays] days, or has already expired. */
    fun needsWarning(nowSec: Long, warnDays: Int): Boolean {
        if (warnDays <= 0) return false
        val left = daysLeft(nowSec) ?: return false
        return left <= warnDays
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Reads [user_info] from a player_api.php body. Returns null when the body has no
         * useful fields (not an Xtream login answer).
         */
        fun parse(body: String, fetchedAtMs: Long = 0): XtreamAccountInfo? {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
            val user = (root["user_info"] as? JsonObject) ?: root
            val status = text(user, "status")
            val exp = epoch(user, "exp_date")
            val created = epoch(user, "created_at")
            val active = int(user, "active_cons")
            val max = int(user, "max_connections")
            val trial = bool(user, "is_trial")
            if (status.isBlank() && exp == null && created == null && active == null && max == null && !trial) return null
            return XtreamAccountInfo(
                status = status,
                expDateEpochSec = exp,
                createdAtEpochSec = created,
                activeCons = active,
                maxConnections = max,
                isTrial = trial,
                fetchedAtMs = fetchedAtMs,
            )
        }

        private fun text(obj: JsonObject, key: String): String {
            val p = obj[key] as? JsonPrimitive ?: return ""
            val raw = p.contentOrNull?.trim().orEmpty()
            return if (raw.equals("null", true)) "" else raw
        }

        /** Unix seconds; 0 / "null" / blank mean unlimited or unknown. */
        private fun epoch(obj: JsonObject, key: String): Long? {
            val p = obj[key] as? JsonPrimitive ?: return null
            p.longOrNull?.let { return it.takeIf { v -> v > 0L } }
            val raw = p.contentOrNull?.trim().orEmpty()
            if (raw.isEmpty() || raw.equals("null", true) || raw == "0") return null
            return raw.toLongOrNull()?.takeIf { it > 0L }
        }

        private fun int(obj: JsonObject, key: String): Int? {
            val p = obj[key] as? JsonPrimitive ?: return null
            p.intOrNull?.let { return it }
            val raw = p.contentOrNull?.trim().orEmpty()
            if (raw.isEmpty() || raw.equals("null", true)) return null
            return raw.toIntOrNull()
        }

        private fun bool(obj: JsonObject, key: String): Boolean {
            val p = obj[key] as? JsonPrimitive ?: return false
            p.booleanOrNull?.let { return it }
            p.intOrNull?.let { return it != 0 }
            val raw = p.contentOrNull?.trim().orEmpty()
            return raw == "1" || raw.equals("true", true) || raw.equals("yes", true)
        }
    }
}
