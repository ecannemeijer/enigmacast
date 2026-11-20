package com.enigmacast.data.download

import android.content.Context
import com.enigmacast.data.db.VodDownloadDao
import com.enigmacast.data.db.VodDownloadRow
import com.enigmacast.data.profile.ProfileStore
import com.enigmacast.domain.download.VodDownload
import com.enigmacast.domain.download.VodDownloadFiles
import com.enigmacast.domain.download.VodDownloadSpace
import com.enigmacast.domain.download.VodDownloadState
import com.enigmacast.live.IptvRecordStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

@Singleton
class VodDownloadStore @Inject constructor(
    private val dao: VodDownloadDao,
    private val profiles: ProfileStore,
    @ApplicationContext private val context: Context,
) {
    fun downloads(): Flow<List<VodDownload>> = combine(dao.all(), profiles.state) { rows, state ->
        rows.filter { it.profileId == state.activeUuid }.map { it.toModel() }
    }

    fun ofActive(): Flow<Map<String, VodDownload>> = downloads().map { list -> list.associateBy { it.libraryKey } }

    suspend fun allNow(): List<VodDownload> {
        val profile = profiles.state.value.activeUuid
        return dao.allNow().filter { it.profileId == profile }.map { it.toModel() }
    }

    suspend fun get(id: String): VodDownload? = dao.get(id)?.toModel()

    suspend fun byLibraryKey(source: String, kind: String, streamId: String): VodDownload? {
        val profile = profiles.state.value.activeUuid
        return dao.byLibraryKey(profile, source, kind, streamId)?.toModel()
    }

    suspend fun nextQueued(): VodDownload? =
        dao.inState(VodDownloadState.QUEUED.name).firstOrNull()?.toModel()
            ?: dao.inState(VodDownloadState.RUNNING.name).firstOrNull()?.toModel()

    suspend fun put(item: VodDownload) {
        dao.put(VodDownloadRow.from(item))
    }

    suspend fun enqueue(item: VodDownload): VodDownload {
        val existing = byLibraryKey(item.source, item.kind, item.streamId)
        if (existing != null && existing.state != VodDownloadState.CANCELED && existing.state != VodDownloadState.FAILED) {
            return existing
        }
        val now = System.currentTimeMillis()
        val profile = profiles.state.value.activeUuid
        val created = if (existing != null) {
            existing.copy(
                url = item.url,
                userAgent = item.userAgent,
                httpHeaders = item.httpHeaders,
                volumeIndex = item.volumeIndex,
                state = VodDownloadState.QUEUED,
                error = "",
                updatedMs = now,
            )
        } else {
            item.copy(
                id = item.id.ifBlank { UUID.randomUUID().toString() },
                profileId = profile,
                addedMs = now,
                updatedMs = now,
                state = VodDownloadState.QUEUED,
            )
        }
        put(created)
        return created
    }

    suspend fun setState(id: String, state: VodDownloadState, error: String = "", downloadedBytes: Long? = null, totalBytes: Long? = null) {
        val current = dao.get(id)?.toModel() ?: return
        put(
            current.copy(
                state = state,
                error = error,
                downloadedBytes = downloadedBytes ?: current.downloadedBytes,
                totalBytes = totalBytes ?: current.totalBytes,
                updatedMs = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun pause(id: String) {
        val current = get(id) ?: return
        if (current.state == VodDownloadState.RUNNING || current.state == VodDownloadState.QUEUED) {
            setState(id, VodDownloadState.PAUSED)
        }
    }

    suspend fun resume(id: String) {
        val current = get(id) ?: return
        if (current.state == VodDownloadState.PAUSED || current.state == VodDownloadState.FAILED) {
            setState(id, VodDownloadState.QUEUED, error = "")
        }
    }

    suspend fun cancel(id: String) {
        val current = get(id) ?: return
        if (current.state != VodDownloadState.DONE) setState(id, VodDownloadState.CANCELED)
    }

    suspend fun delete(id: String) {
        val current = get(id)
        dao.delete(id)
        current?.let { fileFor(it).delete() }
    }

    fun fileFor(item: VodDownload): File {
        val name = item.fileName
        IptvRecordStorage.volumes(context).forEach { volume ->
            val direct = File(volume.dir, name)
            if (direct.exists()) return direct
            val nested = File(File(volume.dir, "downloads"), name)
            if (nested.exists()) return nested
        }
        val dir = File(IptvRecordStorage.dirFor(context, item.volumeIndex), "downloads")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, name)
    }

    fun hasRoom(item: VodDownload): Boolean {
        val file = fileFor(item)
        file.parentFile?.mkdirs()
        return VodDownloadSpace.hasRoom(VodDownloadSpace.freeBytes(file), item.totalBytes, item.downloadedBytes)
    }
}
