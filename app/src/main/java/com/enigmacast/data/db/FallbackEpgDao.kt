package com.enigmacast.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface FallbackEpgDao {
    @Query("SELECT * FROM fallback_epg_meta WHERE country = :country")
    suspend fun meta(country: String): FallbackEpgMetaRow?

    @Query("SELECT * FROM fallback_epg_meta")
    suspend fun allMeta(): List<FallbackEpgMetaRow>

    @Query("SELECT * FROM fallback_epg_channel")
    suspend fun channels(): List<FallbackEpgChannelRow>

    @Query("SELECT xmltvId FROM fallback_epg_channel WHERE country = :country")
    suspend fun idsForCountry(country: String): List<String>

    @Query("SELECT * FROM fallback_epg_programme WHERE xmltvId IN (:ids) AND endSec > :fromSec AND beginSec < :toSec")
    suspend fun programmes(ids: List<String>, fromSec: Long, toSec: Long): List<FallbackEpgProgrammeRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(row: FallbackEpgMetaRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(rows: List<FallbackEpgChannelRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgrammes(rows: List<FallbackEpgProgrammeRow>)

    @Query("DELETE FROM fallback_epg_programme WHERE xmltvId IN (:ids)")
    suspend fun deleteProgrammes(ids: List<String>)

    @Query("DELETE FROM fallback_epg_channel WHERE country = :country")
    suspend fun deleteChannels(country: String)

    @Query("DELETE FROM fallback_epg_meta")
    suspend fun clearMeta()

    @Query("DELETE FROM fallback_epg_channel")
    suspend fun clearChannels()

    @Query("DELETE FROM fallback_epg_programme")
    suspend fun clearProgrammes()

    @Transaction
    suspend fun clearAll() {
        clearProgrammes()
        clearChannels()
        clearMeta()
    }

    @Transaction
    suspend fun replaceCountry(
        country: String,
        channels: List<FallbackEpgChannelRow>,
        programmes: List<FallbackEpgProgrammeRow>,
        fetchedAtMs: Long,
    ) {
        val old = idsForCountry(country)
        old.chunked(400).forEach { deleteProgrammes(it) }
        deleteChannels(country)
        channels.chunked(400).forEach { insertChannels(it) }
        programmes.chunked(400).forEach { insertProgrammes(it) }
        putMeta(FallbackEpgMetaRow(country, fetchedAtMs, programmes.size))
    }
}
