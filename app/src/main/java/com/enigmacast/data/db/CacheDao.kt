package com.enigmacast.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CacheDao {
    // Bouquets and channels: replaced as a whole per receiver.
    @Query("DELETE FROM bouquets WHERE receiverKey = :receiverKey")
    suspend fun deleteBouquets(receiverKey: String)

    @Query("DELETE FROM channels WHERE receiverKey = :receiverKey")
    suspend fun deleteChannels(receiverKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBouquets(rows: List<BouquetRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(rows: List<ChannelRow>)

    @Transaction
    suspend fun replaceChannels(receiverKey: String, bouquets: List<BouquetRow>, channels: List<ChannelRow>) {
        deleteBouquets(receiverKey)
        deleteChannels(receiverKey)
        insertBouquets(bouquets)
        channels.chunked(500).forEach { insertChannels(it) }
    }

    /**
     * A sync's channel lists: everything of this receiver is replaced at once (removed, renamed
     * and reordered bouquets included), its hash stored, and the guide marked due again.
     */
    @Transaction
    suspend fun replaceChannelLists(receiverKey: String, bouquets: List<BouquetRow>, channels: List<ChannelRow>, state: SyncStateRow) {
        replaceChannels(receiverKey, bouquets, channels)
        putSyncState(state)
        expireSyncState(receiverKey, listOf("EPG", "FILMS"))
    }

    @Query("UPDATE sync_state SET lastSuccessMs = 0 WHERE receiverKey = :receiverKey AND kind IN (:kinds)")
    suspend fun expireSyncState(receiverKey: String, kinds: List<String>)

    /** Copies the guide of one receiver onto another (same channel lists after a switch). */
    @Query(
        "INSERT OR REPLACE INTO epg_events SELECT :toKey, serviceKey, reference, beginSec, endSec, eventId, title, " +
            "shortDescription, longDescription, genreId, genre FROM epg_events WHERE receiverKey = :fromKey",
    )
    suspend fun copyEvents(fromKey: String, toKey: String)

    @Query("SELECT * FROM bouquets WHERE receiverKey = :receiverKey ORDER BY position")
    suspend fun bouquets(receiverKey: String): List<BouquetRow>

    @Query("SELECT * FROM channels WHERE receiverKey = :receiverKey ORDER BY position")
    suspend fun channels(receiverKey: String): List<ChannelRow>

    @Query("SELECT COUNT(*) FROM channels WHERE receiverKey = :receiverKey")
    suspend fun channelCount(receiverKey: String): Int

    // EPG
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(rows: List<EpgRow>)

    // IPTV EPG from XMLTV
    @Query("DELETE FROM iptv_epg WHERE receiverKey = :receiverKey")
    suspend fun deleteIptvEpg(receiverKey: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIptvEpg(rows: List<IptvEpgRow>)

    @Query("SELECT * FROM iptv_epg WHERE receiverKey = :receiverKey AND endSec > :fromSec AND beginSec < :toSec")
    suspend fun iptvEpg(receiverKey: String, fromSec: Long, toSec: Long): List<IptvEpgRow>

    /** Replaces this receiver's XMLTV programmes and puts them in the guide cache right away. */
    @Transaction
    suspend fun replaceIptvEpg(receiverKey: String, rows: List<IptvEpgRow>, guide: List<EpgRow>) {
        deleteIptvEpg(receiverKey)
        rows.chunked(500).forEach { insertIptvEpg(it) }
        guide.chunked(500).forEach { insertEvents(it) }
    }

    @Query("DELETE FROM epg_events WHERE receiverKey = :receiverKey AND beginSec >= :fromSec AND beginSec < :toSec")
    suspend fun deleteEvents(receiverKey: String, fromSec: Long, toSec: Long)

    /** Replaces the window [fromSec, toSec) in one transaction, in batches. */
    @Transaction
    suspend fun replaceEvents(receiverKey: String, fromSec: Long, toSec: Long, rows: List<EpgRow>) {
        deleteEvents(receiverKey, fromSec, toSec)
        rows.chunked(500).forEach { insertEvents(it) }
    }

    @Query("SELECT * FROM epg_events WHERE receiverKey = :receiverKey AND endSec > :fromSec AND beginSec < :toSec ORDER BY beginSec")
    suspend fun events(receiverKey: String, fromSec: Long, toSec: Long): List<EpgRow>

    @Query("SELECT * FROM epg_events WHERE receiverKey = :receiverKey AND serviceKey = :serviceKey AND endSec > :fromSec ORDER BY beginSec")
    suspend fun serviceEvents(receiverKey: String, serviceKey: String, fromSec: Long): List<EpgRow>

    /** A bouquet's programmes overlapping [fromSec, toSec), through the cached channel list. */
    @Query(
        "SELECT e.* FROM epg_events e WHERE e.receiverKey = :receiverKey AND e.endSec > :fromSec AND e.beginSec < :toSec " +
            "AND e.serviceKey IN (SELECT c.serviceKey FROM channels c WHERE c.receiverKey = :receiverKey AND c.bouquetReference = :bouquetReference) " +
            "ORDER BY e.beginSec",
    )
    suspend fun bouquetEvents(receiverKey: String, bouquetReference: String, fromSec: Long, toSec: Long): List<EpgRow>

    @Query("SELECT * FROM channels WHERE receiverKey = :receiverKey AND bouquetReference = :bouquetReference ORDER BY position")
    suspend fun channelsIn(receiverKey: String, bouquetReference: String): List<ChannelRow>

    @Query("SELECT * FROM channels WHERE receiverKey = :receiverKey ORDER BY position")
    fun observeChannels(receiverKey: String): Flow<List<ChannelRow>>

    @Query("SELECT * FROM bouquets WHERE receiverKey = :receiverKey ORDER BY position")
    fun observeBouquets(receiverKey: String): Flow<List<BouquetRow>>

    @Query("SELECT * FROM epg_events WHERE receiverKey = :receiverKey AND beginSec >= :fromSec AND beginSec <= :toSec")
    suspend fun eventsStarting(receiverKey: String, fromSec: Long, toSec: Long): List<EpgRow>

    @Query("SELECT COUNT(*) FROM epg_events WHERE receiverKey = :receiverKey AND beginSec >= :fromSec AND beginSec < :toSec")
    suspend fun eventCount(receiverKey: String, fromSec: Long, toSec: Long): Int

    @Query("DELETE FROM epg_events WHERE endSec < :beforeSec")
    suspend fun purgeEvents(beforeSec: Long)

    // TMDB
    @Query("SELECT * FROM tmdb_matches WHERE matchKey = :key AND cachedUntilMs > :nowMs")
    suspend fun match(key: String, nowMs: Long): TmdbMatchRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMatch(row: TmdbMatchRow)

    @Query("SELECT * FROM tmdb_movies WHERE tmdbId = :id AND language = :language AND cachedUntilMs > :nowMs")
    suspend fun movie(id: Int, language: String, nowMs: Long): TmdbMovieRow?

    @Query("SELECT * FROM tmdb_movies WHERE language = :language AND tmdbId IN (:ids)")
    suspend fun movies(ids: List<Int>, language: String): List<TmdbMovieRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovie(row: TmdbMovieRow)

    @Query("DELETE FROM tmdb_matches WHERE cachedUntilMs < :nowMs")
    suspend fun purgeMatches(nowMs: Long)

    // Home films
    @Query("DELETE FROM home_films WHERE receiverKey = :receiverKey AND beginSec >= :fromSec AND beginSec < :toSec AND kind = :kind")
    suspend fun deleteHomeFilms(receiverKey: String, fromSec: Long, toSec: Long, kind: String = HomeKinds.FILM)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHomeFilms(rows: List<HomeFilmRow>)

    @Transaction
    suspend fun replaceHomeFilms(receiverKey: String, fromSec: Long, toSec: Long, rows: List<HomeFilmRow>, kind: String = HomeKinds.FILM) {
        deleteHomeFilms(receiverKey, fromSec, toSec, kind)
        rows.chunked(500).forEach { insertHomeFilms(it) }
    }

    /** TMDB titles of the guide's films, keyed by programme, for search. */
    @Query(
        "SELECT h.serviceKey AS serviceKey, h.beginSec AS beginSec, m.title AS title FROM home_films h " +
            "JOIN tmdb_movies m ON m.tmdbId = h.tmdbId AND m.language = h.language " +
            "WHERE h.receiverKey = :receiverKey AND h.endSec > :fromSec",
    )
    suspend fun filmTitles(receiverKey: String, fromSec: Long): List<FilmTitle>

    @Query("SELECT * FROM home_films WHERE receiverKey = :receiverKey AND endSec > :fromSec AND beginSec < :toSec AND kind = :kind ORDER BY beginSec")
    fun observeHomeFilms(receiverKey: String, fromSec: Long, toSec: Long, kind: String = HomeKinds.FILM): Flow<List<HomeFilmRow>>

    /** Poster of a matched film or series by its cleaned EPG title (for Home's scheduled cards). */
    @Query(
        "SELECT m.posterPath FROM home_films h JOIN tmdb_movies m ON m.tmdbId = h.tmdbId AND m.language = h.language " +
            "WHERE h.receiverKey = :receiverKey AND (h.searchTitle IN (:titles) OR h.epgTitle IN (:titles)) AND m.posterPath IS NOT NULL LIMIT 1",
    )
    suspend fun posterFor(receiverKey: String, titles: List<String>): String?

    @Query("DELETE FROM home_films WHERE endSec < :beforeSec")
    suspend fun purgeHomeFilms(beforeSec: Long)

    // Sync state
    @Query("SELECT * FROM sync_state WHERE receiverKey = :receiverKey")
    fun observeSyncState(receiverKey: String): Flow<List<SyncStateRow>>

    @Query("SELECT * FROM sync_state WHERE receiverKey = :receiverKey AND kind = :kind")
    suspend fun syncState(receiverKey: String, kind: String): SyncStateRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSyncState(row: SyncStateRow)

    @Query("DELETE FROM bouquets")
    suspend fun clearBouquets()
    @Query("DELETE FROM channels")
    suspend fun clearChannels()
    @Query("DELETE FROM epg_events")
    suspend fun clearEvents()
    @Query("DELETE FROM tmdb_matches")
    suspend fun clearMatches()
    @Query("DELETE FROM tmdb_movies")
    suspend fun clearMovies()
    @Query("DELETE FROM home_films")
    suspend fun clearHomeFilms()
    @Query("DELETE FROM sync_state")
    suspend fun clearSyncState()

    @Query("DELETE FROM soccer_matches")
    suspend fun clearSoccerMatches()
    @Query("DELETE FROM soccer_standings")
    suspend fun clearSoccerStandings()
    @Query("DELETE FROM soccer_fetches")
    suspend fun clearSoccerFetches()

    @Transaction
    suspend fun clearAll() {
        clearBouquets(); clearChannels(); clearEvents(); clearMatches(); clearMovies(); clearHomeFilms(); clearSyncState()
        clearSoccerMatches(); clearSoccerStandings(); clearSoccerFetches()
    }
}

data class FilmTitle(val serviceKey: String, val beginSec: Long, val title: String)
