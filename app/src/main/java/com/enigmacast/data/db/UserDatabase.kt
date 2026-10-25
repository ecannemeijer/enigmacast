package com.enigmacast.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

/** Where the viewer stopped in a recording. [key] is the recording id (app or box). */
@Entity(tableName = "resume_positions")
data class ResumeRow(
    @PrimaryKey val key: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedMs: Long,
)

@Dao
interface ResumeDao {
    @Query("SELECT * FROM resume_positions")
    fun all(): Flow<List<ResumeRow>>

    @Query("SELECT * FROM resume_positions WHERE `key` = :key")
    suspend fun get(key: String): ResumeRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: ResumeRow)

    @Query("DELETE FROM resume_positions WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM resume_positions WHERE `key` LIKE :prefix || '%'")
    suspend fun deletePrefix(prefix: String)
}

/**
 * The viewer's own data (unlike [EnigmaDatabase], which is a cache that a schema change
 * wipes). Kept separate so resume positions survive cache rebuilds.
 */
@Database(
    entities = [
        ResumeRow::class, SeriesRuleRow::class, IptvLibraryRow::class, IptvResumeRow::class,
        SyncOutboxRow::class, SyncCursorRow::class, EpisodeHistoryRow::class, AudioBoostRow::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun resume(): ResumeDao
    abstract fun seriesRules(): SeriesRuleDao
    abstract fun iptvLibrary(): IptvLibraryDao
    abstract fun syncOutbox(): SyncOutboxDao
    abstract fun audioBoost(): AudioBoostDao

    companion object {
        const val NAME = "enigmacast-user.db"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object UserDatabaseModule {
    @Provides
    @Singleton
    fun userDatabase(@ApplicationContext context: Context): UserDatabase =
        Room.databaseBuilder(context, UserDatabase::class.java, UserDatabase.NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()

    private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `series_rules` (`id` TEXT NOT NULL, `receiverId` TEXT NOT NULL, `titleKey` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `serviceKey` TEXT NOT NULL, `reference` TEXT NOT NULL, `channelName` TEXT NOT NULL, " +
                    "`place` TEXT NOT NULL, `autoTimerId` TEXT, `createdMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            )
        }
    }

    /** Additive: IPTV favorites and IPTV resume positions (with what Library needs to show them offline). */
    internal val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `iptv_library` (`source` TEXT NOT NULL, `kind` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                    "`title` TEXT NOT NULL, `poster` TEXT, `backdrop` TEXT, `year` INTEGER, `categoryId` TEXT NOT NULL, " +
                    "`extension` TEXT NOT NULL, `url` TEXT NOT NULL, `addedMs` INTEGER NOT NULL, PRIMARY KEY(`source`, `kind`, `id`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `iptv_resume` (`source` TEXT NOT NULL, `kind` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                    "`positionMs` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `updatedMs` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                    "`poster` TEXT, `extension` TEXT NOT NULL, `url` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `seriesId` TEXT, " +
                    "`seriesTitle` TEXT, `season` INTEGER NOT NULL, `episode` INTEGER NOT NULL, PRIMARY KEY(`source`, `kind`, `id`))",
            )
        }
    }

    internal val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            UserMigrations.v3ToV4 { db.execSQL(it) }
        }
    }

    /** Additive: the watch history of films and episodes (progress per episode on the info page). */
    internal val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            UserMigrations.v4ToV5 { db.execSQL(it) }
        }
    }

    /** Additive: the live TV audio boost per channel (synced). */
    internal val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            UserMigrations.v5ToV6 { db.execSQL(it) }
        }
    }

    @Provides
    fun audioBoostDao(db: UserDatabase): AudioBoostDao = db.audioBoost()

    @Provides
    fun iptvLibraryDao(db: UserDatabase): IptvLibraryDao = db.iptvLibrary()

    @Provides
    fun syncOutboxDao(db: UserDatabase): SyncOutboxDao = db.syncOutbox()

    @Provides
    fun seriesRuleDao(db: UserDatabase): SeriesRuleDao = db.seriesRules()

    @Provides
    fun resumeDao(db: UserDatabase): ResumeDao = db.resume()
}

/**
 * "Record all upcoming": every new episode of [title] on that channel is recorded on [place]
 * ("TV" or "RECEIVER"). [autoTimerId] is set when the box's AutoTimer plugin does the work.
 */
@Entity(tableName = "series_rules")
data class SeriesRuleRow(
    @PrimaryKey val id: String,
    val receiverId: String,
    /** SeriesDetector.key title part (normalized, no episode marks). */
    val titleKey: String,
    val title: String,
    /** NowEvents.key of the channel. */
    val serviceKey: String,
    val reference: String,
    val channelName: String,
    val place: String,
    val autoTimerId: String?,
    val createdMs: Long,
)

@Dao
interface SeriesRuleDao {
    @Query("SELECT * FROM series_rules ORDER BY createdMs")
    fun all(): Flow<List<SeriesRuleRow>>

    @Query("SELECT * FROM series_rules WHERE receiverId = :receiverId")
    suspend fun forReceiver(receiverId: String): List<SeriesRuleRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: SeriesRuleRow)

    @Query("DELETE FROM series_rules WHERE id = :id")
    suspend fun delete(id: String)
}

/** An IPTV film ([kind] "m") or series ("s") in Library › IPTV favorites. [source] is the playlist account key. */
@Entity(tableName = "iptv_library", primaryKeys = ["source", "kind", "id", "profileId"])
data class IptvLibraryRow(
    val source: String,
    val kind: String,
    val id: String,
    val title: String,
    val poster: String?,
    val backdrop: String?,
    val year: Int?,
    val categoryId: String,
    val extension: String,
    val url: String,
    val addedMs: Long,
    /** Viewer who owns this favorite. Legacy rows are the original profile. */
    val profileId: String = com.enigmacast.domain.profile.ProfileState.LEGACY_UUID,
)

/** Where the viewer stopped in an IPTV film ("m") or episode ("e"), for Continue watching. */
@Entity(tableName = "iptv_resume", primaryKeys = ["source", "kind", "id", "profileId"])
data class IptvResumeRow(
    val source: String,
    val kind: String,
    val id: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedMs: Long,
    val title: String,
    val poster: String?,
    val extension: String,
    val url: String,
    val categoryId: String,
    val seriesId: String?,
    val seriesTitle: String?,
    val season: Int,
    val episode: Int,
    val profileId: String = com.enigmacast.domain.profile.ProfileState.LEGACY_UUID,
)

@Dao
interface IptvLibraryDao {
    @Query("SELECT * FROM iptv_library ORDER BY addedMs DESC")
    fun favorites(): Flow<List<IptvLibraryRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFavorite(row: IptvLibraryRow)

    @Query("SELECT * FROM iptv_library WHERE profileId = :profileId AND source = :source AND kind = :kind AND id = :id")
    suspend fun favorite(profileId: String, source: String, kind: String, id: String): IptvLibraryRow?

    @Query("DELETE FROM iptv_library WHERE profileId = :profileId AND source = :source AND kind = :kind AND id = :id")
    suspend fun deleteFavorite(profileId: String, source: String, kind: String, id: String)

    @Query("DELETE FROM iptv_library WHERE profileId = :profileId")
    suspend fun deleteFavoritesFor(profileId: String)

    @Query("SELECT * FROM iptv_resume ORDER BY updatedMs DESC")
    fun resumes(): Flow<List<IptvResumeRow>>

    @Query("SELECT * FROM iptv_resume WHERE profileId = :profileId AND source = :source AND kind = :kind AND id = :id")
    suspend fun resume(profileId: String, source: String, kind: String, id: String): IptvResumeRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putResume(row: IptvResumeRow)

    @Query("DELETE FROM iptv_resume WHERE profileId = :profileId AND source = :source AND kind = :kind AND id = :id")
    suspend fun deleteResume(profileId: String, source: String, kind: String, id: String)

    @Query("DELETE FROM iptv_resume WHERE profileId = :profileId")
    suspend fun deleteResumesFor(profileId: String)

    @Query("SELECT * FROM episode_history")
    fun histories(): Flow<List<EpisodeHistoryRow>>

    @Query("SELECT * FROM episode_history WHERE profileId = :profileId AND source = :source AND kind = :kind AND id = :id")
    suspend fun history(profileId: String, source: String, kind: String, id: String): EpisodeHistoryRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHistory(row: EpisodeHistoryRow)

    @Query("DELETE FROM episode_history WHERE profileId = :profileId")
    suspend fun deleteHistoryFor(profileId: String)
}

/**
 * How far a film ("m") or episode ("e") was ever played, for the progress bars and watched
 * marks on the info page's episode cards. Unlike [IptvResumeRow] this is history: removing a
 * Continue watching card (and its tombstones) leaves it alone, and [watched] never goes back.
 */
@Entity(tableName = "episode_history", primaryKeys = ["source", "kind", "id", "profileId"])
data class EpisodeHistoryRow(
    val source: String,
    val kind: String,
    val id: String,
    val seriesId: String?,
    val positionMs: Long,
    val durationMs: Long,
    val watched: Boolean,
    val updatedMs: Long,
    val profileId: String = com.enigmacast.domain.profile.ProfileState.LEGACY_UUID,
)

/** A local change waiting for the EnigmaCast sync server. [op] is "upsert" or "delete". */
@Entity(tableName = "sync_outbox")
data class SyncOutboxRow(
    @PrimaryKey val changeId: String,
    val profileId: String,
    val entity: String,
    val op: String,
    val payload: String,
    val updatedMs: Long,
    val deviceId: String,
)

/** Last pulled cursor per profile. The server's cursor is opaque. */
@Entity(tableName = "sync_cursor")
data class SyncCursorRow(
    @PrimaryKey val profileId: String,
    val cursor: String,
)

@Dao
interface SyncOutboxDao {
    @Query("SELECT * FROM sync_outbox ORDER BY updatedMs, changeId")
    suspend fun pending(): List<SyncOutboxRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: SyncOutboxRow)

    @Query("DELETE FROM sync_outbox WHERE changeId = :changeId")
    suspend fun delete(changeId: String)

    @Query("DELETE FROM sync_outbox WHERE profileId = :profileId")
    suspend fun deleteProfile(profileId: String)

    @Query("SELECT * FROM sync_cursor WHERE profileId = :profileId")
    suspend fun cursor(profileId: String): SyncCursorRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCursor(row: SyncCursorRow)
}

/**
 * Live TV audio boost of one channel for one viewer. [channelKey] comes from
 * AudioBoost.satelliteKey / iptvKey; [level] 0 (Off) is kept too, so switching it off syncs.
 * [updatedMs] and [changeId] decide which change wins (last write wins).
 */
@Entity(tableName = "audio_boost", primaryKeys = ["profileId", "channelKey"])
data class AudioBoostRow(
    val profileId: String,
    val channelKey: String,
    val level: Int,
    val updatedMs: Long,
    val changeId: String,
)

@Dao
interface AudioBoostDao {
    @Query("SELECT * FROM audio_boost")
    fun all(): Flow<List<AudioBoostRow>>

    @Query("SELECT * FROM audio_boost WHERE profileId = :profileId AND channelKey = :channelKey")
    suspend fun get(profileId: String, channelKey: String): AudioBoostRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: AudioBoostRow)

    @Query("DELETE FROM audio_boost WHERE profileId = :profileId")
    suspend fun deleteFor(profileId: String)
}
