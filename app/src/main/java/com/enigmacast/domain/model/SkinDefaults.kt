package com.enigmacast.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One-time move to Glass Earth, the default skin.
 *
 * Settings, saved profile scopes and synced profile documents written before [CURRENT] carry no
 * [MARKER]. When one of them is read, a skin that is EnigmaCast or missing (older versions left
 * EnigmaCast out as the default) becomes Glass Earth; any other skin stays. The marker is then
 * set, and both fields are always written from here on, so EnigmaCast picked again afterwards is
 * kept. Fresh installs and new profiles start with Glass Earth and the marker already set.
 */
object SkinDefaults {
    const val KEY = "osdSkin"
    const val MARKER = "skinDefaults"

    /** 1: EnigmaCast and unsaved skins moved to Glass Earth. */
    const val CURRENT = 1

    fun migrated(element: JsonElement): Boolean =
        element is JsonObject && (element[MARKER]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: 0) >= CURRENT

    /** [element] after the move, when it is an object that has not had it yet. */
    fun migrate(element: JsonElement): JsonElement {
        if (element !is JsonObject || migrated(element)) return element
        val skin = runCatching { element[KEY]?.jsonPrimitive?.content }.getOrNull()
        val next = if (skin == null || skin == OsdSkinId.LEGACY_DEFAULT.name) OsdSkinId.DEFAULT.name else skin
        return JsonObject(element + (KEY to JsonPrimitive(next)) + (MARKER to JsonPrimitive(CURRENT)))
    }

    /** The same, for each value of a map of objects (the saved profile scopes). */
    fun migrateIn(element: JsonElement, mapKey: String): JsonElement {
        if (element !is JsonObject) return element
        val map = element[mapKey] as? JsonObject ?: return element
        return JsonObject(element + (mapKey to JsonObject(map.mapValues { (_, value) -> migrate(value) })))
    }

    /** True when any saved scope in [mapKey] still needs the move, so the store writes it back once. */
    fun pendingIn(json: Json, raw: String, mapKey: String = "scopes"): Boolean = runCatching {
        val map = (json.parseToJsonElement(raw) as? JsonObject)?.get(mapKey) as? JsonObject ?: return false
        map.values.any { !migrated(it) }
    }.getOrDefault(false)

    fun pending(json: Json, raw: String): Boolean = runCatching { !migrated(json.parseToJsonElement(raw)) }.getOrDefault(false)

    /** Decodes stored settings ([AppSettings]) written by any version. */
    fun <T> decodeSettings(json: Json, serializer: KSerializer<T>, raw: String): T =
        json.decodeFromJsonElement(serializer, migrate(json.parseToJsonElement(raw)))

    /** Decodes a stored profile state whose [mapKey] holds the saved scopes. */
    fun <T> decodeScopes(json: Json, serializer: KSerializer<T>, raw: String, mapKey: String = "scopes"): T =
        json.decodeFromJsonElement(serializer, migrateIn(json.parseToJsonElement(raw), mapKey))
}
