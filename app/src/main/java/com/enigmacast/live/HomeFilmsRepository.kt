package com.enigmacast.live

import android.content.Context
import com.enigmacast.data.db.CacheDao
import com.enigmacast.data.db.CacheMappers
import com.enigmacast.data.db.EnigmaDatabase
import com.enigmacast.data.db.SyncStateRow
import com.enigmacast.domain.films.HomeFilm
import com.enigmacast.domain.model.ReceiverConfig
import com.enigmacast.sync.SyncEngine
import com.enigmacast.sync.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.combine
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Home reads only from Room: today's matched films for a receiver, as a Flow. Filling the
 * cache is [SyncEngine]'s job, started through [SyncScheduler].
 */
@Singleton
class HomeFilmsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CacheDao,
    private val engine: SyncEngine,
    private val tmdb: com.enigmacast.data.tmdb.TmdbHttp,
    private val bouquetStore: com.enigmacast.data.tmdb.HomeBouquetStore,
) {
    /** All cached TV bouquets of a receiver with the Home choice. */
    fun bouquetChoice(receiverKey: String): Flow<Pair<List<com.enigmacast.data.db.BouquetRow>, Set<String>>> =
        dao.observeBouquets(receiverKey).combine(bouquetStore.changes) { bouquets, _ ->
            bouquets to com.enigmacast.domain.sync.HomeBouquets.effective(bouquetStore.selected(receiverKey), bouquets.map { it.reference })
        }

    /** Stores the choice and re-matches Home films from the cache (no full resync). */
    fun setBouquets(receiverKey: String, selection: Set<String>?) {
        bouquetStore.set(receiverKey, selection)
        SyncScheduler.runNow(context, force = false, refilm = true)
    }

    fun apiKeyMissing(): Boolean = tmdb.apiKey().isBlank()

    val progress = engine.progress

    fun receiverKey(receiver: ReceiverConfig) = engine.receiverKey(receiver)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun films(receiverKey: String, fromSec: Long, toSec: Long): Flow<List<HomeFilm>> =
        dao.observeHomeFilms(receiverKey, fromSec, toSec).mapLatest { rows ->
            val movies = rows.groupBy { it.language }.flatMap { (language, list) ->
                dao.movies(list.map { it.tmdbId }.distinct(), language)
            }.associate { it.tmdbId to CacheMappers.movie(it) }
            CacheMappers.homeFilms(rows, movies)
        }

    fun syncState(receiverKey: String): Flow<List<SyncStateRow>> = dao.observeSyncState(receiverKey)

    suspend fun hasGuideFor(receiverKey: String, fromSec: Long, toSec: Long): Boolean = dao.eventCount(receiverKey, fromSec, toSec) > 0

    fun syncNow(force: Boolean) = SyncScheduler.runNow(context, force)

    fun ensurePeriodic() = SyncScheduler.ensurePeriodic(context)

    /** Database files plus the TMDB HTTP cache, in bytes. */
    fun cacheBytes(): Long {
        val db = context.getDatabasePath(EnigmaDatabase.NAME)
        val files = listOf(db, File(db.path + "-wal"), File(db.path + "-shm"))
        val http = File(context.cacheDir, "tmdb").listFiles().orEmpty().sumOf { it.length() }
        return files.sumOf { if (it.isFile) it.length() else 0L } + http
    }

    suspend fun clearCache() {
        dao.clearAll()
        File(context.cacheDir, "tmdb").deleteRecursively()
    }
}
