package com.enigmacast.data.radio

import com.enigmacast.data.radio.RadioDao
import com.enigmacast.data.radio.RadioStreamResolver
import com.enigmacast.data.radio.ResolvedRadioStream
import com.enigmacast.data.radio.RadioBrowserApi
import com.enigmacast.data.radio.RadioBrowserCategoryDto
import com.enigmacast.data.radio.RadioBrowserStationDto
import com.enigmacast.domain.radio.RadioCategory
import com.enigmacast.domain.radio.RadioStation
import com.enigmacast.domain.radio.toFavorite
import com.enigmacast.domain.radio.toRecent
import com.enigmacast.domain.radio.toStation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RadioBrowserRepository @Inject constructor(
    private val api: RadioBrowserApi,
    private val dao: RadioDao,
    private val streamResolver: RadioStreamResolver
) {
    fun observeFavorites(): Flow<List<RadioStation>> =
        dao.observeFavorites().map { list -> list.map { it.toStation() } }

    fun observeFavoriteIds(): Flow<Set<String>> =
        dao.observeFavoriteIds().map { it.toSet() }

    fun observeRecent(): Flow<List<RadioStation>> =
        combine(dao.observeRecent(RECENT_LIMIT), dao.observeFavoriteIds()) { recents, favoriteIds ->
            val ids = favoriteIds.toSet()
            recents.map { it.toStation(isFavorite = it.stationUuid in ids) }
        }

    suspend fun searchStations(
        name: String? = null,
        country: String? = null,
        countryCode: String? = null,
        tag: String? = null,
        limit: Int = SEARCH_LIMIT
    ): Result<List<RadioStation>> = runCatching {
        withContext(Dispatchers.IO) {
            api.searchStations(
                name = name?.takeIf { it.isNotBlank() },
                country = country?.takeIf { it.isNotBlank() },
                countryCode = countryCode?.takeIf { it.isNotBlank() },
                tag = tag?.takeIf { it.isNotBlank() },
                hideBroken = true,
                limit = limit,
                order = "clickcount",
                reverse = true
            ).toStations()
        }
    }

    suspend fun getPopular(limit: Int = SHELF_LIMIT): Result<List<RadioStation>> = runCatching {
        withContext(Dispatchers.IO) { api.getTopClick(limit).toStations() }
    }

    suspend fun getTopVoted(limit: Int = SHELF_LIMIT): Result<List<RadioStation>> = runCatching {
        withContext(Dispatchers.IO) { api.getTopVote(limit).toStations() }
    }

    suspend fun getLocalStations(countryCode: String, limit: Int = SHELF_LIMIT): Result<List<RadioStation>> =
        searchStations(countryCode = countryCode, limit = limit)

    suspend fun getCountries(): Result<List<RadioCategory>> = runCatching {
        withContext(Dispatchers.IO) {
            api.getCountries(order = "name", hideBroken = true)
                .mapNotNull { it.toCategory() }
                .filter { it.stationCount > 0 }
        }
    }

    suspend fun getTags(): Result<List<RadioCategory>> = runCatching {
        withContext(Dispatchers.IO) {
            api.getTags(order = "stationcount", reverse = true, hideBroken = true, limit = TAG_LIMIT)
                .mapNotNull { it.toCategory() }
                .filter { it.name.isNotBlank() }
        }
    }

    suspend fun resolvePlayUrl(station: RadioStation): Result<ResolvedRadioStream> = runCatching {
        withContext(Dispatchers.IO) {
            val click = runCatching { api.clickStation(station.stationUuid) }.getOrNull()
            val raw = click?.url?.takeIf { it.isNotBlank() }
                ?: station.streamUrl.takeIf { it.isNotBlank() }
                ?: error("No stream URL for ${station.name}")
            streamResolver.resolve(
                url = raw,
                codec = station.codec,
                hls = station.hls || station.codec.equals("HLS", ignoreCase = true)
            )
        }
    }

    suspend fun toggleFavorite(station: RadioStation) = withContext(Dispatchers.IO) {
        if (dao.isFavorite(station.stationUuid)) {
            dao.deleteFavorite(station.stationUuid)
        } else {
            dao.upsertFavorite(station.toFavorite())
        }
    }

    suspend fun markRecent(station: RadioStation) = withContext(Dispatchers.IO) {
        dao.upsertRecent(station.toRecent())
        val ids = dao.getRecentIdsOrdered()
        if (ids.size > RECENT_LIMIT) {
            dao.deleteRecentByIds(ids.drop(RECENT_LIMIT))
        }
    }

    private fun List<RadioBrowserStationDto>.toStations(): List<RadioStation> =
        mapNotNull { it.toStation() }

    private fun RadioBrowserStationDto.toStation(): RadioStation? {
        val uuid = stationUuid?.trim().orEmpty()
        val stationName = name?.trim().orEmpty()
        if (uuid.isBlank() || stationName.isBlank()) return null
        val resolved = urlResolved?.trim().orEmpty().ifBlank { url?.trim().orEmpty() }
        return RadioStation(
            stationUuid = uuid,
            name = stationName,
            streamUrl = resolved,
            homepage = homepage?.trim()?.takeIf { it.isNotBlank() },
            favicon = favicon?.trim()?.takeIf { it.isNotBlank() },
            tags = tags.orEmpty(),
            country = country.orEmpty(),
            countryCode = countryCode.orEmpty(),
            codec = codec.orEmpty(),
            bitrate = bitrate ?: 0,
            votes = votes ?: 0,
            clickCount = clickCount ?: 0,
            hls = (hls ?: 0) == 1 || "HLS".equals(codec, ignoreCase = true)
        )
    }

    private fun RadioBrowserCategoryDto.toCategory(): RadioCategory? {
        val label = name?.trim().orEmpty()
        if (label.isBlank()) return null
        return RadioCategory(
            name = label,
            code = iso3166?.trim()?.takeIf { it.isNotBlank() },
            stationCount = stationCount
        )
    }

    companion object {
        private const val SEARCH_LIMIT = 60
        private const val SHELF_LIMIT = 20
        private const val TAG_LIMIT = 80
        private const val RECENT_LIMIT = 20
    }
}
