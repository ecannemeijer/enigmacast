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
@Database(entities = [ResumeRow::class], version = 1, exportSchema = false)
abstract class UserDatabase : RoomDatabase() {
    abstract fun resume(): ResumeDao

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
        Room.databaseBuilder(context, UserDatabase::class.java, UserDatabase.NAME).build()

    @Provides
    fun resumeDao(db: UserDatabase): ResumeDao = db.resume()
}
