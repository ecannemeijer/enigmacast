package com.enigmacast.data.radio

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.enigmacast.domain.radio.RadioStationFavorite
import com.enigmacast.domain.radio.RadioStationRecent
import kotlinx.coroutines.flow.Flow

@Dao
interface RadioDao {
    @Query("SELECT * FROM radio_station_favorites ORDER BY addedAt DESC")
    fun observeFavorites(): Flow<List<RadioStationFavorite>>

    @Query("SELECT stationUuid FROM radio_station_favorites")
    fun observeFavoriteIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(item: RadioStationFavorite)

    @Query("DELETE FROM radio_station_favorites WHERE stationUuid = :stationUuid")
    suspend fun deleteFavorite(stationUuid: String)

    @Query("SELECT EXISTS(SELECT 1 FROM radio_station_favorites WHERE stationUuid = :stationUuid)")
    suspend fun isFavorite(stationUuid: String): Boolean

    @Query("SELECT * FROM radio_station_recent ORDER BY playedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<RadioStationRecent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecent(item: RadioStationRecent)

    @Query("SELECT stationUuid FROM radio_station_recent ORDER BY playedAt DESC")
    suspend fun getRecentIdsOrdered(): List<String>

    @Query("DELETE FROM radio_station_recent WHERE stationUuid IN (:ids)")
    suspend fun deleteRecentByIds(ids: List<String>)
}
