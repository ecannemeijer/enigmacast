package com.enigmacast.domain.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Glass Earth is the default skin for fresh installs and new profiles, without changing the look of
 * an existing install. Versions before it wrote settings and profile scopes without the skin when it
 * was EnigmaCast (defaults were left out), so "no skin stored" there means EnigmaCast — whether the
 * user picked it or never opened the skin chooser cannot be told apart, and both keep it. Every
 * write now includes the skin, so only those older documents lack it.
 */
object SkinDefaults {
    const val KEY = "osdSkin"

    /** [element] with the legacy default filled in when it is an object without a skin. */
    fun pinLegacy(element: JsonElement): JsonElement {
        if (element !is JsonObject || KEY in element) return element
        return JsonObject(element + (KEY to JsonPrimitive(OsdSkinId.LEGACY_DEFAULT.name)))
    }

    /** The same, for each value of a map of objects (the saved profile scopes). */
    fun pinLegacyIn(element: JsonElement, mapKey: String): JsonElement {
        if (element !is JsonObject) return element
        val map = element[mapKey] as? JsonObject ?: return element
        return JsonObject(element + (mapKey to JsonObject(map.mapValues { (_, value) -> pinLegacy(value) })))
    }

    /** Decodes stored settings ([AppSettings]) written by any version. */
    fun <T> decodeSettings(json: Json, serializer: KSerializer<T>, raw: String): T =
        json.decodeFromJsonElement(serializer, pinLegacy(json.parseToJsonElement(raw)))

    /** Decodes a stored profile state whose [mapKey] holds the saved scopes. */
    fun <T> decodeScopes(json: Json, serializer: KSerializer<T>, raw: String, mapKey: String = "scopes"): T =
        json.decodeFromJsonElement(serializer, pinLegacyIn(json.parseToJsonElement(raw), mapKey))
}
