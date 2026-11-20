package com.enigmacast.domain.download

import com.enigmacast.data.xtream.IptvLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodDownloadTest {
    @Test
    fun libraryKeyMatchesStreamedVod() {
        val item = VodDownload(
            id = "1",
            profileId = "p",
            source = "abc",
            kind = "m",
            streamId = "42",
            title = "Film",
            url = "http://example/movie.mp4",
            extension = "mp4",
            userAgent = "EnigmaCast",
            httpHeaders = "",
            volumeIndex = 0,
            fileName = "vod_m_42.mp4",
            addedMs = 1,
            updatedMs = 1,
        )
        assertEquals(IptvLibrary.key("abc", "m", "42"), item.libraryKey)
    }

    @Test
    fun freeSpaceCheckKeepsAMargin() {
        assertTrue(VodDownloadSpace.hasRoom(300L * 1024 * 1024, 100L * 1024 * 1024, 0))
        assertFalse(VodDownloadSpace.hasRoom(5L * 1024 * 1024, 100L * 1024 * 1024, 0))
        assertEquals(VodDownloadSpace.UNKNOWN_MINIMUM_BYTES, VodDownloadSpace.neededBytes(0, 0))
    }

    @Test
    fun fileNameIsSafe() {
        assertEquals("vod_e_99.mp4", VodDownloadFiles.safeName("e", "99", "mp4"))
        assertEquals("vod_m_ab_12.mkv", VodDownloadFiles.safeName("m", "ab/12", "mkv"))
    }
}
