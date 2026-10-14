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
}

/**
 * The viewer's own data (unlike [EnigmaDatabase], which is a cache that a schema change
 * wipes). Kept separate so resume positions survive cache rebuilds.
 */
@Database(entities = [ResumeRow::class, SeriesRuleRow::class, IptvLibraryRow::class, IptvResumeRow::class], version = 3, exportSchema = false)
abstract class UserDatabase : RoomDatabase() {
    abstract fun resume(): ResumeDao
    abstract fun seriesRules(): SeriesRuleDao
    abstract fun iptvLibrary(): IptvLibraryDao

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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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

    @Provides
    fun iptvLibraryDao(db: UserDatabase): IptvLibraryDao = db.iptvLibrary()

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
@Entity(tableName = "iptv_library", primaryKeys = ["source", "kind", "id"])
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
)

/** Where the viewer stopped in an IPTV film ("m") or episode ("e"), for Continue watching. */
@Entity(tableName = "iptv_resume", primaryKeys = ["source", "kind", "id"])
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
)

@Dao
interface IptvLibraryDao {
    @Query("SELECT * FROM iptv_library ORDER BY addedMs DESC")
    fun favorites(): Flow<List<IptvLibraryRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFavorite(row: IptvLibraryRow)

    @Query("DELETE FROM iptv_library WHERE source = :source AND kind = :kind AND id = :id")
    suspend fun deleteFavorite(source: String, kind: String, id: String)

    @Query("SELECT * FROM iptv_resume ORDER BY updatedMs DESC")
    fun resumes(): Flow<List<IptvResumeRow>>

    @Query("SELECT * FROM iptv_resume WHERE source = :source AND kind = :kind AND id = :id")
    suspend fun resume(source: String, kind: String, id: String): IptvResumeRow?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putResume(row: IptvResumeRow)

    @Query("DELETE FROM iptv_resume WHERE source = :source AND kind = :kind AND id = :id")
    suspend fun deleteResume(source: String, kind: String, id: String)
}
