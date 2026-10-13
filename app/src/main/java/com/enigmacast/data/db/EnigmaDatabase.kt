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
        SoccerMatchRow::class, SoccerStandingsRow::class, SoccerFetchRow::class, IptvEpgRow::class],
    version = 6,
    exportSchema = false,
)
abstract class EnigmaDatabase : RoomDatabase() {
    abstract fun cache(): CacheDao
    abstract fun soccer(): SoccerDao

    companion object {
        const val NAME = "enigmacast-cache.db"

        /** 4 → 5: the channel-list hash per receiver. Existing rows keep their receiver key. */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_state ADD COLUMN contentHash TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 5 → 6: XMLTV programmes for imported IPTV services. */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `iptv_epg` (`receiverKey` TEXT NOT NULL, `serviceKey` TEXT NOT NULL, `reference` TEXT NOT NULL, " +
                        "`beginSec` INTEGER NOT NULL, `endSec` INTEGER NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "PRIMARY KEY(`receiverKey`, `serviceKey`, `beginSec`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_iptv_epg_receiverKey_endSec` ON `iptv_epg` (`receiverKey`, `endSec`)")
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
            .addMigrations(EnigmaDatabase.MIGRATION_4_5, EnigmaDatabase.MIGRATION_5_6)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun cacheDao(db: EnigmaDatabase): CacheDao = db.cache()

    @Provides
    fun soccerDao(db: EnigmaDatabase): SoccerDao = db.soccer()
}
