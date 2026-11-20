package com.enigmacast.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.enigmacast.domain.download.VodDownload
import com.enigmacast.domain.download.VodDownloadState
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vod_downloads")
data class VodDownloadRow(
    @PrimaryKey val id: String,
    val profileId: String,
    val source: String,
    val kind: String,
    val streamId: String,
    val title: String,
    val seriesId: String?,
    val seriesTitle: String?,
    val season: Int,
    val episode: Int,
    val poster: String?,
    val url: String,
    val extension: String,
    val userAgent: String,
    val httpHeaders: String,
    val volumeIndex: Int,
    val fileName: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val state: String,
    val error: String,
    val addedMs: Long,
    val updatedMs: Long,
) {
    fun toModel(): VodDownload = VodDownload(
        id = id,
        profileId = profileId,
        source = source,
        kind = kind,
        streamId = streamId,
        title = title,
        seriesId = seriesId,
        seriesTitle = seriesTitle,
        season = season,
        episode = episode,
        poster = poster,
        url = url,
        extension = extension,
        userAgent = userAgent,
        httpHeaders = httpHeaders,
        volumeIndex = volumeIndex,
        fileName = fileName,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        state = runCatching { VodDownloadState.valueOf(state) }.getOrDefault(VodDownloadState.QUEUED),
        error = error,
        addedMs = addedMs,
        updatedMs = updatedMs,
    )

    companion object {
        fun from(model: VodDownload) = VodDownloadRow(
            id = model.id,
            profileId = model.profileId,
            source = model.source,
            kind = model.kind,
            streamId = model.streamId,
            title = model.title,
            seriesId = model.seriesId,
            seriesTitle = model.seriesTitle,
            season = model.season,
            episode = model.episode,
            poster = model.poster,
            url = model.url,
            extension = model.extension,
            userAgent = model.userAgent,
            httpHeaders = model.httpHeaders,
            volumeIndex = model.volumeIndex,
            fileName = model.fileName,
            totalBytes = model.totalBytes,
            downloadedBytes = model.downloadedBytes,
            state = model.state.name,
            error = model.error,
            addedMs = model.addedMs,
            updatedMs = model.updatedMs,
        )
    }
}

@Dao
interface VodDownloadDao {
    @Query("SELECT * FROM vod_downloads ORDER BY addedMs DESC")
    fun all(): Flow<List<VodDownloadRow>>

    @Query("SELECT * FROM vod_downloads ORDER BY addedMs DESC")
    suspend fun allNow(): List<VodDownloadRow>

    @Query("SELECT * FROM vod_downloads WHERE id = :id")
    suspend fun get(id: String): VodDownloadRow?

    @Query("SELECT * FROM vod_downloads WHERE profileId = :profileId AND source = :source AND kind = :kind AND streamId = :streamId LIMIT 1")
    suspend fun byLibraryKey(profileId: String, source: String, kind: String, streamId: String): VodDownloadRow?

    @Query("SELECT * FROM vod_downloads WHERE state = :state ORDER BY addedMs ASC")
    suspend fun inState(state: String): List<VodDownloadRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: VodDownloadRow)

    @Query("DELETE FROM vod_downloads WHERE id = :id")
    suspend fun delete(id: String)
}
