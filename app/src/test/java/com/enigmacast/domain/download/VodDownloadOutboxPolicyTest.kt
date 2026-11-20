package com.enigmacast.domain.download

import com.enigmacast.data.db.EpisodeHistoryRow
import com.enigmacast.data.xtream.IptvLibrary
import com.enigmacast.domain.resume.EpisodeHistory
import com.enigmacast.domain.sync.LibraryMerge
import com.enigmacast.domain.sync.LibraryMergeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline plays of a download use the same IptvLibrary key as streamed VOD, so resume and
 * watch history go into the existing Room outbox and merge with the same rules when the
 * sync server is reachable again.
 */
class VodDownloadOutboxPolicyTest {
    @Test
    fun downloadAndStreamShareOneLibraryKey() {
        val download = sample(source = "acc", kind = "e", streamId = "42")
        assertEquals("acc/e/42", download.libraryKey)
        assertEquals(IptvLibrary.key("acc", "e", "42"), download.libraryKey)
        assertEquals(IptvLibrary.key("acc", "m", "9"), sample(kind = "m", streamId = "9").libraryKey)
    }

    @Test
    fun watchedIsNeverClearedByALaterShorterPlay() {
        val watched = EpisodeHistory.entry("acc", "e", "42", "7", 1_100_000, 1_200_000, 1_000L, "p")
        assertTrue(watched.watched)
        val rewatch = EpisodeHistory.entry("acc", "e", "42", "7", 20_000, 1_200_000, 2_000L, "p")
        val merged = EpisodeHistory.merge(watched, rewatch)
        assertTrue(merged.watched)
        assertEquals(2_000L, merged.updatedMs)
        assertEquals(20_000L, merged.positionMs)
    }

    @Test
    fun newerTimestampWinsThePosition() {
        val first = EpisodeHistory.entry("acc", "m", "9", null, 30_000, 100_000, 1_000L, "p")
        val later = EpisodeHistory.entry("acc", "m", "9", null, 80_000, 100_000, 2_000L, "p")
        assertEquals(80_000L, EpisodeHistory.merge(first, later).positionMs)
        assertEquals(80_000L, EpisodeHistory.merge(later, first).positionMs)
        assertEquals(2_000L, EpisodeHistory.merge(first, later).updatedMs)
    }

    @Test
    fun anOlderIncomingSaveDoesNotReplaceANewerOne() {
        val newest = EpisodeHistory.entry("acc", "m", "9", null, 80_000, 100_000, 3_000L, "p")
        val stale = EpisodeHistory.entry("acc", "m", "9", null, 10_000, 100_000, 1_000L, "p")
        val merged = EpisodeHistory.merge(newest, stale)
        assertEquals(80_000L, merged.positionMs)
        assertEquals(3_000L, merged.updatedMs)
    }

    @Test
    fun outboxProgressKeepsTheNewestTimestamp() {
        assertEquals(LibraryMergeResult.UPSERT, LibraryMerge.decide(1_000, null, 2_000, "tv", "upsert"))
        assertEquals(LibraryMergeResult.IGNORE, LibraryMerge.decide(3_000, null, 2_000, "tv", "upsert"))
        assertFalse(EpisodeHistory.worthKeeping(0, 2_400_000))
        assertTrue(EpisodeHistory.worthKeeping(5_000, 2_400_000))
    }

    private fun sample(source: String = "acc", kind: String = "m", streamId: String = "1") = VodDownload(
        id = "d1",
        profileId = "p",
        source = source,
        kind = kind,
        streamId = streamId,
        title = "Film",
        url = "http://example/movie.mp4",
        extension = "mp4",
        userAgent = "EnigmaCast",
        httpHeaders = "",
        volumeIndex = 0,
        fileName = "vod_${kind}_$streamId.mp4",
        addedMs = 1,
        updatedMs = 1,
    )
}
