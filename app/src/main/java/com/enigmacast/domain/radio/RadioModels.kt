package com.enigmacast.domain.radio

import androidx.room.Entity
import androidx.room.PrimaryKey

data class RadioStation(
    val stationUuid: String,
    val name: String,
    val streamUrl: String,
    val homepage: String? = null,
    val favicon: String? = null,
    val tags: String = "",
    val country: String = "",
    val countryCode: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val votes: Int = 0,
    val clickCount: Int = 0,
    val hls: Boolean = false,
    val isFavorite: Boolean = false
) {
    val tagList: List<String>
        get() = tags.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    val metaLine: String
        get() = buildList {
            country.takeIf { it.isNotBlank() }?.let(::add)
            codec.takeIf { it.isNotBlank() }?.let(::add)
            if (bitrate > 0) add("${bitrate} kbps")
        }.joinToString(" · ")
}

data class RadioCategory(
    val name: String,
    val code: String? = null,
    val stationCount: Int = 0
)

@Entity(tableName = "radio_station_favorites")
data class RadioStationFavorite(
    @PrimaryKey val stationUuid: String,
    val name: String,
    val streamUrl: String,
    val homepage: String? = null,
    val favicon: String? = null,
    val tags: String = "",
    val country: String = "",
    val countryCode: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "radio_station_recent")
data class RadioStationRecent(
    @PrimaryKey val stationUuid: String,
    val name: String,
    val streamUrl: String,
    val homepage: String? = null,
    val favicon: String? = null,
    val tags: String = "",
    val country: String = "",
    val countryCode: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val playedAt: Long = System.currentTimeMillis()
)

fun RadioStationFavorite.toStation(): RadioStation = RadioStation(
    stationUuid = stationUuid,
    name = name,
    streamUrl = streamUrl,
    homepage = homepage,
    favicon = favicon,
    tags = tags,
    country = country,
    countryCode = countryCode,
    codec = codec,
    bitrate = bitrate,
    isFavorite = true
)

fun RadioStationRecent.toStation(isFavorite: Boolean): RadioStation = RadioStation(
    stationUuid = stationUuid,
    name = name,
    streamUrl = streamUrl,
    homepage = homepage,
    favicon = favicon,
    tags = tags,
    country = country,
    countryCode = countryCode,
    codec = codec,
    bitrate = bitrate,
    isFavorite = isFavorite
)

fun RadioStation.toFavorite(addedAt: Long = System.currentTimeMillis()): RadioStationFavorite =
    RadioStationFavorite(
        stationUuid = stationUuid,
        name = name,
        streamUrl = streamUrl,
        homepage = homepage,
        favicon = favicon,
        tags = tags,
        country = country,
        countryCode = countryCode,
        codec = codec,
        bitrate = bitrate,
        addedAt = addedAt
    )

fun RadioStation.toRecent(playedAt: Long = System.currentTimeMillis()): RadioStationRecent =
    RadioStationRecent(
        stationUuid = stationUuid,
        name = name,
        streamUrl = streamUrl,
        homepage = homepage,
        favicon = favicon,
        tags = tags,
        country = country,
        countryCode = countryCode,
        codec = codec,
        bitrate = bitrate,
        playedAt = playedAt
    )
