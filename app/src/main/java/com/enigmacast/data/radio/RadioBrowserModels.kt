package com.enigmacast.data.radio

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

@Serializable
data class RadioBrowserStationDto(
    @SerialName("stationuuid") val stationUuid: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("url") val url: String? = null,
    @SerialName("url_resolved") val urlResolved: String? = null,
    @SerialName("homepage") val homepage: String? = null,
    @SerialName("favicon") val favicon: String? = null,
    @SerialName("tags") val tags: String? = null,
    @SerialName("country") val country: String? = null,
    @SerialName("countrycode") val countryCode: String? = null,
    @SerialName("codec") val codec: String? = null,
    @SerialName("bitrate") val bitrate: Int? = null,
    @SerialName("votes") val votes: Int? = null,
    @SerialName("clickcount") val clickCount: Int? = null,
    @SerialName("hls") val hls: Int? = null,
)

@Serializable
data class RadioBrowserCategoryDto(
    @SerialName("name") val name: String? = null,
    @SerialName("iso_3166_1") val iso3166: String? = null,
    @SerialName("stationcount") val stationCountRaw: JsonElement? = null,
) {
    val stationCount: Int
        get() = stationCountRaw.flexibleInt()
}

@Serializable
data class RadioBrowserClickResult(
    @SerialName("ok") val ok: JsonElement? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("stationuuid") val stationUuid: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("url") val url: String? = null,
) {
    val isOk: Boolean
        get() {
            val value = ok ?: return !url.isNullOrBlank()
            if (value !is JsonPrimitive) return !url.isNullOrBlank()
            return value.booleanOrNull
                ?: value.intOrNull?.let { it != 0 }
                ?: value.contentOrNull.equals("true", ignoreCase = true)
                ?: !url.isNullOrBlank()
        }
}

private fun JsonElement?.flexibleInt(): Int {
    if (this == null) return 0
    if (this !is JsonPrimitive) return 0
    return intOrNull ?: contentOrNull?.toIntOrNull() ?: 0
}
