package com.enigmacast.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** One football-data.org match, stored as its JSON object; [competition] and [kickoffSec] for queries. */
@Entity(tableName = "soccer_matches", primaryKeys = ["id"], indices = [Index("competition", "kickoffSec")])
data class SoccerMatchRow(
    val id: Int,
    val competition: String,
    val kickoffSec: Long,
    val status: String,
    val json: String,
)

/** The last standings answer of a competition. */
@Entity(tableName = "soccer_standings", primaryKeys = ["competition"])
data class SoccerStandingsRow(val competition: String, val json: String, val fetchedAtMs: Long)

/** When something was last fetched ("matches|DED", "competitions", "h2h|558876"), with the answer for small ones. */
@Entity(tableName = "soccer_fetches", primaryKeys = ["key"])
data class SoccerFetchRow(val key: String, val fetchedAtMs: Long, val body: String = "", val error: String = "")

@Dao
interface SoccerDao {
    @Query("SELECT * FROM soccer_matches WHERE competition IN (:codes) AND kickoffSec >= :fromSec AND kickoffSec < :toSec ORDER BY kickoffSec")
    fun observeMatches(codes: List<String>, fromSec: Long, toSec: Long): Flow<List<SoccerMatchRow>>

    @Query("SELECT * FROM soccer_matches WHERE competition IN (:codes) AND kickoffSec >= :fromSec AND kickoffSec < :toSec ORDER BY kickoffSec")
    suspend fun matches(codes: List<String>, fromSec: Long, toSec: Long): List<SoccerMatchRow>

    @Query("SELECT * FROM soccer_standings WHERE competition IN (:codes)")
    fun observeStandings(codes: List<String>): Flow<List<SoccerStandingsRow>>

    @Query("SELECT * FROM soccer_fetches WHERE `key` = :key")
    suspend fun fetch(key: String): SoccerFetchRow?

    @Query("SELECT * FROM soccer_fetches WHERE `key` IN (:keys)")
    fun observeFetches(keys: List<String>): Flow<List<SoccerFetchRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFetch(row: SoccerFetchRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putStandings(row: SoccerStandingsRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMatches(rows: List<SoccerMatchRow>)

    @Query("DELETE FROM soccer_matches WHERE competition = :code AND kickoffSec >= :fromSec AND kickoffSec < :toSec")
    suspend fun deleteMatches(code: String, fromSec: Long, toSec: Long)

    @Query("DELETE FROM soccer_matches WHERE kickoffSec < :beforeSec")
    suspend fun purgeMatches(beforeSec: Long)

    /** A fetched window replaces what was stored for it (so moved or removed fixtures disappear). */
    @Transaction
    suspend fun replaceMatches(code: String, fromSec: Long, toSec: Long, rows: List<SoccerMatchRow>) {
        deleteMatches(code, fromSec, toSec)
        putMatches(rows)
    }
}
