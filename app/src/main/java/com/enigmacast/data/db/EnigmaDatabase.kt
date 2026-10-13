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
        SoccerMatchRow::class, SoccerStandingsRow::class, SoccerFetchRow::class],
    version = 5,
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
            .addMigrations(EnigmaDatabase.MIGRATION_4_5)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun cacheDao(db: EnigmaDatabase): CacheDao = db.cache()

    @Provides
    fun soccerDao(db: EnigmaDatabase): SoccerDao = db.soccer()
}
