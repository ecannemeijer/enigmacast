package com.enigmacast.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface IptvDao {
    @Query("SELECT * FROM iptv_row_epg WHERE account = :account AND streamId IN (:streamIds)")
    suspend fun rowEpg(account: String, streamIds: List<String>): List<IptvRowEpgRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRowEpg(row: IptvRowEpgRow)

    /** Every stored channel guide (all accounts): searched by the actor page's "where to watch". */
    @Query("SELECT * FROM iptv_row_epg WHERE fetchedAtMs >= :sinceMs")
    suspend fun allRowEpg(sinceMs: Long): List<IptvRowEpgRow>

    @Query("SELECT * FROM iptv_item WHERE source = :source AND section = :section AND id IN (:ids)")
    suspend fun itemsByIds(source: String, section: String, ids: List<String>): List<IptvItemRow>

    @Query("DELETE FROM iptv_row_epg WHERE fetchedAtMs < :beforeMs")
    suspend fun pruneRowEpg(beforeMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(rows: List<IptvCategoryRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(rows: List<IptvItemRow>)

    @Query("DELETE FROM iptv_category WHERE source = :source")
    suspend fun clearCategories(source: String)

    @Query("DELETE FROM iptv_item WHERE source = :source")
    suspend fun clearItems(source: String)

    @Query("UPDATE iptv_category SET source = :to WHERE source = :from")
    suspend fun moveCategories(from: String, to: String)

    @Query("UPDATE iptv_item SET source = :to WHERE source = :from")
    suspend fun moveItems(from: String, to: String)

    @Query("SELECT * FROM iptv_category WHERE source = :source AND section = :section ORDER BY sort")
    suspend fun categories(source: String, section: String): List<IptvCategoryRow>

    /**
     * The first rows of a category. Later pages continue after the last row ([itemsAfter]) rather
     * than with OFFSET, so each page is a seek on the (source, section, categoryId, sort) index.
     * Read in pages so a 20 000-channel group never fills a CursorWindow.
     */
    @Query("SELECT * FROM iptv_item WHERE source = :source AND section = :section AND categoryId = :categoryId ORDER BY sort, id LIMIT :limit")
    suspend fun itemsFirst(source: String, section: String, categoryId: String, limit: Int): List<IptvItemRow>

    /** The rows after ([afterSort], [afterId]) in the order of [itemsFirst]. */
    @Query(
        "SELECT * FROM iptv_item WHERE source = :source AND section = :section AND categoryId = :categoryId " +
            "AND (sort > :afterSort OR (sort = :afterSort AND id > :afterId)) ORDER BY sort, id LIMIT :limit",
    )
    suspend fun itemsAfter(source: String, section: String, categoryId: String, afterSort: Int, afterId: String, limit: Int): List<IptvItemRow>

    /** All rows of one section; used for name matching across the whole playlist. */
    @Query("SELECT * FROM iptv_item WHERE source = :source AND section = :section ORDER BY sort")
    suspend fun allItems(source: String, section: String): List<IptvItemRow>

    /** Items per category of a section (one query for the whole categories column). */
    @Query("SELECT categoryId AS categoryId, COUNT(*) AS n FROM iptv_item WHERE source = :source AND section = :section GROUP BY categoryId")
    suspend fun countsByCategory(source: String, section: String): List<IptvCategoryCount>

    /** Search: titles containing the query ([pattern] from IptvSearch.likePattern), per section. */
    @Query("SELECT * FROM iptv_item WHERE source = :source AND section = :section AND name LIKE :pattern ESCAPE '\\' LIMIT :limit")
    suspend fun search(source: String, section: String, pattern: String, limit: Int): List<IptvItemRow>

    @Query("SELECT COUNT(*) FROM iptv_item WHERE source = :source")
    suspend fun itemCount(source: String): Int

    @Query("SELECT COUNT(*) FROM iptv_category WHERE source = :source")
    suspend fun categoryCount(source: String): Int
}

/** Result row of [IptvDao.countsByCategory]. */
data class IptvCategoryCount(val categoryId: String, val n: Int)
