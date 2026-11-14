package com.enigmacast.data.radio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class RadioBrowserApi @Inject constructor(
    @Named("radioBrowser") private val client: OkHttpClient,
    @Named("radioJson") private val json: Json,
) {
    suspend fun searchStations(
        name: String? = null,
        country: String? = null,
        countryCode: String? = null,
        tag: String? = null,
        hideBroken: Boolean = true,
        limit: Int = 50,
        order: String = "clickcount",
        reverse: Boolean = true,
    ): List<RadioBrowserStationDto> = get(
        "json/stations/search",
        listOf(
            "name" to name,
            "country" to country,
            "countrycode" to countryCode,
            "tag" to tag,
            "hidebroken" to hideBroken.toRadioFlag(),
            "limit" to limit.toString(),
            "order" to order,
            "reverse" to reverse.toRadioFlag(),
        ),
    )

    suspend fun getTopClick(limit: Int, hideBroken: Boolean = true): List<RadioBrowserStationDto> =
        get("json/stations/topclick/$limit", listOf("hidebroken" to hideBroken.toRadioFlag()))

    suspend fun getTopVote(limit: Int, hideBroken: Boolean = true): List<RadioBrowserStationDto> =
        get("json/stations/topvote/$limit", listOf("hidebroken" to hideBroken.toRadioFlag()))

    suspend fun getCountries(order: String = "name", hideBroken: Boolean = true): List<RadioBrowserCategoryDto> =
        get("json/countries", listOf("order" to order, "hidebroken" to hideBroken.toRadioFlag()))

    suspend fun getTags(
        order: String = "stationcount",
        reverse: Boolean = true,
        hideBroken: Boolean = true,
        limit: Int = 80,
    ): List<RadioBrowserCategoryDto> = get(
        "json/tags",
        listOf(
            "order" to order,
            "reverse" to reverse.toRadioFlag(),
            "hidebroken" to hideBroken.toRadioFlag(),
            "limit" to limit.toString(),
        ),
    )

    suspend fun clickStation(stationUuid: String): RadioBrowserClickResult =
        get("json/url/$stationUuid", emptyList())

    private suspend inline fun <reified T> get(path: String, query: List<Pair<String, String?>>): T =
        withContext(Dispatchers.IO) {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host(RadioBrowserServerSelector.FALLBACK_HOST)
                .addPathSegments(path)
                .apply {
                    query.forEach { (key, value) ->
                        if (!value.isNullOrBlank()) addQueryParameter(key, value)
                    }
                }
                .build()
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("Radio Browser HTTP ${response.code}")
                }
                json.decodeFromString<T>(body)
            }
        }
}

private fun Boolean.toRadioFlag(): String = if (this) "true" else "false"
