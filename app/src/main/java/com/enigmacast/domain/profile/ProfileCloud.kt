package com.enigmacast.domain.profile

import com.enigmacast.domain.model.SkinDefaults
import com.enigmacast.domain.model.StartupLive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Home bouquet picks that belong to one profile. [all] lists receivers explicitly set back to every bouquet. */
@Serializable
data class HomeBouquetCloud(
    val selected: Map<String, List<String>> = emptyMap(),
    val all: List<String> = emptyList(),
)

/**
 * Everything a profile keeps for itself: the live profile settings, its language, Home bouquets
 * and hidden IPTV categories. Receivers and the IPTV login are not in here.
 */
@Serializable
data class ProfileSettingsSnapshot(
    val scope: ProfileScope = ProfileScope(),
    val language: String? = null,
    val homeBouquets: Map<String, List<String>> = emptyMap(),
    val homeAll: List<String> = emptyList(),
    val hiddenCategories: Map<String, List<String>> = emptyMap(),
) {
    fun normalized(): ProfileSettingsSnapshot = copy(
        scope = scope.copy(
            lastChannels = scope.lastChannels.toSortedMap(),
            recentByReceiver = scope.recentByReceiver.mapValues { (_, channels) -> channels }.toSortedMap(),
            lastIptvChannels = scope.lastIptvChannels.toSortedMap(),
            pictureModes = scope.pictureModes.toSortedMap(),
        ),
        homeBouquets = homeBouquets.mapValues { (_, ids) -> ids.sorted() }.toSortedMap(),
        homeAll = homeAll.distinct().sorted(),
        hiddenCategories = hiddenCategories.mapValues { (_, ids) -> ids.sorted() }.toSortedMap(),
    )
}

/** JSON for the sync server, and the rule for a device that has never edited a profile. */
object ProfileCloud {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun encode(snapshot: ProfileSettingsSnapshot): String = json.encodeToString(snapshot.normalized())

    /** A server document from before the move to Glass Earth gets it once, like local settings ([SkinDefaults]). */
    fun decode(raw: String): ProfileSettingsSnapshot =
        json.decodeFromJsonElement(ProfileSettingsSnapshot.serializer(), pinScopeSkin(json.parseToJsonElement(raw))).normalized()

    private fun pinScopeSkin(element: JsonElement): JsonElement {
        val scope = (element as? JsonObject)?.get("scope") ?: return element
        return JsonObject(element + ("scope" to SkinDefaults.migrate(scope)))
    }

    /** Defaults only: do not upload them over a profile another device already filled in. */
    fun untouched(snapshot: ProfileSettingsSnapshot): Boolean = snapshot.normalized() == ProfileSettingsSnapshot().normalized()

    /** Old server documents have no startup choice. Missing means "leave the value already stored". */
    fun includesStartupLive(raw: String): Boolean = runCatching {
        val scope = json.parseToJsonElement(raw).jsonObject["scope"]?.jsonObject ?: return false
        "startupLive" in scope
    }.getOrDefault(false)

    fun keepStartupWhenMissing(snapshot: ProfileSettingsSnapshot, raw: String, keep: StartupLive): ProfileSettingsSnapshot {
        if (includesStartupLive(raw) || snapshot.scope.startupLive == keep) return snapshot
        return snapshot.copy(scope = snapshot.scope.copy(startupLive = keep))
    }
}
