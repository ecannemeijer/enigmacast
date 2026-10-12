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
    entities = [BouquetRow::class, ChannelRow::class, EpgRow::class, TmdbMatchRow::class, TmdbMovieRow::class, HomeFilmRow::class, SyncStateRow::class],
    version = 2,
    exportSchema = false,
)
abstract class EnigmaDatabase : RoomDatabase() {
    abstract fun cache(): CacheDao

    companion object {
        const val NAME = "enigmacast-cache.db"
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
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun cacheDao(db: EnigmaDatabase): CacheDao = db.cache()
}
