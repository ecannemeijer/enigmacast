package com.enigmacast.domain.download

/** One IPTV film or episode queued for offline viewing. [kind] is "m" (film) or "e" (episode). */
enum class VodDownloadState { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELED }

data class VodDownload(
    val id: String,
    val profileId: String,
    val source: String,
    val kind: String,
    val streamId: String,
    val title: String,
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val poster: String? = null,
    val url: String,
    val extension: String,
    val userAgent: String,
    val httpHeaders: String,
    val volumeIndex: Int,
    val fileName: String,
    val totalBytes: Long = 0L,
    val downloadedBytes: Long = 0L,
    val state: VodDownloadState = VodDownloadState.QUEUED,
    val error: String = "",
    val addedMs: Long,
    val updatedMs: Long,
) {
    /** Same key the streamed VOD uses for resume and watch history. */
    val libraryKey: String get() = "$source/$kind/$streamId"

    val inProgress: Boolean get() = state == VodDownloadState.QUEUED || state == VodDownloadState.RUNNING || state == VodDownloadState.PAUSED
}

object VodDownloadFiles {
    fun safeName(kind: String, streamId: String, extension: String): String {
        val id = streamId.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "item" }
        val ext = extension.replace(Regex("[^A-Za-z0-9]"), "").ifBlank { "mp4" }
        return "vod_${kind}_$id.$ext"
    }
}

object VodDownloadSpace {
    const val MARGIN_BYTES = 8L * 1024 * 1024
    const val UNKNOWN_MINIMUM_BYTES = 256L * 1024 * 1024

    fun neededBytes(totalBytes: Long, downloadedBytes: Long): Long {
        val remaining = if (totalBytes > downloadedBytes) totalBytes - downloadedBytes else 0L
        return if (totalBytes > 0L) remaining + MARGIN_BYTES else UNKNOWN_MINIMUM_BYTES
    }

    fun hasRoom(freeBytes: Long, totalBytes: Long, downloadedBytes: Long): Boolean =
        freeBytes >= neededBytes(totalBytes, downloadedBytes)

    /** Free space of the volume [file] lives on, even when the file itself does not exist yet. */
    fun freeBytes(file: java.io.File): Long {
        val target = generateSequence(file) { it.parentFile }.firstOrNull { it.exists() } ?: file
        return target.usableSpace
    }
}
