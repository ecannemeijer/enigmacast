package com.enigmacast.live

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** One writable app-specific movies folder: internal storage, or a mounted USB/SD volume. */
data class IptvStorageVolume(
    val index: Int,
    val dir: File,
    val label: String,
    val removable: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
)

/**
 * Where IPTV captures are stored. [Context.getExternalFilesDirs] is used instead of the
 * Storage Access Framework, which is unreliable on Android TV 9–14.
 */
object IptvRecordStorage {
    fun volumes(context: Context): List<IptvStorageVolume> {
        val dirs = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)?.filterNotNull().orEmpty()
            .ifEmpty { listOfNotNull(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir) }
        val manager = context.getSystemService(StorageManager::class.java)
        return dirs.mapIndexed { index, dir ->
            if (!dir.exists()) dir.mkdirs()
            val volume = manager?.getStorageVolume(dir)
            val removable = volume?.isRemovable == true
            val label = volume?.getDescription(context)?.takeIf { it.isNotBlank() } ?: dir.absolutePath
            IptvStorageVolume(
                index = index,
                dir = dir,
                label = label,
                removable = removable,
                totalBytes = dir.totalSpace.coerceAtLeast(0L),
                freeBytes = dir.usableSpace.coerceAtLeast(0L),
            )
        }
    }

    fun dirFor(context: Context, index: Int): File {
        val all = volumes(context)
        val dir = (all.getOrNull(index.coerceAtLeast(0)) ?: all.firstOrNull())?.dir
            ?: (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Prefer an existing file on any volume, otherwise the selected volume. */
    fun fileFor(context: Context, id: String, preferredIndex: Int): File {
        val name = "$id.ts"
        volumes(context).forEach { volume ->
            val file = File(volume.dir, name)
            if (file.exists()) return file
        }
        return File(dirFor(context, preferredIndex), name)
    }
}
