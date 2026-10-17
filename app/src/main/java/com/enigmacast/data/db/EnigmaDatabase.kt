package com.enigmacast.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [BouquetRow::class, ChannelRow::class, EpgRow::class, TmdbMatchRow::class, TmdbMovieRow::class, HomeFilmRow::class, SyncStateRow::class,
        SoccerMatchRow::class, SoccerStandingsRow::class, SoccerFetchRow::class, IptvCategoryRow::class, IptvItemRow::class],
    version = 9,
    exportSchema = false,
)
abstract class EnigmaDatabase : RoomDatabase() {
    abstract fun cache(): CacheDao
    abstract fun soccer(): SoccerDao
    abstract fun iptv(): IptvDao

    companion object {
        const val NAME = "enigmacast-cache.db"

        /** 4 → 5: the channel-list hash per receiver. Existing rows keep their receiver key. */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_state ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 5 → 7: nothing changed (v6 was a test build). */
        val MIGRATION_5_7 = object : androidx.room.migration.Migration(5, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
        }

        /** 6 → 7: a test build's XMLTV table (iptv_epg) is dropped; everything else stays. */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `iptv_epg`")
            }
        }

        /** 7 → 8: IPTV playlist tables. Only adds tables; the cache stays. */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `iptv_category` (`source` TEXT NOT NULL, `section` TEXT NOT NULL, `id` TEXT NOT NULL, `name` TEXT NOT NULL, `sort` INTEGER NOT NULL, PRIMARY KEY(`source`, `section`, `id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `iptv_item` (`source` TEXT NOT NULL, `section` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `id` TEXT NOT NULL, `name` TEXT NOT NULL, `icon` TEXT NOT NULL, `extension` TEXT NOT NULL, `number` INTEGER NOT NULL, `url` TEXT NOT NULL, `sort` INTEGER NOT NULL, PRIMARY KEY(`source`, `section`, `categoryId`, `id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_iptv_item_source_section_categoryId_sort` ON `iptv_item` (`source`, `section`, `categoryId`, `sort`)")
            }
        }

        /** 8 → 9: catch-up days and the M3U catchup-source on playlist channels. */
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE iptv_item ADD COLUMN archiveDays INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE iptv_item ADD COLUMN catchupSource TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    /** A cache only: a schema change simply drops and refills it. */
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): EnigmaDatabase =
        Room.databaseBuilder(context, EnigmaDatabase::class.java, EnigmaDatabase.NAME)
            .addMigrations(EnigmaDatabase.MIGRATION_4_5, EnigmaDatabase.MIGRATION_5_7, EnigmaDatabase.MIGRATION_6_7, EnigmaDatabase.MIGRATION_7_8, EnigmaDatabase.MIGRATION_8_9)
            // A cache only: an older build reading a newer database just starts empty instead of crashing.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun cacheDao(db: EnigmaDatabase): CacheDao = db.cache()

    @Provides
    fun iptvDao(db: EnigmaDatabase): IptvDao = db.iptv()

    @Provides
    fun soccerDao(db: EnigmaDatabase): SoccerDao = db.soccer()
}
